package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDynamicDiscoveryCacheRetirementTest {
    @Test
    fun `credential commit fence rejects publication before durable cleanup deletes the file`() {
        val root = Files.createTempDirectory("desktop-dynamic-discovery-fence").toFile()
        val cache = DesktopDynamicDiscoveryCache(root)
        val accountStorageKey = "d".repeat(64)
        val cacheAccountId = "5".repeat(64)
        val staleProducer = DynamicNativeMemoryCacheProducer(accountStorageKey, 0L)
        try {
            cache.save(accountStorageKey, cacheAccountId, "deck", "before", staleProducer)

            cache.fenceAccount(accountStorageKey)
            cache.save(accountStorageKey, cacheAccountId, "deck", "late", staleProducer)

            assertTrue(root.resolve("$cacheAccountId-deck.json").isFile)
            assertNull(cache.load(accountStorageKey, cacheAccountId, "deck"))

            cache.retireAccount(accountStorageKey, cacheAccountId)
            assertFalse(root.resolve("$cacheAccountId-deck.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retirement deletes one account prefix and rejects stale publication until activation`() {
        val root = Files.createTempDirectory("desktop-dynamic-discovery").toFile()
        val first = DesktopDynamicDiscoveryCacheCoordinator.get(root)
        val second = DesktopDynamicDiscoveryCacheCoordinator.get(root)
        val removedStorageKey = "a".repeat(64)
        val removedCacheId = "1".repeat(64)
        val retainedStorageKey = "b".repeat(64)
        val retainedCacheId = "2".repeat(64)
        val removedProducer = DynamicNativeMemoryCacheProducer(removedStorageKey, 0L)
        val retainedProducer = DynamicNativeMemoryCacheProducer(retainedStorageKey, 0L)
        try {
            first.save(removedStorageKey, removedCacheId, "deck", "removed", removedProducer)
            first.save(retainedStorageKey, retainedCacheId, "deck", "retained", retainedProducer)

            second.retireAccount(removedStorageKey, removedCacheId)
            first.activateAccount(removedStorageKey)
            first.save(removedStorageKey, removedCacheId, "deck", "stale", removedProducer)

            assertNull(first.load(removedStorageKey, removedCacheId, "deck"))
            assertEquals("retained", first.load(retainedStorageKey, retainedCacheId, "deck"))
            assertFalse(root.resolve("$removedCacheId-deck.json").exists())
            assertTrue(root.resolve("$retainedCacheId-deck.json").isFile)

            first.save(
                removedStorageKey,
                removedCacheId,
                "deck",
                "current",
                DynamicNativeMemoryCacheProducer(removedStorageKey, 1L),
            )
            assertEquals("current", first.load(removedStorageKey, removedCacheId, "deck"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restart cleanup without a storage identity still removes account files`() {
        val root = Files.createTempDirectory("desktop-dynamic-discovery-restart").toFile()
        val cache = DesktopDynamicDiscoveryCache(root)
        val removedCacheId = "3".repeat(64)
        val retainedCacheId = "4".repeat(64)
        try {
            root.resolve("$removedCacheId-deck.json").writeText("removed")
            root.resolve("$removedCacheId-talk.json.part").writeText("partial")
            root.resolve("$retainedCacheId-deck.json").writeText("retained")

            cache.retireAccount(accountStorageKey = null, cacheAccountId = removedCacheId)

            assertFalse(root.resolve("$removedCacheId-deck.json").exists())
            assertFalse(root.resolve("$removedCacheId-talk.json.part").exists())
            assertTrue(root.resolve("$retainedCacheId-deck.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
