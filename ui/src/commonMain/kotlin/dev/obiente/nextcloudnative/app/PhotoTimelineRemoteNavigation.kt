package dev.obiente.nextcloudnative.app

sealed interface PhotoTimelineNavigationApplyResult {
    data class Applied(
        val state: PhotoTimelineState,
        val targetDayId: Long,
    ) : PhotoTimelineNavigationApplyResult

    data class Retained(
        val message: String?,
        val snapshotStale: Boolean,
    ) : PhotoTimelineNavigationApplyResult
}

fun applyMemoriesTimelineNavigationResult(
    state: PhotoTimelineState,
    snapshot: MemoriesTimelineNavigationSnapshot,
    result: MemoriesTimelineNavigationLoadResult,
): PhotoTimelineNavigationApplyResult = when (result) {
    is MemoriesTimelineNavigationLoadResult.Loaded -> {
        if (result.sourceGeneration != snapshot.sourceGeneration) {
            PhotoTimelineNavigationApplyResult.Retained(
                message = null,
                snapshotStale = true,
            )
        } else {
            val retainedEntries = result.page.entries.take(state.retentionLimit)
            val retainedIdentities = retainedEntries
                .mapTo(mutableSetOf(), PhotoTimelineEntry::identity)
            val nextGeneration = if (state.generation == Long.MAX_VALUE) {
                0L
            } else {
                state.generation + 1L
            }
            PhotoTimelineNavigationApplyResult.Applied(
                state = state.copy(
                    entries = retainedEntries,
                    nextCursor = result.page.nextCursor,
                    loading = null,
                    error = null,
                    failedLoadKind = null,
                    generation = nextGeneration,
                    discardedNewerEntries = result.advertisedNewerItemCount,
                    loadedOlderPages = result.advertisedNewerItemCount > 0,
                    revalidationCursorCatchUpPagesRemaining = 0,
                    revalidationCursorCatchUpTailIdentity = null,
                    revalidationCursorCatchUpTailEpochSeconds = null,
                    revalidationCursorCatchUpTailFileId = null,
                    revalidationPendingRemovalIdentities = emptySet(),
                    revalidationPendingRawRemovalAuthoritative = true,
                    rawEverObserved = state.rawEverObserved || result.page.rawObserved,
                    rawStackFileIdsByEntryIdentity =
                        result.page.rawStackFileIdsByEntryIdentity
                            .filterKeys(retainedIdentities::contains),
                ),
                targetDayId = result.targetDayId,
            )
        }
    }

    MemoriesTimelineNavigationLoadResult.Stale ->
        PhotoTimelineNavigationApplyResult.Retained(
            message = null,
            snapshotStale = true,
        )

    is MemoriesTimelineNavigationLoadResult.Unavailable ->
        PhotoTimelineNavigationApplyResult.Retained(
            message = result.message,
            snapshotStale = false,
        )
}
