package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject

class ContractAcquisitionOutcomeTest {
    @Test
    fun `an app absent from the catalog has no matching release`() {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse(body = """[{"id":"other","certificate":"c","releases":[]}]""")) }
            val acquirer = acquirer(server)
            assertUnavailable(ContractUnavailableReason.NoMatchingRelease, acquirer, "4.0.2")
            // The nullable API stays a projection of the typed outcome; the catalog is reused.
            assertNull(acquirer.acquire(request("4.0.2")))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `an installed version absent from every catalog has no matching release`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server, "5.1.0")))
            server.enqueue(MockResponse(body = catalog(server, "5.1.0")))
            assertUnavailable(ContractUnavailableReason.NoMatchingRelease, acquirer(server), "4.0.2")
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a throttled or refused exact-tag archive leaves the contract temporarily unavailable`() {
        for (status in listOf(403, 429)) {
            MockWebServer().use { server ->
                server.start()
                enqueueMissingPackage(server)
                server.enqueue(MockResponse(code = 403, body = "rate limited"))
                server.enqueue(MockResponse(code = status, body = "rate limited"))
                val acquirer = acquirer(server)
                assertUnavailable(ContractUnavailableReason.SourceTemporarilyUnavailable, acquirer, "4.0.2")
                assertEquals("/codeload/source-owner/source-repo/tar.gz/refs/tags/v4.0.2", lastPath(server))
            }
        }
    }

    @Test
    fun `an inspected release without a contract is not reported as throttled`() {
        // A throttled tree listing that falls back to a missing archive, and a complete tree
        // without OpenAPI files, both prove the tag has no usable contract.
        for (responses in listOf(
            listOf(MockResponse(code = 403, body = "rate limited"), MockResponse(code = 404, body = "missing")),
            listOf(MockResponse(body = tree("README.md"))),
        )) {
            MockWebServer().use { server ->
                server.start()
                enqueueMissingPackage(server)
                responses.forEach(server::enqueue)
                assertUnavailable(ContractUnavailableReason.NoUsableContract, acquirer(server), "4.0.2")
            }
        }
    }

    @Test
    fun `a package without a contract and without linked sources has no usable contract`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server, "4.0.2", website = null, linkedDownload = false)))
            server.enqueue(MockResponse(body = "signed-package-without-openapi"))
            assertUnavailable(ContractUnavailableReason.NoUsableContract, acquirer(server), "4.0.2")
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `an acquired contract is the acquired outcome`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server, "4.0.2")))
            server.enqueue(MockResponse(body = "package"))
            val outcome = acquirer(server) { release ->
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
            }.acquireOutcome(request("4.0.2"))
            val acquired = assertIs<ContractAcquisitionOutcome.Acquired>(outcome)
            assertEquals(OpenApiContractSourceKind.SignedAppPackage, acquired.contract.sourceKind)
        }
    }

    private fun assertUnavailable(
        expected: ContractUnavailableReason,
        acquirer: SignedAppStoreContractAcquirer,
        installedVersion: String,
    ) {
        assertEquals(ContractAcquisitionOutcome.Unavailable(expected), acquirer.acquireOutcome(request(installedVersion)))
    }

    private fun enqueueMissingPackage(server: MockWebServer) {
        server.enqueue(MockResponse(body = catalog(server, "4.0.2")))
        server.enqueue(MockResponse(body = "signed-package-without-openapi"))
        server.enqueue(MockResponse(body = "<info><id>cospend</id><version>4.0.2</version></info>"))
    }

    private fun lastPath(server: MockWebServer): String? =
        List(server.requestCount) { server.takeRequest().url.encodedPath }.lastOrNull()

    private fun request(installedVersion: String) = ContractAcquisitionRequest("cospend", "34.0.3", installedVersion)

    private fun catalog(
        server: MockWebServer,
        version: String,
        website: String? = "https://github.com/source-owner/source-repo",
        linkedDownload: Boolean = true,
    ): String {
        val download = if (linkedDownload) {
            server.url("source-owner/source-repo/releases/download/v$version/cospend-$version.tar.gz")
        } else {
            server.url("downloads/cospend-$version.tar.gz")
        }
        val websiteField = website?.let { "\"website\":\"$it\"," }.orEmpty()
        return """[{"id":"cospend",$websiteField"certificate":"certificate","releases":[""" +
            """{"version":"$version","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""
    }

    private fun tree(vararg paths: String): String = JSONObject()
        .put("truncated", false)
        .put("tree", paths.map { path -> JSONObject().put("path", path).put("type", "blob") })
        .toString()

    private fun acquirer(
        server: MockWebServer,
        verify: (AppStoreRelease) -> VerifiedPackageContract = {
            throw OpenApiContractMissingException("synthetic package without a contract")
        },
    ) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = verify(release)
        },
        rawGitHubBaseUrl = server.url("raw").toString(),
        githubApiBaseUrl = server.url("github-api").toString(),
        codeloadGitHubBaseUrl = server.url("codeload").toString(),
    )
}
