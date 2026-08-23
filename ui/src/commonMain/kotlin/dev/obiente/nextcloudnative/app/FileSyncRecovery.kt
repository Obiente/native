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
