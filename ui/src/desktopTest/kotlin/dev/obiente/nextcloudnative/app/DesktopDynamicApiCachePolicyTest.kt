package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDynamicApiCachePolicyTest {
    @Test
    fun `force network bypasses both desktop dynamic cache reads`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
        val cached = NextcloudApiResponse(200, "cached".encodeToByteArray(), "application/json", "\"cached\"")
        val network = NextcloudApiResponse(200, "network".encodeToByteArray(), "application/json", "\"network\"")
        var cacheLoads = 0
        var invalidations = 0
        var networkLoads = 0
        val committed = mutableListOf<NextcloudApiResponse>()

        val result = executeDesktopDynamicApiGet(
            accountId = "a".repeat(64),
            requestIdentity = "GET /apps/deck/api/v1.1/boards/7",
            cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
            coalescer = coalescer,
            loadCached = {
                cacheLoads += 1
                cached
            },
            invalidateCached = { invalidations += 1 },
            executeNetwork = {
                networkLoads += 1
                network
            },
            commit = committed::add,
        )

        assertEquals(network, result)
        assertEquals(0, cacheLoads)
        assertEquals(1, invalidations)
        assertEquals(1, networkLoads)
        assertEquals(listOf(network), committed)
    }

    @Test
    fun `refresh network preserves the desktop cache until replacement succeeds`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
        var cacheLoads = 0
        var invalidations = 0
        var networkLoads = 0

        kotlin.test.assertFailsWith<IllegalStateException> {
            executeDesktopDynamicApiGet(
                accountId = "a".repeat(64),
                requestIdentity = "GET /dashboard/widgets",
                cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
                coalescer = coalescer,
                loadCached = { cacheLoads += 1; error("refresh must bypass cache reads") },
                invalidateCached = { invalidations += 1 },
                executeNetwork = { networkLoads += 1; error("offline") },
                commit = {},
            )
        }

        assertEquals(0, cacheLoads)
        assertEquals(0, invalidations)
        assertEquals(1, networkLoads)
    }
}
