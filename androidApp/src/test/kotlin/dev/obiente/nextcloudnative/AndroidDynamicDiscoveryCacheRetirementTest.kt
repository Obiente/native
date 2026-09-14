package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DynamicNativeMemoryCacheProducer
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDynamicDiscoveryCacheRetirementTest {
    @Test
    fun `retirement deletes one account prefix and rejects stale publication until activation`() {
        val root = Files.createTempDirectory("dynamic-discovery").toFile()
        val cache = AndroidDynamicDiscoveryCache(root)
        val removedStorageKey = "a".repeat(64)
        val removedCacheId = "1".repeat(64)
        val retainedStorageKey = "b".repeat(64)
        val retainedCacheId = "2".repeat(64)
        val removedProducer = producerForTest(removedStorageKey, 0L)
        val retainedProducer = producerForTest(retainedStorageKey, 0L)
        try {
            cache.save(removedStorageKey, removedCacheId, "deck", "removed", removedProducer)
            cache.save(retainedStorageKey, retainedCacheId, "deck", "retained", retainedProducer)

            cache.retireAccount(removedStorageKey, removedCacheId)
            cache.activateAccount(removedStorageKey)
            cache.save(removedStorageKey, removedCacheId, "deck", "stale", removedProducer)

            assertNull(cache.load(removedStorageKey, removedCacheId, "deck"))
            assertEquals("retained", cache.load(retainedStorageKey, retainedCacheId, "deck"))
            assertFalse(root.resolve("$removedCacheId-deck.json").exists())
            assertTrue(root.resolve("$retainedCacheId-deck.json").isFile)

            cache.save(
                removedStorageKey, removedCacheId, "deck", "current",
                producerForTest(removedStorageKey, 1L),
            )
            assertEquals("current", cache.load(removedStorageKey, removedCacheId, "deck"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retirement removes both known generations without clearing another account`() {
        val root = Files.createTempDirectory("dynamic-discovery-prefixes").toFile()
        val cache = AndroidDynamicDiscoveryCache(root)
        val account = "a".repeat(64)
        val current = "1".repeat(64)
        val legacy = "2".repeat(32)
        val unrelated = "3".repeat(64)
        try {
            cache.save(account, current, "deck", "current", producerForTest(account, 0L))
            cache.save(account, legacy, "deck", "legacy", producerForTest(account, 0L))
            cache.save("b".repeat(64), unrelated, "deck", "retained", producerForTest("b".repeat(64), 0L))
            cache.retireAccount(account, current, legacy)
            cache.activateAccount(account)
            assertNull(cache.load(account, current, "deck"))
            assertNull(cache.load(account, legacy, "deck"))
            assertEquals("retained", cache.load("b".repeat(64), unrelated, "deck"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `legacy cleanup without a full cache identity preserves unrelated contracts`() {
        val root = Files.createTempDirectory("dynamic-discovery-legacy").toFile()
        val cache = AndroidDynamicDiscoveryCache(root)
        val removed = NextcloudSession("https://cloud.example.test", "removed", "unused")
        val retained = NextcloudSession("https://cloud.example.test", "retained", "unused")
        val current = NextcloudDocumentIds.cacheAccountId(removed)
        val legacy = NextcloudDocumentIds.accountKey(removed)
        val unrelated = NextcloudDocumentIds.cacheAccountId(retained)
        try {
            cache.save(removed.accountId.storageKey, current, "deck", "current", producerForTest(removed.accountId.storageKey, 0L))
            cache.save(removed.accountId.storageKey, legacy, "deck", "legacy", producerForTest(removed.accountId.storageKey, 0L))
            cache.save(retained.accountId.storageKey, unrelated, "talk", "retained", producerForTest(retained.accountId.storageKey, 0L))
            root.resolve("$current-deck.json.part").writeText("unfinished")

            cache.retireAccount(removed.accountId.storageKey, null, legacy)
            cache.activateAccount(removed.accountId.storageKey)

            assertNull(cache.load(removed.accountId.storageKey, current, "deck"))
            assertNull(cache.load(removed.accountId.storageKey, legacy, "deck"))
            assertEquals("retained", cache.load(retained.accountId.storageKey, unrelated, "talk"))
            assertEquals(setOf("$unrelated-talk.json"), root.listFiles().orEmpty().map { it.name }.toSet())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `missing every cache identity refuses cleanup rather than deleting another account`() {
        val root = Files.createTempDirectory("dynamic-discovery-unknown").toFile()
        val cache = AndroidDynamicDiscoveryCache(root)
        try {
            cache.save("b".repeat(64), "2".repeat(64), "talk", "retained", producerForTest("b".repeat(64), 0L))
            assertFailsWith<IllegalArgumentException> { cache.retireAccount("a".repeat(64), null) }
            assertEquals("retained", cache.load("b".repeat(64), "2".repeat(64), "talk"))
        } finally { root.deleteRecursively() }
    }

    private fun producerForTest(accountStorageKey: String, incarnation: Long): DynamicNativeMemoryCacheProducer =
        DynamicNativeMemoryCacheProducer::class.java
            .getDeclaredConstructor(String::class.java, java.lang.Long.TYPE)
            .newInstance(accountStorageKey, incarnation)
}
