package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.JvmResumableNextcloudUploadRemote
import dev.obiente.nextcloudnative.app.JvmExactFileComparisonOutputStream
import dev.obiente.nextcloudnative.app.NextcloudUploadChunk
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.isValidNextcloudChunkUploadId
import dev.obiente.nextcloudnative.app.jvmOwnedUploadId
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

/** Recursive, bounded and revision-guarded view of one Nextcloud Files subtree. */
internal class AndroidFileSyncRemoteTree(
    private val session: NextcloudSession,
    private val userId: String,
    remoteRootPath: String,
    private val webDav: NextcloudDocumentWebDav,
    private val ownedUploadIds: Set<String> = emptySet(),
) : JvmResumableNextcloudUploadRemote {
    private val rootPath = remoteRootPath.trim('/')

    init {
        require(ownedUploadIds.all(::isValidNextcloudChunkUploadId))
    }

    fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
    ): List<AndroidRemoteSyncDocument> {
        val result = ArrayList<AndroidRemoteSyncDocument>()
        val pending = ArrayDeque<String>()
        pending += ""
        while (pending.isNotEmpty()) {
            val relativeParent = pending.removeFirst()
            require(relativeParent.count { it == '/' } < MAX_DEPTH) { "The Nextcloud folder is nested too deeply." }
            val listing = webDav.listDirectory(
                session = session,
                userId = userId,
                path = fullPath(relativeParent),
                maximumEntries = MAX_CHILDREN,
            )
            require(!listing.limited) { "A Nextcloud folder contains too many entries to sync safely." }
            listing.files.forEach { file ->
                val relativePath = toRelativePath(file.path) ?: return@forEach
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
                if (file.isDirectory) pending += relativePath
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
        return webDav.listDirectory(session, userId, fullPath(parent), MAX_CHILDREN)
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
            webDav.createFile(session, userId, fullPath(relativePath), source)
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

    override fun createChunkCollection(
        uploadId: String,
        relativePath: String,
        allowExisting: Boolean,
    ): Boolean = createChunkUpload(
        uploadId,
        jvmOwnedUploadStagePath(relativePath, uploadId),
        allowExisting,
        NoDocumentRequestCancellation,
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
        NoDocumentRequestCancellation,
    )

    override fun listChunkCollection(uploadId: String): Map<Int, Long> =
        webDav.listChunkUpload(session, userId, uploadId, NoDocumentRequestCancellation)

    override fun deleteChunk(uploadId: String, chunkNumber: Int) =
        webDav.deleteChunk(session, userId, uploadId, chunkNumber, NoDocumentRequestCancellation)

    override fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long) {
        commitChunkUpload(
            uploadId,
            jvmOwnedUploadStagePath(relativePath, uploadId),
            sizeBytes,
            NoDocumentRequestCancellation,
            onRequestStarted = {},
        )
    }

    override fun verifyOwnedStage(uploadId: String, relativePath: String, source: File): String {
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stage = requireNotNull(resolveIncludingOwnedStage(stagePath)) {
            "The assembled upload stage disappeared."
        }
        require(!stage.isDirectory && stage.entry.size == source.length()) {
            "The assembled upload stage has an unexpected size."
        }
        JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
            webDav.readFile(
                session = session,
                userId = userId,
                path = fullPath(stagePath),
                destination = comparison,
                maximumBytes = source.length().coerceAtLeast(1L),
                expectedEtag = stage.entry.etag,
            )
            comparison.requireComplete()
        }
        return stage.entry.etag
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

    override fun discardOwnedUpload(uploadId: String, relativePath: String) {
        deleteChunkUpload(uploadId, NoDocumentRequestCancellation)
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        resolveIncludingOwnedStage(stagePath)?.let { stage ->
            webDav.delete(session, userId, fullPath(stagePath), stage.entry.etag, stage.isDirectory)
        }
    }

    /** Lists known destination names once; [complete] is false when the bounded DAV page was truncated. */
    fun rootChildNames(): AndroidRemoteChildNameSnapshot {
        val listing = try {
            webDav.listDirectory(session, userId, fullPath(""), MAX_CHILDREN)
        } catch (failure: DocumentWebDavException) {
            if (failure.error == DocumentWebDavError.TooLarge) {
                return AndroidRemoteChildNameSnapshot(emptySet(), complete = false)
            }
            throw failure
        }
        return AndroidRemoteChildNameSnapshot(
            names = listing.files
                .filterNot { file -> jvmOwnedUploadId(file.path) in ownedUploadIds }
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

    private fun fullPath(relativePath: String): String =
        listOf(rootPath, relativePath.trim('/')).filter(String::isNotBlank).joinToString("/")

    private fun toRelativePath(fullPath: String): String? {
        val normalized = fullPath.trim('/')
        if (rootPath.isBlank()) return normalized.takeIf(String::isNotBlank)
        if (!normalized.startsWith("$rootPath/")) return null
        return normalized.removePrefix("$rootPath/").takeIf(String::isNotBlank)
    }

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
