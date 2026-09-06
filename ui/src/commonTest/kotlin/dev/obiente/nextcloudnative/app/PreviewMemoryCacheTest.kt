package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
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
            loadPreviewMemoryCached(key) {
                loads += 1
                byteArrayOf(loads.toByte())
            }
        }

        assertNull(key)
        assertEquals(2, loads)
    }

    @Test
    fun authoritativeGenerationCachesAndSeparatesRevisions() = runBlocking {
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

        val first = loadPreviewMemoryCached(firstKey) {
            firstLoads += 1
            byteArrayOf(1)
        }
        val repeated = loadPreviewMemoryCached(firstKey) {
            firstLoads += 1
            byteArrayOf(2)
        }
        val second = loadPreviewMemoryCached(secondKey) { byteArrayOf(3) }

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
            assertNull(PreviewMemoryCache.get(key))

            assertContentEquals(byteArrayOf(5), loadPreviewMemoryCached(key) { byteArrayOf(5) })
            assertContentEquals(byteArrayOf(5), PreviewMemoryCache.get(key))
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
                                PreviewMemoryCache.get(key)
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
}
