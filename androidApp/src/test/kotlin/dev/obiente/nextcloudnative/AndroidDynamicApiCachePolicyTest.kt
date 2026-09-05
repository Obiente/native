package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.NextcloudApiCachePolicy
import dev.obiente.nextcloudnative.app.NextcloudApiResponse
import dev.obiente.nextcloudnative.contracts.CachedDynamicApiResponse
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidDynamicApiCachePolicyTest {
    @Test
    fun `Android services sharing a canonical cache root share removal fences`() {
        val parent = Files.createTempDirectory("android-dynamic-process-state-").toFile()
        try {
            val first = androidDynamicApiProcessState(parent.resolve("cache"))
            val second = androidDynamicApiProcessState(parent.resolve("nested/../cache"))

            assertSame(first, second)
            assertSame(first.cache, second.cache)
            assertSame(first.coalescer, second.coalescer)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `force network bypasses both Android dynamic cache reads`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
        val cached = NextcloudApiResponse(200, "cached".encodeToByteArray(), "application/json", "\"cached\"")
        val network = NextcloudApiResponse(200, "network".encodeToByteArray(), "application/json", "\"network\"")
        var cacheLoads = 0
        var invalidations = 0
        var networkLoads = 0
        val committed = mutableListOf<NextcloudApiResponse>()

        val result = executeAndroidDynamicApiGet(
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
    fun `refresh network preserves the Android cache until replacement succeeds`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
        var cacheLoads = 0
        var invalidations = 0
        var networkLoads = 0

        kotlin.test.assertFailsWith<IllegalStateException> {
            executeAndroidDynamicApiGet(
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

    @Test
    fun `account cleanup fences a late Android GET before deleting its cache`() = runBlocking {
        supervisorScope {
            val root = Files.createTempDirectory("android-dynamic-cache-cleanup-").toFile()
            try {
                val accountId = "a".repeat(64)
                val requestIdentity = "GET /dashboard/widgets"
                val cache = DynamicApiResponseCache(root)
                val coalescer = DynamicApiRequestCoalescer<CachedDynamicApiResponse>()
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val response = CachedDynamicApiResponse(200, "private".encodeToByteArray(), null, null)
                cache.store(accountId, requestIdentity, response)
                val read = async {
                    coalescer.execute(accountId, requestIdentity, load = {
                        started.complete(Unit)
                        release.await()
                        response
                    }, commit = { cache.store(accountId, requestIdentity, it) })
                }
                started.await()

                clearAndroidDynamicApiState(accountId, coalescer, cache)
                release.complete(Unit)

                assertFails { read.await() }
                assertNull(cache.load(accountId, requestIdentity, 1_024))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `second Android service cannot commit a GET that crossed account removal`() = runBlocking {
        supervisorScope {
            val root = Files.createTempDirectory("android-dynamic-cross-service-").toFile()
            try {
                val accountId = "b".repeat(64)
                val requestIdentity = "GET /dashboard/widgets"
                val firstService = androidDynamicApiProcessState(root)
                val secondService = androidDynamicApiProcessState(root.resolve("."))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val response = CachedDynamicApiResponse(200, "private".encodeToByteArray(), null, null)
                val read = async {
                    secondService.coalescer.execute(accountId, requestIdentity, load = {
                        started.complete(Unit)
                        release.await()
                        NextcloudApiResponse(200, response.body, response.contentType, response.etag)
                    }, commit = { loaded ->
                        secondService.cache.store(
                            accountId,
                            requestIdentity,
                            CachedDynamicApiResponse(loaded.status, loaded.body, loaded.contentType, loaded.etag),
                        )
                    })
                }
                started.await()

                clearAndroidDynamicApiState(accountId, firstService.coalescer, firstService.cache)
                release.complete(Unit)

                assertFails { read.await() }
                assertNull(firstService.cache.load(accountId, requestIdentity, 1_024))
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
