package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewMemoryCacheTest {
    @Test
    fun missingGenerationBypassesMemoryCache() = runBlocking {
        val cache = PreviewMemoryCache()
        var loads = 0
        val key = previewCacheKeyOrNull(
            account = "no-generation-account",
            variant = "core-preview",
            fileId = 101L,
            etag = null,
            width = 320,
            height = 320,
        )

        repeat(2) {
            loadPreviewMemoryCached(key, cache) {
                loads += 1
                byteArrayOf(loads.toByte())
            }
        }

        assertNull(key)
        assertEquals(2, loads)
    }

    @Test
    fun authoritativeGenerationCachesAndSeparatesRevisions() = runBlocking {
        val cache = PreviewMemoryCache()
        var firstLoads = 0
        val firstKey = previewCacheKeyOrNull(
            account = "generation-account",
            variant = "core-preview",
            fileId = 102L,
            etag = "\"first\"",
            width = 320,
            height = 320,
        )
        val secondKey = previewCacheKeyOrNull(
            account = "generation-account",
            variant = "core-preview",
            fileId = 102L,
            etag = "\"second\"",
            width = 320,
            height = 320,
        )

        val first = loadPreviewMemoryCached(firstKey, cache) {
            firstLoads += 1
            byteArrayOf(1)
        }
        val repeated = loadPreviewMemoryCached(firstKey, cache) {
            firstLoads += 1
            byteArrayOf(2)
        }
        val second = loadPreviewMemoryCached(secondKey, cache) { byteArrayOf(3) }

        assertEquals(1, firstLoads)
        assertContentEquals(byteArrayOf(1), first)
        assertContentEquals(byteArrayOf(1), repeated)
        assertContentEquals(byteArrayOf(3), second)
    }

    @Test
    fun loadStartedBeforeRetirementCannotPublishAfterReactivation() = runBlocking {
        val session = NextcloudSession("https://preview-incarnation.example.test", "user", "secret")
        val accountKey = session.accountId.storageKey
        val key = PreviewCacheKey(accountKey, "core", 103L, "etag", 64, 64)
        val loadStarted = CompletableDeferred<Unit>()
        val allowLoadToFinish = CompletableDeferred<Unit>()
        AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        try {
            val pending = async(Dispatchers.Default) {
                loadPreviewMemoryCached(key) {
                    loadStarted.complete(Unit)
                    allowLoadToFinish.await()
                    byteArrayOf(4)
                }
            }
            loadStarted.await()

            AccountPrivateMemoryLifecycle.retireAccount(accountKey)
            AccountPrivateMemoryLifecycle.activateAccount(accountKey)
            allowLoadToFinish.complete(Unit)

            assertContentEquals(byteArrayOf(4), pending.await())
            assertNull(sharedPreviewMemoryCache.get(key))

            assertContentEquals(byteArrayOf(5), loadPreviewMemoryCached(key) { byteArrayOf(5) })
            assertContentEquals(byteArrayOf(5), sharedPreviewMemoryCache.get(key))
        } finally {
            allowLoadToFinish.complete(Unit)
            AccountPrivateMemoryCleanup.removeAccount(accountKey)
            AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        }
    }

    @Test
    fun concurrentRetirementAndPublicationKeepsThePreviewMapConsistent(): Unit = runBlocking {
        val session = NextcloudSession("https://preview-race.example.test", "user", "secret")
        val accountKey = session.accountId.storageKey
        AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        try {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val publishers = List(4) { publisher ->
                        async {
                            repeat(100) { revision ->
                                val key = PreviewCacheKey(
                                    accountKey, "core", publisher.toLong(), "etag-$revision", 64, 64,
                                )
                                loadPreviewMemoryCached(key) { byteArrayOf(revision.toByte()) }
                                sharedPreviewMemoryCache.get(key)
                            }
                        }
                    }
                    val retirements = async {
                        repeat(50) {
                            AccountPrivateMemoryLifecycle.retireAccount(accountKey)
                            AccountPrivateMemoryLifecycle.activateAccount(accountKey)
                        }
                    }
                    (publishers + retirements).awaitAll()
                }
            }
        } finally {
            AccountPrivateMemoryCleanup.removeAccount(accountKey)
            AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        }
    }

    @Test
    fun `retirement purges only the exact preview account`() {
        val cache = PreviewMemoryCache()
        val target = key("target-account", 201L)
        val other = key("other-account", 202L)
        cache.put(target, byteArrayOf(1), requireNotNull(cache.producer(target)))
        cache.put(other, byteArrayOf(2), requireNotNull(cache.producer(other)))

        cache.retireAccount(target.account)

        assertNull(cache.get(target))
        assertContentEquals(byteArrayOf(2), cache.get(other))
        cache.activateAccount(target.account)
        assertNull(cache.get(target))
    }

    @Test
    fun `synchronized preview cache retains its byte bound and lru order`() {
        val cache = PreviewMemoryCache(maximumBytes = 2)
        val first = key("bounded-account", 301L)
        val second = key("bounded-account", 302L)
        val third = key("bounded-account", 303L)
        val producer = requireNotNull(cache.producer(first))
        cache.put(first, byteArrayOf(1), producer)
        cache.put(second, byteArrayOf(2), producer)
        assertContentEquals(byteArrayOf(1), cache.get(first))

        cache.put(third, byteArrayOf(3), producer)

        assertNull(cache.get(second))
        assertContentEquals(byteArrayOf(1), cache.get(first))
        assertContentEquals(byteArrayOf(3), cache.get(third))
    }

    @Test
    fun `completion crossing retirement and reactivation cannot repopulate previews`() = runBlocking {
        val cache = PreviewMemoryCache()
        val key = key("recreated-account", 203L)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val staleLoad = async(start = CoroutineStart.UNDISPATCHED) {
            loadPreviewMemoryCached(key, cache) {
                loads += 1
                started.complete(Unit)
                release.await()
                byteArrayOf(3)
            }
        }
        started.await()

        cache.retireAccount(key.account)
        cache.activateAccount(key.account)
        release.complete(Unit)

        assertContentEquals(byteArrayOf(3), staleLoad.await())
        val current = loadPreviewMemoryCached(key, cache) {
            loads += 1
            byteArrayOf(4)
        }
        assertEquals(2, loads)
        assertContentEquals(byteArrayOf(4), current)
        assertContentEquals(byteArrayOf(4), cache.get(key))
    }

    @Test
    fun `concurrent preview access remains safe across retirement`() = runBlocking {
        val cache = PreviewMemoryCache(maximumBytes = 1_024)
        val account = "concurrent-account"
        val producer = requireNotNull(cache.producer(key(account, 0L)))

        val writers = List(8) { worker ->
            async(Dispatchers.Default) {
                repeat(100) { iteration ->
                    val key = key(account, (worker * 100 + iteration).toLong())
                    cache.put(key, byteArrayOf(iteration.toByte()), producer)
                    cache.get(key)
                }
            }
        }
        val retirement = async(Dispatchers.Default) { cache.retireAccount(account) }
        (writers + retirement).awaitAll()

        repeat(800) { fileId -> assertNull(cache.get(key(account, fileId.toLong()))) }
    }

    private fun key(account: String, fileId: Long) = PreviewCacheKey(
        account = account,
        variant = "core-preview",
        fileId = fileId,
        etag = "etag-$fileId",
        width = 320,
        height = 320,
    )
}
