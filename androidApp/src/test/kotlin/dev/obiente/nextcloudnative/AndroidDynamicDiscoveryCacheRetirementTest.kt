package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DynamicNativeMemoryCacheProducer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `legacy cleanup without a persisted cache identity removes all discovery metadata`() {
        val root = Files.createTempDirectory("dynamic-discovery-legacy").toFile()
        val cache = AndroidDynamicDiscoveryCache(root)
        try {
            cache.save(
                "a".repeat(64), "1".repeat(64), "deck", "first",
                producerForTest("a".repeat(64), 0L),
            )
            cache.save(
                "b".repeat(64), "2".repeat(64), "talk", "second",
                producerForTest("b".repeat(64), 0L),
            )

            cache.retireAccount("a".repeat(64), null)

            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun producerForTest(accountStorageKey: String, incarnation: Long): DynamicNativeMemoryCacheProducer =
        DynamicNativeMemoryCacheProducer::class.java
            .getDeclaredConstructor(String::class.java, java.lang.Long.TYPE)
            .newInstance(accountStorageKey, incarnation)
}
