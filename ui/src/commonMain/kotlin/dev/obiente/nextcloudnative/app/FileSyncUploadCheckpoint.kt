package dev.obiente.nextcloudnative.app

/** Durable progress for one exact local file generation and one owned chunk collection. */
data class FileSyncUploadCheckpoint(
    val uploadId: String,
    val localRevision: String,
    val sizeBytes: Long,
    val chunkBytes: Long,
    val chunkCount: Int,
    val uploadedChunks: Int = 0,
    val commitInFlight: Boolean = false,
) {
    init {
        require(isValidNextcloudChunkUploadId(uploadId))
        require(localRevision.isNotBlank())
        val plan = transferPlan
        require(uploadedChunks in 0..plan.chunkCount)
        require(!commitInFlight || uploadedChunks == plan.chunkCount)
    }

    val transferPlan: NextcloudUploadTransferPlan.Chunked
        get() = NextcloudUploadTransferPlan.Chunked(sizeBytes, chunkBytes, chunkCount)
}

fun newFileSyncUploadCheckpoint(
    uploadId: String,
    localRevision: String,
    plan: NextcloudUploadTransferPlan.Chunked,
): FileSyncUploadCheckpoint = FileSyncUploadCheckpoint(
    uploadId = uploadId,
    localRevision = localRevision,
    sizeBytes = plan.sizeBytes,
    chunkBytes = plan.chunkBytes,
    chunkCount = plan.chunkCount,
)

internal fun requireValidFileSyncUploadCheckpoint(work: FileSyncWorkItem) {
    val checkpoint = work.uploadCheckpoint ?: return
    val upload = work.operation as? FileSyncOperation.Upload
        ?: error("Only upload work can retain chunk progress.")
    val local = requireNotNull(work.observedLocal)
    require(local.kind == SyncEntryKind.File)
    require(local.revision == checkpoint.localRevision && local.size == checkpoint.sizeBytes)
    require(upload.relativePath == work.relativePath)
}

fun checkpointFileSyncUpload(
    state: FileSyncCoordinatorState,
    pairId: String,
    workId: Long,
    checkpoint: FileSyncUploadCheckpoint,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    pair.updateWork(workId) { work ->
        require(work.state == FileSyncExecutionState.Running) { "The sync upload is not running." }
        work.copy(uploadCheckpoint = checkpoint)
    }
}
