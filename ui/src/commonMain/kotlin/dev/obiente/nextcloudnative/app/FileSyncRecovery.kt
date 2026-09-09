package dev.obiente.nextcloudnative.app

internal const val INTERRUPTED_FILE_SYNC_FAILURE_MESSAGE =
    "The previous sync attempt ended before its result was verified."

internal fun recoverInterruptedFileSyncWork(work: FileSyncWorkItem): FileSyncWorkItem =
    if (work.state == FileSyncExecutionState.Running) {
        work.copy(
            state = FileSyncExecutionState.Failed,
            failureMessage = INTERRUPTED_FILE_SYNC_FAILURE_MESSAGE,
        )
    } else {
        work
    }

internal fun recoverInterruptedFileSyncWork(state: FileSyncCoordinatorState): FileSyncCoordinatorState =
    state.copy(
        pairs = state.pairs.map { pair ->
            pair.copy(workItems = pair.workItems.map(::recoverInterruptedFileSyncWork))
        },
    )

/** Returns cooperatively cancelled work to the ready queue without consuming a retry attempt. */
fun releaseCancelledFileSyncOperation(
    state: FileSyncCoordinatorState,
    pairId: String,
    workId: Long,
): FileSyncCoordinatorState = state.updatePair(pairId) { pair ->
    pair.updateWork(workId) { work ->
        require(work.state == FileSyncExecutionState.Running) { "The sync work item is not running." }
        require(work.attemptCount > 0) { "A running sync work item must have a claimed attempt." }
        work.copy(
            state = FileSyncExecutionState.Ready,
            attemptCount = work.attemptCount - 1,
            lastAttemptEpochMillis = null,
            failureMessage = null,
        )
    }
}
