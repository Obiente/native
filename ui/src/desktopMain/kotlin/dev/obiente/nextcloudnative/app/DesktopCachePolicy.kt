package dev.obiente.nextcloudnative.app

internal suspend fun executeDesktopDynamicApiGet(
    accountId: String,
    requestIdentity: String,
    cachePolicy: NextcloudApiCachePolicy,
    coalescer: DynamicApiRequestCoalescer<NextcloudApiResponse>,
    loadCached: () -> NextcloudApiResponse?,
    invalidateCached: () -> Unit,
    executeNetwork: suspend () -> NextcloudApiResponse,
    commit: (NextcloudApiResponse) -> Unit,
): NextcloudApiResponse {
    when (cachePolicy) {
        NextcloudApiCachePolicy.PreferCache -> loadCached()?.let { return it }
        NextcloudApiCachePolicy.RefreshNetwork ->
            coalescer.invalidateRequest(accountId, requestIdentity) {}
        NextcloudApiCachePolicy.ForceNetwork ->
            coalescer.invalidateRequest(accountId, requestIdentity, invalidateCached)
    }
    return coalescer.execute(
        accountId = accountId,
        requestIdentity = requestIdentity,
        load = {
            if (cachePolicy != NextcloudApiCachePolicy.PreferCache) {
                executeNetwork()
            } else {
                loadCached() ?: executeNetwork()
            }
        },
        commit = commit,
    )
}

internal fun combinedAutomaticCacheExcess(
    maximumBytes: Long,
    completeFileBytes: Long,
    rangeBytes: Long,
    windowsCachedBytes: Long,
    windowsPinnedBytes: Long,
): Long {
    require(maximumBytes > 0L)
    require(listOf(completeFileBytes, rangeBytes, windowsCachedBytes, windowsPinnedBytes).all { it >= 0L })
    require(windowsPinnedBytes <= windowsCachedBytes)
    val total = listOf(
        completeFileBytes,
        rangeBytes,
        windowsCachedBytes - windowsPinnedBytes,
    ).fold(0L) { accumulated, bytes ->
        if (bytes > Long.MAX_VALUE - accumulated) Long.MAX_VALUE else accumulated + bytes
    }
    return (total - maximumBytes).coerceAtLeast(0L)
}
