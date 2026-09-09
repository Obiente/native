package dev.obiente.nextcloudnative.app

fun updateFileSyncPairConfiguration(
    state: FileSyncCoordinatorState,
    pairId: String,
    configuration: FileSyncConfiguration,
): FileSyncCoordinatorState {
    val pair = state.pairs.singleOrNull { it.id == pairId }
        ?: error("The sync pair does not exist.")
    require(pair.workItems.none { it.state == FileSyncExecutionState.Running }) {
        "Sync configuration cannot change while work is running."
    }
    val updated = pair.copy(
        configuration = configuration,
        contentVerificationProgress = emptyList(),
        workItems = emptyList(),
    )
    return state.copy(pairs = state.pairs.map { if (it.id == pairId) updated else it })
}
