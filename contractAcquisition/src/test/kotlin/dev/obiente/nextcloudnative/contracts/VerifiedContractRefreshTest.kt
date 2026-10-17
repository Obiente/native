package dev.obiente.nextcloudnative.contracts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class VerifiedContractRefreshTest {
    private var now = 50_000_000L
    private var packageDocument = "{\"version\":1}"

    @Test
    fun `a refresh bypasses a cached contract and replaces it with the corrected one`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            enqueueCatalogAndPackage(server)
            assertEquals("{\"version\":1}", document(acquirer.acquireOutcome(request())))

            packageDocument = "{\"version\":2}"
            enqueueCatalogAndPackage(server)
            assertEquals("{\"version\":2}", document(acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh)))
            val requests = server.requestCount

            // Automatic discovery serves the replacement from the cache.
            assertEquals("{\"version\":2}", document(acquirer.acquireOutcome(request())))
            assertEquals(requests, server.requestCount)
        }
    }

    @Test
    fun `a failed refresh keeps the cached contract for automatic discovery`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            enqueueCatalogAndPackage(server)
            acquirer.acquireOutcome(request())
            server.enqueue(MockResponse(code = 503, body = "unavailable"))
            assertFailsWith<ContractSourceHttpException> { acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh) }
            assertEquals("{\"version\":1}", document(acquirer.acquireOutcome(request())))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `a refresh refused by the throttle serves the cached contract`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            enqueueCatalogAndPackage(server)
            acquirer.acquireOutcome(request())
            server.enqueue(MockResponse(code = 503, body = "unavailable"))
            assertFailsWith<ContractSourceHttpException> { acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh) }
            assertEquals("{\"version\":1}", document(acquirer.acquireOutcome(request(), AcquisitionCachePolicy.Refresh)))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `an invalidated contract is acquired again by the next automatic discovery`() {
        val directory = Files.createTempDirectory("verified-contracts").toFile()
        try {
            MockWebServer().use { server ->
                server.start()
                val cache = FileVerifiedContractCache(directory, nowMillis = { now })
                val acquirer = acquirer(server, cache)
                enqueueCatalogAndPackage(server)
                acquirer.acquireOutcome(request())
                val other = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.1")
                cache.store(other, cache.load(request())!!.copy(appVersion = "4.0.1"))

                acquirer.invalidateVerifiedContract(request())
                assertNull(cache.load(request()))
                assertNotNull(cache.load(other))

                packageDocument = "{\"version\":2}"
                server.enqueue(MockResponse(body = "package"))
                // The catalog stays cached; only the package is downloaded again.
                assertEquals("{\"version\":2}", document(acquirer.acquireOutcome(request())))
                assertEquals(3, server.requestCount)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun document(outcome: ContractAcquisitionOutcome): String =
        assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.document

    private fun request() = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    private fun enqueueCatalogAndPackage(server: MockWebServer) {
        val download = server.url("downloads/cospend-4.0.2.tar.gz")
        server.enqueue(MockResponse(body = """[{"id":"cospend","certificate":"c","releases":[""" +
            """{"version":"4.0.2","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""))
        server.enqueue(MockResponse(body = "package"))
    }

    private fun acquirer(server: MockWebServer, cache: VerifiedContractCache = MemoryVerifiedContractCache()) =
        SignedAppStoreContractAcquirer(
            httpClient = OkHttpClient(),
            appStoreBaseUrl = server.url("api/v1").toString(),
            requireHttps = false,
            trustVerifier = object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                    VerifiedPackageContract(release.appId, release.version, "openapi.json", packageDocument)
            },
            verifiedContractCache = cache,
            catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { now }),
        )
}
