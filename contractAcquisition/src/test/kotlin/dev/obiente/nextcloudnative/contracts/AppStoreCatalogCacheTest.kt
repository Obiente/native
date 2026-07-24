package dev.obiente.nextcloudnative.contracts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppStoreCatalogCacheTest {
    @Test
    fun catalogSurvivesAcrossCacheInstancesWithoutExposingUrlInFilename() {
        val directory = Files.createTempDirectory("ncn-catalog-cache-").toFile()
        try {
            var now = 10_000L
            val url = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json"
            val bytes = "[{\"id\":\"cospend\"}]".encodeToByteArray()
            FileAppStoreCatalogCache(directory) { now }.store(url, bytes)

            val loaded = FileAppStoreCatalogCache(directory) { now }.load(url)

            assertContentEquals(bytes, loaded)
            assertEquals(1, directory.listFiles().orEmpty().size)
            assertEquals(false, directory.listFiles().single().name.contains("nextcloud"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleCatalogsAreNotLoaded() {
        val directory = Files.createTempDirectory("ncn-catalog-cache-").toFile()
        try {
            var now = 10_000L
            val url = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json"
            val cache = FileAppStoreCatalogCache(directory) { now }
            cache.store(url, "[]".encodeToByteArray())
            now += 7L * 60L * 60L * 1_000L

            assertNull(cache.load(url))
        } finally {
            directory.deleteRecursively()
        }
    }
}
