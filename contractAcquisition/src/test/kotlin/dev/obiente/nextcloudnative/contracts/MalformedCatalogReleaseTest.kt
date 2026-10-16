package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class MalformedCatalogReleaseTest {
    @Test
    fun `parsing records which required value made each entry unusable`() {
        val catalog = catalog(
            release("4.0.1", omit = "download"),
            release("4.0.2"),
            release(null),
            release("4.0.3", omit = "signature"),
            release("4.0.4", omit = "signatureDigest"),
        )
        val parsed = parseReleases(catalog.toString(), "cospend", "https://fixture.invalid/apps.json", false)
        assertEquals(listOf("4.0.2"), parsed.releases.map(AppStoreRelease::version))
        assertEquals(
            listOf(
                MalformedCatalogEntry("4.0.1", MalformedCatalogField.Download),
                MalformedCatalogEntry(null, MalformedCatalogField.Version),
                MalformedCatalogEntry("4.0.3", MalformedCatalogField.Signature),
                MalformedCatalogEntry("4.0.4", MalformedCatalogField.SignatureDigest),
            ),
            parsed.malformed,
        )
        val withoutCertificate = catalog(release("4.0.2"), certificate = null)
        assertEquals(
            listOf(MalformedCatalogEntry("4.0.2", MalformedCatalogField.Certificate)),
            parseReleases(withoutCertificate.toString(), "cospend", "https://fixture.invalid/apps.json", false).malformed,
        )
    }

    @Test
    fun `a matching release missing any required value is a malformed source`() {
        for (field in listOf("download", "signature", "signatureDigest", "version", "certificate")) {
            val catalog = if (field == "certificate") {
                catalog(release("4.0.2"), certificate = null)
            } else {
                catalog(release(if (field == "version") null else "4.0.2", omit = field))
            }
            assertEquals(unavailable(ContractUnavailableReason.MalformedSource), acquireFrom(catalog), field)
        }
    }

    @Test
    fun `an app without a releases list is a malformed source`() {
        val catalog = JSONArray().put(JSONObject().put("id", "cospend").put("certificate", "certificate"))
        assertEquals(unavailable(ContractUnavailableReason.MalformedSource), acquireFrom(catalog))
    }

    @Test
    fun `a compatible malformed release is a malformed source when nothing usable matches`() {
        val catalog = catalog(release("4.0.1", omit = "signature"))
        assertEquals(unavailable(ContractUnavailableReason.MalformedSource), acquireFrom(catalog))
    }

    @Test
    fun `malformed entries that would not be selected leave genuine absence unchanged`() {
        for (catalog in listOf(
            catalog(release("5.1.0", omit = "signature")),
            catalog(release("4.0.3-beta.1", omit = "download")),
            JSONArray().put(JSONObject().put("id", "other").put("releases", JSONArray())),
        )) {
            assertEquals(unavailable(ContractUnavailableReason.NoMatchingRelease), acquireFrom(catalog))
        }
    }

    @Test
    fun `a malformed non-matching entry beside a valid matching release is ignored`() {
        MockWebServer().use { server ->
            server.start()
            val catalog = catalog(release("5.1.0", omit = "signature"), release("4.0.2", server = server))
            server.enqueue(MockResponse(body = catalog.toString()))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server) { release ->
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
            }.acquireOutcome(request())
            val acquired = assertIs<ContractAcquisitionOutcome.Acquired>(outcome)
            assertEquals("4.0.2", acquired.contract.contractVersion)
            assertEquals(2, server.requestCount)
        }
    }

    private fun acquireFrom(catalog: JSONArray): ContractAcquisitionOutcome = MockWebServer().use { server ->
        server.start()
        // The installed version is absent from the platform catalog, so the full catalog is read too.
        repeat(2) { server.enqueue(MockResponse(body = catalog.toString())) }
        acquirer(server) { error("No package may be verified.") }.acquireOutcome(request())
    }

    private fun unavailable(reason: ContractUnavailableReason) = ContractAcquisitionOutcome.Unavailable(reason)

    private fun request() = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    private fun catalog(vararg releases: JSONObject, certificate: String? = "certificate"): JSONArray {
        val app = JSONObject().put("id", "cospend").put("releases", JSONArray(releases.toList()))
        certificate?.let { app.put("certificate", it) }
        return JSONArray().put(app)
    }

    private fun release(version: String?, omit: String? = null, server: MockWebServer? = null): JSONObject {
        val download = server?.url("downloads/cospend-$version.tar.gz")?.toString()
            ?: "https://fixture.invalid/cospend-$version.tar.gz"
        val values = mapOf(
            "version" to version,
            "download" to download,
            "signature" to "c2lnbmF0dXJl",
            "signatureDigest" to "sha512",
        )
        return JSONObject().also { release ->
            values.forEach { (key, value) -> if (key != omit && value != null) release.put(key, value) }
            release.put("isNightly", false)
        }
    }

    private fun acquirer(
        server: MockWebServer,
        verify: (AppStoreRelease) -> VerifiedPackageContract,
    ) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = verify(release)
        },
    )
}
