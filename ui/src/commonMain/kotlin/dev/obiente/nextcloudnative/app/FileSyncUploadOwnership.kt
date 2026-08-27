package dev.obiente.nextcloudnative.app

/** Durable ownership retained after a resumable upload is no longer attached to executable work. */
data class FileSyncPendingUploadCleanup(
    val uploadId: String,
    val relativePath: String,
    val assembledStageEtag: String? = null,
    val replacementBackupEtag: String? = null,
    val expectedStageSizeBytes: Long? = null,
    val expectedStageContentHash: String? = null,
) {
    init {
        require(isValidNextcloudChunkUploadId(uploadId))
        requireValidSyncPath(relativePath)
        require(assembledStageEtag == null || assembledStageEtag.isNotBlank())
        require(assembledStageEtag == null || assembledStageEtag.none { it == '\r' || it == '\n' })
        require(replacementBackupEtag == null || replacementBackupEtag.isNotBlank())
        require(replacementBackupEtag == null || replacementBackupEtag.none { it == '\r' || it == '\n' })
        require((expectedStageSizeBytes == null) == (expectedStageContentHash == null))
        require(expectedStageSizeBytes == null || expectedStageSizeBytes >= 0L)
        require(expectedStageContentHash == null || normalizeSyncSha256(expectedStageContentHash) == expectedStageContentHash)
    }
}

fun fileSyncOwnedUploads(pair: FileSyncPair): List<FileSyncPendingUploadCleanup> =
    (pair.pendingUploadCleanups + pair.workItems.mapNotNull { work ->
        work.uploadCheckpoint?.let { checkpoint ->
            FileSyncPendingUploadCleanup(
                checkpoint.uploadId,
                work.relativePath,
                checkpoint.assembledStageEtag,
                work.replacementBackupEtag(),
            )
        }
    }).distinctBy(FileSyncPendingUploadCleanup::uploadId)

fun fileSyncOwnedUploadStageEtags(pair: FileSyncPair): Map<String, String> =
    fileSyncOwnedUploads(pair).mapNotNull { owned ->
        owned.assembledStageEtag?.let { etag -> owned.uploadId to etag }
    }.toMap()

fun fileSyncOwnedUploadPaths(pair: FileSyncPair): Map<String, String> =
    fileSyncOwnedUploads(pair).associate { owned -> owned.uploadId to owned.relativePath }

fun fileSyncOwnedReplacementBackupEtags(pair: FileSyncPair): Map<String, String> =
    fileSyncOwnedUploads(pair).mapNotNull { owned ->
        owned.replacementBackupEtag?.let { etag -> owned.uploadId to etag }
    }.toMap()

internal fun FileSyncWorkItem.retainCommitInFlightUpload(
    local: LocalSyncEntry?,
    remote: RemoteSyncEntry?,
): FileSyncWorkItem? {
    val checkpoint = uploadCheckpoint ?: return null
    val destinationUnchanged = observedRemote?.let { previous ->
        remote?.kind == previous.kind && remote.etag == previous.etag
    } ?: (remote == null)
    val destinationPublished = remote?.kind == SyncEntryKind.File &&
        remote.etag == checkpoint.assembledStageEtag && remote.size == checkpoint.sizeBytes
    return takeIf {
        checkpoint.commitInFlight && operation is FileSyncOperation.Upload &&
            local?.kind == SyncEntryKind.File && local.revision == checkpoint.localRevision &&
            (local.size == null || local.size == checkpoint.sizeBytes) &&
            (destinationUnchanged || destinationPublished)
    }?.copy(observedLocal = local)
}

private fun FileSyncWorkItem.replacementBackupEtag(): String? =
    (operation as? FileSyncOperation.Upload)?.expectedRemoteEtag
        ?.takeIf { observedRemote?.kind == SyncEntryKind.Directory }

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

fun retainFileSyncUploadCleanup(
    state: FileSyncCoordinatorState,
    pairId: String,
    cleanup: FileSyncPendingUploadCleanup,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    require(pair.workItems.none { it.uploadCheckpoint?.uploadId == cleanup.uploadId }) {
        "Active upload progress and replacement-stage cleanup must remain distinct."
    }
    pair.copy(
        pendingUploadCleanups = pair.pendingUploadCleanups
            .filterNot { it.uploadId == cleanup.uploadId } + cleanup,
    )
}

internal fun retainFileSyncUploadOwnership(
    previous: FileSyncPair,
    currentWork: List<FileSyncWorkItem>,
): List<FileSyncPendingUploadCleanup> {
    val retainedUploadIds = currentWork.mapNotNullTo(mutableSetOf()) { it.uploadCheckpoint?.uploadId }
    val abandonedUploads = previous.workItems.mapNotNull { work ->
        work.uploadCheckpoint?.takeIf { it.uploadId !in retainedUploadIds }?.let { checkpoint ->
            FileSyncPendingUploadCleanup(
                checkpoint.uploadId,
                work.relativePath,
                checkpoint.assembledStageEtag,
                work.replacementBackupEtag(),
            )
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
