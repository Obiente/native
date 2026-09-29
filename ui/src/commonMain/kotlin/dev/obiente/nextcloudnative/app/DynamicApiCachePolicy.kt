package dev.obiente.nextcloudnative.app

suspend fun executeDynamicApiGet(
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
