package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class CatalogFieldTypeTest {
    private val wrongTypes: List<Pair<String, Any>> = listOf(
        "number" to 402,
        "boolean" to true,
        "object" to JSONObject().put("value", "4.0.2"),
        "array" to JSONArray().put("4.0.2"),
        "null" to JSONObject.NULL,
    )

    @Test
    fun `a required release string of any other JSON type is malformed, never coerced`() {
        val fields = mapOf(
            "version" to MalformedCatalogField.Version,
            "download" to MalformedCatalogField.Download,
            "signature" to MalformedCatalogField.Signature,
            "signatureDigest" to MalformedCatalogField.SignatureDigest,
        )
        for ((field, expected) in fields) {
            for ((type, value) in wrongTypes) {
                val catalog = catalog(release("4.0.2").put(field, value))
                val version = if (field == "version") null else "4.0.2"
                assertEquals(listOf(MalformedCatalogEntry(version, expected)), parse(catalog).malformed, "$field $type")
                assertEquals(malformedSource, acquireFrom(catalog), "$field $type")
            }
        }
    }

    @Test
    fun `a certificate of any other JSON type is malformed, never coerced`() {
        for ((type, value) in wrongTypes) {
            val catalog = catalog(release("4.0.2"))
            catalog.getJSONObject(0).put("certificate", value)
            assertEquals(
                listOf(MalformedCatalogEntry("4.0.2", MalformedCatalogField.Certificate)),
                parse(catalog).malformed,
                type,
            )
            assertEquals(malformedSource, acquireFrom(catalog), type)
        }
    }

    @Test
    fun `an isNightly flag that is not a boolean leaves the release channel unknown`() {
        for (value in listOf<Any>("false", 0, JSONObject(), JSONObject.NULL)) {
            val catalog = catalog(release("4.0.2").put("isNightly", value))
            assertEquals(
                listOf(MalformedCatalogEntry("4.0.2", MalformedCatalogField.Nightly)),
                parse(catalog).malformed,
                value.toString(),
            )
            assertEquals(malformedSource, acquireFrom(catalog), value.toString())
        }
        // A real nightly is skipped, not malformed, and an absent flag means a regular release.
        val nightly = catalog(release("4.0.2").put("isNightly", true))
        assertEquals(emptyList<MalformedCatalogEntry>(), parse(nightly).malformed)
        assertEquals(ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoMatchingRelease), acquireFrom(nightly))
        val regular = release("4.0.2").also { it.remove("isNightly") }
        assertEquals(listOf("4.0.2"), parse(catalog(regular)).releases.map(AppStoreRelease::version))
    }

    @Test
    fun `a wrong-typed optional value is dropped instead of coerced`() {
        val release = parse(catalog(release("4.0.2").put("platformVersionSpec", 34))).releases.single()
        assertEquals(null, release.platformVersionSpec)
    }

    @Test
    fun `a wrong-typed non-matching entry beside a valid matching release still acquires`() {
        MockWebServer().use { server ->
            server.start()
            val catalog = catalog(
                release("5.1.0").put("signature", 12345),
                release("4.0.2").put("version", JSONObject().put("value", "4.0.2")),
                release("4.0.2", server = server),
            )
            server.enqueue(MockResponse(body = catalog.toString()))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server) { release ->
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
            }.acquireOutcome(request)
            assertEquals("4.0.2", assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.contractVersion)
            assertEquals(2, server.requestCount)
        }
    }

    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val malformedSource = ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.MalformedSource)

    private fun parse(catalog: JSONArray) =
        parseReleases(catalog.toString(), "cospend", "https://fixture.invalid/apps.json", false)

    private fun acquireFrom(catalog: JSONArray): ContractAcquisitionOutcome = MockWebServer().use { server ->
        server.start()
        // The installed version is absent from the platform catalog, so the full catalog is read too.
        repeat(2) { server.enqueue(MockResponse(body = catalog.toString())) }
        acquirer(server) { error("No package may be verified.") }.acquireOutcome(request)
    }

    private fun catalog(vararg releases: JSONObject): JSONArray = JSONArray().put(
        JSONObject().put("id", "cospend").put("certificate", "certificate").put("releases", JSONArray(releases.toList())),
    )

    private fun release(version: String, server: MockWebServer? = null): JSONObject = JSONObject()
        .put("version", version)
        .put("download", server?.url("downloads/cospend-$version.tar.gz")?.toString()
            ?: "https://fixture.invalid/cospend-$version.tar.gz")
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
}
