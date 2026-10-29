package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Installed version 4.0.2. */
class DuplicateAppRecordTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    @Test
    fun `differing records for the requested app are ambiguous, not a selection`() {
        // The first record has no matching release; a later one has the exact release.
        val first = app(release("4.0.1"))
        val second = app(release("4.0.2"))
        val catalog = JSONArray().put(first).put(second).toString()
        assertEquals(
            listOf(MalformedCatalogEntry(null, MalformedCatalogField.DuplicateApp)),
            parseReleases(catalog, "cospend", "https://fixture.invalid/apps.json", false).malformed,
        )
        val outcome = acquire(platform = catalog, full = catalog, packages = 0)
        assertEquals(ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.MalformedSource), outcome)
    }

    @Test
    fun `identical duplicate records are read once`() {
        MockWebServer().use { server ->
            server.start()
            val record = app(release("4.0.2", server))
            val catalog = JSONArray().put(record).put(JSONObject(record.toString())).toString()
            assertEquals(1, parseReleases(catalog, "cospend", "https://fixture.invalid/apps.json", false).releases.size)
            server.enqueue(MockResponse(body = catalog))
            server.enqueue(MockResponse(body = "package"))
            assertIs<ContractAcquisitionOutcome.Acquired>(acquirer(server).acquireOutcome(request))
        }
    }

    @Test
    fun `duplicates of another app beside the requested app do not matter`() {
        MockWebServer().use { server ->
            server.start()
            val other = JSONObject().put("id", "other").put("releases", JSONArray())
            val otherChanged = JSONObject().put("id", "other").put("certificate", "x").put("releases", JSONArray())
            val catalog = JSONArray().put(other).put(otherChanged).put(app(release("4.0.2", server))).toString()
            server.enqueue(MockResponse(body = catalog))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server).acquireOutcome(request)
            assertEquals("4.0.2", assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.contractVersion)
        }
    }

    private fun acquire(platform: String, full: String, packages: Int): ContractAcquisitionOutcome =
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = platform))
            server.enqueue(MockResponse(body = full))
            repeat(packages) { server.enqueue(MockResponse(body = "package")) }
            acquirer(server).acquireOutcome(request)
        }

    private fun app(vararg releases: JSONObject): JSONObject =
        JSONObject().put("id", "cospend").put("certificate", "certificate").put("releases", JSONArray(releases.toList()))

    private fun release(version: String, server: MockWebServer? = null): JSONObject = JSONObject()
        .put("version", version)
        .put("download", server?.url("downloads/cospend-$version.tar.gz")?.toString()
            ?: "https://fixture.invalid/cospend-$version.tar.gz")
        .put("signature", "c2lnbmF0dXJl")
        .put("signatureDigest", "sha512")
        .put("isNightly", false)

    private fun acquirer(server: MockWebServer) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
        },
    )
}
