package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoriesMainTimelineIndexCacheTest {
    @Test
    fun cacheHitsKeepRecentlyUsedIndicesWithinTheFourScopeLimit() = runBlocking {
        val cache = MemoriesMainTimelineIndexCache(AccountPrivateMemoryGate())
        (1..4).forEach { publish(cache, it) }

        val cached = cache.load(account(1), scope(1), false, cache.producer(account(1))) {
            error("The retained index must be reused without another request")
        }
        val retained = assertIs<MemoriesMainTimelineLoadResult.Loaded<MemoriesMainTimelineCachedIndex>>(cached).value
        assertEquals(1L, retained.index.days.single().id)
        publish(cache, 5)

        assertNull(cache.activeMemoriesIndex(account(2), scope(2)))
        assertNotNull(cache.activeMemoriesIndex(account(1), scope(1)))
        publish(cache, 6)

        assertNull(cache.activeMemoriesIndex(account(3), scope(3)))
        listOf(1, 4, 5, 6).forEach { id ->
            assertNotNull(cache.activeMemoriesIndex(account(id), scope(id)))
        }
    }

    @Test
    fun inFlightRefreshMayPublishAfterEvictionWithoutExceedingTheLimit() = runBlocking {
        val cache = MemoriesMainTimelineIndexCache(AccountPrivateMemoryGate())
        (1..4).forEach { publish(cache, it) }
        val releaseRefresh = CompletableDeferred<Unit>()
        val producer = cache.producer(account(1))
        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            cache.load(account(1), scope(1), true, producer) {
                releaseRefresh.await()
                MemoriesMainTimelineLoadResult.Loaded(MemoriesMainTimelineDayIndex(listOf(NativeMediaDay(99L, 1))))
            }
        }

        publish(cache, 5)
        assertNull(cache.activeMemoriesIndex(account(1), scope(1)))
        releaseRefresh.complete(Unit)
        val refreshed = assertIs<MemoriesMainTimelineLoadResult.Loaded<MemoriesMainTimelineCachedIndex>>(refresh.await())
            .value
        assertTrue(cache.markMemoriesActive(account(1), scope(1), refreshed.sourceGeneration, producer))

        assertEquals(99L, assertNotNull(cache.activeMemoriesIndex(account(1), scope(1))).index.days.single().id)
        assertNull(cache.activeMemoriesIndex(account(2), scope(2)))
        (3..5).forEach { id -> assertNotNull(cache.activeMemoriesIndex(account(id), scope(id))) }
    }

    private suspend fun publish(cache: MemoriesMainTimelineIndexCache, id: Int) {
        val account = account(id)
        val producer = cache.producer(account)
        val result = cache.load(account, scope(id), true, producer) {
            MemoriesMainTimelineLoadResult.Loaded(MemoriesMainTimelineDayIndex(listOf(NativeMediaDay(id.toLong(), 1))))
        }
        val loaded = assertIs<MemoriesMainTimelineLoadResult.Loaded<MemoriesMainTimelineCachedIndex>>(result).value
        assertTrue(cache.markMemoriesActive(account, scope(id), loaded.sourceGeneration, producer))
    }

    private fun account(id: Int) = NextcloudAccountId(id.toString().repeat(64))

    private fun scope(id: Int) = "synthetic-scope-$id"
}
