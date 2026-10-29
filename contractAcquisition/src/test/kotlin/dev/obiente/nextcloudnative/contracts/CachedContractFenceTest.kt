package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** A cached verified contract is served only while it is the latest accepted result for its key. */
class CachedContractFenceTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val cache = MemoryVerifiedContractCache()

    @Test
    fun `a newer definitive no-contract result is reported instead of the stale cached contract`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            enqueueCatalogAndPackage(server)
            assertEquals("{}", assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request)).contract.document)

            // A refresh finds that neither catalog lists the app any more.
            repeat(2) { server.enqueue(MockResponse(body = "[]")) }
            val noRelease = ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoMatchingRelease)
            assertEquals(noRelease, acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh))

            // The cached contract is still stored, but automatic discovery does not contradict the refresh.
            val requests = server.requestCount
            assertEquals(noRelease, acquirer.acquireOutcome(request))
            assertEquals(requests, server.requestCount)
        }
    }

    @Test
    fun `a cached contract read during an invalidation is not served`() {
        val fence = ContractPublicationFence()
        val reading = fence.start(request)
        fence.invalidate(request) {}
        assertEquals(
            ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoUsableContract),
            fence.serveCached(request, reading, contract()),
        )
        // A read that starts after the invalidation still reports it while nothing newer was published.
        assertEquals(
            ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoUsableContract),
            fence.serveCached(request, fence.start(request), contract()),
        )
    }

    @Test
    fun `a cached contract that is still current is served`() {
        val fence = ContractPublicationFence()
        // Nothing recorded yet, for example after a process start.
        assertEquals(ContractAcquisitionOutcome.Acquired(contract()), fence.serveCached(request, fence.start(request), contract()))
        // The latest accepted result is a contract.
        fence.publish(request, fence.start(request), contract()) {}
        assertEquals(ContractAcquisitionOutcome.Acquired(contract()), fence.serveCached(request, fence.start(request), contract()))
        // A transient ending changes nothing.
        val transient = ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.UnreadableCatalog)
        fence.complete(request, fence.start(request), transient)
        assertEquals(ContractAcquisitionOutcome.Acquired(contract()), fence.serveCached(request, fence.start(request), contract()))
    }

    @Test
    fun `automatic discovery serves a current cached contract without a request`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            enqueueCatalogAndPackage(server)
            acquirer.acquireOutcome(request)
            val requests = server.requestCount
            assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request))
            assertEquals(requests, server.requestCount)
        }
    }

    private fun contract() = VerifiedOpenApiContract(
        appId = "cospend",
        appVersion = "4.0.2",
        contractVersion = "4.0.2",
        specFile = "openapi.json",
        document = "{}",
        catalogUrl = "https://fixture.invalid/apps.json",
        packageUrl = "https://fixture.invalid/cospend.tar.gz",
        sourceUrl = "https://fixture.invalid/cospend.tar.gz#openapi.json",
        sourceKind = OpenApiContractSourceKind.SignedAppPackage,
        contractKind = VerifiedContractKind.OpenApi,
    )

    private fun enqueueCatalogAndPackage(server: MockWebServer) {
        val download = server.url("downloads/cospend-4.0.2.tar.gz")
        server.enqueue(MockResponse(body = """[{"id":"cospend","certificate":"c","releases":[""" +
            """{"version":"4.0.2","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""))
        server.enqueue(MockResponse(body = "package"))
    }

    private fun acquirer(server: MockWebServer) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
        },
        verifiedContractCache = cache,
        catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { 50_000_000L }),
    )
}
