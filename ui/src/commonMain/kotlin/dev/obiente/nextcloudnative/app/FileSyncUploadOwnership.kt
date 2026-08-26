package dev.obiente.nextcloudnative.app

/** Durable ownership retained after a resumable upload is no longer attached to executable work. */
data class FileSyncPendingUploadCleanup(
    val uploadId: String,
    val relativePath: String,
    val assembledStageEtag: String? = null,
) {
    init {
        require(isValidNextcloudChunkUploadId(uploadId))
        requireValidSyncPath(relativePath)
        require(assembledStageEtag == null || assembledStageEtag.isNotBlank())
        require(assembledStageEtag == null || assembledStageEtag.none { it == '\r' || it == '\n' })
    }
}

fun fileSyncOwnedUploads(pair: FileSyncPair): List<FileSyncPendingUploadCleanup> =
    (pair.pendingUploadCleanups + pair.workItems.mapNotNull { work ->
        work.uploadCheckpoint?.let { checkpoint ->
            FileSyncPendingUploadCleanup(checkpoint.uploadId, work.relativePath, checkpoint.assembledStageEtag)
        }
    }).distinctBy(FileSyncPendingUploadCleanup::uploadId)

internal fun requireNoFileSyncUploadOwnership(pair: FileSyncPair) {
    require(fileSyncOwnedUploads(pair).isEmpty()) {
        "Owned remote upload state must be cleaned before removing a sync pair."
    }
}

fun completeFileSyncUploadCleanup(
    state: FileSyncCoordinatorState,
    pairId: String,
    uploadId: String,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    require(fileSyncOwnedUploads(pair).any { it.uploadId == uploadId }) {
        "The owned upload cleanup is no longer pending."
    }
    pair.copy(
        pendingUploadCleanups = pair.pendingUploadCleanups.filterNot { it.uploadId == uploadId },
        workItems = pair.workItems.map { work ->
            if (work.uploadCheckpoint?.uploadId == uploadId) work.copy(uploadCheckpoint = null) else work
        },
    )
}

internal fun retainFileSyncUploadOwnership(
    previous: FileSyncPair,
    currentWork: List<FileSyncWorkItem>,
): List<FileSyncPendingUploadCleanup> {
    val retainedUploadIds = currentWork.mapNotNullTo(mutableSetOf()) { it.uploadCheckpoint?.uploadId }
    val abandonedUploads = previous.workItems.mapNotNull { work ->
        work.uploadCheckpoint?.takeIf { it.uploadId !in retainedUploadIds }?.let { checkpoint ->
            FileSyncPendingUploadCleanup(checkpoint.uploadId, work.relativePath, checkpoint.assembledStageEtag)
        }
    }
    return (previous.pendingUploadCleanups + abandonedUploads)
        .distinctBy(FileSyncPendingUploadCleanup::uploadId)
        .filterNot { it.uploadId in retainedUploadIds }
}

internal fun requireValidFileSyncUploadOwnership(pair: FileSyncPair) {
    require(pair.pendingUploadCleanups.size <= MAX_FILE_SYNC_WORK_ITEMS) {
        "The sync pair contains too many pending upload cleanups."
    }
    require(pair.pendingUploadCleanups.map(FileSyncPendingUploadCleanup::uploadId).distinct().size ==
        pair.pendingUploadCleanups.size) { "The sync pair contains duplicate upload cleanups." }
    require(pair.pendingUploadCleanups.map(FileSyncPendingUploadCleanup::uploadId).none { uploadId ->
        pair.workItems.any { it.uploadCheckpoint?.uploadId == uploadId }
    }) { "Active and abandoned upload ownership must remain distinct." }
}
