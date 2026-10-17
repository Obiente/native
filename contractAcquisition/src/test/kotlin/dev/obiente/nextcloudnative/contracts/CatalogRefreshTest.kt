package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class CatalogRefreshTest {
    private var now = 1_000_000L

    @Test
    fun `an explicit retry after no matching release downloads the catalog again and finds the new release`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            repeat(2) { server.enqueue(MockResponse(body = catalog(server, "5.1.0"))) }
            assertEquals(unavailable(ContractUnavailableReason.NoMatchingRelease), acquirer.acquireOutcome(request()))
            assertEquals(2, server.requestCount)

            server.enqueue(MockResponse(body = catalog(server, "4.0.2")))
            server.enqueue(MockResponse(body = "package"))
            val refreshed = acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh)
            assertEquals("4.0.2", assertIs<ContractAcquisitionOutcome.Acquired>(refreshed).contract.contractVersion)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `automatic discovery keeps using the cached catalog`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            repeat(2) { server.enqueue(MockResponse(body = catalog(server, "5.1.0"))) }
            repeat(2) { acquirer.acquireOutcome(request()) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a failed or unreadable forced download keeps the cached catalog`() {
        for (failed in listOf(MockResponse(code = 503, body = "unavailable"), MockResponse(body = "[truncated"))) {
            MockWebServer().use { server ->
                server.start()
                val cache = MemoryAppStoreCatalogCache()
                val acquirer = acquirer(server, cache)
                repeat(2) { server.enqueue(MockResponse(body = catalog(server, "5.1.0"))) }
                acquirer.acquireOutcome(request())
                val cached = catalogUrls(server).map { url -> cache.load(url)?.decodeToString() }

                server.enqueue(failed)
                // Each catalog URL has its own refresh window, so the full catalog is refreshed too.
                if (failed.code != 503) server.enqueue(MockResponse(body = catalog(server, "5.1.0")))
                if (failed.code == 503) {
                    assertFailsWith<ContractSourceHttpException> { acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh) }
                } else {
                    // The unreadable platform catalog is not cached and makes the source malformed.
                    assertEquals(
                        unavailable(ContractUnavailableReason.UnreadableCatalog),
                        acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh),
                    )
                }
                assertEquals(cached, catalogUrls(server).map { url -> cache.load(url)?.decodeToString() })
                assertEquals(if (failed.code == 503) 3 else 4, server.requestCount)
            }
        }
    }

    @Test
    fun `repeated retries download each catalog at most once per interval`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            repeat(2) { server.enqueue(MockResponse(body = catalog(server, "5.1.0"))) }
            acquirer.acquireOutcome(request())
            repeat(2) { server.enqueue(MockResponse(body = catalog(server, "5.1.0"))) }
            repeat(5) { acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh) }
            assertEquals(4, server.requestCount)

            now += CatalogRefreshThrottle.DEFAULT_CATALOG_REFRESH_INTERVAL_MILLIS
            repeat(2) { server.enqueue(MockResponse(body = catalog(server, "5.1.0"))) }
            acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh)
            assertEquals(6, server.requestCount)
        }
    }

    @Test
    fun `the throttle opens one window per catalog and counts clock skew as a new window`() {
        val throttle = CatalogRefreshThrottle(intervalMillis = 10, nowMillis = { now })
        assertTrue(throttle.tryRefresh("a"))
        assertFalse(throttle.tryRefresh("a"))
        assertTrue(throttle.tryRefresh("b"))
        now += 9
        assertFalse(throttle.tryRefresh("a"))
        now += 1
        assertTrue(throttle.tryRefresh("a"))
        now -= 100
        assertTrue(throttle.tryRefresh("a"))
    }

    private fun unavailable(reason: ContractUnavailableReason) = ContractAcquisitionOutcome.Unavailable(reason)

    private fun request() = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    private fun catalogUrls(server: MockWebServer) =
        listOf(server.url("api/v1/platform/34.0.3/apps.json").toString(), server.url("api/v1/apps.json").toString())

    private fun catalog(server: MockWebServer, version: String): String {
        val download = server.url("downloads/cospend-$version.tar.gz")
        return """[{"id":"cospend","certificate":"certificate","releases":[""" +
            """{"version":"$version","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""
    }

    private fun acquirer(server: MockWebServer, cache: AppStoreCatalogCache = MemoryAppStoreCatalogCache()) =
        SignedAppStoreContractAcquirer(
            httpClient = OkHttpClient(),
            appStoreBaseUrl = server.url("api/v1").toString(),
            requireHttps = false,
            trustVerifier = object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                    VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
            },
            catalogCache = cache,
            catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { now }),
        )
}
