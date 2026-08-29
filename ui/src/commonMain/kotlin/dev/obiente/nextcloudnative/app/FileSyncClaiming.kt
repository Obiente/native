package dev.obiente.nextcloudnative.app

fun claimNextFileSyncOperation(
    state: FileSyncCoordinatorState,
    pairId: String,
    nowEpochMillis: Long,
): FileSyncClaim {
    require(nowEpochMillis >= 0)
    val pair = state.requirePair(pairId)
    require(pair.workItems.none { it.state == FileSyncExecutionState.Running }) {
        "Only one operation per sync pair may run at a time."
    }
    if (pair.pendingUploadCleanups.isNotEmpty()) return FileSyncClaim(state, null)
    val next = pair.workItems.firstOrNull { it.state == FileSyncExecutionState.Ready }
        ?: return FileSyncClaim(state, null)
    require(next.attemptCount < MAX_FILE_SYNC_ATTEMPTS) { "The sync work item exceeded its retry limit." }
    val updated = state.updatePair(pairId) { current ->
        current.updateWork(next.id) { work ->
            work.copy(
                state = FileSyncExecutionState.Running,
                attemptCount = work.attemptCount + 1,
                lastAttemptEpochMillis = nowEpochMillis,
            )
        }
    }
    return FileSyncClaim(updated, FileSyncExecutionCommand(pairId, next.id, next.operation))
}
