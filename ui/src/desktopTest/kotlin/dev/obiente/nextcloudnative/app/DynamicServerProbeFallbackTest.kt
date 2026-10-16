package dev.obiente.nextcloudnative.app

import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicServerProbeFallbackTest {
    private val session = NextcloudSession("https://fixture.invalid", "fixture", "synthetic")
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    private enum class ServerProbe(val failure: DynamicServerProbeFailure?) {
        Absent(null),
        EmptyCatalog(null),
        Network(DynamicServerProbeFailure.NoResponse),
        ServerError(DynamicServerProbeFailure.ServerError),
        Throttled(DynamicServerProbeFailure.Throttled),
    }

    @Test
    fun everyServerProbeAndAppStoreOutcomeCombinationHasTheDocumentedReason() = runBlocking {
        for (probe in ServerProbe.entries) {
            for (unavailable in AppStoreContractUnavailableReason.entries) {
                val discovery = discover(probe) { AppStoreContractAcquisition.Unavailable(unavailable) }
                val expected = if (probe.failure == null) {
                    unavailable.fallbackReason()
                } else {
                    DynamicContractFallbackReason.ServerProbeInconclusive
                }
                val label = "$probe x $unavailable"
                assertEquals(DynamicDescriptorAcquisition.MetadataFallback, discovery.acquisition, label)
                assertEquals(expected, discovery.fallbackReason, label)
                // Diagnostics keep both sources regardless of which one decided the copy.
                assertTrue(discovery.diagnostics.any { unavailable.name in it && "No App Store contract" in it }, label)
                val serverDiagnostics = discovery.diagnostics.filter { "probe was inconclusive" in it }
                assertEquals(listOfNotNull(probe.failure?.let { "The server API probe was inconclusive: ${it.name}." }),
                    serverDiagnostics, label)
                val codes = discovery.toContractInfo(null).diagnosticCodes
                assertEquals(probe.failure != null, "server-probe-inconclusive" in codes, label)
            }
        }
    }

    @Test
    fun definitiveAbsenceKeepsThePermanentCopyAndAnInconclusiveProbeIsRetryable() = runBlocking {
        val absent = discover(ServerProbe.Absent) {
            AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoUsableContract)
        }
        assertEquals(DynamicContractFallbackReason.NoVerifiedContract, absent.fallbackReason)
        val inconclusive = DynamicContractFallbackReason.ServerProbeInconclusive.fallbackNotice()
        assertEquals("Try again", inconclusive.actionLabel)
        assertTrue("Neither" !in inconclusive.message)
    }

    @Test
    fun oneInconclusiveStaticProbeAmongDefinitiveAnswersStillMakesTheServerInconclusive() = runBlocking {
        val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            val status = if (request.relativePath == "/custom_apps/example/openapi.json") 503 else 404
            NextcloudApiResponse(status, ByteArray(0), "application/json", etag = null)
        })
        assertEquals(DynamicContractFallbackReason.ServerProbeInconclusive, discovery.fallbackReason)
        assertTrue("The server API probe was inconclusive: ServerError." in discovery.diagnostics)
    }

    @Test
    fun theFirstInconclusiveProbeIsRecordedAndDefinitiveProbesRecordNothing() = runBlocking {
        for (probe in ServerProbe.entries) {
            val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = server(probe))
            val expected = probe.failure?.let { DynamicContractFallbackReason.ServerProbeInconclusive }
            assertEquals(expected, discovery.fallbackReason, probe.name)
        }
        val catalogThrottledThenFailing = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            if (request.relativePath.contains("ocs_api_viewer")) {
                NextcloudApiResponse(429, ByteArray(0), "application/json", etag = null)
            } else {
                throw IllegalStateException("synthetic transport failure")
            }
        })
        assertEquals(
            listOf("The server API probe was inconclusive: Throttled."),
            catalogThrottledThenFailing.diagnostics.filter { "probe was inconclusive" in it },
        )
    }

    @Test
    fun cancellationDuringAServerProbeIsNotRecordedAsInconclusive() = runBlocking {
        val cancelled = CancellationException("stop")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { throw cancelled })
        })
    }

    private fun server(probe: ServerProbe): suspend (NextcloudApiRequest) -> NextcloudApiResponse = { request ->
        when (probe) {
            ServerProbe.Absent -> NextcloudApiResponse(404, ByteArray(0), "application/json", etag = null)
            ServerProbe.EmptyCatalog -> if (request.relativePath.contains("ocs_api_viewer")) {
                NextcloudApiResponse(200, "[]".encodeToByteArray(), "application/json", etag = null)
            } else {
                NextcloudApiResponse(404, ByteArray(0), "application/json", etag = null)
            }
            ServerProbe.Network -> throw IllegalStateException("synthetic transport failure")
            ServerProbe.ServerError -> NextcloudApiResponse(503, ByteArray(0), "application/json", etag = null)
            ServerProbe.Throttled -> NextcloudApiResponse(429, ByteArray(0), "application/json", etag = null)
        }
    }

    private suspend fun discover(
        probe: ServerProbe,
        acquire: suspend () -> AppStoreContractAcquisition,
    ): DynamicDescriptorDiscovery = discoverDynamicAppDescriptor(
        services = services(server(probe), acquire),
        session = session,
        app = app,
        serverVersion = "34.0.3",
        installedAppVersionHint = "1.0.0",
    )

    /** Only same-origin probes and the App Store boundary are available. */
    private fun services(
        execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
        acquire: suspend () -> AppStoreContractAcquisition,
    ): NextcloudPlatformServices {
        val unavailable = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { _, method, _ -> error("Unexpected ${method.name}") } as NextcloudPlatformServices
        return object : NextcloudPlatformServices by unavailable {
            override fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) = Unit

            override suspend fun executeNextcloudApi(session: NextcloudSession, request: NextcloudApiRequest) =
                execute(request)

            override suspend fun acquireSignedOpenApiContract(
                appId: String,
                serverVersion: String,
                installedAppVersion: String?,
            ): AppStoreContractAcquisition = acquire()
        }
    }
}
