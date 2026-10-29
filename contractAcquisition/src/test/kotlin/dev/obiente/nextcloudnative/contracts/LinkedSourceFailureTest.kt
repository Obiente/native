package dev.obiente.nextcloudnative.contracts

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** A network failure keeps the identity of the source that failed: the App Store or the linked source. */
class LinkedSourceFailureTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    @Test
    fun `an App Store request without a response is an App Store network failure`() {
        MockWebServer().use { server ->
            server.start()
            val failure = assertFailsWith<IOException> { acquirer(server, failingPath = "apps.json").acquireOutcome(request) }
            assertEquals(ContractAcquisitionFailureKind.Network, classifyContractAcquisitionFailure(failure))
        }
    }

    @Test
    fun `a linked source without a response is a linked-source failure, never no usable contract`() {
        for (failingPath in listOf("appinfo/info.xml", "git/trees")) {
            MockWebServer().use { server ->
                server.start()
                // The only candidate's signed package has no contract, so the linked source is the last resort.
                enqueueCatalogAndPackage(server)
                if (failingPath == "git/trees") server.enqueue(MockResponse(body = INFO_XML))
                val failure = assertFailsWith<IOException> {
                    acquirer(server, failingPath = failingPath).acquireOutcome(request)
                }
                assertIs<ContractLinkedSourceNetworkException>(failure, failingPath)
                assertIs<IOException>(failure.cause, failingPath)
                assertEquals(
                    ContractAcquisitionFailureKind.LinkedSourceUnreachable,
                    classifyContractAcquisitionFailure(failure),
                    failingPath,
                )
            }
        }
    }

    private fun enqueueCatalogAndPackage(server: MockWebServer) {
        // A GitHub-style release download, so the release has a linked source to try.
        val download = server.url("example/cospend/releases/download/v4.0.2/cospend-4.0.2.tar.gz")
        server.enqueue(MockResponse(body = """[{"id":"cospend","certificate":"c","releases":[""" +
            """{"version":"4.0.2","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""))
        server.enqueue(MockResponse(body = "package"))
    }

    private fun acquirer(server: MockWebServer, failingPath: String): SignedAppStoreContractAcquirer {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (failingPath in chain.request().url.encodedPath) throw IOException("synthetic: no response")
            chain.proceed(chain.request())
        }.build()
        val base = server.url("").toString().trimEnd('/')
        return SignedAppStoreContractAcquirer(
            httpClient = client,
            appStoreBaseUrl = "$base/api/v1",
            requireHttps = false,
            trustVerifier = object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract =
                    throw OpenApiContractMissingException("synthetic package without a contract")
            },
            rawGitHubBaseUrl = "$base/raw",
            githubApiBaseUrl = "$base/api",
            codeloadGitHubBaseUrl = "$base/codeload",
        )
    }

    private companion object {
        const val INFO_XML = """<?xml version="1.0"?><info><id>cospend</id><version>4.0.2</version></info>"""
    }
}
