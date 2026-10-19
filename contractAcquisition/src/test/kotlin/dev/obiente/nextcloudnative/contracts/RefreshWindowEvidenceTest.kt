package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The refresh window is shared per catalog URL across apps and versions, so another request can
 * use up one catalog's window while this refresh downloads the other. Installed version: 4.0.2.
 */
class RefreshWindowEvidenceTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val throttle = CatalogRefreshThrottle(nowMillis = { 50_000_000L })
    private val cache = MemoryVerifiedContractCache()

    @Test
    fun `a refused exact lookup still lets a fresh compatible candidate provide the contract`() {
        MockWebServer().use { server ->
            server.start()
            throttle.tryRefresh(fullUrl(server))
            server.enqueue(MockResponse(body = catalog(release("4.0.1", server)).toString()))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server, withContract = true).acquireOutcome(request, AcquisitionCachePolicy.Refresh)
            assertEquals("4.0.1", assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.contractVersion)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a refused exact lookup is uninspected when compatible candidates provide no contract`() {
        MockWebServer().use { server ->
            server.start()
            throttle.tryRefresh(fullUrl(server))
            server.enqueue(MockResponse(body = catalog(release("4.0.1", server)).toString()))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server, withContract = false).acquireOutcome(request, AcquisitionCachePolicy.Refresh)
            assertEquals(ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.RecentlyChecked), outcome)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a refused exact lookup serves a cached contract when compatible candidates provide none`() {
        MockWebServer().use { server ->
            server.start()
            val cachedContract = contract("cached")
            cache.store(request, cachedContract)
            throttle.tryRefresh(fullUrl(server))
            server.enqueue(MockResponse(body = catalog(release("4.0.1", server)).toString()))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server, withContract = false).acquireOutcome(request, AcquisitionCachePolicy.Refresh)
            assertEquals(ContractAcquisitionOutcome.Acquired(cachedContract), outcome)
        }
    }

    @Test
    fun `a refused platform catalog still lets a fresh full catalog provide the exact release`() {
        MockWebServer().use { server ->
            server.start()
            throttle.tryRefresh(platformUrl(server))
            server.enqueue(MockResponse(body = catalog(release("4.0.2", server)).toString()))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server, withContract = true).acquireOutcome(request, AcquisitionCachePolicy.Refresh)
            assertEquals("4.0.2", assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.contractVersion)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `two refused catalogs with nothing cached were recently checked`() {
        MockWebServer().use { server ->
            server.start()
            throttle.tryRefresh(platformUrl(server))
            throttle.tryRefresh(fullUrl(server))
            val outcome = acquirer(server, withContract = true).acquireOutcome(request, AcquisitionCachePolicy.Refresh)
            assertEquals(ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.RecentlyChecked), outcome)
            assertEquals(0, server.requestCount)
        }
    }

    private fun base(server: MockWebServer) = server.url("api/v1").toString().trimEnd('/')

    private fun platformUrl(server: MockWebServer) = "${base(server)}/platform/34.0.3/apps.json"

    private fun fullUrl(server: MockWebServer) = "${base(server)}/apps.json"

    private fun catalog(vararg releases: JSONObject): JSONArray = JSONArray().put(
        JSONObject().put("id", "cospend").put("certificate", "certificate").put("releases", JSONArray(releases.toList())),
    )

    private fun release(version: String, server: MockWebServer): JSONObject = JSONObject()
        .put("version", version)
        .put("download", server.url("downloads/cospend-$version.tar.gz").toString())
        .put("signature", "c2lnbmF0dXJl")
        .put("signatureDigest", "sha512")
        .put("isNightly", false)

    private fun contract(document: String) = VerifiedOpenApiContract(
        appId = "cospend",
        appVersion = "4.0.2",
        contractVersion = "4.0.2",
        specFile = "openapi.json",
        document = document,
        catalogUrl = "https://fixture.invalid/apps.json",
        packageUrl = "https://fixture.invalid/cospend.tar.gz",
        sourceUrl = "https://fixture.invalid/cospend.tar.gz#openapi.json",
        sourceKind = OpenApiContractSourceKind.SignedAppPackage,
        contractKind = VerifiedContractKind.OpenApi,
    )

    private fun acquirer(server: MockWebServer, withContract: Boolean) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
                if (!withContract) throw OpenApiContractMissingException("synthetic")
                return VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
            }
        },
        verifiedContractCache = cache,
        catalogRefreshThrottle = throttle,
    )
}
