package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/** Shares account invalidation and bounded JSON caching across JVM platform adapters. */
suspend fun executeJvmDynamicApiRequest(
    accountId: String,
    safeRequest: NextcloudApiRequest,
    dynamicApiRequestCoalescer: DynamicApiRequestCoalescer<NextcloudApiResponse>,
    loadCached: (String, Long) -> NextcloudApiResponse?,
    invalidateCached: (String) -> Unit,
    invalidateAccountCache: () -> Unit,
    storeCached: (String, NextcloudApiResponse) -> Unit,
    executeNetworkRequest: suspend () -> NextcloudApiResponse,
): NextcloudApiResponse {
    val cacheIdentity = safeRequest.dynamicReadCacheIdentity()
    if (safeRequest.method != NextcloudApiMethod.GET) {
        dynamicApiRequestCoalescer.invalidateAccount(accountId) {
            maintainDynamicReadCache { invalidateAccountCache() }
        }
        return try {
            executeNetworkRequest()
        } finally {
            dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                maintainDynamicReadCache { invalidateAccountCache() }
            }
        }
    }
    return executeDynamicApiGet(
        accountId = accountId,
        requestIdentity = cacheIdentity,
        cachePolicy = safeRequest.cachePolicy,
        coalescer = dynamicApiRequestCoalescer,
        loadCached = {
            loadCached(cacheIdentity, safeRequest.maximumResponseBytes)
        },
        invalidateCached = {
            maintainDynamicReadCache { invalidateCached(cacheIdentity) }
        },
        executeNetwork = executeNetworkRequest,
        commit = { result ->
            if (
                result.status in 200..299 &&
                result.contentType?.contains("json", ignoreCase = true) == true
            ) {
                maintainDynamicReadCache {
                    storeCached(cacheIdentity, result)
                }
            }
        },
    )
}

/** Disposable cache writes may fail without discarding a network result, but cancellation is control flow. */
private inline fun maintainDynamicReadCache(update: () -> Unit) {
    try {
        update()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The authoritative network response remains usable when local cache maintenance fails.
        return
    }
}
