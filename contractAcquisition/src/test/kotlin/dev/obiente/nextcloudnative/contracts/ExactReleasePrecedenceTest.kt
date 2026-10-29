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
 * The installed version 4.0.2 is absent from the platform catalog, which offers the compatible 4.0.1,
 * so the full catalog is read for the exact release.
 */
class ExactReleasePrecedenceTest {
    @Test
    fun `an unreadable exact catalog outranks a compatible candidate without a contract`() {
        assertEquals(unavailable(ContractUnavailableReason.UnreadableCatalog), acquire(fullCatalog = "[truncated"))
    }

    @Test
    fun `a malformed exact entry outranks a compatible candidate without a contract`() {
        val full = catalog(release("4.0.2").also { it.remove("signature") }, release("4.0.1")).toString()
        assertEquals(unavailable(ContractUnavailableReason.MalformedSource), acquire(fullCatalog = full))
    }

    @Test
    fun `a readable exact catalog without the version reports that candidates provide no contract`() {
        val full = catalog(release("4.0.1")).toString()
        assertEquals(unavailable(ContractUnavailableReason.NoUsableContract), acquire(fullCatalog = full))
    }

    @Test
    fun `a compatible candidate with a contract is still acquired beside an unreadable exact catalog`() {
        val outcome = acquire(fullCatalog = "[truncated") { release ->
            VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
        }
        assertEquals("4.0.1", assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.contractVersion)
    }

    private fun acquire(
        fullCatalog: String,
        verify: (AppStoreRelease) -> VerifiedPackageContract = { throw OpenApiContractMissingException("synthetic") },
    ): ContractAcquisitionOutcome = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse(body = catalog(release("4.0.1", server)).toString()))
        server.enqueue(MockResponse(body = fullCatalog))
        server.enqueue(MockResponse(body = "package"))
        SignedAppStoreContractAcquirer(
            httpClient = OkHttpClient(),
            appStoreBaseUrl = server.url("api/v1").toString(),
            requireHttps = false,
            trustVerifier = object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = verify(release)
            },
        ).acquireOutcome(ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2"))
    }

    private fun unavailable(reason: ContractUnavailableReason) = ContractAcquisitionOutcome.Unavailable(reason)

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
}
