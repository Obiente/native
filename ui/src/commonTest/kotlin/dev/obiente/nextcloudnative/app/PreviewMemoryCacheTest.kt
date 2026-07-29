package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
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
}
