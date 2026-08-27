package dev.obiente.nextcloudnative.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Recursive, bounded and revision-guarded WebDAV adapter used by desktop sync. */
internal class DesktopFileSyncRemoteTree(
    private val session: NextcloudSession,
    private val userId: String,
    remoteRootPath: String,
    private val client: OkHttpClient = desktopFileSyncHttpClient(),
    private val onMutationCommitted: (relativePath: String) -> Unit = {},
    private val onAmbiguousMutationResult: (relativePath: String) -> Unit = onMutationCommitted,
    private val ownedUploadIds: Set<String> = emptySet(),
    private val ownedStageEtags: Map<String, String> = emptyMap(),
    private val ownedUploadPaths: Map<String, String> = emptyMap(),
    internal val ownedReplacementBackupEtags: Map<String, String> = emptyMap(),
) : LinuxVirtualWritebackRemote {
    private val rootPath = remoteRootPath.trim('/')
    private val mutationExecutor = DesktopHttpMutationExecutor(client)
    private val ownedDestinationPaths = ownedUploadPaths.mapValues { (_, path) -> fullPath(path) }

    init {
        require(ownedUploadIds.all(::isValidNextcloudChunkUploadId))
        require(ownedStageEtags.keys.all { it in ownedUploadIds })
        require(ownedUploadPaths.keys.all { it in ownedUploadIds })
        require(ownedReplacementBackupEtags.keys.all { it in ownedUploadIds })
        ownedUploadPaths.values.forEach(::requireValidSyncPath)
        require(ownedStageEtags.values.all { it.isNotBlank() && '\r' !in it && '\n' !in it })
        require(ownedReplacementBackupEtags.values.all { it.isNotBlank() && '\r' !in it && '\n' !in it })
    }

    fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
    ): List<DesktopRemoteSyncDocument> {
        val result = ArrayList<DesktopRemoteSyncDocument>()
        val pending = ArrayDeque<DesktopRemoteScanDirectory>()
        pending += DesktopRemoteScanDirectory("", fullPath(""))
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val logicalParent = directory.logicalRelativePath
            require(logicalParent.count { it == '/' } < MAX_DEPTH) {
                "The Nextcloud folder is nested too deeply."
            }
            listDirectory(directory.physicalPath).forEach { document ->
                val childName = document.entry.relativePath.substringAfterLast('/')
                val relativePath = listOf(logicalParent, childName)
                    .filter(String::isNotBlank)
                    .joinToString("/")
                if (jvmOwnedUploadId(relativePath) in ownedUploadIds) return@forEach
                val normalized = document.copy(entry = document.entry.copy(relativePath = relativePath))
                if (!includes(relativePath, normalized.entry.kind)) return@forEach
                require(result.size < MAX_ENTRIES) { "The Nextcloud folder contains too many entries." }
                result += normalized
                if (normalized.isDirectory) {
                    pending += DesktopRemoteScanDirectory(relativePath, document.physicalPath)
                }
            }
        }
        return result.sortedBy { it.entry.relativePath }
    }

    fun resolve(relativePath: String): DesktopRemoteSyncDocument? {
        requireValidSyncPath(relativePath)
        val parent = relativePath.substringBeforeLast('/', "")
        val target = fullPath(relativePath)
        return listDirectory(fullPath(parent)).firstOrNull { it.entry.relativePath == target }
            ?.let { it.copy(entry = it.entry.copy(relativePath = relativePath)) }
    }

    internal fun resolvePhysical(
        relativePath: String,
        shouldContinue: (() -> Boolean)? = null,
    ): DesktopRemoteSyncDocument? {
        requireValidSyncPath(relativePath)
        val target = fullPath(relativePath)
        return rawListDirectory(target.substringBeforeLast('/', ""), shouldContinue)
            .firstOrNull { it.entry.relativePath == target }
            ?.let { it.copy(entry = it.entry.copy(relativePath = relativePath)) }
    }

    override fun resolveFile(relativePath: String): RemoteSyncEntry? =
        resolve(relativePath)?.takeIf { !it.isDirectory }?.entry

    fun list(relativeDirectoryPath: String): List<DesktopRemoteSyncDocument> {
        val normalizedDirectory = relativeDirectoryPath.trim('/')
        if (normalizedDirectory.isNotBlank()) requireValidSyncPath(normalizedDirectory)
        return listDirectory(fullPath(normalizedDirectory)).mapNotNull { document ->
            val relativePath = toRelativePath(document.entry.relativePath) ?: return@mapNotNull null
            document.copy(entry = document.entry.copy(relativePath = relativePath))
        }.sortedBy { it.entry.relativePath }
    }

    fun isDirectoryEmpty(relativeDirectoryPath: String, expectedRemoteEtag: String): Boolean {
        val normalizedDirectory = relativeDirectoryPath.trim('/')
        requireValidSyncPath(normalizedDirectory)
        val fullDirectoryPath = fullPath(normalizedDirectory)
        val documents = executeDirectoryListing(directoryListingRequest(fullDirectoryPath))
        val directory = requireNotNull(
            documents.singleOrNull { document -> document.entry.relativePath == fullDirectoryPath },
        ) { "The server folder disappeared before it could be changed." }
        require(directory.isDirectory && directory.entry.etag == expectedRemoteEtag) {
            "The server folder changed before it could be changed."
        }
        return documents.none { document ->
            document.entry.relativePath != fullDirectoryPath &&
                document.entry.relativePath.substringBeforeLast('/', "") == fullDirectoryPath
        }
    }

    override fun stageDownload(
        relativePath: String,
        expectedRemoteEtag: String,
        destination: File,
        maximumBytes: Long,
    ): RemoteSyncEntry = stageDownload(
        relativePath,
        expectedRemoteEtag,
        destination,
        maximumBytes,
        beforeTransfer = {},
    )

    fun stageDownload(
        relativePath: String,
        expectedRemoteEtag: String,
        destination: File,
        maximumBytes: Long,
        beforeTransfer: (declaredBytes: Long?) -> Unit,
    ): RemoteSyncEntry {
        require(maximumBytes > 0L)
        val request = requestBuilder(fileUrl(fullPath(relativePath)))
            .header("Accept", "application/octet-stream")
            .header("If-Match", safeEtag(expectedRemoteEtag))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            response.requireAccepted(response.code == 200, "download file")
            val declared = response.body.contentLength()
            require(declared == -1L || declared <= maximumBytes) { "The server file exceeds the sync size limit." }
            beforeTransfer(declared.takeIf { it >= 0L })
            FileOutputStream(destination).use { output ->
                response.body.byteStream().copyBoundedTo(output, maximumBytes)
                output.fd.sync()
            }
            response.header("ETag")?.let { returned ->
                require(returned == expectedRemoteEtag) { "The server file changed while downloading." }
            }
        }
        val after = requireNotNull(resolve(relativePath)) { "The server file disappeared while downloading." }
        require(after.entry.etag == expectedRemoteEtag) { "The server file changed while downloading." }
        return after.entry
    }

    /** Streams one exact ETag generation into a digest without staging the remote file on disk. */
    fun verifyContentHash(
        relativePath: String,
        expectedRemoteEtag: String,
        expectedBytes: Long,
        expectedContentHash: String,
        maximumBytes: Long,
        shouldContinue: () -> Boolean,
        ownedStage: Boolean = false,
        physicalDestination: Boolean = false,
    ): Boolean {
        require(expectedBytes in 0L..maximumBytes)
        require(normalizeSyncSha256(expectedContentHash) == expectedContentHash)
        val request = requestBuilder(fileUrl(fullPath(relativePath)))
            .header("Accept", "application/octet-stream")
            .header("If-Match", safeEtag(expectedRemoteEtag))
            .get()
            .build()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        executeDesktopFileSyncCancellableCall(client.newCall(request), shouldContinue) { call ->
            call.execute().use { response ->
                require(response.code == 200) {
                    "The server rejected file content verification with HTTP ${response.code}."
                }
                val declared = response.body.contentLength()
                require(declared == -1L || declared == expectedBytes) {
                    "The server file size changed during content verification."
                }
                response.header("ETag")?.let { returned ->
                    require(returned == expectedRemoteEtag) {
                        "The server file changed during content verification."
                    }
                }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                response.body.byteStream().use { input ->
                    while (true) {
                        if (!shouldContinue()) throw DesktopFileSyncScanStoppedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maximumBytes) { "The server file exceeds the sync size limit." }
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }
        require(total == expectedBytes) { "The server returned truncated content during verification." }
        val after = requireNotNull(when {
            ownedStage -> resolveOwnedUploadStage(relativePath, shouldContinue)
            physicalDestination -> resolvePhysical(relativePath, shouldContinue)
            else -> resolvePhysical(relativePath, shouldContinue)
        }) {
            "The server file disappeared during content verification."
        }
        require(after.entry.etag == expectedRemoteEtag && !after.isDirectory) {
            "The server file changed during content verification."
        }
        val actual = "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return actual == expectedContentHash
    }

    fun contentRangeHash(
        relativePath: String,
        expectedRemoteEtag: String,
        expectedBytes: Long,
        offset: Long,
        length: Int,
        shouldContinue: () -> Boolean,
    ): String {
        require(offset >= 0L && length >= 0 && offset <= expectedBytes - length)
        if (length == 0) {
            require(expectedBytes == 0L)
            val after = requireNotNull(resolvePhysical(relativePath, shouldContinue)) {
                "The server file disappeared during content verification."
            }
            require(after.entry.etag == expectedRemoteEtag && after.entry.size == 0L && !after.isDirectory) {
                "The server file changed during content verification."
            }
            return hashExactJvmFileSyncSlice(java.io.ByteArrayInputStream(byteArrayOf()), 0)
        }
        val endInclusive = offset + length - 1L
        val request = requestBuilder(fileUrl(fullPath(relativePath)))
            .header("Accept", "application/octet-stream")
            .header("If-Match", safeEtag(expectedRemoteEtag))
            .header("Range", "bytes=$offset-$endInclusive")
            .get()
            .build()
        val hash = executeDesktopFileSyncCancellableCall(client.newCall(request), shouldContinue) { call ->
            call.execute().use { response ->
                require(response.code == 206) {
                    "The server did not honor bounded content verification (HTTP ${response.code})."
                }
                require(isExactHttpByteContentRange(response.header("Content-Range"), offset, endInclusive)) {
                    "The server returned a different content-verification range."
                }
                response.header("ETag")?.let { returned ->
                    require(returned == expectedRemoteEtag) { "The server file changed during content verification." }
                }
                hashExactJvmFileSyncSlice(
                    response.body.byteStream(),
                    length,
                    shouldContinue,
                    requireExhausted = true,
                )
            }
        }
        val after = requireNotNull(resolvePhysical(relativePath, shouldContinue)) {
            "The server file disappeared during content verification."
        }
        require(after.entry.etag == expectedRemoteEtag && after.entry.size == expectedBytes && !after.isDirectory) {
            "The server file changed during content verification."
        }
        return hash
    }

    fun createDirectory(relativePath: String, expectedRemoteEtag: String?) {
        val current = resolve(relativePath)
        if (expectedRemoteEtag != null) {
            require(current?.entry?.etag == expectedRemoteEtag && current.isDirectory) {
                "The server folder changed after the sync scan."
            }
            return
        }
        require(current == null) { "The server folder appeared after the sync scan." }
        executeMutation(
            requestBuilder(fileUrl(fullPath(relativePath)))
                .header("If-None-Match", "*")
                .method("MKCOL", EMPTY_BODY)
                .build(),
            "create folder",
            relativePath,
        )
    }

    fun replaceWithDirectory(relativePath: String, expectedRemoteEtag: String) {
        val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
        require(current.entry.etag == expectedRemoteEtag && !current.isDirectory) {
            "The server item changed after the sync scan."
        }
        val destinationPath = fullPath(relativePath)
        val backupPath = replacementBackupPath(destinationPath)
        val currentAtFullPath = current.withPath(destinationPath)
        moveRemoteDocument(currentAtFullPath, backupPath, relativePath)
        try {
            executeMutation(
                requestBuilder(fileUrl(destinationPath))
                    .header("If-None-Match", "*")
                    .method("MKCOL", EMPTY_BODY)
                    .build(),
                "replace item with folder",
                relativePath,
            )
            require(resolve(relativePath)?.isDirectory == true) {
                "The replacement server folder could not be verified."
            }
        } catch (failure: Throwable) {
            restoreRemoteBackup(destinationPath, backupPath, relativePath)
            throw failure
        }
        deleteRemoteBackup(backupPath)
    }

    override fun writeFile(relativePath: String, source: File, expectedRemoteEtag: String?): RemoteSyncEntry {
        return writeFileInternal(relativePath, source, expectedRemoteEtag, shouldContinue = null)
    }

    internal fun writeFileCancellable(
        relativePath: String,
        source: File,
        expectedRemoteEtag: String?,
        shouldContinue: () -> Boolean,
    ): RemoteSyncEntry = writeFileInternal(relativePath, source, expectedRemoteEtag, shouldContinue)

    private fun writeFileInternal(
        relativePath: String,
        source: File,
        expectedRemoteEtag: String?,
        shouldContinue: (() -> Boolean)?,
    ): RemoteSyncEntry {
        require(source.isFile)
        val current = if (shouldContinue == null) {
            resolve(relativePath)
        } else {
            resolvePhysical(relativePath, shouldContinue)
        }
        if (expectedRemoteEtag == null) {
            require(current == null) { "The server file appeared after the sync scan." }
            createFile(fullPath(relativePath), source, relativePath, shouldContinue = shouldContinue)
        } else {
            require(current?.entry?.etag == expectedRemoteEtag && !current.isDirectory) {
                "The server file changed after the sync scan."
            }
            replaceFileAtomically(
                fullPath(relativePath), source, expectedRemoteEtag, relativePath,
                shouldContinue = shouldContinue,
            )
        }
        val after = requireNotNull(
            if (shouldContinue == null) resolve(relativePath) else resolvePhysical(relativePath, shouldContinue),
        ) { "The uploaded server file disappeared." }
        require(!after.isDirectory) { "The uploaded server item is not a file." }
        return after.entry
    }

    internal fun ownedStageCreationAllowed(
        relativePath: String,
        shouldContinue: () -> Boolean,
    ): Boolean? {
        requireValidSyncPath(relativePath)
        val parent = relativePath.substringBeforeLast('/', "")
        val request = requestBuilder(fileUrl(fullPath(parent)))
            .header("Accept", "application/xml")
            .header("Depth", "0")
            .method("PROPFIND", DIRECTORY_PROPERTIES.toRequestBody(XML_CONTENT_TYPE))
            .build()
        return executeDesktopFileSyncCancellableCall(client.newCall(request), shouldContinue) { call ->
            call.execute().use { response ->
                response.requireAccepted(response.code == 207, "inspect upload destination")
                val directory = parseDesktopDavDirectoryAccess(
                    input = response.body.byteStream(),
                    userId = userId,
                    maximumBytes = MAX_ERROR_RESPONSE_BYTES,
                ) ?: error("The upload destination did not return one DAV record.")
                require(directory.relativePath == fullPath(parent)) {
                    "The upload destination returned a different DAV record."
                }
                require(directory.isDirectory) { "The upload destination is not a folder." }
                directory.permissions?.contains('C')
            }
        }
    }

    fun resumableUploadRemote(
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        replacingDirectoryEtag: String? = null,
    ) = DesktopFileSyncChunkUploadRemote(
        session = session,
        userId = userId,
        rootPath = rootPath,
        client = client,
        tree = this,
        onMutationCommitted = onMutationCommitted,
        onAmbiguousMutationResult = onAmbiguousMutationResult,
        shouldContinue = shouldContinue,
        replacingDirectoryEtag = replacingDirectoryEtag,
    )

    internal fun resolveOwnedUploadStage(
        relativePath: String,
        shouldContinue: (() -> Boolean)? = null,
    ): DesktopRemoteSyncDocument? {
        require(isJvmOwnedUploadStagePath(relativePath))
        val fullStagePath = fullPath(relativePath)
        return rawListDirectory(fullStagePath.substringBeforeLast('/', ""), shouldContinue)
            .firstOrNull { it.entry.relativePath == fullStagePath }
    }

    fun delete(relativePath: String, expectedRemoteEtag: String) {
        val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
        require(current.entry.etag == expectedRemoteEtag) { "The server item changed after the sync scan." }
        val url = fileUrl(fullPath(relativePath))
        val builder = requestBuilder(url)
        if (current.isDirectory) builder.header("If", "<$url> ([$expectedRemoteEtag])")
        else builder.header("If-Match", safeEtag(expectedRemoteEtag))
        executeMutation(builder.delete().build(), "delete item", relativePath)
    }

    fun move(sourceRelativePath: String, destinationRelativePath: String, expectedRemoteEtag: String) {
        requireValidSyncPath(sourceRelativePath)
        requireValidSyncPath(destinationRelativePath)
        require(resolve(destinationRelativePath) == null) { "The move destination already exists." }
        val current = requireNotNull(resolve(sourceRelativePath)) { "The server item was already removed." }
        require(current.entry.etag == expectedRemoteEtag) { "The server item changed before it could be moved." }
        moveRemoteDocument(
            current.withPath(fullPath(sourceRelativePath)),
            fullPath(destinationRelativePath),
            sourceRelativePath,
            destinationRelativePath,
        )
    }

    fun moveReplacing(
        sourceRelativePath: String,
        destinationRelativePath: String,
        expectedSourceEtag: String,
        expectedDestinationEtag: String,
    ) {
        requireValidSyncPath(sourceRelativePath)
        requireValidSyncPath(destinationRelativePath)
        val source = requireNotNull(resolve(sourceRelativePath)) { "The server source was already removed." }
        val destination = requireNotNull(resolve(destinationRelativePath)) {
            "The server destination was already removed."
        }
        require(source.entry.etag == expectedSourceEtag) { "The server source changed before it could be moved." }
        require(destination.entry.etag == expectedDestinationEtag) {
            "The server destination changed before it could be replaced."
        }
        require(source.isDirectory == destination.isDirectory) { "The move destination has a different item type." }
        val sourcePath = fullPath(sourceRelativePath)
        val destinationPath = fullPath(destinationRelativePath)
        val backupPath = replacementBackupPath(destinationPath)
        moveRemoteDocument(destination.withPath(destinationPath), backupPath, destinationRelativePath)
        try {
            moveRemoteDocument(
                source.withPath(sourcePath),
                destinationPath,
                sourceRelativePath,
                destinationRelativePath,
            )
            val published = requireNotNull(resolve(destinationRelativePath)) {
                "The moved server item could not be verified."
            }
            require(published.isDirectory == source.isDirectory) { "The moved server item type changed." }
        } catch (failure: Throwable) {
            restoreRemoteBackup(destinationPath, backupPath, destinationRelativePath)
            throw failure
        }
        deleteRemoteBackup(backupPath)
    }

    private fun listDirectory(path: String): List<DesktopRemoteSyncDocument> {
        var documents = rawListDirectory(path)
        var recovered = false
        val documentsByPath = documents.associateBy { document -> document.entry.relativePath }
        desktopOwnedBackupRecoveryPlan(
            documentsByPath.keys,
            ownedDestinationPaths,
            MAX_RECOVERY_ITEMS,
        ).forEach { (source, destination) ->
            val uploadId = requireNotNull(
                jvmOwnedReplacementBackupDestination(source, ownedDestinationPaths),
            ).second
            val expectedBackupEtag = requireNotNull(ownedReplacementBackupEtags[uploadId])
            val backup = requireNotNull(documentsByPath[source])
            require(backup.entry.etag == expectedBackupEtag) { "The owned replacement backup changed." }
            val recoveredRelativePath = toRelativePath(destination)
            moveRemoteDocument(
                backup,
                destination,
                *recoveredRelativePath?.let(::arrayOf).orEmpty(),
            )
            recovered = true
        }
        if (recovered) documents = rawListDirectory(path)
        return projectDesktopOwnedReplacementBackups(
            documents,
            ownedDestinationPaths,
            ownedStageEtags,
            MAX_RECOVERY_ITEMS,
        )
            .also { require(it.size <= MAX_CHILDREN) { "A Nextcloud folder contains too many entries." } }
    }

    internal fun rawListDirectory(
        path: String,
        shouldContinue: (() -> Boolean)? = null,
    ): List<DesktopRemoteSyncDocument> {
        val documents = executeDirectoryListing(directoryListingRequest(path), shouldContinue)
        val parent = path.trim('/')
        return documents
            .filter { it.entry.relativePath.substringBeforeLast('/', "") == parent }
            .also { require(it.size <= MAX_CHILDREN + MAX_RECOVERY_ITEMS) { "A Nextcloud folder contains too many entries." } }
    }

    private fun directoryListingRequest(path: String): Request = requestBuilder(fileUrl(path))
        .header("Accept", "application/xml")
        .header("Depth", "1")
        .method("PROPFIND", DIRECTORY_PROPERTIES.toRequestBody(XML_CONTENT_TYPE))
        .build()

    private fun executeDirectoryListing(
        request: Request,
        shouldContinue: (() -> Boolean)? = null,
    ): List<DesktopRemoteSyncDocument> {
        val consume = { call: okhttp3.Call -> call.execute().use { response ->
            response.requireAccepted(response.code == 207, "list folder")
            parseDesktopSyncDav(
                input = response.body.byteStream(),
                userId = userId,
                maximumBytes = MAX_DIRECTORY_RESPONSE_BYTES,
                maximumDocuments = MAX_CHILDREN + MAX_RECOVERY_ITEMS + 1,
            )
        } }
        val call = client.newCall(request)
        return if (shouldContinue == null) consume(call) else {
            executeDesktopFileSyncCancellableCall(call, shouldContinue, consume)
        }
    }

    private fun DesktopRemoteSyncDocument.withPath(path: String): DesktopRemoteSyncDocument =
        copy(entry = entry.copy(relativePath = path))

    internal fun replacementBackupPath(destinationPath: String): String {
        val parent = destinationPath.substringBeforeLast('/', "")
        val name = destinationPath.substringAfterLast('/')
        return listOf(parent, ".$name$BACKUP_MARKER${UUID.randomUUID()}")
            .filter(String::isNotBlank).joinToString("/")
    }

    internal fun moveRemoteDocument(
        source: DesktopRemoteSyncDocument,
        destinationPath: String,
        vararg mutationRelativePaths: String,
        shouldContinue: (() -> Boolean)? = null,
    ) {
        moveRemotePath(
            sourcePath = source.entry.relativePath,
            destinationPath = destinationPath,
            sourceEtag = source.entry.etag,
            sourceIsDirectory = source.isDirectory,
            mutationRelativePaths = mutationRelativePaths,
            shouldContinue = shouldContinue,
        )
    }

    internal fun moveRemotePath(
        sourcePath: String,
        destinationPath: String,
        sourceEtag: String?,
        sourceIsDirectory: Boolean,
        mutationRelativePaths: Array<out String> = emptyArray(),
        shouldContinue: (() -> Boolean)? = null,
    ) {
        val sourceUrl = fileUrl(sourcePath)
        val builder = requestBuilder(sourceUrl)
            .header("Destination", fileUrl(destinationPath))
            .header("Overwrite", "F")
        if (sourceEtag != null) {
            if (sourceIsDirectory) builder.header("If", "<$sourceUrl> ([${safeEtag(sourceEtag)}])")
            else builder.header("If-Match", safeEtag(sourceEtag))
        }
        executeMutation(
            builder.method("MOVE", EMPTY_BODY).build(),
            "move item",
            *mutationRelativePaths,
            shouldContinue = shouldContinue,
        )
    }

    internal fun restoreRemoteBackup(
        destinationPath: String,
        backupPath: String,
        vararg mutationRelativePaths: String,
        expectedBackupEtag: String? = null,
        shouldContinue: (() -> Boolean)? = null,
    ) {
        runCatching {
            val documents = rawListDirectory(destinationPath.substringBeforeLast('/', ""), shouldContinue)
            if (documents.none { it.entry.relativePath == destinationPath }) {
                documents.firstOrNull { it.entry.relativePath == backupPath }?.let { backup ->
                    require(expectedBackupEtag == null || backup.entry.etag == expectedBackupEtag) {
                        "The protected backup changed."
                    }
                    moveRemoteDocument(
                        backup,
                        destinationPath,
                        *mutationRelativePaths,
                        shouldContinue = shouldContinue,
                    )
                }
            }
        }
    }

    internal fun deleteRemoteBackup(backupPath: String) {
        runCatching {
            rawListDirectory(backupPath.substringBeforeLast('/', ""))
                .firstOrNull { it.entry.relativePath == backupPath }
                ?.let(::deleteRemoteDocument)
        }
    }

    internal fun deleteOwnedReplacementBackup(
        backupPath: String,
        expectedBackupEtag: String,
        shouldContinue: (() -> Boolean)? = null,
    ) {
        rawListDirectory(backupPath.substringBeforeLast('/', ""), shouldContinue)
            .firstOrNull { it.entry.relativePath == backupPath }
            ?.let { backup ->
                require(backup.isDirectory) { "The owned replacement backup changed type." }
                require(backup.entry.etag == expectedBackupEtag) { "The owned replacement backup changed." }
                deleteRemoteDocument(backup, shouldContinue)
            }
    }

    internal fun deleteRemoteDocument(
        document: DesktopRemoteSyncDocument,
        shouldContinue: (() -> Boolean)? = null,
    ) {
        val url = fileUrl(document.entry.relativePath)
        val builder = requestBuilder(url)
        if (document.isDirectory) builder.header("If", "<$url> ([${safeEtag(document.entry.etag)}])")
        else builder.header("If-Match", safeEtag(document.entry.etag))
        executeMutation(
            builder.delete().build(),
            "remove protected backup",
            shouldContinue = shouldContinue,
        )
    }

    private fun createFile(
        path: String,
        source: File,
        vararg mutationRelativePaths: String,
        shouldContinue: (() -> Boolean)? = null,
    ): String? = executeMutationForEtag(
        requestBuilder(fileUrl(path))
            .header("If-None-Match", "*")
            .put(cancellableFileRequestBody(source, shouldContinue))
            .build(),
        "create file",
        *mutationRelativePaths,
        shouldContinue = shouldContinue,
    )

    private fun replaceFileAtomically(
        path: String,
        source: File,
        expectedEtag: String,
        vararg mutationRelativePaths: String,
        shouldContinue: (() -> Boolean)? = null,
    ) {
        executeMutation(
            requestBuilder(fileUrl(path))
                .header("If-Match", safeEtag(expectedEtag))
                .put(cancellableFileRequestBody(source, shouldContinue))
                .build(),
            "replace file",
            *mutationRelativePaths,
            shouldContinue = shouldContinue,
        )
    }

    private fun cancellableFileRequestBody(source: File, shouldContinue: (() -> Boolean)?) =
        if (shouldContinue == null || source.length() == 0L) {
            source.asRequestBody(OCTET_STREAM)
        } else {
            jvmFileRangeRequestBody(source, 0L, source.length()) {
                if (!shouldContinue()) throw kotlinx.coroutines.CancellationException("Sync upload paused.")
            }
        }

    internal fun executeMutationForEtag(
        request: Request,
        operation: String,
        vararg mutationRelativePaths: String,
        shouldContinue: (() -> Boolean)? = null,
    ): String? = mutationExecutor.execute(
        request = request,
        onAmbiguousNetworkResult = { notifyAmbiguousMutationResult(*mutationRelativePaths) },
        onAcceptedResponse = { notifyMutationCommitted(*mutationRelativePaths) },
        shouldContinue = shouldContinue,
    ) { response ->
            response.requireAccepted(response.code in 200..299, operation)
            response.header("ETag") ?: response.header("OC-Etag")
        }

    private fun executeMutation(
        request: Request,
        operation: String,
        vararg mutationRelativePaths: String,
        expectedStatus: Int? = null,
        maximumResponseBytes: Long = MAX_ERROR_RESPONSE_BYTES,
        shouldContinue: (() -> Boolean)? = null,
    ): ByteArray = mutationExecutor.execute(
        request = request,
        onAmbiguousNetworkResult = { notifyAmbiguousMutationResult(*mutationRelativePaths) },
        onAcceptedResponse = { notifyMutationCommitted(*mutationRelativePaths) },
        shouldContinue = shouldContinue,
    ) { response ->
        val accepted = expectedStatus?.let { response.code == it } ?: (response.code in 200..299)
        response.requireAccepted(accepted, operation)
        response.body.byteStream().readBounded(maximumResponseBytes)
    }

    private fun execute(
        request: Request,
        operation: String,
        expectedStatus: Int? = null,
        maximumResponseBytes: Long = MAX_ERROR_RESPONSE_BYTES,
    ): ByteArray = client.newCall(request).execute().use { response ->
        val accepted = expectedStatus?.let { response.code == it } ?: (response.code in 200..299)
        response.requireAccepted(accepted, operation)
        response.body.byteStream().readBounded(maximumResponseBytes)
    }

    internal fun requestBuilder(url: String): Request.Builder {
        val authorization = Base64.getEncoder().encodeToString(
            "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
        )
        return Request.Builder().url(url)
            .header("Authorization", "Basic $authorization")
            .header("User-Agent", USER_AGENT)
    }

    internal fun fileUrl(path: String): String = buildNextcloudFileUrl(session.serverUrl, userId, path)

    internal fun fullPath(relativePath: String): String =
        listOf(rootPath, relativePath.trim('/')).filter(String::isNotBlank).joinToString("/")

    private fun toRelativePath(fullPath: String): String? {
        val normalized = fullPath.trim('/')
        if (rootPath.isBlank()) return normalized.takeIf(String::isNotBlank)
        return normalized.removePrefix("$rootPath/").takeIf { normalized.startsWith("$rootPath/") && it.isNotBlank() }
    }

    private fun notifyAmbiguousMutationResult(vararg relativePaths: String) {
        notifyMutation(onAmbiguousMutationResult, *relativePaths)
    }

    private fun notifyMutationCommitted(vararg relativePaths: String) {
        notifyMutation(onMutationCommitted, *relativePaths)
    }

    private fun notifyMutation(callback: (String) -> Unit, vararg relativePaths: String) {
        relativePaths.asSequence().map(String::trim).map { it.trim('/') }
            .filter(String::isNotBlank).distinct()
            .forEach { path -> runCatching { callback(path) } }
    }

    internal fun safeEtag(value: String): String = value.also {
        require(it.isNotBlank() && '\r' !in it && '\n' !in it) { "The server revision is invalid." }
    }

    private companion object {
        const val MAX_ENTRIES = MAX_FILE_SYNC_ENTRIES
        const val MAX_CHILDREN = 50_000
        const val MAX_RECOVERY_ITEMS = 32
        const val MAX_DEPTH = 64
        const val MAX_DIRECTORY_RESPONSE_BYTES = 128L * 1024L * 1024L
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024L
        const val USER_AGENT = "Nextcloud-Native/0.1.0 (Desktop file sync)"
        val XML_CONTENT_TYPE = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val EMPTY_BODY = byteArrayOf().toRequestBody(null)
        const val BACKUP_MARKER = ".nextcloud-native-backup-"
        val DIRECTORY_PROPERTIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop>
              <d:displayname/><d:getcontentlength/><d:getetag/><d:getlastmodified/><d:resourcetype/>
              <oc:permissions/>
            </d:prop></d:propfind>
        """.trimIndent()
    }
}

private fun java.io.InputStream.readBounded(maximumBytes: Long): ByteArray {
    val output = ByteArrayOutputStream()
    copyBoundedTo(output, maximumBytes)
    return output.toByteArray()
}

private fun java.io.InputStream.copyBoundedTo(output: java.io.OutputStream, maximumBytes: Long) {
    var total = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "The server response exceeds its safe size limit." }
        output.write(buffer, 0, count)
    }
}

private fun okhttp3.Response.requireAccepted(accepted: Boolean, operation: String): Unit =
    if (accepted) Unit else throw DesktopFileSyncHttpStatusException(code, operation)

private fun desktopFileSyncHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .readTimeout(FILE_SYNC_NETWORK_INACTIVITY_MINUTES, TimeUnit.MINUTES)
    .writeTimeout(FILE_SYNC_NETWORK_INACTIVITY_MINUTES, TimeUnit.MINUTES)
    .callTimeout(0L, TimeUnit.MILLISECONDS)
    .build()

private const val FILE_SYNC_NETWORK_INACTIVITY_MINUTES = 30L
