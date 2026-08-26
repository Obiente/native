package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.JvmResumableNextcloudUploadRemote
import dev.obiente.nextcloudnative.app.JvmExactFileComparisonOutputStream
import dev.obiente.nextcloudnative.app.NextcloudUploadChunk
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.isValidNextcloudChunkUploadId
import dev.obiente.nextcloudnative.app.jvmOwnedUploadId
import dev.obiente.nextcloudnative.app.jvmOwnedReplacementBackup
import dev.obiente.nextcloudnative.app.jvmOwnedReplacementBackupPath
import dev.obiente.nextcloudnative.app.jvmOwnedUploadStagePath
import dev.obiente.nextcloudnative.app.normalizeSyncSha256
import dev.obiente.nextcloudnative.app.safeIncomingShareFileName
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal data class AndroidRemoteSyncDocument(
    val entry: RemoteSyncEntry,
    val isDirectory: Boolean,
)

internal data class AndroidRemoteChildNameSnapshot(
    val names: Set<String>,
    val complete: Boolean,
)

private data class AndroidRemoteScanDirectory(
    val logicalRelativePath: String,
    val physicalPath: String,
)

private data class AndroidRemoteScanChild(
    val file: NextcloudFile,
    val logicalRelativePath: String,
)

/** Recursive, bounded and revision-guarded view of one Nextcloud Files subtree. */
internal class AndroidFileSyncRemoteTree(
    private val session: NextcloudSession,
    private val userId: String,
    remoteRootPath: String,
    private val webDav: NextcloudDocumentWebDav,
    private val ownedUploadIds: Set<String> = emptySet(),
    private val transferCancellation: DocumentRequestCancellation = AndroidFileSyncRunCancellation {
        !Thread.currentThread().isInterrupted
    },
) : JvmResumableNextcloudUploadRemote {
    private val rootPath = remoteRootPath.trim('/')

    init {
        require(ownedUploadIds.all(::isValidNextcloudChunkUploadId))
    }

    fun shouldContinueTransfer(): Boolean {
        transferCancellation.throwIfCancelled()
        return true
    }

    fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
    ): List<AndroidRemoteSyncDocument> {
        val result = ArrayList<AndroidRemoteSyncDocument>()
        val pending = ArrayDeque<AndroidRemoteScanDirectory>()
        pending += AndroidRemoteScanDirectory("", fullPath(""))
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val relativeParent = directory.logicalRelativePath
            require(relativeParent.count { it == '/' } < MAX_DEPTH) { "The Nextcloud folder is nested too deeply." }
            val listing = listSyncDirectoryAt(directory.physicalPath)
            require(!listing.limited) { "A Nextcloud folder contains too many entries to sync safely." }
            scanChildren(relativeParent, listing).forEach { child ->
                val file = child.file
                val relativePath = child.logicalRelativePath
                if (jvmOwnedUploadId(relativePath) in ownedUploadIds) return@forEach
                val kind = if (file.isDirectory) SyncEntryKind.Directory else SyncEntryKind.File
                if (!includes(relativePath, kind)) return@forEach
                require(result.size < MAX_ENTRIES) { "The Nextcloud folder contains too many entries." }
                val etag = file.etag?.takeIf(String::isNotBlank)
                    ?: error("Refresh failed because ${file.name} has no server revision.")
                val document = AndroidRemoteSyncDocument(
                    entry = RemoteSyncEntry(
                        relativePath = relativePath,
                        kind = kind,
                        etag = etag,
                        size = if (file.isDirectory) null else file.size,
                        modifiedEpochMillis = file.lastModified.androidFileSyncModifiedEpochMillis(),
                        contentHash = if (file.isDirectory) {
                            null
                        } else {
                            file.checksums.firstNotNullOfOrNull(::normalizeSyncSha256)
                        },
                    ),
                    isDirectory = file.isDirectory,
                )
                result += document
                if (file.isDirectory) {
                    pending += AndroidRemoteScanDirectory(relativePath, file.path.trim('/'))
                }
            }
        }
        return result.sortedBy { it.entry.relativePath }
    }

    fun resolve(relativePath: String): AndroidRemoteSyncDocument? {
        if (jvmOwnedUploadId(relativePath) in ownedUploadIds) return null
        return resolveIncludingOwnedStage(relativePath)
    }

    private fun resolveIncludingOwnedStage(relativePath: String): AndroidRemoteSyncDocument? {
        val parent = relativePath.substringBeforeLast('/', "")
        val target = fullPath(relativePath)
        return listSyncDirectory(parent)
            .files
            .firstOrNull { it.path.trim('/') == target }
            ?.let { file ->
                val etag = file.etag?.takeIf(String::isNotBlank)
                    ?: error("The server item has no usable revision.")
                AndroidRemoteSyncDocument(
                    RemoteSyncEntry(
                        relativePath = relativePath,
                        kind = if (file.isDirectory) SyncEntryKind.Directory else SyncEntryKind.File,
                        etag = etag,
                        size = if (file.isDirectory) null else file.size,
                        modifiedEpochMillis = file.lastModified.androidFileSyncModifiedEpochMillis(),
                        contentHash = if (file.isDirectory) {
                            null
                        } else {
                            file.checksums.firstNotNullOfOrNull(::normalizeSyncSha256)
                        },
                    ),
                    file.isDirectory,
                )
            }
    }

    fun stageDownload(
        relativePath: String,
        expectedRemoteEtag: String,
        destination: File,
        maximumBytes: Long,
    ): RemoteSyncEntry = FileOutputStream(destination).use { output ->
        streamDownload(relativePath, expectedRemoteEtag, output, maximumBytes).also { output.fd.sync() }
    }

    fun streamDownload(
        relativePath: String,
        expectedRemoteEtag: String,
        destination: OutputStream,
        maximumBytes: Long,
    ): RemoteSyncEntry {
        webDav.readFile(
            session = session,
            userId = userId,
            path = fullPath(relativePath),
            destination = destination,
            maximumBytes = maximumBytes,
            expectedEtag = expectedRemoteEtag,
        )
        val after = requireNotNull(resolve(relativePath)) { "The server file disappeared while downloading." }
        require(after.entry.etag == expectedRemoteEtag) {
            "The server file changed while downloading."
        }
        return after.entry
    }

    /**
     * Verifies a DAV checksum hint against bytes read from the exact ETag generation.
     *
     * Nextcloud documents that regular-upload checksum properties are client supplied and are not
     * always server validated. They can narrow candidates, but only this bounded GET makes them
     * safe evidence for automatically accepting identical local and remote content.
     */
    fun verifyContentHash(
        relativePath: String,
        expectedRemoteEtag: String,
        expectedContentHash: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(byte: Int) {
                digest.update(byte.toByte())
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                digest.update(bytes, offset, length)
            }
        }
        val result = webDav.readFile(
            session = session,
            userId = userId,
            path = fullPath(relativePath),
            destination = sink,
            maximumBytes = maximumBytes,
            expectedEtag = expectedRemoteEtag,
        )
        require(result.byteCount == expectedBytes) { "The server returned truncated content during verification." }
        require(result.etag == null || result.etag == expectedRemoteEtag) {
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
    ): String {
        val hash = webDav.readFileRangeHash(
            session = session,
            userId = userId,
            path = fullPath(relativePath),
            expectedEtag = expectedRemoteEtag,
            expectedBytes = expectedBytes,
            offset = offset,
            length = length,
        )
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
        if (expectedRemoteEtag == null) {
            require(current == null) { "The server folder appeared after the sync scan." }
            webDav.createFolder(session, userId, fullPath(relativePath))
        } else {
            require(current?.entry?.etag == expectedRemoteEtag) {
                "The server folder changed after the sync scan."
            }
            require(current.isDirectory)
        }
    }

    fun writeFile(relativePath: String, source: File, expectedRemoteEtag: String?): RemoteSyncEntry {
        val current = resolve(relativePath)
        if (expectedRemoteEtag == null) {
            require(current == null) { "The server file appeared after the sync scan." }
            webDav.createFile(
                session, userId, fullPath(relativePath), source,
                cancellation = transferCancellation,
            )
        } else {
            require(current?.entry?.etag == expectedRemoteEtag) {
                "The server file changed after the sync scan."
            }
            require(!current.isDirectory) { "The server item changed type." }
            webDav.replaceFile(
                session,
                userId,
                fullPath(relativePath),
                source,
                expectedRemoteEtag,
                transferCancellation,
            )
        }
        val after = requireNotNull(resolve(relativePath)) { "The uploaded server file disappeared." }
        require(!after.isDirectory) { "The uploaded server item is not a file." }
        return after.entry
    }

    override fun uploadDirect(
        source: File,
        relativePath: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry = writeFile(relativePath, source, expectedRemoteEtag)

    override fun verifyDirectUpload(
        source: File,
        relativePath: String,
        uploaded: RemoteSyncEntry,
    ): RemoteSyncEntry {
        val exact = requireNotNull(resolveIncludingOwnedStage(relativePath)) {
            "The directly uploaded file disappeared."
        }
        require(!exact.isDirectory && exact.entry.etag == uploaded.etag && exact.entry.size == source.length()) {
            "The directly uploaded file changed before verification."
        }
        JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
            webDav.readFile(
                session = session,
                userId = userId,
                path = fullPath(relativePath),
                destination = comparison,
                maximumBytes = source.length().coerceAtLeast(1L),
                expectedEtag = exact.entry.etag,
                cancellation = transferCancellation,
            )
            comparison.requireComplete()
        }
        return exact.entry
    }

    override fun createChunkCollection(
        uploadId: String,
        relativePath: String,
        allowExisting: Boolean,
    ): Boolean = createChunkUpload(
        uploadId,
        jvmOwnedUploadStagePath(relativePath, uploadId),
        allowExisting,
        transferCancellation,
    )

    override fun uploadChunk(
        uploadId: String,
        relativePath: String,
        source: File,
        chunk: NextcloudUploadChunk,
    ) = uploadChunk(
        uploadId,
        jvmOwnedUploadStagePath(relativePath, uploadId),
        source,
        chunk.offsetBytes,
        chunk.sizeBytes,
        chunk.number,
        transferCancellation,
    )

    override fun listChunkCollection(uploadId: String): Map<Int, Long> =
        webDav.listChunkUpload(session, userId, uploadId, transferCancellation)

    override fun deleteChunk(uploadId: String, chunkNumber: Int) =
        webDav.deleteChunk(session, userId, uploadId, chunkNumber, transferCancellation)

    override fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long): String? =
        commitChunkUpload(
            uploadId,
            jvmOwnedUploadStagePath(relativePath, uploadId),
            sizeBytes,
            transferCancellation,
            onRequestStarted = {},
        ).etag

    override fun verifyOwnedStage(
        uploadId: String,
        relativePath: String,
        source: File,
        expectedStageEtag: String?,
    ): String {
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stage = requireNotNull(resolveIncludingOwnedStage(stagePath)) {
            "The assembled upload stage disappeared."
        }
        require(!stage.isDirectory && stage.entry.size == source.length()) {
            "The assembled upload stage has an unexpected size."
        }
        require(expectedStageEtag == null || stage.entry.etag == expectedStageEtag) {
            "The assembled upload stage changed before verification."
        }
        JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
            webDav.readFile(
                session = session,
                userId = userId,
                path = fullPath(stagePath),
                destination = comparison,
                maximumBytes = source.length().coerceAtLeast(1L),
                expectedEtag = stage.entry.etag,
                cancellation = transferCancellation,
            )
            comparison.requireComplete()
        }
        return stage.entry.etag
    }

    override fun ownedStageEtag(uploadId: String, relativePath: String): String? =
        resolveIncludingOwnedStage(jvmOwnedUploadStagePath(relativePath, uploadId))?.entry?.etag

    override fun resolvePublishedFile(relativePath: String): RemoteSyncEntry? =
        resolveIncludingOwnedStage(relativePath)?.takeUnless { it.isDirectory }?.entry

    internal fun requireDirectoryGeneration(relativePath: String, expectedRemoteEtag: String) {
        val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
        require(current.isDirectory && current.entry.etag == expectedRemoteEtag) {
            "The server directory changed after the sync scan."
        }
    }

    internal fun publishOwnedStageReplacingDirectory(
        uploadId: String,
        relativePath: String,
        verifiedStageEtag: String,
        expectedDirectoryEtag: String,
    ): RemoteSyncEntry {
        requireDirectoryGeneration(relativePath, expectedDirectoryEtag)
        val backupPath = jvmOwnedReplacementBackupPath(relativePath, uploadId)
        require(resolveIncludingOwnedStage(backupPath) == null) { "The replacement backup already exists." }
        try {
            webDav.moveDirectory(
                session,
                userId,
                fullPath(relativePath),
                fullPath(backupPath),
                expectedDirectoryEtag,
            )
            webDav.publishChunkUploadStage(
                session,
                userId,
                fullPath(jvmOwnedUploadStagePath(relativePath, uploadId)),
                fullPath(relativePath),
                verifiedStageEtag,
                expectedRemoteEtag = null,
            )
            val published = requireNotNull(resolveIncludingOwnedStage(relativePath)) {
                "The uploaded server file disappeared."
            }
            require(!published.isDirectory) { "The uploaded server item is not a file." }
            completeReplacementBackup(relativePath, uploadId)
            return published.entry
        } catch (failure: Throwable) {
            restoreReplacementBackupIfDestinationMissing(relativePath, uploadId)
            throw failure
        }
    }

    internal fun completeReplacementBackup(relativePath: String, uploadId: String) {
        val backupPath = jvmOwnedReplacementBackupPath(relativePath, uploadId)
        resolveIncludingOwnedStage(backupPath)?.let { backup ->
            require(backup.isDirectory) { "The owned replacement backup changed type." }
            webDav.delete(session, userId, fullPath(backupPath), backup.entry.etag, isDirectory = true)
        }
    }

    override fun publishOwnedStage(
        uploadId: String,
        relativePath: String,
        verifiedStageEtag: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry {
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        webDav.publishChunkUploadStage(
            session,
            userId,
            fullPath(stagePath),
            fullPath(relativePath),
            verifiedStageEtag,
            expectedRemoteEtag,
        )
        val after = requireNotNull(resolve(relativePath)) { "The uploaded server file disappeared." }
        require(!after.isDirectory)
        return after.entry
    }

    override fun discardOwnedUpload(
        uploadId: String,
        relativePath: String,
        assembledStageEtag: String?,
    ): Boolean {
        deleteChunkUpload(uploadId, transferCancellation)
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stageCleaned = if (assembledStageEtag == null) {
            resolveIncludingOwnedStage(stagePath) == null
        } else {
            try {
                webDav.delete(session, userId, fullPath(stagePath), assembledStageEtag, isDirectory = false)
            } catch (failure: DocumentWebDavException) {
                if (failure.error !in setOf(DocumentWebDavError.NotFound, DocumentWebDavError.Conflict)) throw failure
            }
            true
        }
        return stageCleaned && discardReplacementBackup(
            relativePath,
            uploadId,
            assembledStageEtag,
        )
    }

    /** Lists known destination names once; [complete] is false when the bounded DAV page was truncated. */
    fun rootChildNames(): AndroidRemoteChildNameSnapshot {
        val listing = try {
            listSyncDirectory("")
        } catch (failure: DocumentWebDavException) {
            if (failure.error == DocumentWebDavError.TooLarge) {
                return AndroidRemoteChildNameSnapshot(emptySet(), complete = false)
            }
            throw failure
        }
        return AndroidRemoteChildNameSnapshot(
            names = listing.files
                .filterNot { file -> jvmOwnedUploadId(file.path) in ownedUploadIds }
                .filterNot { file -> jvmOwnedReplacementBackup(file.path.trim('/'))?.second in ownedUploadIds }
                .mapTo(mutableSetOf()) { file -> file.path.trim('/').substringAfterLast('/') },
            complete = !listing.limited,
        )
    }

    fun resourceExists(
        relativePath: String,
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): Boolean = webDav.resourceExists(session, userId, fullPath(relativePath), cancellation)

    /** Performs a conditional create without inferring absence from a directory listing. */
    fun createFileIfAbsent(
        relativePath: String,
        source: File,
        onRequestStarted: () -> Unit,
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ) {
        require(safeIncomingShareFileName(relativePath, 0) == relativePath) {
            "The incoming share filename is invalid."
        }
        webDav.createFile(
            session = session,
            userId = userId,
            path = fullPath(relativePath),
            source = source,
            onRequestStarted = onRequestStarted,
            cancellation = cancellation,
        )
    }

    fun directoryAccess(
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): DocumentDirectoryAccess = webDav.inspectDirectoryAccess(session, userId, fullPath(""), cancellation)

    fun createChunkUpload(
        uploadId: String,
        relativePath: String,
        allowExistingSession: Boolean,
        cancellation: DocumentRequestCancellation,
    ): Boolean = webDav.createChunkUpload(
        session,
        userId,
        uploadId,
        fullPath(relativePath),
        allowExistingSession,
        cancellation,
    )

    fun uploadChunk(
        uploadId: String,
        relativePath: String,
        source: File,
        offset: Long,
        length: Long,
        chunkNumber: Int,
        cancellation: DocumentRequestCancellation,
    ) = webDav.uploadChunk(
        session,
        userId,
        uploadId,
        fullPath(relativePath),
        source,
        offset,
        length,
        source.length(),
        chunkNumber,
        cancellation,
    )

    fun deleteChunkUpload(
        uploadId: String,
        cancellation: DocumentRequestCancellation,
    ) = webDav.deleteChunkUpload(session, userId, uploadId, cancellation)

    fun commitChunkUpload(
        uploadId: String,
        relativePath: String,
        sourceLength: Long,
        cancellation: DocumentRequestCancellation,
        onRequestStarted: () -> Unit,
    ) = webDav.commitChunkUpload(
        session,
        userId,
        uploadId,
        fullPath(relativePath),
        sourceLength,
        cancellation,
        onRequestStarted,
    )

    fun delete(relativePath: String, expectedRemoteEtag: String) {
        val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
        require(current.entry.etag == expectedRemoteEtag) {
            "The server item changed after the sync scan."
        }
        webDav.delete(
            session,
            userId,
            fullPath(relativePath),
            expectedRemoteEtag,
            isDirectory = current.isDirectory,
        )
    }

    private fun listSyncDirectory(relativeParent: String): DocumentDirectoryResult =
        listSyncDirectoryAt(fullPath(relativeParent))

    private fun listSyncDirectoryAt(directoryPath: String): DocumentDirectoryResult {
        var listing = webDav.listDirectory(session, userId, directoryPath, MAX_CHILDREN)
        val listedPaths = listing.files.mapTo(mutableSetOf()) { it.path.trim('/') }
        val ownedBackups = listing.files.mapNotNull { file ->
            val parsed = jvmOwnedReplacementBackup(file.path.trim('/')) ?: return@mapNotNull null
            parsed.takeIf { (_, uploadId) -> uploadId in ownedUploadIds }?.let { parsed to file }
        }
        require(ownedBackups.map { it.first.first }.distinct().size == ownedBackups.size) {
            "A Nextcloud folder contains duplicate owned replacement backups."
        }
        var recovered = false
        ownedBackups.forEach { (parsed, file) ->
            val destination = parsed.first
            if (destination in listedPaths) return@forEach
            require(file.isDirectory) { "The owned replacement backup changed type." }
            val backupEtag = requireNotNull(file.etag?.takeIf(String::isNotBlank)) {
                "The owned replacement backup has no usable revision."
            }
            webDav.moveDirectory(session, userId, file.path.trim('/'), destination, backupEtag)
            recovered = true
        }
        if (recovered) {
            listing = webDav.listDirectory(session, userId, directoryPath, MAX_CHILDREN)
        }
        return listing
    }

    private fun scanChildren(
        logicalRelativeParent: String,
        listing: DocumentDirectoryResult,
    ): List<AndroidRemoteScanChild> {
        val ownedBackups = listing.files.mapNotNull { file ->
            val parsed = jvmOwnedReplacementBackup(file.path.trim('/')) ?: return@mapNotNull null
            parsed.takeIf { (_, uploadId) -> uploadId in ownedUploadIds }?.let { parsed.first to file }
        }
        require(ownedBackups.map { it.first }.distinct().size == ownedBackups.size) {
            "A Nextcloud folder contains duplicate owned replacement backups."
        }
        val protectedDestinations = ownedBackups.mapTo(mutableSetOf()) { it.first }
        val ordinary = listing.files.mapNotNull { file ->
            val physicalPath = file.path.trim('/')
            if (
                physicalPath in protectedDestinations ||
                jvmOwnedReplacementBackup(physicalPath)?.second in ownedUploadIds
            ) {
                return@mapNotNull null
            }
            val name = physicalPath.substringAfterLast('/')
            AndroidRemoteScanChild(file, logicalChildPath(logicalRelativeParent, name))
        }
        return ordinary + ownedBackups.map { (destination, backup) ->
            AndroidRemoteScanChild(
                file = backup,
                logicalRelativePath = logicalChildPath(
                    logicalRelativeParent,
                    destination.substringAfterLast('/'),
                ),
            )
        }
    }

    private fun restoreReplacementBackupIfDestinationMissing(relativePath: String, uploadId: String) {
        runCatching {
            if (resolveIncludingOwnedStage(relativePath) != null) return@runCatching
            val backupPath = jvmOwnedReplacementBackupPath(relativePath, uploadId)
            val backup = resolveIncludingOwnedStage(backupPath) ?: return@runCatching
            require(backup.isDirectory)
            webDav.moveDirectory(session, userId, fullPath(backupPath), fullPath(relativePath), backup.entry.etag)
        }
    }

    private fun discardReplacementBackup(
        relativePath: String,
        uploadId: String,
        assembledStageEtag: String?,
    ): Boolean {
        val backupPath = jvmOwnedReplacementBackupPath(relativePath, uploadId)
        if (!webDav.resourceExists(session, userId, fullPath(backupPath), transferCancellation)) return true
        val backup = resolveIncludingOwnedStage(backupPath) ?: return true
        require(backup.isDirectory) { "The owned replacement backup changed type." }
        val destination = resolveIncludingOwnedStage(relativePath)
        if (destination == null) {
            webDav.moveDirectory(session, userId, fullPath(backupPath), fullPath(relativePath), backup.entry.etag)
            return true
        }
        if (!destination.isDirectory && assembledStageEtag != null && destination.entry.etag == assembledStageEtag) {
            webDav.delete(session, userId, fullPath(relativePath), destination.entry.etag, isDirectory = false)
            webDav.moveDirectory(session, userId, fullPath(backupPath), fullPath(relativePath), backup.entry.etag)
            return true
        }
        return false
    }

    private fun fullPath(relativePath: String): String =
        listOf(rootPath, relativePath.trim('/')).filter(String::isNotBlank).joinToString("/")

    private fun logicalChildPath(parent: String, name: String): String =
        listOf(parent, name).filter(String::isNotBlank).joinToString("/")

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_CHILDREN = 5_000
        const val MAX_DEPTH = 64
    }
}

internal fun String?.androidFileSyncModifiedEpochMillis(): Long? = this?.let { value ->
    runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()?.takeIf { it >= 0L }
}
