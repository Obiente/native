package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class JvmDynamicApiExecutionTest {
    @Test
    fun `mutations clear cache before and after unknown delivery without retry`() = runBlocking {
        var invalidations = 0
        var requests = 0
        val failure = IllegalStateException("delivery unknown")
        val actual = assertFailsWith<IllegalStateException> {
            executeJvmDynamicApiRequest(
                "a".repeat(64), NextcloudApiRequest(NextcloudApiMethod.POST, "/apps/example/items"),
                DynamicApiRequestCoalescer(),
                loadCached = { _, _ -> error("Mutation must not read cache") },
                invalidateCached = { error("Mutation invalidates account") },
                invalidateAccountCache = { invalidations++ },
                storeCached = { _, _ -> error("Mutation must not cache") },
                executeNetworkRequest = { requests++; throw failure },
            )
        }
        assertSame(failure, actual)
        assertEquals(1, requests)
        assertEquals(2, invalidations)
    }

    @Test
    fun `GET cancellation is propagated and never cached`() = runBlocking {
        val failure = CancellationException("synthetic cancellation")
        val actual = assertFailsWith<CancellationException> {
            executeJvmDynamicApiRequest(
                "a".repeat(64), NextcloudApiRequest(NextcloudApiMethod.GET, "/apps/example/items"),
                DynamicApiRequestCoalescer(), loadCached = { _, _ -> null },
                invalidateCached = {}, invalidateAccountCache = {},
                storeCached = { _, _ -> error("Cancellation must not cache") },
                executeNetworkRequest = { throw failure },
            )
        }
        assertSame(failure, actual)
    }

    @Test
    fun `only successful JSON responses are retained and disk failure does not discard response`() = runBlocking {
        for ((status, contentType, expectedWrites) in listOf(
            Triple(200, "application/json", 1), Triple(200, "text/html", 0), Triple(403, "application/json", 0),
        )) {
            var writes = 0
            val response = NextcloudApiResponse(status, "body".encodeToByteArray(), contentType, null)
            val actual = executeJvmDynamicApiRequest(
                "a".repeat(64), NextcloudApiRequest(NextcloudApiMethod.GET, "/apps/example/items"),
                DynamicApiRequestCoalescer(), loadCached = { _, _ -> null },
                invalidateCached = {}, invalidateAccountCache = {},
                storeCached = { _, _ -> writes++; error("synthetic disk failure") },
                executeNetworkRequest = { response },
            )
            assertSame(response, actual)
            assertEquals(expectedWrites, writes)
        }
    }
    @Test
    fun `cache maintenance cancellation never becomes a successful request`() = runBlocking {
        for (stage in listOf("invalidate-account", "invalidate-after-mutation", "invalidate-request", "store")) {
            val failure = CancellationException("synthetic cache cancellation")
            var requests = 0
            var invalidations = 0
            val method = if (stage in setOf("invalidate-account", "invalidate-after-mutation")) NextcloudApiMethod.POST else NextcloudApiMethod.GET
            val actual = assertFailsWith<CancellationException> {
                executeJvmDynamicApiRequest(
                    "a".repeat(64), NextcloudApiRequest(method, "/apps/example/items", cachePolicy = NextcloudApiCachePolicy.ForceNetwork),
                    DynamicApiRequestCoalescer(), loadCached = { _, _ -> null },
                    invalidateCached = { if (stage == "invalidate-request") throw failure },
                    invalidateAccountCache = {
                        invalidations++
                        if (stage == "invalidate-account" || stage == "invalidate-after-mutation" && invalidations == 2) throw failure
                    },
                    storeCached = { _, _ -> if (stage == "store") throw failure },
                    executeNetworkRequest = { requests++; NextcloudApiResponse(200, byteArrayOf(), "application/json", null) },
                )
            }
            assertSame(failure, actual)
            assertEquals(if (stage in setOf("store", "invalidate-after-mutation")) 1 else 0, requests)
        }
    }

}
