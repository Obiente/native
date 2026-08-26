package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal data class DesktopRemoteSyncDocument(
    val entry: RemoteSyncEntry,
    val isDirectory: Boolean,
    val lastModifiedEpochMillis: Long? = null,
)

/** Recursive, bounded and revision-guarded WebDAV adapter used by desktop sync. */
internal class DesktopFileSyncRemoteTree(
    private val session: NextcloudSession,
    private val userId: String,
    remoteRootPath: String,
    private val client: OkHttpClient = desktopFileSyncHttpClient(),
    private val onMutationCommitted: (relativePath: String) -> Unit = {},
    private val onAmbiguousMutationResult: (relativePath: String) -> Unit = onMutationCommitted,
    private val ownedUploadIds: Set<String> = emptySet(),
) : LinuxVirtualWritebackRemote {
    private val rootPath = remoteRootPath.trim('/')
    private val mutationExecutor = DesktopHttpMutationExecutor(client)

    init { require(ownedUploadIds.all(::isValidNextcloudChunkUploadId)) }

    fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
    ): List<DesktopRemoteSyncDocument> {
        val result = ArrayList<DesktopRemoteSyncDocument>()
        val pending = ArrayDeque<String>()
        pending += ""
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            require(parent.count { it == '/' } < MAX_DEPTH) { "The Nextcloud folder is nested too deeply." }
            listDirectory(fullPath(parent)).forEach { document ->
                val relativePath = toRelativePath(document.entry.relativePath) ?: return@forEach
                if (jvmOwnedUploadId(relativePath) in ownedUploadIds) return@forEach
                val normalized = document.copy(entry = document.entry.copy(relativePath = relativePath))
                if (!includes(relativePath, normalized.entry.kind)) return@forEach
                require(result.size < MAX_ENTRIES) { "The Nextcloud folder contains too many entries." }
                result += normalized
                if (normalized.isDirectory) pending += relativePath
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
        client.newCall(request).execute().use { response ->
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
        require(total == expectedBytes) { "The server returned truncated content during verification." }
        val after = requireNotNull(resolve(relativePath)) {
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
            val after = requireNotNull(resolve(relativePath)) {
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
        val hash = client.newCall(request).execute().use { response ->
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
        val after = requireNotNull(resolve(relativePath)) {
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
        require(source.isFile)
        val current = resolve(relativePath)
        if (expectedRemoteEtag == null) {
            require(current == null) { "The server file appeared after the sync scan." }
            createFile(fullPath(relativePath), source, relativePath)
        } else {
            require(current?.entry?.etag == expectedRemoteEtag && !current.isDirectory) {
                "The server file changed after the sync scan."
            }
            replaceFileAtomically(fullPath(relativePath), source, expectedRemoteEtag, relativePath)
        }
        val after = requireNotNull(resolve(relativePath)) { "The uploaded server file disappeared." }
        require(!after.isDirectory) { "The uploaded server item is not a file." }
        return after.entry
    }

    fun resumableUploadRemote(shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted }) = DesktopFileSyncChunkUploadRemote(
        session = session,
        userId = userId,
        rootPath = rootPath,
        client = client,
        tree = this,
        onMutationCommitted = onMutationCommitted,
        onAmbiguousMutationResult = onAmbiguousMutationResult,
        shouldContinue = shouldContinue,
    )

    internal fun resolveOwnedUploadStage(relativePath: String): DesktopRemoteSyncDocument? {
        require(isJvmOwnedUploadStagePath(relativePath))
        val fullStagePath = fullPath(relativePath)
        return rawListDirectory(fullStagePath.substringBeforeLast('/', ""))
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
        desktopOwnedBackupRecoveryPlan(documentsByPath.keys, MAX_RECOVERY_ITEMS).forEach { (source, destination) ->
            val recoveredRelativePath = toRelativePath(destination)
            moveRemoteDocument(
                requireNotNull(documentsByPath[source]),
                destination,
                *recoveredRelativePath?.let(::arrayOf).orEmpty(),
            )
            recovered = true
        }
        if (recovered) documents = rawListDirectory(path)
        val recoveredPaths = documents.mapTo(hashSetOf()) { it.entry.relativePath }
        return documents
            .filterNot { jvmOwnedUploadId(it.entry.relativePath) in ownedUploadIds }
            .filterNot { backup -> shouldSuppressDesktopOwnedBackup(backup.entry.relativePath, recoveredPaths) }
            .also { require(it.size <= MAX_CHILDREN) { "A Nextcloud folder contains too many entries." } }
    }

    private fun rawListDirectory(path: String): List<DesktopRemoteSyncDocument> {
        val documents = executeDirectoryListing(directoryListingRequest(path))
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

    private fun executeDirectoryListing(request: Request): List<DesktopRemoteSyncDocument> =
        client.newCall(request).execute().use { response ->
            response.requireAccepted(response.code == 207, "list folder")
            parseDesktopSyncDav(
                input = response.body.byteStream(),
                userId = userId,
                maximumBytes = MAX_DIRECTORY_RESPONSE_BYTES,
                maximumDocuments = MAX_CHILDREN + MAX_RECOVERY_ITEMS + 1,
            )
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
    ) {
        moveRemotePath(
            sourcePath = source.entry.relativePath,
            destinationPath = destinationPath,
            sourceEtag = source.entry.etag,
            sourceIsDirectory = source.isDirectory,
            mutationRelativePaths = mutationRelativePaths,
        )
    }

    internal fun moveRemotePath(
        sourcePath: String,
        destinationPath: String,
        sourceEtag: String?,
        sourceIsDirectory: Boolean,
        mutationRelativePaths: Array<out String> = emptyArray(),
    ) {
        val sourceUrl = fileUrl(sourcePath)
        val builder = requestBuilder(sourceUrl)
            .header("Destination", fileUrl(destinationPath))
            .header("Overwrite", "F")
        if (sourceEtag != null) {
            if (sourceIsDirectory) builder.header("If", "<$sourceUrl> ([${safeEtag(sourceEtag)}])")
            else builder.header("If-Match", safeEtag(sourceEtag))
        }
        executeMutation(builder.method("MOVE", EMPTY_BODY).build(), "move item", *mutationRelativePaths)
    }

    internal fun restoreRemoteBackup(
        destinationPath: String,
        backupPath: String,
        vararg mutationRelativePaths: String,
    ) {
        runCatching {
            val documents = rawListDirectory(destinationPath.substringBeforeLast('/', ""))
            if (documents.none { it.entry.relativePath == destinationPath }) {
                documents.firstOrNull { it.entry.relativePath == backupPath }
                    ?.let { moveRemoteDocument(it, destinationPath, *mutationRelativePaths) }
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

    private fun deleteRemoteDocument(document: DesktopRemoteSyncDocument) {
        val url = fileUrl(document.entry.relativePath)
        val builder = requestBuilder(url)
        if (document.isDirectory) builder.header("If", "<$url> ([${safeEtag(document.entry.etag)}])")
        else builder.header("If-Match", safeEtag(document.entry.etag))
        execute(builder.delete().build(), "remove protected backup")
    }

    private fun createFile(
        path: String,
        source: File,
        vararg mutationRelativePaths: String,
    ): String? = executeMutationForEtag(
        requestBuilder(fileUrl(path))
            .header("If-None-Match", "*")
            .put(source.asRequestBody(OCTET_STREAM))
            .build(),
        "create file",
        *mutationRelativePaths,
    )

    private fun replaceFileAtomically(
        path: String,
        source: File,
        expectedEtag: String,
        vararg mutationRelativePaths: String,
    ) {
        executeMutation(
            requestBuilder(fileUrl(path))
                .header("If-Match", safeEtag(expectedEtag))
                .put(source.asRequestBody(OCTET_STREAM))
                .build(),
            "replace file",
            *mutationRelativePaths,
        )
    }

    internal fun executeMutationForEtag(
        request: Request,
        operation: String,
        vararg mutationRelativePaths: String,
    ): String? = mutationExecutor.execute(
        request = request,
        onAmbiguousNetworkResult = { notifyAmbiguousMutationResult(*mutationRelativePaths) },
        onAcceptedResponse = { notifyMutationCommitted(*mutationRelativePaths) },
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
    ): ByteArray = mutationExecutor.execute(
        request = request,
        onAmbiguousNetworkResult = { notifyAmbiguousMutationResult(*mutationRelativePaths) },
        onAcceptedResponse = { notifyMutationCommitted(*mutationRelativePaths) },
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
            <d:propfind xmlns:d="DAV:"><d:prop>
              <d:displayname/><d:getcontentlength/><d:getetag/><d:getlastmodified/><d:resourcetype/>
            </d:prop></d:propfind>
        """.trimIndent()
    }
}

internal fun isDesktopOwnedUploadStage(relativePath: String): Boolean {
    return isJvmOwnedUploadStagePath(relativePath)
}

internal fun desktopOwnedBackupDestination(relativePath: String): String? {
    val name = relativePath.substringAfterLast('/')
    val markerIndex = name.lastIndexOf(".nextcloud-native-backup-")
    if (!name.startsWith('.') || markerIndex <= 1) return null
    val token = name.substring(markerIndex + ".nextcloud-native-backup-".length)
    if (runCatching { UUID.fromString(token) }.isFailure) return null
    val destinationName = name.substring(1, markerIndex)
    if (destinationName.isBlank()) return null
    val parent = relativePath.substringBeforeLast('/', "")
    return listOf(parent, destinationName).filter(String::isNotBlank).joinToString("/")
}

internal fun shouldSuppressDesktopOwnedBackup(
    relativePath: String,
    listedPaths: Set<String>,
): Boolean {
    val destination = desktopOwnedBackupDestination(relativePath) ?: return false
    return destination !in listedPaths
}

internal fun desktopOwnedBackupRecoveryPlan(
    relativePaths: Collection<String>,
    maximumRecoveryItems: Int,
): List<Pair<String, String>> {
    require(maximumRecoveryItems >= 0)
    val listedPaths = relativePaths.toHashSet()
    val recoveries = relativePaths.mapNotNull { source ->
        desktopOwnedBackupDestination(source)?.let { destination -> source to destination }
    }.filterNot { (_, destination) -> destination in listedPaths }
    require(recoveries.size <= maximumRecoveryItems) { "A Nextcloud folder contains too many recovery items." }
    return recoveries
}

internal fun parseDesktopSyncDav(
    bytes: ByteArray,
    userId: String,
    maximumDocuments: Int = MAX_PARSED_DAV_DOCUMENTS,
): List<DesktopRemoteSyncDocument> {
    return parseDesktopSyncDav(ByteArrayInputStream(bytes), userId, bytes.size.toLong(), maximumDocuments)
}

private fun parseDesktopSyncDav(
    input: InputStream,
    userId: String,
    maximumBytes: Long,
    maximumDocuments: Int,
): List<DesktopRemoteSyncDocument> {
    require(maximumBytes > 0L && maximumDocuments > 0)
    val factory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.IS_COALESCING, false)
    }
    val reader = factory.createXMLStreamReader(
        GuardedXmlInputStream(BoundedInputStream(input, maximumBytes)),
        StandardCharsets.UTF_8.name(),
    )
    val documents = ArrayList<DesktopRemoteSyncDocument>()
    var responseCount = 0
    var response: DesktopDavResponseBuilder? = null
    var textField: String? = null
    val text = StringBuilder()
    try {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    if (reader.namespaceURI == DAV_NAMESPACE) {
                        when (reader.localName) {
                            "response" -> {
                                require(responseCount < maximumDocuments) {
                                    "A Nextcloud folder contains too many entries."
                                }
                                responseCount += 1
                                response = DesktopDavResponseBuilder()
                            }
                            "href", "getetag", "getcontentlength", "getlastmodified" -> if (response != null) {
                                textField = reader.localName
                                text.clear()
                            }
                            "collection" -> response?.isDirectory = true
                        }
                    }
                }
                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                -> if (textField != null) {
                    val eventLength = reader.textLength
                    require(eventLength <= MAX_DAV_PROPERTY_CHARS - text.length) {
                        "A DAV property is too large."
                    }
                    text.append(reader.textCharacters, reader.textStart, eventLength)
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.namespaceURI == DAV_NAMESPACE && reader.localName == textField) {
                        response?.set(reader.localName, text.toString())
                        textField = null
                        text.clear()
                    }
                    if (reader.namespaceURI == DAV_NAMESPACE && reader.localName == "response") {
                        response?.toDocument(userId)?.let { document ->
                            require(documents.size < maximumDocuments) {
                                "A Nextcloud folder contains too many entries."
                            }
                            documents.add(document)
                        }
                        response = null
                    }
                }
            }
        }
    } finally {
        reader.close()
    }
    return documents
}

private class DesktopDavResponseBuilder {
    private var href: String? = null
    private var etag: String? = null
    private var contentLength: String? = null
    private var lastModified: String? = null
    var isDirectory: Boolean = false

    fun set(name: String, value: String) {
        when (name) {
            "href" -> href = value
            "getetag" -> etag = value
            "getcontentlength" -> contentLength = value
            "getlastmodified" -> lastModified = value
        }
    }

    fun toDocument(userId: String): DesktopRemoteSyncDocument? {
        val encodedHref = href ?: return null
        val decoded = URLDecoder.decode(encodedHref.replace("+", "%2B"), StandardCharsets.UTF_8)
        val path = decoded.substringAfter("/files/$userId/", "").trim('/')
        if (path.isBlank()) return null
        val revision = etag?.takeIf(String::isNotBlank) ?: error("A server item has no usable revision.")
        val modifiedEpochMillis = lastModified?.let(::parseDesktopSyncDavTimestamp)
        return DesktopRemoteSyncDocument(
            RemoteSyncEntry(
                relativePath = path,
                kind = if (isDirectory) SyncEntryKind.Directory else SyncEntryKind.File,
                etag = revision,
                size = contentLength?.toLongOrNull()?.takeIf { !isDirectory },
                modifiedEpochMillis = modifiedEpochMillis,
            ),
            isDirectory,
            lastModifiedEpochMillis = modifiedEpochMillis,
        )
    }
}

internal fun parseDesktopSyncDavTimestamp(value: String): Long? = runCatching {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        .takeIf { it >= 0L }
}.getOrNull()

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

private const val MAX_DAV_PROPERTY_CHARS = 16_384
private const val MAX_PARSED_DAV_DOCUMENTS = 50_032
private const val DAV_NAMESPACE = "DAV:"
private const val FILE_SYNC_NETWORK_INACTIVITY_MINUTES = 30L
