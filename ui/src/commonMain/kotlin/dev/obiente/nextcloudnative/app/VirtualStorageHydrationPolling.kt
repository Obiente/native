package dev.obiente.nextcloudnative.app

internal fun virtualStorageHydrationPollDelay(
    statuses: List<VirtualFolderHydrationStatus>,
    nowEpochMillis: Long,
): Long? {
    require(nowEpochMillis >= 0L)
    if (statuses.any { status ->
            status.phase == VirtualFolderHydrationPhase.Queued ||
                status.phase == VirtualFolderHydrationPhase.Downloading ||
                status.refreshing
        }
    ) return VIRTUAL_STORAGE_HYDRATION_POLL_MILLIS
    val retryAt = statuses.mapNotNull(VirtualFolderHydrationStatus::refreshRetryAtEpochMillis).minOrNull()
        ?: return null
    if (retryAt <= nowEpochMillis) return VIRTUAL_STORAGE_RETRY_POLL_MILLIS
    return (retryAt - nowEpochMillis).coerceAtMost(VIRTUAL_STORAGE_RETRY_POLL_MILLIS)
}

private const val VIRTUAL_STORAGE_HYDRATION_POLL_MILLIS = 750L
private const val VIRTUAL_STORAGE_RETRY_POLL_MILLIS = 10_000L
