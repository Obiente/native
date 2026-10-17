package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class UnreadableContractSourceTest {
    @Test
    fun `an unreadable catalog document is a malformed source and is never cached`() {
        for (body in listOf(
            """[{"id":"cospend","certificate":"c","releases":[{"version":"4.0.2"""",
            """{"id":"cospend","releases":[]}""",
            "",
            "<html><body>Maintenance</body></html>",
        )) {
            MockWebServer().use { server ->
                server.start()
                // The platform catalog has no usable exact release, so the full catalog is read too.
                repeat(2) { server.enqueue(MockResponse(body = body)) }
                val cache = RecordingCatalogCache()
                val outcome = acquirer(server, cache).acquireOutcome(request())
                assertEquals(ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.UnreadableCatalog), outcome, body)
                assertEquals(emptyList(), cache.stored, body)
                assertEquals(2, server.requestCount, body)
            }
        }
    }

    @Test
    fun `parsing reports an unreadable document without throwing`() {
        for (body in listOf("[", "{}", "", "not json", "42")) {
            val parsed = parseReleases(body, "cospend", "https://fixture.invalid/apps.json", false)
            assertEquals(listOf(MalformedCatalogEntry(null, MalformedCatalogField.Document)), parsed.malformed, body)
            assertTrue(!parsed.documentReadable, body)
        }
        val readable = parseReleases("[]", "cospend", "https://fixture.invalid/apps.json", false)
        assertTrue(readable.documentReadable)
    }

    @Test
    fun `an unreadable platform catalog still allows an exact release from the full catalog`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "[truncated"))
            server.enqueue(MockResponse(body = validCatalog(server)))
            server.enqueue(MockResponse(body = "package"))
            val cache = RecordingCatalogCache()
            val outcome = acquirer(server, cache) { release ->
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
            }.acquireOutcome(request())
            assertIs<ContractAcquisitionOutcome.Acquired>(outcome)
            assertEquals(listOf(server.url("api/v1/apps.json").toString()), cache.stored)
        }
    }

    @Test
    fun `a corrupt linked source archive is an unusable source rather than a client fault`() {
        for (archive in listOf("not a gzip archive", "")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = validCatalog(server, linked = true)))
                server.enqueue(MockResponse(body = "signed-package-without-openapi"))
                server.enqueue(MockResponse(body = "<info><id>cospend</id><version>4.0.2</version></info>"))
                server.enqueue(MockResponse(code = 403, body = "rate limited"))
                server.enqueue(MockResponse(body = archive))
                val outcome = acquirer(server) {
                    throw OpenApiContractMissingException("synthetic package without a contract")
                }.acquireOutcome(request())
                assertEquals(ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoUsableContract), outcome)
                assertEquals(5, server.requestCount)
            }
        }
    }

    private class RecordingCatalogCache : AppStoreCatalogCache {
        val stored = mutableListOf<String>()

        override fun load(url: String): ByteArray? = null

        override fun store(url: String, bytes: ByteArray) {
            stored += url
        }
    }

    private fun request() = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    private fun validCatalog(server: MockWebServer, linked: Boolean = false): String {
        val download = if (linked) {
            server.url("source-owner/source-repo/releases/download/v4.0.2/cospend-4.0.2.tar.gz")
        } else {
            server.url("downloads/cospend-4.0.2.tar.gz")
        }
        return """[{"id":"cospend","website":"https://github.com/source-owner/source-repo",""" +
            """"certificate":"certificate","releases":[{"version":"4.0.2","download":"$download",""" +
            """"signature":"c2lnbmF0dXJl","signatureDigest":"sha512","isNightly":false}]}]"""
    }

    private fun acquirer(
        server: MockWebServer,
        cache: AppStoreCatalogCache = MemoryAppStoreCatalogCache(),
        verify: (AppStoreRelease) -> VerifiedPackageContract = { error("No package may be verified.") },
    ) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = verify(release)
        },
        catalogCache = cache,
        rawGitHubBaseUrl = server.url("raw").toString(),
        githubApiBaseUrl = server.url("github-api").toString(),
        codeloadGitHubBaseUrl = server.url("codeload").toString(),
    )
}
