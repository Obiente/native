package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** The installed version is 4.0.2. No candidate package in these tests provides a contract. */
class AcquisitionEvidenceTest {
    @Test
    fun `a malformed compatible entry outranks a valid compatible candidate without a contract`() {
        val platform = catalog(release("4.0.1", server = true), release("4.0.0").also { it.remove("signature") })
        assertEquals(malformedSource, acquire(platform, full = catalog(release("4.0.1"))))
    }

    @Test
    fun `a malformed compatible entry outranks an inspected exact release without a contract`() {
        val platform = catalog(release("4.0.2", server = true), release("4.0.1").also { it.remove("download") })
        assertEquals(malformedSource, acquire(platform, full = null))
    }

    @Test
    fun `every inspected candidate without a contract and nothing dropped is no usable contract`() {
        val platform = catalog(release("4.0.2", server = true), release("4.0.1", server = true))
        assertEquals(unavailable(ContractUnavailableReason.NoUsableContract), acquire(platform, full = null, packages = 2))
    }

    @Test
    fun `an unidentified top-level element where the app is missing is malformed, not absence`() {
        val other = JSONObject().put("id", "other").put("releases", JSONArray())
        for (unidentified in listOf<Any>("junk", 7, JSONObject().put("id", 42), JSONObject().put("name", "cospend"))) {
            val full = JSONArray().put(other).put(unidentified)
            assertEquals(malformedSource, acquire(JSONArray().put(other), full = full, packages = 0), unidentified.toString())
        }
        // A catalog that simply does not list the app is still genuine absence.
        assertEquals(
            unavailable(ContractUnavailableReason.NoMatchingRelease),
            acquire(JSONArray().put(other), full = JSONArray().put(other), packages = 0),
        )
    }

    @Test
    fun `a catalog made only of unidentified elements is unreadable and never cached`() {
        for (body in listOf("""["junk", 7]""", """[{"id": 42}, {"name": "cospend"}, null]""")) {
            val parsed = parseReleases(body, "cospend", "https://fixture.invalid/apps.json", false)
            assertEquals(listOf(MalformedCatalogEntry(null, MalformedCatalogField.Document)), parsed.malformed, body)
            assertFalse(parsed.documentReadable, body)
        }
        val unreadable = unavailable(ContractUnavailableReason.UnreadableCatalog)
        assertEquals(unreadable, acquireRaw(platform = """["junk", 7]""", full = """["junk", 7]"""))
    }

    @Test
    fun `an unidentified element beside a valid matching release is ignored`() {
        val platform = JSONArray().put("junk").put(JSONObject().put("id", 42)).put(app(release("4.0.2", server = true)))
        assertEquals(emptyList<MalformedCatalogEntry>(), parseReleases(platform.toString(), "cospend", "https://fixture.invalid/apps.json", false).malformed)
        val outcome = acquire(platform, full = null) { release ->
            VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
        }
        assertEquals("4.0.2", assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.contractVersion)
    }

    private val malformedSource = unavailable(ContractUnavailableReason.MalformedSource)
    private var serverUrl = ""

    private fun acquire(
        platform: JSONArray,
        full: JSONArray?,
        packages: Int = 1,
        verify: (AppStoreRelease) -> VerifiedPackageContract = { throw OpenApiContractMissingException("synthetic") },
    ): ContractAcquisitionOutcome = withServer { server ->
        server.enqueue(MockResponse(body = platform.toString().replace(SERVER, serverUrl)))
        full?.let { server.enqueue(MockResponse(body = it.toString().replace(SERVER, serverUrl))) }
        repeat(packages) { server.enqueue(MockResponse(body = "package")) }
        acquirer(server, verify).acquireOutcome(request)
    }

    private fun acquireRaw(platform: String, full: String): ContractAcquisitionOutcome = withServer { server ->
        server.enqueue(MockResponse(body = platform))
        server.enqueue(MockResponse(body = full))
        acquirer(server) { error("No package may be verified.") }.acquireOutcome(request)
    }

    private fun withServer(block: (MockWebServer) -> ContractAcquisitionOutcome): ContractAcquisitionOutcome =
        MockWebServer().use { server ->
            server.start()
            serverUrl = server.url("downloads").toString()
            block(server)
        }

    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    private fun unavailable(reason: ContractUnavailableReason) = ContractAcquisitionOutcome.Unavailable(reason)

    private fun app(vararg releases: JSONObject): JSONObject =
        JSONObject().put("id", "cospend").put("certificate", "certificate").put("releases", JSONArray(releases.toList()))

    private fun catalog(vararg releases: JSONObject): JSONArray = JSONArray().put(app(*releases))

    /** A release downloadable from the test server when [server] is true; otherwise a fixture host. */
    private fun release(version: String, server: Boolean = false): JSONObject = JSONObject()
        .put("version", version)
        .put("download", if (server) "$SERVER/cospend-$version.tar.gz" else "https://fixture.invalid/cospend-$version.tar.gz")
        .put("signature", "c2lnbmF0dXJl")
        .put("signatureDigest", "sha512")
        .put("isNightly", false)

    private fun acquirer(server: MockWebServer, verify: (AppStoreRelease) -> VerifiedPackageContract) =
        SignedAppStoreContractAcquirer(
            httpClient = OkHttpClient(),
            appStoreBaseUrl = server.url("api/v1").toString(),
            requireHttps = false,
            trustVerifier = object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = verify(release)
            },
        )

    private companion object {
        const val SERVER = "http://server.placeholder"
    }
}
