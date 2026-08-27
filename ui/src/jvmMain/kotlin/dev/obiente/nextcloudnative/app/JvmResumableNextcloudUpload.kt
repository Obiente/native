package dev.obiente.nextcloudnative.app

import java.io.File
import kotlinx.coroutines.CancellationException

fun jvmOwnedUploadStagePath(relativePath: String, uploadId: String): String {
    requireValidSyncPath(relativePath)
    require(isValidNextcloudChunkUploadId(uploadId))
    val parent = relativePath.substringBeforeLast('/', "")
    val name = ".nextcloud-native-$uploadId.upload"
    return listOf(parent, name).filter(String::isNotBlank).joinToString("/")
}

fun isJvmOwnedUploadStagePath(relativePath: String): Boolean {
    return jvmOwnedUploadId(relativePath) != null
}

fun jvmOwnedUploadId(relativePath: String): String? {
    val name = relativePath.substringAfterLast('/')
    if (!name.startsWith(".nextcloud-native-") || !name.endsWith(".upload")) return null
    val uploadId = name.removePrefix(".nextcloud-native-").removeSuffix(".upload")
    return uploadId.takeIf(::isValidNextcloudChunkUploadId)
}

fun jvmOwnedReplacementBackupPath(relativePath: String, uploadId: String): String {
    requireValidSyncPath(relativePath)
    require(isValidNextcloudChunkUploadId(uploadId))
    val parent = relativePath.substringBeforeLast('/', "")
    val backupName = ".nextcloud-native-backup-$uploadId"
    return listOf(parent, backupName).filter(String::isNotBlank).joinToString("/")
}

fun jvmOwnedReplacementBackupUploadId(relativePath: String): String? {
    val name = relativePath.substringAfterLast('/')
    val marker = ".nextcloud-native-backup-"
    if (!name.startsWith(marker)) return null
    return name.removePrefix(marker).takeIf(::isValidNextcloudChunkUploadId)
}

fun jvmOwnedReplacementBackupDestination(
    backupPath: String,
    ownedUploadPaths: Map<String, String>,
): Pair<String, String>? {
    val uploadId = jvmOwnedReplacementBackupUploadId(backupPath) ?: return null
    val destination = ownedUploadPaths[uploadId] ?: return null
    if (backupPath.substringBeforeLast('/', "") != destination.substringBeforeLast('/', "")) return null
    return destination to uploadId
}

fun shouldProjectJvmOwnedReplacementBackup(
    uploadId: String,
    destination: RemoteSyncEntry?,
    ownedStageEtags: Map<String, String>,
): Boolean = destination?.kind == SyncEntryKind.File && ownedStageEtags[uploadId] == destination.etag

fun jvmOwnedReplacementConflictPath(relativePath: String, uploadId: String): String {
    requireValidSyncPath(relativePath)
    require(isValidNextcloudChunkUploadId(uploadId))
    val parent = relativePath.substringBeforeLast('/', "")
    val conflictName = ".nextcloud-native-conflict-$uploadId"
    return listOf(parent, conflictName).filter(String::isNotBlank).joinToString("/")
}

interface JvmResumableNextcloudUploadRemote {
    fun uploadDirect(source: File, relativePath: String, expectedRemoteEtag: String?): RemoteSyncEntry

    /** Byte-compares the exact generation returned by a successful direct PUT with [source]. */
    fun verifyDirectUpload(source: File, relativePath: String, uploaded: RemoteSyncEntry): RemoteSyncEntry

    /** Returns true when a new collection was created and false when an owned one already existed. */
    fun createChunkCollection(uploadId: String, relativePath: String, allowExisting: Boolean): Boolean

    fun listChunkCollection(uploadId: String): Map<Int, Long>

    fun deleteChunk(uploadId: String, chunkNumber: Int)

    fun uploadChunk(uploadId: String, relativePath: String, source: File, chunk: NextcloudUploadChunk)

    /** Returns the stage ETag supplied by the successful assembly response, when available. */
    fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long): String?

    /** Compares one exact resolved stage generation with [source]. */
    fun verifyOwnedStage(
        uploadId: String,
        relativePath: String,
        source: File,
        expectedStageEtag: String?,
    ): String

    fun ownedStageEtag(uploadId: String, relativePath: String): String?

    fun resolvePublishedFile(relativePath: String): RemoteSyncEntry?

    fun verifyPublishedFile(
        uploadId: String,
        source: File,
        relativePath: String,
        published: RemoteSyncEntry,
    ): RemoteSyncEntry = verifyDirectUpload(source, relativePath, published)

    /** Completes durable publication bookkeeping after the visible file has been verified. */
    fun completePublishedFile(uploadId: String, relativePath: String) = Unit

    fun publishOwnedStage(
        uploadId: String,
        relativePath: String,
        verifiedStageEtag: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry

    /** Returns false when an unverified stage must remain durably owned and hidden. */
    fun discardOwnedUpload(
        uploadId: String,
        relativePath: String,
        assembledStageEtag: String?,
        expectedStageSizeBytes: Long? = null,
        expectedStageContentHash: String? = null,
        publicationInFlight: Boolean = false,
    ): Boolean
}

data class JvmFileSyncUploadCleanupResult(
    val state: FileSyncCoordinatorState,
    val unresolvedUploads: List<FileSyncPendingUploadCleanup>,
) {
    init {
        require(unresolvedUploads.map(FileSyncPendingUploadCleanup::uploadId).distinct().size ==
            unresolvedUploads.size)
    }
}

fun cleanupJvmFileSyncOwnedUploads(
    remote: JvmResumableNextcloudUploadRemote,
    state: FileSyncCoordinatorState,
    pairId: String,
    uploads: List<FileSyncPendingUploadCleanup>,
    onStateChanged: (FileSyncCoordinatorState) -> Unit = {},
): JvmFileSyncUploadCleanupResult {
    var updated = state
    val unresolved = mutableListOf<FileSyncPendingUploadCleanup>()
    uploads.forEach { cleanup ->
        if (
            remote.discardOwnedUpload(
                cleanup.uploadId,
                cleanup.relativePath,
                cleanup.assembledStageEtag,
                cleanup.expectedStageSizeBytes,
                cleanup.expectedStageContentHash,
                cleanup.publicationInFlight,
            )
        ) {
            updated = completeFileSyncUploadCleanup(updated, pairId, cleanup.uploadId)
            onStateChanged(updated)
        } else {
            unresolved += cleanup
        }
    }
    return JvmFileSyncUploadCleanupResult(updated, unresolved)
}

/**
 * Runs the same crash-safe chunk state machine for every JVM platform.
 *
 * Progress is persisted before remote ownership is created and after every accepted chunk. A
 * process interruption can therefore repeat at most one idempotent chunk PUT. An interruption
 * around the collection MOVE is handled by discarding only the UUID-owned stage and restarting;
 * publishing to the visible path remains a separate generation-guarded operation.
 */
fun jvmResumableNextcloudUpload(
    source: File,
    relativePath: String,
    localRevision: String,
    expectedRemoteEtag: String?,
    checkpoint: FileSyncUploadCheckpoint?,
    newUploadId: () -> String,
    persistCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
    remote: JvmResumableNextcloudUploadRemote,
    shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    contentRevision: String = localRevision,
    contentHash: String? = null,
): RemoteSyncEntry {
    fun ensureActive() {
        if (!shouldContinue()) throw CancellationException("Resumable upload cancelled.")
    }
    ensureActive()
    require(source.isFile)
    requireValidSyncPath(relativePath)
    require(localRevision.isNotBlank())
    require(contentRevision.isNotBlank())
    fun publishVerifiedStage(uploadId: String, verifiedStageEtag: String): RemoteSyncEntry {
        ensureActive()
        val published = remote.publishOwnedStage(
            uploadId,
            relativePath,
            verifiedStageEtag,
            expectedRemoteEtag,
        )
        ensureActive()
        val verified = remote.verifyPublishedFile(uploadId, source, relativePath, published)
        ensureActive()
        remote.completePublishedFile(uploadId, relativePath)
        return verified
    }
    val plan = nextcloudUploadTransferPlan(source.length())
    if (plan is NextcloudUploadTransferPlan.Direct) {
        checkpoint?.let {
            check(remote.discardOwnedUpload(it.uploadId, relativePath, it.assembledStageEtag)) {
                "An unverified upload stage still requires recovery."
            }
        }
        val uploaded = remote.uploadDirect(source, relativePath, expectedRemoteEtag)
        ensureActive()
        return remote.verifyDirectUpload(source, relativePath, uploaded)
    }
    require(plan is NextcloudUploadTransferPlan.Chunked)

    val matchingCheckpoint = checkpoint?.takeIf {
        it.localRevision == localRevision && it.contentRevision == contentRevision &&
            it.contentHash == contentHash && it.transferPlan == plan
    }
    if (matchingCheckpoint?.commitInFlight == true) {
        val stageEtag = remote.ownedStageEtag(matchingCheckpoint.uploadId, relativePath)
        if (stageEtag != null) {
            val verifiedStageEtag = remote.verifyOwnedStage(
                matchingCheckpoint.uploadId,
                relativePath,
                source,
                matchingCheckpoint.assembledStageEtag ?: stageEtag,
            )
            if (matchingCheckpoint.assembledStageEtag != verifiedStageEtag) {
                persistCheckpoint(matchingCheckpoint.copy(assembledStageEtag = verifiedStageEtag))
            }
            return publishVerifiedStage(matchingCheckpoint.uploadId, verifiedStageEtag)
        }
        remote.resolvePublishedFile(relativePath)?.let { published ->
            ensureActive()
            val verified = remote.verifyPublishedFile(matchingCheckpoint.uploadId, source, relativePath, published)
            ensureActive()
            remote.completePublishedFile(matchingCheckpoint.uploadId, relativePath)
            return verified
        }
    }

    val resumable = matchingCheckpoint?.takeIf { !it.commitInFlight }
    if (checkpoint != null && resumable == null) {
        check(remote.discardOwnedUpload(checkpoint.uploadId, relativePath, checkpoint.assembledStageEtag)) {
            "An unverified upload stage still requires recovery."
        }
    }
    val resumed = resumable != null
    var progress = resumable ?: newFileSyncUploadCheckpoint(
        newUploadId(), localRevision, plan, contentRevision, contentHash,
    )
        .also(persistCheckpoint)

    val collectionCreated = remote.createChunkCollection(
        progress.uploadId,
        relativePath,
        allowExisting = resumed,
    )
    val uploadedOnServer = if (collectionCreated) {
        0
    } else {
        val reconciliation = reconcileNextcloudChunkCollection(plan, remote.listChunkCollection(progress.uploadId))
        reconciliation.staleChunkNumbers.forEach { remote.deleteChunk(progress.uploadId, it) }
        reconciliation.uploadedChunks
    }
    if (progress.uploadedChunks != uploadedOnServer) {
        progress = progress.copy(uploadedChunks = uploadedOnServer)
        persistCheckpoint(progress)
    }
    while (progress.uploadedChunks < plan.chunkCount) {
        ensureActive()
        val chunk = nextcloudUploadChunk(plan, source.length(), progress.uploadedChunks)
        remote.uploadChunk(progress.uploadId, relativePath, source, chunk)
        progress = progress.copy(uploadedChunks = progress.uploadedChunks + 1)
        persistCheckpoint(progress)
    }

    ensureActive()
    progress = progress.copy(commitInFlight = true)
    persistCheckpoint(progress)
    val assembledStageEtag = remote.commitChunksToOwnedStage(progress.uploadId, relativePath, source.length())
    if (assembledStageEtag != null) {
        progress = progress.copy(assembledStageEtag = assembledStageEtag)
        persistCheckpoint(progress)
    }
    val verifiedStageEtag = remote.verifyOwnedStage(
        progress.uploadId, relativePath, source, assembledStageEtag,
    )
    if (progress.assembledStageEtag == null) {
        progress = progress.copy(assembledStageEtag = verifiedStageEtag)
        persistCheckpoint(progress)
    }
    return publishVerifiedStage(progress.uploadId, verifiedStageEtag)
}
