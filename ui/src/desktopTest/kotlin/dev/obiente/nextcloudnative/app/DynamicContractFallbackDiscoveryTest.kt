package dev.obiente.nextcloudnative.app

import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicContractFallbackDiscoveryTest {
    private val session = NextcloudSession("https://fixture.invalid", "fixture", "synthetic")
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    @Test
    fun everyTypedAcquisitionFailureBecomesAnExplainedMetadataFallback() = runBlocking {
        val expected = mapOf(
            AppStoreContractFailureKind.Network to DynamicContractFallbackReason.AppStoreUnreachable,
            AppStoreContractFailureKind.SourceUnavailable to DynamicContractFallbackReason.AppStoreUnavailable,
            AppStoreContractFailureKind.VerificationFailed to DynamicContractFallbackReason.VerificationFailed,
            AppStoreContractFailureKind.InvalidRequest to DynamicContractFallbackReason.VersionUnavailable,
            AppStoreContractFailureKind.RuntimeIncompatible to DynamicContractFallbackReason.ClientFault,
            AppStoreContractFailureKind.Unexpected to DynamicContractFallbackReason.ClientFault,
        )
        assertEquals(AppStoreContractFailureKind.entries.toSet(), expected.keys)
        for ((kind, reason) in expected) {
            val cause = NoClassDefFoundError("dev.obiente.nextcloudnative.contracts.SyntheticKt")
            val discovery = discover { throw AppStoreContractAcquisitionException(kind, cause) }
            assertEquals(DynamicDescriptorAcquisition.MetadataFallback, discovery.acquisition)
            assertEquals(reason, discovery.fallbackReason)
            assertEquals("App Store contract acquisition failed: ${kind.name}.", discovery.diagnostics.last())
            assertNoPrivateText(discovery)
            assertTrue("app-store-acquisition-failed" in discovery.toContractInfo(null).diagnosticCodes)
        }
    }

    @Test
    fun untypedPlatformFailuresAreClientFaultsWithoutTheirMessage() = runBlocking {
        val discovery = discover {
            throw IllegalStateException("dev.obiente.nextcloudnative.contracts.SyntheticKt https://private.invalid/?token=secret")
        }
        assertEquals(DynamicContractFallbackReason.ClientFault, discovery.fallbackReason)
        assertNoPrivateText(discovery)
    }

    @Test
    fun missingContractUnavailableVersionAndInvalidContractEachHaveTheirOwnReason() = runBlocking {
        assertEquals(DynamicContractFallbackReason.NoVerifiedContract, discover { null }.fallbackReason)
        var acquisitions = 0
        val noVersion = discover(serverVersion = null) { acquisitions += 1; null }
        assertEquals(DynamicContractFallbackReason.VersionUnavailable, noVersion.fallbackReason)
        assertEquals(0, acquisitions)
        for (document in listOf("not json", "[]", """{"openapi":"3.0.3","paths":{"/apps/example/items":"""")) {
            val invalid = discover { contract(document) }
            assertEquals(DynamicContractFallbackReason.UnsupportedContract, invalid.fallbackReason)
            assertNoPrivateText(invalid)
        }
    }

    @Test
    fun cancellationDuringAcquisitionIsNotConvertedIntoAFallback() = runBlocking {
        val cancelled = CancellationException("stop")
        assertSame(cancelled, assertFailsWith<CancellationException> { discover { throw cancelled } })
    }

    @Test
    fun fallbackReasonIsNeverPersistedAndLegacyEntriesDecodeWithoutIt() = runBlocking {
        val fallback = discover { null }
        assertNull(encodePersistedDynamicDiscovery(fallback))
        val verified = fallback.copy(acquisition = DynamicDescriptorAcquisition.StaticAppAsset, fallbackReason = null)
        // Entries written before the field existed have the same shape as entries without a reason.
        val encoded = requireNotNull(encodePersistedDynamicDiscovery(verified))
        assertFalse("fallbackReason" in encoded)
        val restored = requireNotNull(decodePersistedDynamicDiscovery(encoded, app.id, session.serverUrl))
        assertNull(restored.fallbackReason)
    }

    @Test
    fun everyFallbackReasonExplainsWhatHappenedInDistinctAsciiCopy() {
        val notices = (DynamicContractFallbackReason.entries + null).map { it.fallbackNotice() }
        assertEquals(notices.size, notices.map { it.title }.distinct().size)
        assertEquals(notices.size, notices.map { it.message }.distinct().size)
        for (notice in notices + DynamicContractFallbackNotice(DYNAMIC_DISCOVERY_FAILURE_MESSAGE, "", "")) {
            val text = notice.title + notice.message + notice.actionLabel
            assertTrue(text.all { it.code in 0x20..0x7e }, text)
            assertFalse("Kt" in text || "Exception" in text || "Error" in text, text)
        }
        assertEquals("Check again", DynamicContractFallbackReason.NoVerifiedContract.fallbackNotice().actionLabel)
        assertEquals("Try again", DynamicContractFallbackReason.AppStoreUnreachable.fallbackNotice().actionLabel)
    }

    private fun assertNoPrivateText(discovery: DynamicDescriptorDiscovery) {
        val text = discovery.diagnostics.joinToString("\n")
        for (secret in listOf("SyntheticKt", "private.invalid", "token", "NoClassDefFoundError", "IllegalState")) {
            assertFalse(secret in text, "Discovery diagnostics exposed $secret")
        }
    }

    private suspend fun discover(
        serverVersion: String? = "34.0.3",
        acquire: suspend () -> AcquiredOpenApiContract?,
    ): DynamicDescriptorDiscovery = discoverDynamicAppDescriptor(
        services = services(acquire),
        session = session,
        app = app,
        serverVersion = serverVersion,
        installedAppVersionHint = "1.0.0",
    )

    private fun contract(document: String) = AcquiredOpenApiContract(
        appId = "example",
        appVersion = "1.0.0",
        contractVersion = "1.0.0",
        specFile = "openapi.json",
        document = document,
        packageUrl = "https://fixture.invalid/example-1.0.0.tar.gz",
        sourceUrl = "https://fixture.invalid/example-1.0.0.tar.gz#openapi.json",
        sourceKind = AcquiredOpenApiContractSourceKind.SignedAppPackage,
    )

    /** Only same-origin probes and the App Store boundary are available; every probe is a miss. */
    private fun services(acquire: suspend () -> AcquiredOpenApiContract?): NextcloudPlatformServices {
        val unavailable = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { _, method, _ -> if (method.name == "recordSupportDiagnostic") Unit else error("Unexpected ${method.name}") }
            as NextcloudPlatformServices
        return object : NextcloudPlatformServices by unavailable {
            override fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) = Unit

            override suspend fun executeNextcloudApi(session: NextcloudSession, request: NextcloudApiRequest) =
                NextcloudApiResponse(404, ByteArray(0), "application/json", etag = null)

            override suspend fun acquireSignedOpenApiContract(
                appId: String,
                serverVersion: String,
                installedAppVersion: String?,
            ): AcquiredOpenApiContract? = acquire()
        }
    }
}
