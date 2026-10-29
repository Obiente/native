package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.AppPackageTrustVerifier
import dev.obiente.nextcloudnative.contracts.AppStoreRelease
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractCache
import dev.obiente.nextcloudnative.contracts.VerifiedContractRemoval
import dev.obiente.nextcloudnative.contracts.VerifiedContractStore
import dev.obiente.nextcloudnative.contracts.VerifiedOpenApiContract
import dev.obiente.nextcloudnative.contracts.VerifiedPackageContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** Which source failed, and whether a contract was persisted, survive the shared JVM boundary. */
class ContractSourceOutcomeBoundaryTest {
    @Test
    fun `an App Store outage and a linked-source outage get different reasons and copy`() {
        assertEquals(DynamicContractFallbackReason.AppStoreUnreachable, AppStoreContractFailureKind.Network.fallbackReason())
        val linked = AppStoreContractFailureKind.LinkedSourceUnreachable.fallbackReason()
        assertEquals(DynamicContractFallbackReason.LinkedSourceUnreachable, linked)

        val notice = linked.fallbackNotice()
        assertEquals("App source not reachable", notice.title)
        assertEquals(
            "Could not reach the app's linked source repository. The App Store was reachable; try again later.",
            notice.message,
        )
        assertEquals("Try again", notice.actionLabel)
        assertEquals(DynamicContractFallbackAction.CheckAgain, notice.action)
        assertTrue("App Store could not be reached" in DynamicContractFallbackReason.AppStoreUnreachable.fallbackNotice().message)
    }

    @Test
    fun `a contract served without being persisted is recorded as a typed diagnostic`() {
        val refused = { VerifiedContractStore.InvalidationNotCleared }
        val diskFull: () -> VerifiedContractStore = { throw java.io.IOException("No space left on device.") }
        for ((store, expected) in listOf(refused to "InvalidationNotCleared", diskFull to "Failed")) {
            assertNotPersistedDiagnostic(store, expected)
        }
    }

    private fun assertNotPersistedDiagnostic(store: () -> VerifiedContractStore, expected: String) {
        MockWebServer().use { server ->
            server.start()
            val cache = object : VerifiedContractCache {
                override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract? = null
                override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract) = store()
                override fun remove(request: ContractAcquisitionRequest) = VerifiedContractRemoval.Removed
            }
            val download = server.url("downloads/example-1.0.0.tar.gz")
            server.enqueue(MockResponse(body = """[{"id":"example","certificate":"c","releases":[""" +
                """{"version":"1.0.0","download":"$download","signature":"c2lnbmF0dXJl",""" +
                """"signatureDigest":"sha512","isNightly":false}]}]"""))
            server.enqueue(MockResponse(body = "package"))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = object : AppPackageTrustVerifier {
                    override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                        VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
                },
                verifiedContractCache = cache,
            )
            val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

            val outcome = acquireAppStoreContract(AppStoreContractRequest("example", "34.0.3", "1.0.0"), acquirer, diagnostics::add)

            assertIs<AppStoreContractAcquisition.Acquired>(outcome)
            val diagnostic = diagnostics.single()
            assertEquals(SupportDiagnosticComponent.Cache, diagnostic.component)
            assertEquals("VERIFIED_CONTRACT_NOT_PERSISTED", diagnostic.code)
            assertEquals(listOf(SupportDiagnosticFieldDraft("store", expected)), diagnostic.fields)
            assertEquals(null, diagnostic.message)
        }
    }
}
