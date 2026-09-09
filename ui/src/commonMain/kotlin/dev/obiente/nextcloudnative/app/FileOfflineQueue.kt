package dev.obiente.nextcloudnative.app

/** Opaque account identity plus a canonical path relative to that account's Files root. */
data class FileOfflineKey(
    val accountId: String,
    val relativePath: String,
) : Comparable<FileOfflineKey> {
    init {
        require(accountId.isNotBlank() && accountId.length <= MAX_OFFLINE_ACCOUNT_ID_LENGTH)
        require(accountId.none { it == '\u0000' || it == '\n' || it == '\r' })
        requireValidSyncPath(relativePath)
    }

    override fun compareTo(other: FileOfflineKey): Int =
        compareValuesBy(this, other, FileOfflineKey::accountId, FileOfflineKey::relativePath)
}

data class FileOfflineDescriptor(
    val key: FileOfflineKey,
    val displayName: String,
    val remoteEtag: String,
    val size: Long?,
    val mimeType: String?,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_OFFLINE_DISPLAY_NAME_LENGTH)
        require(remoteEtag.isNotBlank() && remoteEtag.length <= MAX_OFFLINE_ETAG_LENGTH)
        require(size == null || size >= 0L)
        require(mimeType == null || mimeType.length <= MAX_OFFLINE_MIME_TYPE_LENGTH)
    }
}

enum class FileOfflineIntent { Pinned, OnlineOnly }

enum class FileOfflineAvailability {
    OnlineOnly,
    Queued,
    Downloading,
    Available,
    Removing,
    WaitingForNetwork,
    Failed,
    NeedsAttention,
}

data class FileOfflinePinRecord(
    val descriptor: FileOfflineDescriptor,
    val intent: FileOfflineIntent,
    /** Revision of the complete local generation recorded after a verified download. */
    val localRevision: String?,
    /** Remote ETag paired with [localRevision] after a verified download. */
    val syncedRemoteEtag: String?,
    val attentionReason: FileSyncDecisionReason? = null,
    val updatedAtEpochMillis: Long,
) {
    init {
        require((localRevision == null) == (syncedRemoteEtag == null)) {
            "Offline local and remote baseline revisions must be recorded together."
        }
        require(updatedAtEpochMillis >= 0L)
    }
}

enum class FileOfflineJobOperation { Download, RemoveLocal }

enum class FileOfflineJobStatus { Queued, Running, WaitingForNetwork, Failed, NeedsAttention }

data class FileOfflineJob(
    val id: Long,
    val key: FileOfflineKey,
    val operation: FileOfflineJobOperation,
    /** Download must use If-Match or verify this ETag before publishing its local generation. */
    val expectedRemoteEtag: String?,
    /** Local replacement/removal must affect only this observed local generation. */
    val expectedLocalRevision: String?,
    val status: FileOfflineJobStatus,
    val attemptCount: Int,
    val enqueuedAtEpochMillis: Long,
    val failureMessage: String? = null,
    val retryNotBeforeEpochMillis: Long? = null,
) {
    init {
        require(id > 0L)
        require(attemptCount >= 0)
        require(enqueuedAtEpochMillis >= 0L)
        require(retryNotBeforeEpochMillis == null || retryNotBeforeEpochMillis >= 0L)
        require(expectedRemoteEtag == null || expectedRemoteEtag.isNotBlank())
        require(expectedLocalRevision == null || expectedLocalRevision.isNotBlank())
        require(failureMessage == null || failureMessage.length <= MAX_OFFLINE_FAILURE_LENGTH)
        when (operation) {
            FileOfflineJobOperation.Download -> require(!expectedRemoteEtag.isNullOrBlank())
            FileOfflineJobOperation.RemoveLocal -> require(!expectedLocalRevision.isNullOrBlank())
        }
    }
}

data class FileOfflineQueueState(
    val records: List<FileOfflinePinRecord> = emptyList(),
    val jobs: List<FileOfflineJob> = emptyList(),
    val nextJobId: Long = 1L,
) {
    init {
        require(records.map { it.descriptor.key }.distinct().size == records.size) {
            "Offline state contains duplicate file records."
        }
        require(jobs.map(FileOfflineJob::id).distinct().size == jobs.size) {
            "Offline state contains duplicate job IDs."
        }
        require(jobs.map(FileOfflineJob::key).distinct().size == jobs.size) {
            "Offline state contains more than one active job for a file."
        }
        val recordKeys = records.mapTo(mutableSetOf()) { it.descriptor.key }
        require(jobs.all { it.key in recordKeys }) { "Every offline job must have a file record." }
        require(nextJobId > (jobs.maxOfOrNull(FileOfflineJob::id) ?: 0L))
    }

    fun record(key: FileOfflineKey): FileOfflinePinRecord? = records.firstOrNull { it.descriptor.key == key }
    fun job(key: FileOfflineKey): FileOfflineJob? = jobs.firstOrNull { it.key == key }

    fun availability(key: FileOfflineKey): FileOfflineAvailability {
        val record = record(key) ?: return FileOfflineAvailability.OnlineOnly
        val job = job(key)
        return when {
            record.attentionReason != null -> FileOfflineAvailability.NeedsAttention
            job?.status == FileOfflineJobStatus.WaitingForNetwork -> FileOfflineAvailability.WaitingForNetwork
            job?.status == FileOfflineJobStatus.Failed -> FileOfflineAvailability.Failed
            job?.status == FileOfflineJobStatus.NeedsAttention -> FileOfflineAvailability.NeedsAttention
            job?.operation == FileOfflineJobOperation.RemoveLocal -> FileOfflineAvailability.Removing
            job?.status == FileOfflineJobStatus.Running -> FileOfflineAvailability.Downloading
            job != null -> FileOfflineAvailability.Queued
            record.intent == FileOfflineIntent.Pinned && record.localRevision != null -> FileOfflineAvailability.Available
            else -> FileOfflineAvailability.OnlineOnly
        }
    }
}

sealed interface FileOfflineRequest {
    val key: FileOfflineKey

    data class Pin(
        val descriptor: FileOfflineDescriptor,
        /** Current complete app-private generation, if one exists. */
        val observedLocalRevision: String?,
    ) : FileOfflineRequest {
        override val key: FileOfflineKey get() = descriptor.key
    }

    data class Unpin(
        override val key: FileOfflineKey,
        /** Current complete app-private generation, if one exists. */
        val observedLocalRevision: String?,
    ) : FileOfflineRequest
}

/**
 * Applies a user availability intent without doing IO. Pin reconciliation deliberately delegates
 * revision/conflict decisions to [planFileSync] in download-only mode, so this queue cannot ever
 * invent an upload or remote deletion.
 */
fun planFileOfflineRequest(
    current: FileOfflineQueueState,
    request: FileOfflineRequest,
    nowEpochMillis: Long,
): FileOfflineQueueState {
    require(nowEpochMillis >= 0L)
    return when (request) {
        is FileOfflineRequest.Pin -> planPin(current, request, nowEpochMillis)
        is FileOfflineRequest.Unpin -> planUnpin(current, request, nowEpochMillis)
    }.canonical()
}

fun markFileOfflineJobRunning(
    current: FileOfflineQueueState,
    jobId: Long,
    nowEpochMillis: Long,
): FileOfflineQueueState = current.updateJob(jobId) { job ->
    require(nowEpochMillis >= 0L)
    require(job.status in setOf(
        FileOfflineJobStatus.Queued,
        FileOfflineJobStatus.WaitingForNetwork,
        FileOfflineJobStatus.Running,
    )) {
        "Only pending offline work can start."
    }
    if (job.status == FileOfflineJobStatus.Running) {
        job
    } else {
        require(job.retryNotBeforeEpochMillis == null || nowEpochMillis >= job.retryNotBeforeEpochMillis) {
            "Offline work cannot start before its server retry deadline."
        }
        job.copy(
            status = FileOfflineJobStatus.Running,
            attemptCount = job.attemptCount + 1,
            failureMessage = null,
            retryNotBeforeEpochMillis = null,
        )
    }
}

sealed interface FileOfflineJobResult {
    data class Downloaded(val localRevision: String, val remoteEtag: String) : FileOfflineJobResult
    data object LocalRemoved : FileOfflineJobResult
    data class RetryableFailure(
        val message: String,
        val retryNotBeforeEpochMillis: Long? = null,
    ) : FileOfflineJobResult {
        init {
            require(retryNotBeforeEpochMillis == null || retryNotBeforeEpochMillis >= 0L)
        }
    }
    data class PermanentFailure(val message: String) : FileOfflineJobResult
    data class NeedsAttention(val reason: FileSyncDecisionReason, val message: String) : FileOfflineJobResult
}

/** Records an executor result. This still performs no network or filesystem mutation itself. */
fun recordFileOfflineJobResult(
    current: FileOfflineQueueState,
    jobId: Long,
    result: FileOfflineJobResult,
    nowEpochMillis: Long,
): FileOfflineQueueState {
    require(nowEpochMillis >= 0L)
    val job = current.jobs.singleOrNull { it.id == jobId } ?: error("Unknown offline job ID $jobId.")
    val record = requireNotNull(current.record(job.key))
    return when (result) {
        is FileOfflineJobResult.Downloaded -> {
            require(job.operation == FileOfflineJobOperation.Download)
            require(result.localRevision.isNotBlank())
            require(result.remoteEtag == job.expectedRemoteEtag) {
                "A downloaded generation can only commit against its planned remote ETag."
            }
            current.copy(
                records = current.records.replaceRecord(
                    record.copy(
                        localRevision = result.localRevision,
                        syncedRemoteEtag = result.remoteEtag,
                        attentionReason = null,
                        updatedAtEpochMillis = nowEpochMillis,
                    ),
                ),
                jobs = current.jobs.filterNot { it.id == jobId },
            )
        }
        FileOfflineJobResult.LocalRemoved -> {
            require(job.operation == FileOfflineJobOperation.RemoveLocal)
            current.copy(
                records = current.records.filterNot { it.descriptor.key == job.key },
                jobs = current.jobs.filterNot { it.key == job.key },
            )
        }
        is FileOfflineJobResult.RetryableFailure -> current.updateJob(jobId) {
            it.copy(
                status = FileOfflineJobStatus.WaitingForNetwork,
                failureMessage = result.message.requireFailure(),
                retryNotBeforeEpochMillis = result.retryNotBeforeEpochMillis,
            )
        }
        is FileOfflineJobResult.PermanentFailure -> current.updateJob(jobId) {
            it.copy(status = FileOfflineJobStatus.Failed, failureMessage = result.message.requireFailure())
        }
        is FileOfflineJobResult.NeedsAttention -> current.copy(
            records = current.records.replaceRecord(
                record.copy(attentionReason = result.reason, updatedAtEpochMillis = nowEpochMillis),
            ),
            jobs = current.jobs.map {
                if (it.id == jobId) {
                    it.copy(status = FileOfflineJobStatus.NeedsAttention, failureMessage = result.message.requireFailure())
                } else {
                    it
                }
            },
        )
    }.canonical()
}

private fun planPin(
    current: FileOfflineQueueState,
    request: FileOfflineRequest.Pin,
    now: Long,
): FileOfflineQueueState {
    val previous = current.record(request.key)
    val remote = RemoteSyncEntry(
        relativePath = request.key.relativePath,
        kind = SyncEntryKind.File,
        etag = request.descriptor.remoteEtag,
        size = request.descriptor.size,
    )
    val local = request.observedLocalRevision?.let {
        LocalSyncEntry(request.key.relativePath, SyncEntryKind.File, it, request.descriptor.size)
    }
    val baseline = previous?.localRevision?.let { localRevision ->
        FileSyncBaseline(
            relativePath = request.key.relativePath,
            kind = SyncEntryKind.File,
            localRevision = localRevision,
            remoteEtag = requireNotNull(previous.syncedRemoteEtag),
        )
    }
    val operation = planFileSync(
        localEntries = listOfNotNull(local),
        remoteEntries = listOf(remote),
        baselines = listOfNotNull(baseline),
        configuration = OFFLINE_DOWNLOAD_CONFIGURATION,
    ).operations.singleOrNull()
    val updatedRecord = FileOfflinePinRecord(
        descriptor = request.descriptor,
        intent = FileOfflineIntent.Pinned,
        localRevision = previous?.localRevision,
        syncedRemoteEtag = previous?.syncedRemoteEtag,
        attentionReason = (operation as? FileSyncOperation.NeedsDecision)?.reason,
        updatedAtEpochMillis = now,
    )
    val oldJob = current.job(request.key)
    val withoutOldJob = current.jobs.filterNot { it.key == request.key }
    return when (operation) {
        null -> current.copy(records = current.records.replaceRecord(updatedRecord), jobs = withoutOldJob)
        is FileSyncOperation.Download -> {
            val equivalentPendingJob = oldJob?.takeIf {
                it.operation == FileOfflineJobOperation.Download &&
                    it.expectedRemoteEtag == request.descriptor.remoteEtag &&
                    it.expectedLocalRevision == operation.expectedLocalRevision &&
                    it.status in setOf(
                        FileOfflineJobStatus.Queued,
                        FileOfflineJobStatus.Running,
                        FileOfflineJobStatus.WaitingForNetwork,
                    )
            }
            if (equivalentPendingJob != null) {
                current.copy(records = current.records.replaceRecord(updatedRecord))
            } else {
                require(current.nextJobId < Long.MAX_VALUE) { "Offline job sequence is exhausted." }
                current.copy(
                    records = current.records.replaceRecord(updatedRecord),
                    jobs = withoutOldJob + FileOfflineJob(
                        id = current.nextJobId,
                        key = request.key,
                        operation = FileOfflineJobOperation.Download,
                        expectedRemoteEtag = request.descriptor.remoteEtag,
                        expectedLocalRevision = operation.expectedLocalRevision,
                        status = FileOfflineJobStatus.Queued,
                        attemptCount = 0,
                        enqueuedAtEpochMillis = now,
                    ),
                    nextJobId = current.nextJobId + 1L,
                )
            }
        }
        is FileSyncOperation.NeedsDecision -> current.copy(
            records = current.records.replaceRecord(updatedRecord),
            jobs = withoutOldJob,
        )
        is FileSyncOperation.Skipped -> current.copy(
            records = current.records.replaceRecord(updatedRecord.copy(attentionReason = FileSyncDecisionReason.TypeChanged)),
            jobs = withoutOldJob,
        )
        is FileSyncOperation.Upload,
        is FileSyncOperation.DeleteRemote,
        is FileSyncOperation.DeleteLocal,
        is FileSyncOperation.KeepBoth,
        -> error("Download-only offline planning produced an unsafe operation.")
    }
}

private fun planUnpin(
    current: FileOfflineQueueState,
    request: FileOfflineRequest.Unpin,
    now: Long,
): FileOfflineQueueState {
    val previous = current.record(request.key) ?: return current
    val withoutOldJob = current.jobs.filterNot { it.key == request.key }
    val localRevision = request.observedLocalRevision ?: previous.localRevision
    if (localRevision == null) {
        return current.copy(
            records = current.records.filterNot { it.descriptor.key == request.key },
            jobs = withoutOldJob,
        )
    }
    val equivalentRemoval = current.job(request.key)?.takeIf {
        it.operation == FileOfflineJobOperation.RemoveLocal &&
            it.expectedLocalRevision == localRevision &&
            it.status in setOf(
                FileOfflineJobStatus.Queued,
                FileOfflineJobStatus.Running,
                FileOfflineJobStatus.WaitingForNetwork,
            )
    }
    if (equivalentRemoval != null) {
        return current.copy(
            records = current.records.replaceRecord(
                previous.copy(intent = FileOfflineIntent.OnlineOnly, attentionReason = null, updatedAtEpochMillis = now),
            ),
        )
    }
    require(current.nextJobId < Long.MAX_VALUE) { "Offline job sequence is exhausted." }
    return current.copy(
        records = current.records.replaceRecord(
            previous.copy(
                intent = FileOfflineIntent.OnlineOnly,
                attentionReason = null,
                updatedAtEpochMillis = now,
            ),
        ),
        jobs = withoutOldJob + FileOfflineJob(
            id = current.nextJobId,
            key = request.key,
            operation = FileOfflineJobOperation.RemoveLocal,
            expectedRemoteEtag = null,
            expectedLocalRevision = localRevision,
            status = FileOfflineJobStatus.Queued,
            attemptCount = 0,
            enqueuedAtEpochMillis = now,
        ),
        nextJobId = current.nextJobId + 1L,
    )
}

private fun FileOfflineQueueState.updateJob(
    jobId: Long,
    transform: (FileOfflineJob) -> FileOfflineJob,
): FileOfflineQueueState {
    require(jobs.any { it.id == jobId }) { "Unknown offline job ID $jobId." }
    return copy(jobs = jobs.map { if (it.id == jobId) transform(it) else it }).canonical()
}

private fun List<FileOfflinePinRecord>.replaceRecord(record: FileOfflinePinRecord): List<FileOfflinePinRecord> =
    filterNot { it.descriptor.key == record.descriptor.key } + record

private fun FileOfflineQueueState.canonical(): FileOfflineQueueState = copy(
    records = records.sortedBy { it.descriptor.key },
    jobs = jobs.sortedBy(FileOfflineJob::id),
)

private fun String.requireFailure(): String = trim().also {
    require(it.isNotBlank() && it.length <= MAX_OFFLINE_FAILURE_LENGTH)
}

private const val MAX_OFFLINE_ACCOUNT_ID_LENGTH = 256
private const val MAX_OFFLINE_DISPLAY_NAME_LENGTH = 512
private const val MAX_OFFLINE_ETAG_LENGTH = 1024
private const val MAX_OFFLINE_MIME_TYPE_LENGTH = 256
private const val MAX_OFFLINE_FAILURE_LENGTH = 2048

private val OFFLINE_DOWNLOAD_CONFIGURATION = FileSyncConfiguration(
    direction = FileSyncDirection.DownloadOnly,
    conflictPolicy = FileSyncConflictPolicy.Ask,
    deletionPolicy = FileSyncDeletionPolicy.RestoreMissing,
    deviceLabel = "offline-cache",
)
