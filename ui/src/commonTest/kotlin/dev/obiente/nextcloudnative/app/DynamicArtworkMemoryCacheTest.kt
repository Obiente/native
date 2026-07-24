package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicArtworkMemoryCacheTest {
    @Test
    fun `decoded byte budget evicts least recently used artwork`() = runBlocking {
        val cache = DynamicArtworkMemoryCache<String>(
            maximumBytes = 10,
            sizeOf = { value -> value.length.toLong() },
        )

        cache.getOrLoad("album-a") { "123456" }
        cache.getOrLoad("album-b") { "1234" }
        cache.getOrLoad("album-a") { error("cache hit must not reload") }
        cache.getOrLoad("album-c") { "1234" }

        val snapshot = cache.snapshot()
        assertEquals(2, snapshot.entryCount)
        assertEquals(10, snapshot.decodedBytes)
        assertEquals(listOf("album-a", "album-c"), snapshot.keysLeastToMostRecent)
        assertEquals("reloaded", cache.getOrLoad("album-b") { "reloaded" })
        assertNull(cache.getOrLoad("oversized") { "12345678901" })
    }

    @Test
    fun `concurrent artwork reads coalesce and negative results suppress retries`() = runBlocking {
        val cache = DynamicArtworkMemoryCache<String>(
            maximumBytes = 64,
            sizeOf = { value -> value.length.toLong() },
        )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val reads = List(5) {
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.getOrLoad("shared-cover") {
                    loads += 1
                    started.complete(Unit)
                    release.await()
                    "decoded"
                }
            }
        }
        started.await()
        release.complete(Unit)

        assertEquals(List(5) { "decoded" }, reads.awaitAll())
        assertEquals(1, loads)

        var failedLoads = 0
        assertNull(cache.getOrLoad("missing-cover") { failedLoads += 1; null })
        assertNull(cache.getOrLoad("missing-cover") { failedLoads += 1; "unexpected" })
        assertEquals(1, failedLoads)
        assertEquals(1, cache.snapshot().negativeCount)
    }
}
