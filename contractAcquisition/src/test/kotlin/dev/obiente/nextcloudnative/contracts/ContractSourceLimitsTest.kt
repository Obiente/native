package dev.obiente.nextcloudnative.contracts

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class ContractSourceLimitsTest {
    private var now = 10_000_000L
    private val smallLimits = ContractResponseLimits(catalogBytes = 2_000, archiveBytes = 64, appInfoBytes = 512, githubTreeBytes = 32)

    @Test
    fun `repeated retries on a cold start make exactly one catalog request per window`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            server.enqueue(MockResponse(body = "[unreadable"))
            assertEquals(unavailable(ContractUnavailableReason.MalformedSource), acquirer.acquireOutcome(unversioned(), CatalogPolicy.Refresh))
            repeat(3) {
                assertEquals(unavailable(ContractUnavailableReason.RecentlyChecked), acquirer.acquireOutcome(unversioned(), CatalogPolicy.Refresh))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `an expired cached catalog is throttled like a cold start`() {
        val directory = Files.createTempDirectory("catalog-cache").toFile()
        try {
            MockWebServer().use { server ->
                server.start()
                val cache = FileAppStoreCatalogCache(directory, nowMillis = { now })
                val acquirer = acquirer(server, cache)
                server.enqueue(MockResponse(body = """[{"id":"other","certificate":"c","releases":[]}]"""))
                assertEquals(unavailable(ContractUnavailableReason.NoMatchingRelease), acquirer.acquireOutcome(unversioned()))
                now += 7L * 60L * 60L * 1_000L
                server.enqueue(MockResponse(code = 503, body = "unavailable"))
                assertFailsWith<ContractSourceHttpException> { acquirer.acquireOutcome(unversioned(), CatalogPolicy.Refresh) }
                repeat(2) {
                    assertEquals(unavailable(ContractUnavailableReason.RecentlyChecked), acquirer.acquireOutcome(unversioned(), CatalogPolicy.Refresh))
                }
                assertEquals(2, server.requestCount)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `automatic discovery without a cached catalog still downloads it`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            repeat(3) { server.enqueue(MockResponse(body = "[unreadable")) }
            repeat(3) {
                assertEquals(unavailable(ContractUnavailableReason.MalformedSource), acquirer.acquireOutcome(unversioned()))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `an oversized catalog or package is unusable source data whether declared or streamed`() {
        for (streamed in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(oversized(2_500, streamed))
                val failure = assertFailsWith<ContractSourceTooLargeException> { acquirer(server).acquireOutcome(unversioned()) }
                assertEquals(ContractAcquisitionFailureKind.SourceUnusable, classifyContractAcquisitionFailure(failure))
            }
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = catalog(server, "4.0.2")))
                server.enqueue(oversized(100, streamed))
                val failure = assertFailsWith<ContractSourceTooLargeException> { acquirer(server).acquireOutcome(versioned()) }
                assertEquals(ContractAcquisitionFailureKind.SourceUnusable, classifyContractAcquisitionFailure(failure))
            }
        }
    }

    @Test
    fun `an oversized package entry stays a size failure instead of a verification failure`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server, "4.0.2")))
            server.enqueue(MockResponse(body = "package"))
            val tooLarge = ContractSourceTooLargeException(1, "An app package entry is too large.")
            val failure = assertFailsWith<ContractSourceTooLargeException> {
                acquirer(server, verify = { throw tooLarge }).acquireOutcome(versioned())
            }
            assertSame(tooLarge, failure)
        }
    }

    @Test
    fun `an oversized linked tree or archive is skipped like any unusable linked resource`() {
        for (streamed in listOf(false, true)) {
            for (oversizedArchive in listOf(false, true)) {
                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse(body = catalog(server, "4.0.2", linked = true)))
                    server.enqueue(MockResponse(body = "signed-package-without-openapi"))
                    server.enqueue(MockResponse(body = "<info><id>cospend</id><version>4.0.2</version></info>"))
                    if (oversizedArchive) {
                        server.enqueue(MockResponse(code = 404, body = "no tree"))
                        server.enqueue(oversized(100, streamed))
                    } else {
                        server.enqueue(oversized(100, streamed))
                        server.enqueue(MockResponse(code = 404, body = "no archive"))
                    }
                    val outcome = acquirer(server, verify = { throw OpenApiContractMissingException("synthetic") })
                        .acquireOutcome(versioned())
                    assertEquals(unavailable(ContractUnavailableReason.NoUsableContract), outcome)
                    assertEquals(5, server.requestCount)
                }
            }
        }
    }

    @Test
    fun `malformed or mismatched linked app metadata yields no candidate`() {
        val release = AppStoreRelease("cospend", "4.0.2", "https://fixture.invalid/c.tar.gz", "s", "sha512", "c", "u")
        for (infoXml in listOf(
            "<info><id>cospend</id><version>4.0.2</version>",
            "<notinfo><id>cospend</id><version>4.0.2</version></notinfo>",
            """<?xml version="1.0" encoding="x-bogus-encoding"?><info><id>cospend</id><version>4.0.2</version></info>""",
            "<info><id>cospend</id><version>4.0.2</version></info>",
        )) {
            assertNull(extractLinkedSourceContractOrNull(tarGz("app/appinfo/info.xml" to infoXml), release), infoXml)
        }
        assertNull(extractLinkedSourceContractOrNull(byteArrayOf(1, 2, 3), release))
    }

    private fun oversized(bytes: Int, streamed: Boolean): MockResponse {
        val body = "x".repeat(bytes)
        return if (streamed) MockResponse.Builder().chunkedBody(body, 16).build() else MockResponse(body = body)
    }

    private fun tarGz(vararg files: Pair<String, String>): ByteArray {
        val tar = ByteArrayOutputStream()
        files.forEach { (name, content) ->
            val bytes = content.encodeToByteArray()
            val header = ByteArray(512)
            name.encodeToByteArray().copyInto(header, 0)
            "%011o".format(bytes.size).encodeToByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            tar.write(header)
            tar.write(bytes)
            tar.write(ByteArray((512 - bytes.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(tar.toByteArray()) } }
            .toByteArray()
    }

    private fun unavailable(reason: ContractUnavailableReason) = ContractAcquisitionOutcome.Unavailable(reason)

    private fun unversioned() = ContractAcquisitionRequest("cospend", "34.0.3", null)

    private fun versioned() = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    private fun catalog(server: MockWebServer, version: String, linked: Boolean = false): String {
        val download = if (linked) {
            server.url("source-owner/source-repo/releases/download/v$version/cospend-$version.tar.gz")
        } else {
            server.url("downloads/cospend-$version.tar.gz")
        }
        return """[{"id":"cospend","website":"https://github.com/source-owner/source-repo",""" +
            """"certificate":"c","releases":[{"version":"$version","download":"$download",""" +
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
        catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { now }),
        responseLimits = smallLimits,
    )
}
