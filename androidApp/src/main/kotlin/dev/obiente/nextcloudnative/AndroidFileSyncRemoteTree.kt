package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.JvmResumableNextcloudUploadRemote
import dev.obiente.nextcloudnative.app.JvmExactFileComparisonOutputStream
import dev.obiente.nextcloudnative.app.NextcloudUploadChunk
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.isValidNextcloudChunkUploadId
import dev.obiente.nextcloudnative.app.jvmOwnedUploadId
import dev.obiente.nextcloudnative.app.jvmOwnedReplacementBackupDestination
import dev.obiente.nextcloudnative.app.jvmOwnedReplacementConflictPath
import dev.obiente.nextcloudnative.app.jvmOwnedReplacementBackupPath
import dev.obiente.nextcloudnative.app.jvmOwnedUploadStagePath
import dev.obiente.nextcloudnative.app.normalizeSyncSha256
import dev.obiente.nextcloudnative.app.safeIncomingShareFileName
import dev.obiente.nextcloudnative.app.shouldProjectJvmOwnedReplacementBackup
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Recursive, bounded and revision-guarded view of one Nextcloud Files subtree. */
internal class AndroidFileSyncRemoteTree(
    internal val session: NextcloudSession,
    internal val userId: String,
    remoteRootPath: String,
    internal val webDav: NextcloudDocumentWebDav,
    private val ownedUploadIds: Set<String> = emptySet(),
    internal val transferCancellation: DocumentRequestCancellation = AndroidFileSyncRunCancellation {
        !Thread.currentThread().isInterrupted
    },
    private val ownedStageEtags: Map<String, String> = emptyMap(),
    private val ownedUploadPaths: Map<String, String> = emptyMap(),
    private val ownedReplacementBackupEtags: Map<String, String> = emptyMap(),
) : JvmResumableNextcloudUploadRemote {
    private val rootPath = remoteRootPath.trim('/')
    private val ownedDestinationPaths = ownedUploadPaths.mapValues { (_, path) -> fullPath(path) }

    init {
        require(ownedUploadIds.all(::isValidNextcloudChunkUploadId))
        require(ownedStageEtags.keys.all { it in ownedUploadIds })
        require(ownedUploadPaths.keys.all { it in ownedUploadIds })
        require(ownedReplacementBackupEtags.keys.all { it in ownedUploadIds })
        require(ownedStageEtags.values.all { it.isNotBlank() && '\r' !in it && '\n' !in it })
        require(ownedReplacementBackupEtags.values.all { it.isNotBlank() && '\r' !in it && '\n' !in it })
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
            ?.let { file -> file.toRemoteDocument(relativePath) }
    }

    internal fun resolvePhysical(relativePath: String): AndroidRemoteSyncDocument? {
        val target = fullPath(relativePath)
        return webDav.listDirectory(
            session,
            userId,
            target.substringBeforeLast('/', ""),
            MAX_CHILDREN,
            transferCancellation,
        ).files.firstOrNull { it.path.trim('/') == target }
            ?.let { file -> file.toRemoteDocument(relativePath) }
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
            cancellation = transferCancellation,
        )
        val after = requireNotNull(resolve(relativePath)) { "The server file disappeared while downloading." }
        require(after.entry.etag == expectedRemoteEtag) {
            "The server file changed while downloading."
        }
        return after.entry
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

    override fun ownedStageCreationAllowed(relativePath: String): Boolean? {
        val parent = relativePath.substringBeforeLast('/', "")
        val access = webDav.inspectDirectoryAccess(session, userId, fullPath(parent), transferCancellation)
        return access.canCreateFiles.takeIf { access.permissionsKnown }
    }

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
            moveReplacementDirectory(fullPath(relativePath), fullPath(backupPath), expectedDirectoryEtag)
            webDav.publishChunkUploadStage(
                session,
                userId,
                fullPath(jvmOwnedUploadStagePath(relativePath, uploadId)),
                fullPath(relativePath),
                verifiedStageEtag,
                expectedRemoteEtag = null,
                cancellation = transferCancellation,
            )
            val published = requireNotNull(resolveIncludingOwnedStage(relativePath)) {
                "The uploaded server file disappeared."
            }
            require(!published.isDirectory) { "The uploaded server item is not a file." }
            return published.entry
        } catch (failure: Throwable) {
            restoreReplacementBackupIfDestinationMissing(relativePath, uploadId, expectedDirectoryEtag)
            throw failure
        }
    }

    internal fun completeReplacementBackup(
        relativePath: String,
        uploadId: String,
        expectedBackupEtag: String,
    ) {
        val backupPath = jvmOwnedReplacementBackupPath(relativePath, uploadId)
        resolveIncludingOwnedStage(backupPath)?.let { backup ->
            require(backup.isDirectory) { "The owned replacement backup changed type." }
            require(backup.entry.etag == expectedBackupEtag) { "The owned replacement backup changed." }
            webDav.delete(
                session,
                userId,
                fullPath(backupPath),
                expectedBackupEtag,
                isDirectory = true,
                cancellation = transferCancellation,
            )
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
            transferCancellation,
        )
        val after = requireNotNull(resolve(relativePath)) { "The uploaded server file disappeared." }
        require(!after.isDirectory)
        return after.entry
    }

    override fun discardOwnedUpload(
        uploadId: String,
        relativePath: String,
        assembledStageEtag: String?,
        expectedStageSizeBytes: Long?,
        expectedStageContentHash: String?,
        publicationInFlight: Boolean,
    ): Boolean {
        deleteChunkUpload(uploadId, transferCancellation)
        if (publicationInFlight) {
            reconcilePublishedReplacement(
                relativePath,
                uploadId,
                expectedStageSizeBytes,
                expectedStageContentHash,
                ownedReplacementBackupEtags[uploadId],
            )?.let { return it }
        }
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stageCleaned = if (assembledStageEtag == null) {
            reconcileUnrecordedOwnedStage(
                stagePath,
                expectedStageSizeBytes,
                expectedStageContentHash,
            )
        } else {
            try {
                webDav.delete(
                    session,
                    userId,
                    fullPath(stagePath),
                    assembledStageEtag,
                    isDirectory = false,
                    cancellation = transferCancellation,
                )
                true
            } catch (failure: DocumentWebDavException) {
                when (failure.error) {
                    DocumentWebDavError.NotFound -> true
                    DocumentWebDavError.Conflict -> false
                    else -> throw failure
                }
            }
        }
        return stageCleaned && discardReplacementBackup(
            relativePath,
            uploadId,
            assembledStageEtag,
        )
    }

    private fun reconcileUnrecordedOwnedStage(
        stagePath: String,
        expectedStageSizeBytes: Long?,
        expectedStageContentHash: String?,
    ): Boolean {
        val stage = resolveIncludingOwnedStage(stagePath) ?: return true
        if (expectedStageSizeBytes == null || expectedStageContentHash == null) return false
        if (stage.isDirectory || stage.entry.size != expectedStageSizeBytes) return false
        if (
            !verifyContentHash(
                stagePath,
                stage.entry.etag,
                expectedStageContentHash,
                expectedStageSizeBytes,
                expectedStageSizeBytes.coerceAtLeast(1L),
            )
        ) {
            return false
        }
        try {
            webDav.delete(
                session,
                userId,
                fullPath(stagePath),
                stage.entry.etag,
                isDirectory = false,
                cancellation = transferCancellation,
            )
            return true
        } catch (failure: DocumentWebDavException) {
            return when (failure.error) {
                DocumentWebDavError.NotFound -> true
                DocumentWebDavError.Conflict -> false
                else -> throw failure
            }
        }
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
                .filterNot { file ->
                    jvmOwnedReplacementBackupDestination(file.path.trim('/'), ownedDestinationPaths) != null
                }
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
            cancellation = transferCancellation,
        )
    }

    private fun listSyncDirectory(relativeParent: String): DocumentDirectoryResult =
        listSyncDirectoryAt(fullPath(relativeParent))

    private fun listSyncDirectoryAt(directoryPath: String): DocumentDirectoryResult {
        var listing = webDav.listDirectory(
            session, userId, directoryPath, MAX_CHILDREN, transferCancellation,
        )
        val listedPaths = listing.files.mapTo(mutableSetOf()) { it.path.trim('/') }
        val ownedBackups = listing.files.mapNotNull { file ->
            val parsed = jvmOwnedReplacementBackupDestination(file.path.trim('/'), ownedDestinationPaths)
                ?: return@mapNotNull null
            parsed to file
        }
        require(ownedBackups.map { it.first.first }.distinct().size == ownedBackups.size) {
            "A Nextcloud folder contains duplicate owned replacement backups."
        }
        var recovered = false
        ownedBackups.forEach { (parsed, file) ->
            val (destination, uploadId) = parsed
            if (destination in listedPaths) return@forEach
            require(file.isDirectory) { "The owned replacement backup changed type." }
            val backupEtag = requireNotNull(file.etag?.takeIf(String::isNotBlank)) {
                "The owned replacement backup has no usable revision."
            }
            val expectedBackupEtag = requireNotNull(ownedReplacementBackupEtags[uploadId])
            require(backupEtag == expectedBackupEtag) { "The owned replacement backup changed." }
            moveReplacementDirectory(file.path.trim('/'), destination, expectedBackupEtag)
            recovered = true
        }
        if (recovered) {
            listing = webDav.listDirectory(
                session, userId, directoryPath, MAX_CHILDREN, transferCancellation,
            )
        }
        return listing
    }

    private fun scanChildren(
        logicalRelativeParent: String,
        listing: DocumentDirectoryResult,
    ): List<AndroidRemoteScanChild> {
        val ownedBackups = listing.files.mapNotNull { file ->
            val parsed = jvmOwnedReplacementBackupDestination(file.path.trim('/'), ownedDestinationPaths)
                ?: return@mapNotNull null
            parsed to file
        }
        require(ownedBackups.map { it.first.first }.distinct().size == ownedBackups.size) {
            "A Nextcloud folder contains duplicate owned replacement backups."
        }
        val filesByPath = listing.files.associateBy { it.path.trim('/') }
        val projectedBackups = ownedBackups.mapNotNull { (parsed, backup) ->
            val (destination, uploadId) = parsed
            val destinationFile = filesByPath[destination] ?: return@mapNotNull destination to backup
            val destinationEtag = destinationFile.etag?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val destinationEntry = RemoteSyncEntry(
                relativePath = destination,
                kind = if (destinationFile.isDirectory) SyncEntryKind.Directory else SyncEntryKind.File,
                etag = destinationEtag,
                size = if (destinationFile.isDirectory) null else destinationFile.size,
            )
            (destination to backup).takeIf {
                shouldProjectJvmOwnedReplacementBackup(uploadId, destinationEntry, ownedStageEtags)
            }
        }.toMap()
        val ordinary = listing.files.mapNotNull { file ->
            val physicalPath = file.path.trim('/')
            if (
                physicalPath in projectedBackups ||
                jvmOwnedReplacementBackupDestination(physicalPath, ownedDestinationPaths) != null
            ) {
                return@mapNotNull null
            }
            val name = physicalPath.substringAfterLast('/')
            AndroidRemoteScanChild(file, logicalChildPath(logicalRelativeParent, name))
        }
        return ordinary + projectedBackups.map { (destination, backup) ->
            AndroidRemoteScanChild(
                file = backup,
                logicalRelativePath = logicalChildPath(
                    logicalRelativeParent,
                    destination.substringAfterLast('/'),
                ),
            )
        }
    }

    private fun restoreReplacementBackupIfDestinationMissing(
        relativePath: String,
        uploadId: String,
        expectedBackupEtag: String,
    ) {
        runCatching {
            if (resolveIncludingOwnedStage(relativePath) != null) return@runCatching
            val backupPath = jvmOwnedReplacementBackupPath(relativePath, uploadId)
            val backup = resolveIncludingOwnedStage(backupPath) ?: return@runCatching
            require(backup.isDirectory && backup.entry.etag == expectedBackupEtag)
            moveReplacementDirectory(fullPath(backupPath), fullPath(relativePath), expectedBackupEtag)
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
        val expectedBackupEtag = requireNotNull(ownedReplacementBackupEtags[uploadId])
        require(backup.isDirectory) { "The owned replacement backup changed type." }
        require(backup.entry.etag == expectedBackupEtag) { "The owned replacement backup changed." }
        val destination = resolveIncludingOwnedStage(relativePath)
        if (destination == null) {
            moveReplacementDirectory(fullPath(backupPath), fullPath(relativePath), expectedBackupEtag)
            return true
        }
        if (!destination.isDirectory && assembledStageEtag != null && destination.entry.etag == assembledStageEtag) {
            webDav.delete(
                session,
                userId,
                fullPath(relativePath),
                destination.entry.etag,
                isDirectory = false,
                cancellation = transferCancellation,
            )
            moveReplacementDirectory(fullPath(backupPath), fullPath(relativePath), expectedBackupEtag)
            return true
        }
        val conflictPath = jvmOwnedReplacementConflictPath(relativePath, uploadId)
        if (resolveIncludingOwnedStage(conflictPath) != null) return false
        moveReplacementDirectory(fullPath(backupPath), fullPath(conflictPath), expectedBackupEtag)
        return true
    }

    private fun moveReplacementDirectory(sourcePath: String, destinationPath: String, expectedEtag: String) =
        webDav.moveDirectory(session, userId, sourcePath, destinationPath, expectedEtag, transferCancellation)

    internal fun fullPath(relativePath: String): String =
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
