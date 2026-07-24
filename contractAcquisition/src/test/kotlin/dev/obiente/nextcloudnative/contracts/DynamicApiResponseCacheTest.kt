package dev.obiente.nextcloudnative.contracts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicApiResponseCacheTest {
    private val account = "a".repeat(64)

    @Test
    fun successfulResponseSurvivesCacheRecreationAndRemainsAccountIsolated() {
        val root = Files.createTempDirectory("ncn-dynamic-api-cache-").toFile()
        try {
            val response = CachedDynamicApiResponse(
                status = 200,
                body = """[{"id":1}]""".encodeToByteArray(),
                contentType = "application/json",
                etag = "\"v1\"",
            )
            DynamicApiResponseCache(root).store(account, "GET /apps/example/items", response)

            val loaded = DynamicApiResponseCache(root).load(account, "GET /apps/example/items", 1_024)

            assertEquals(response.status, loaded?.status)
            assertContentEquals(response.body, loaded?.body)
            assertEquals(response.contentType, loaded?.contentType)
            assertNull(
                DynamicApiResponseCache(root).load("b".repeat(64), "GET /apps/example/items", 1_024),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun entriesExpireAndMutationsCanInvalidateAnAccount() {
        val root = Files.createTempDirectory("ncn-dynamic-api-cache-").toFile()
        try {
            var now = 1_000L
            val cache = DynamicApiResponseCache(root, freshForMillis = 100L) { now }
            val response = CachedDynamicApiResponse(200, byteArrayOf(1), "application/json", null)
            cache.store(account, "GET /apps/example/items", response)
            now += 101L
            assertNull(cache.load(account, "GET /apps/example/items", 10))

            cache.store(account, "GET /apps/example/items", response)
            cache.invalidateAccount(account)
            assertNull(cache.load(account, "GET /apps/example/items", 10))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun callersWithSmallerResponseLimitCannotReadOversizedCachedBody() {
        val root = Files.createTempDirectory("ncn-dynamic-api-cache-").toFile()
        try {
            val cache = DynamicApiResponseCache(root)
            cache.store(
                account,
                "GET /apps/example/items",
                CachedDynamicApiResponse(200, ByteArray(32), "application/json", null),
            )

            assertNull(cache.load(account, "GET /apps/example/items", 16))
        } finally {
            root.deleteRecursively()
        }
    }
}
