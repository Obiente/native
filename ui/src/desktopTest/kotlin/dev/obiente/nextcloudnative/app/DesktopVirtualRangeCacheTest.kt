package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DesktopVirtualRangeCacheTest {
    @Test
    fun `combined automatic cache budget is shared while pinned Windows bytes are excluded`() {
        assertEquals(
            7L,
            combinedAutomaticCacheExcess(
                maximumBytes = 20L,
                completeFileBytes = 9L,
                rangeBytes = 8L,
                windowsCachedBytes = 15L,
                windowsPinnedBytes = 5L,
            ),
        )
        assertEquals(
            0L,
            combinedAutomaticCacheExcess(20L, 5L, 5L, windowsCachedBytes = 15L, windowsPinnedBytes = 5L),
        )
    }

    @Test
    fun `exact revision blocks survive cache restart`() {
        val directory = Files.createTempDirectory("virtual-range-cache-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { VirtualFileCachePolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 8L, 0L, "abcd".encodeToByteArray())

            val restarted = DesktopVirtualRangeCache(directory) { VirtualFileCachePolicy() }
            assertContentEquals(
                "abcd".encodeToByteArray(),
                restarted.readBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 8L, 0L, 4),
            )
            assertEquals(null, restarted.readBlock(ACCOUNT_ID, "Photos/example.raf", "etag-2", 8L, 0L, 4))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `active virtual file blocks are protected until the final handle closes`() {
        val directory = Files.createTempDirectory("virtual-range-cache-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(maximumCacheBytes = 1L, minimumFreeSpaceBytes = 0L, unusedFileAgeMillis = null)
            }
            cache.acquire(ACCOUNT_ID, "Photos/example.raf")
            cache.storeBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 4L, 0L, "data".encodeToByteArray())
            assertEquals(4L, cache.summary(ACCOUNT_ID).cachedBytes)

            cache.freeUp(ACCOUNT_ID, 4L)
            assertEquals(4L, cache.summary(ACCOUNT_ID).cachedBytes)

            cache.release(ACCOUNT_ID, "Photos/example.raf")
            cache.freeUp(ACCOUNT_ID, 4L)
            assertEquals(0L, cache.summary(ACCOUNT_ID).cachedBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val ACCOUNT_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
