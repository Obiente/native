package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileOfflineIntent
import dev.obiente.nextcloudnative.app.FileOfflineJob
import dev.obiente.nextcloudnative.app.FileOfflineJobOperation
import dev.obiente.nextcloudnative.app.FileOfflineJobResult
import dev.obiente.nextcloudnative.app.FileOfflineJobStatus
import dev.obiente.nextcloudnative.app.recordFileOfflineJobResult

internal data class AndroidOfflineRemovalCommit(
    val state: AndroidFileOfflinePersistedState,
    val outcome: AndroidOfflineExecutionOutcome,
    val completedRemoval: Boolean,
)

/**
 * Removes one offline generation only while the persisted removal intent still matches the worker
 * claim. The caller must keep the queue lock held while this function and the following save run.
 */
internal fun commitAndroidFileOfflineRemoval(
    current: AndroidFileOfflinePersistedState,
    startedJob: FileOfflineJob,
    nowEpochMillis: Long,
    removeLocalGeneration: () -> Boolean,
): AndroidOfflineRemovalCommit? {
    require(startedJob.operation == FileOfflineJobOperation.RemoveLocal)
    require(startedJob.status == FileOfflineJobStatus.Running)
    require(nowEpochMillis >= 0L)
    val currentJob = current.queue.jobs.singleOrNull { it.id == startedJob.id }
    val currentRecord = current.queue.record(startedJob.key)
    if (
        currentJob != startedJob ||
        currentRecord?.intent != FileOfflineIntent.OnlineOnly ||
        currentRecord.localRevision != startedJob.expectedLocalRevision
    ) {
        return null
    }
    val completed = removeLocalGeneration()
    val result = if (completed) {
        FileOfflineJobResult.LocalRemoved
    } else {
        FileOfflineJobResult.RetryableFailure("Android could not remove the local copy yet.")
    }
    val nextQueue = recordFileOfflineJobResult(current.queue, startedJob.id, result, nowEpochMillis)
    return AndroidOfflineRemovalCommit(
        state = current.copy(queue = nextQueue),
        outcome = if (completed) AndroidOfflineExecutionOutcome.Complete else AndroidOfflineExecutionOutcome.Retry,
        completedRemoval = completed,
    )
}
