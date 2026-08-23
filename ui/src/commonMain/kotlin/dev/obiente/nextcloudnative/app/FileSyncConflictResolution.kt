package dev.obiente.nextcloudnative.app

data class FileSyncConflictResolution(
    val workId: Long,
    val choice: FileSyncDecisionChoice,
) {
    init {
        require(workId > 0L)
    }
}

/** Applies a bounded conflict batch atomically or leaves the coordinator unchanged. */
fun resolveFileSyncDecisions(
    state: FileSyncCoordinatorState,
    pairId: String,
    resolutions: List<FileSyncConflictResolution>,
): FileSyncCoordinatorState {
    require(resolutions.isNotEmpty()) { "Choose at least one conflict to resolve." }
    require(resolutions.size <= MAX_FILE_SYNC_CONFLICT_BATCH) { "The conflict batch is too large." }
    require(resolutions.map(FileSyncConflictResolution::workId).distinct().size == resolutions.size) {
        "The conflict batch contains the same work item more than once."
    }
    state.requirePair(pairId).also { pair ->
        resolutions.forEach { resolution ->
            val work = pair.workItems.singleOrNull { it.id == resolution.workId }
                ?: error("A selected conflict no longer exists.")
            require(work.state == FileSyncExecutionState.AwaitingDecision) {
                "A selected sync item is no longer awaiting a decision."
            }
            require(resolution.choice in requireNotNull(work.decision).choices) {
                "That batch choice is not valid for every selected conflict."
            }
        }
    }
    return resolutions.fold(state) { current, resolution ->
        resolveFileSyncDecision(current, pairId, resolution.workId, resolution.choice)
    }
}

internal const val MAX_FILE_SYNC_CONFLICT_BATCH = 1_000
