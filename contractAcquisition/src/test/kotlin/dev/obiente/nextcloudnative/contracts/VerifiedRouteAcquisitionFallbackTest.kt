package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class VerifiedRouteAcquisitionFallbackTest {
    @Test
    fun `unavailable older package retains verified routes and exact provenance`() {
        for (status in listOf(404, 410)) {
            withAcquirer(listOf(200, status)) { acquirer ->
                val contract = assertNotNull(acquirer.acquire(request()))
                assertEquals("3.2.2", contract.appVersion)
                assertEquals("3.2.2", contract.contractVersion)
                assertEquals(OpenApiContractSourceKind.SignedAppPackage, contract.sourceKind)
                assertEquals(VerifiedContractKind.VerifiedReadRoutes, contract.contractKind)
            }
        }
    }

    @Test
    fun `unknown installed version retains compatible read provenance`() {
        withAcquirer(listOf(200, 404)) { acquirer ->
            val contract = assertNotNull(acquirer.acquire(request().copy(installedAppVersion = null)))
            assertEquals("3.2.2", contract.contractVersion)
            assertEquals(OpenApiContractSourceKind.SignedCompatibleAppPackage, contract.sourceKind)
        }
    }

    @Test
    fun `unavailable first package still fails without verified evidence`() {
        for (status in listOf(404, 410)) {
            withAcquirer(listOf(status)) { acquirer ->
                assertFailsWith<IllegalStateException> { acquirer.acquire(request()) }
            }
        }
    }

    @Test
    fun `authorization and server failures remain failures with verified routes`() {
        for (status in listOf(401, 403, 429, 500)) {
            withAcquirer(listOf(200, status)) { acquirer ->
                assertFailsWith<IllegalStateException> { acquirer.acquire(request()) }
            }
        }
    }

    @Test
    fun `older package trust failures are never downgraded to verified routes`() {
        withAcquirer(listOf(200, 200), rejectOlder = true) { acquirer ->
            assertFailsWith<SecurityException> { acquirer.acquire(request()) }
        }
    }

    @Test
    fun `richer compatible package remains preferred after unavailable release`() {
        withAcquirer(listOf(200, 404, 200)) { acquirer ->
            val contract = assertNotNull(acquirer.acquire(request()))
            assertEquals("3.2.2", contract.appVersion)
            assertEquals("3.2.0", contract.contractVersion)
            assertEquals(VerifiedContractKind.OpenApi, contract.contractKind)
            assertEquals(OpenApiContractSourceKind.SignedCompatibleAppPackage, contract.sourceKind)
        }
    }

    private fun request() = ContractAcquisitionRequest("music", "34.0.1", "3.2.2")

    private fun withAcquirer(
        statuses: List<Int>,
        rejectOlder: Boolean = false,
        block: (SignedAppStoreContractAcquirer) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.start()
            val versions = listOf("3.2.2", "3.2.1", "3.2.0").take(statuses.size)
            val releases = versions.joinToString { version ->
                """{"version":"$version","download":"${server.url("package-$version.tar.gz")}","signature":"c2lnbmF0dXJl","signatureDigest":"sha512","isNightly":false}"""
            }
            server.enqueue(MockResponse(body = """[{"id":"music","certificate":"certificate","releases":[$releases]}]"""))
            statuses.forEach { status -> server.enqueue(MockResponse(code = status, body = "package")) }
            val verifier = object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
                    if (rejectOlder && release.version != "3.2.2") throw SecurityException("signature rejected")
                    return VerifiedPackageContract(
                        appId = release.appId,
                        appVersion = release.version,
                        specFile = "appinfo/routes.php",
                        document = """{"openapi":"3.0.3","paths":{}}""",
                        contractKind = if (release.version == "3.2.0") VerifiedContractKind.OpenApi
                            else VerifiedContractKind.VerifiedReadRoutes,
                    )
                }
            }
            block(SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = verifier,
            ))
        }
    }
}
