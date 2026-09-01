package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileOfflineJob
import dev.obiente.nextcloudnative.app.FileOfflineJobOperation
import dev.obiente.nextcloudnative.app.FileOfflineJobResult
import dev.obiente.nextcloudnative.app.FileOfflineJobStatus
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileOfflinePinRecord
import dev.obiente.nextcloudnative.app.FileOfflineQueueState
import dev.obiente.nextcloudnative.app.recordFileOfflineJobResult

internal data class AndroidOfflineDownloadCommit(
    val state: AndroidFileOfflinePersistedState,
    val committed: Boolean,
    val removableLocalRevisions: Set<String>,
)

/**
 * Reconciles a published download with the authoritative queue generation. Only the exact running
 * job may commit. Cleanup excludes every generation still owned by a record or pending job.
 */
internal fun commitAndroidFileOfflineDownload(
    current: AndroidFileOfflinePersistedState,
    startedJob: FileOfflineJob,
    startedRecord: FileOfflinePinRecord,
    downloadedLocalRevision: String,
    remoteEtag: String,
    nowEpochMillis: Long,
): AndroidOfflineDownloadCommit {
    require(startedJob.operation == FileOfflineJobOperation.Download)
    require(startedJob.status == FileOfflineJobStatus.Running)
    require(startedRecord.descriptor.key == startedJob.key)
    require(downloadedLocalRevision.isNotBlank())
    require(remoteEtag == startedJob.expectedRemoteEtag)
    require(nowEpochMillis >= 0L)
    val currentJob = current.queue.jobs.singleOrNull { it.id == startedJob.id }
    val committed = currentJob == startedJob
    val nextState = if (committed) {
        current.copy(
            queue = recordFileOfflineJobResult(
                current.queue,
                startedJob.id,
                FileOfflineJobResult.Downloaded(downloadedLocalRevision, remoteEtag),
                nowEpochMillis,
            ),
        )
    } else {
        current
    }
    val candidates = buildSet {
        add(downloadedLocalRevision)
        startedRecord.localRevision?.let(::add)
    }
    return AndroidOfflineDownloadCommit(
        state = nextState,
        committed = committed,
        removableLocalRevisions = candidates - nextState.queue.retainedLocalRevisions(startedJob.key),
    )
}

private fun FileOfflineQueueState.retainedLocalRevisions(key: FileOfflineKey): Set<String> = buildSet {
    record(key)?.localRevision?.let(::add)
    jobs.asSequence()
        .filter { it.key == key }
        .mapNotNull(FileOfflineJob::expectedLocalRevision)
        .forEach(::add)
}
