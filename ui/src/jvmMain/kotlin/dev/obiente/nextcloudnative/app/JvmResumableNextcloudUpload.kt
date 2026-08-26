package dev.obiente.nextcloudnative.app

import java.io.File

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

interface JvmResumableNextcloudUploadRemote {
    fun uploadDirect(source: File, relativePath: String, expectedRemoteEtag: String?): RemoteSyncEntry

    /** Returns true when a new collection was created and false when an owned one already existed. */
    fun createChunkCollection(uploadId: String, relativePath: String, allowExisting: Boolean): Boolean

    fun listChunkCollection(uploadId: String): Map<Int, Long>

    fun deleteChunk(uploadId: String, chunkNumber: Int)

    fun uploadChunk(uploadId: String, relativePath: String, source: File, chunk: NextcloudUploadChunk)

    fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long)

    /** Returns the exact stage ETag whose bytes matched [source]. */
    fun verifyOwnedStage(uploadId: String, relativePath: String, source: File): String

    fun publishOwnedStage(
        uploadId: String,
        relativePath: String,
        verifiedStageEtag: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry

    fun discardOwnedUpload(uploadId: String, relativePath: String)
}

fun cleanupJvmFileSyncOwnedUploads(
    remote: JvmResumableNextcloudUploadRemote,
    state: FileSyncCoordinatorState,
    pairId: String,
    uploads: List<FileSyncPendingUploadCleanup>,
    onStateChanged: (FileSyncCoordinatorState) -> Unit = {},
): FileSyncCoordinatorState {
    var updated = state
    uploads.forEach { cleanup ->
        remote.discardOwnedUpload(cleanup.uploadId, cleanup.relativePath)
        updated = completeFileSyncUploadCleanup(updated, pairId, cleanup.uploadId)
        onStateChanged(updated)
    }
    return updated
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
): RemoteSyncEntry {
    require(source.isFile)
    requireValidSyncPath(relativePath)
    require(localRevision.isNotBlank())
    val plan = nextcloudUploadTransferPlan(source.length())
    if (plan is NextcloudUploadTransferPlan.Direct) {
        checkpoint?.let { remote.discardOwnedUpload(it.uploadId, relativePath) }
        return remote.uploadDirect(source, relativePath, expectedRemoteEtag)
    }
    require(plan is NextcloudUploadTransferPlan.Chunked)

    val resumable = checkpoint?.takeIf {
        it.localRevision == localRevision && it.transferPlan == plan && !it.commitInFlight
    }
    if (checkpoint != null && resumable == null) {
        remote.discardOwnedUpload(checkpoint.uploadId, relativePath)
    }
    val resumed = resumable != null
    var progress = resumable ?: newFileSyncUploadCheckpoint(newUploadId(), localRevision, plan)
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
        val chunk = nextcloudUploadChunk(plan, source.length(), progress.uploadedChunks)
        remote.uploadChunk(progress.uploadId, relativePath, source, chunk)
        progress = progress.copy(uploadedChunks = progress.uploadedChunks + 1)
        persistCheckpoint(progress)
    }

    progress = progress.copy(commitInFlight = true)
    persistCheckpoint(progress)
    remote.commitChunksToOwnedStage(progress.uploadId, relativePath, source.length())
    val verifiedStageEtag = remote.verifyOwnedStage(progress.uploadId, relativePath, source)
    return remote.publishOwnedStage(progress.uploadId, relativePath, verifiedStageEtag, expectedRemoteEtag)
}
