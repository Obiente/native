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

    /** One server behavior per scenario, with the probe result and fallback reason it must produce. */
    private enum class ServerProbe(
        val failure: DynamicServerProbeFailure?,
        val reason: DynamicContractFallbackReason?,
        val diagnosticCode: String?,
    ) {
        Absent(null, null, null),
        EmptyCatalog(null, null, null),
        Network(DynamicServerProbeFailure.NoResponse, DynamicContractFallbackReason.ServerProbeInconclusive, INCONCLUSIVE),
        ServerError(DynamicServerProbeFailure.ServerError, DynamicContractFallbackReason.ServerProbeInconclusive, INCONCLUSIVE),
        Throttled(DynamicServerProbeFailure.Throttled, DynamicContractFallbackReason.ServerProbeInconclusive, INCONCLUSIVE),
        MalformedStaticSpec(
            DynamicServerProbeFailure.UnreadableResponse, DynamicContractFallbackReason.ServerProbeInconclusive, INCONCLUSIVE,
        ),
        UnversionedStaticJson(
            DynamicServerProbeFailure.UnreadableResponse, DynamicContractFallbackReason.ServerProbeInconclusive, INCONCLUSIVE,
        ),
        SwaggerStaticSpec(
            DynamicServerProbeFailure.UnsupportedContract, DynamicContractFallbackReason.UnsupportedContract, UNSUPPORTED,
        ),
        Unauthorized(
            DynamicServerProbeFailure.AuthenticationRequired, DynamicContractFallbackReason.ServerAuthenticationRequired, DENIED,
        ),
        Forbidden(DynamicServerProbeFailure.PermissionDenied, DynamicContractFallbackReason.ServerPermissionDenied, DENIED),
    }

    @Test
    fun everyServerProbeAndAppStoreOutcomeCombinationHasTheDocumentedReason() = runBlocking {
        for (probe in ServerProbe.entries) {
            for (unavailable in AppStoreContractUnavailableReason.entries) {
                val discovery = discover(probe) { AppStoreContractAcquisition.Unavailable(unavailable) }
                val label = "$probe x $unavailable"
                assertEquals(DynamicDescriptorAcquisition.MetadataFallback, discovery.acquisition, label)
                // Any server probe result outranks an App Store no-contract outcome; absence defers to it.
                assertEquals(probe.reason ?: unavailable.fallbackReason(), discovery.fallbackReason, label)
                // Diagnostics keep both sources regardless of which one decided the copy.
                assertTrue(discovery.diagnostics.any { unavailable.name in it && "No App Store contract" in it }, label)
                val serverDiagnostics = discovery.diagnostics.filter { "server API probe" in it }
                assertEquals(if (probe.failure == null) 0 else 1, serverDiagnostics.size, label)
                val codes = discovery.toContractInfo(null).diagnosticCodes
                assertEquals(listOfNotNull(probe.diagnosticCode), codes.filter { it.startsWith("server-") }, label)
            }
        }
    }

    @Test
    fun aTypedAcquisitionFailureKeepsARecordedServerReasonAndBothDiagnostics() = runBlocking {
        for (probe in ServerProbe.entries) {
            for (kind in AppStoreContractFailureKind.entries) {
                val discovery = discover(probe) { throw AppStoreContractAcquisitionException(kind) }
                val label = "$probe x $kind"
                assertEquals(probe.reason ?: kind.fallbackReason(), discovery.fallbackReason, label)
                assertTrue("App Store contract acquisition failed: ${kind.name}." in discovery.diagnostics, label)
                assertEquals(if (probe.failure == null) 0 else 1, discovery.diagnostics.count { "server API probe" in it }, label)
            }
        }
    }

    @Test
    fun anAcquiredButUnusableContractKeepsARecordedServerReason() = runBlocking {
        for (probe in ServerProbe.entries) {
            val discovery = discover(probe) { AppStoreContractAcquisition.Acquired(unreadableContract()) }
            assertEquals(probe.reason ?: DynamicContractFallbackReason.UnsupportedContract, discovery.fallbackReason, probe.name)
        }
    }

    @Test
    fun aMissingServerVersionKeepsARecordedServerReason() = runBlocking {
        for (probe in ServerProbe.entries) {
            var acquisitions = 0
            val discovery = discover(probe, serverVersion = null) {
                acquisitions += 1
                AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoUsableContract)
            }
            assertEquals(probe.reason ?: DynamicContractFallbackReason.VersionUnavailable, discovery.fallbackReason, probe.name)
            assertEquals(0, acquisitions, probe.name)
            assertTrue(discovery.diagnostics.any { "server version is unavailable" in it }, probe.name)
            assertEquals(if (probe.failure == null) 0 else 1, discovery.diagnostics.count { "server API probe" in it }, probe.name)
        }
    }

    @Test
    fun sameOriginDiscoveryRecordsTheTypedProbeResultForEachScenario() = runBlocking {
        for (probe in ServerProbe.entries) {
            val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = server(probe))
            assertEquals(probe.reason, discovery.fallbackReason, probe.name)
            probe.failure?.let { failure ->
                assertTrue(discovery.diagnostics.any { failure.name in it || "unsupported contract" in it }, probe.name)
            }
            assertTrue(discovery.diagnostics.none { "fixture.invalid" in it && "probe" in it }, probe.name)
        }
    }

    @Test
    fun accessProblemsOutrankInconclusiveResultsWhichOutrankUnsupportedContracts() = runBlocking {
        fun statuses(viewer: Int, static: Int, staticBody: String = ""): suspend (NextcloudApiRequest) -> NextcloudApiResponse =
            { request ->
                if (request.relativePath.contains("ocs_api_viewer")) {
                    response(viewer)
                } else {
                    response(static, staticBody)
                }
            }
        val cases = listOf(
            statuses(403, 503) to DynamicContractFallbackReason.ServerPermissionDenied,
            statuses(503, 401) to DynamicContractFallbackReason.ServerAuthenticationRequired,
            statuses(403, 401) to DynamicContractFallbackReason.ServerAuthenticationRequired,
            statuses(401, 403) to DynamicContractFallbackReason.ServerAuthenticationRequired,
            statuses(429, 200, SWAGGER) to DynamicContractFallbackReason.ServerProbeInconclusive,
            statuses(404, 200, SWAGGER) to DynamicContractFallbackReason.UnsupportedContract,
            statuses(403, 200, SWAGGER) to DynamicContractFallbackReason.ServerPermissionDenied,
            statuses(404, 404) to null,
        )
        for ((execute, expected) in cases) {
            val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = execute)
            assertEquals(expected, discovery.fallbackReason)
        }
    }

    @Test
    fun oneInconclusiveStaticProbeAmongDefinitiveAnswersStillMakesTheServerInconclusive() = runBlocking {
        val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            response(if (request.relativePath == "/custom_apps/example/openapi.json") 503 else 404)
        })
        assertEquals(DynamicContractFallbackReason.ServerProbeInconclusive, discovery.fallbackReason)
        assertTrue("The server API probe was inconclusive: ServerError." in discovery.diagnostics)
    }

    @Test
    fun theFirstResultWinsAmongEqualRanks() = runBlocking {
        val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            if (request.relativePath.contains("ocs_api_viewer")) response(429) else throw IllegalStateException("synthetic")
        })
        assertEquals(
            listOf("The server API probe was inconclusive: Throttled."),
            discovery.diagnostics.filter { "server API probe" in it },
        )
    }

    @Test
    fun accessAndUnsupportedCopyGivesTheRightNextStepWithoutRequestDetails() {
        val authentication = DynamicContractFallbackReason.ServerAuthenticationRequired.fallbackNotice()
        assertTrue("Sign in again to continue." in authentication.message)
        val permission = DynamicContractFallbackReason.ServerPermissionDenied.fallbackNotice()
        assertTrue("permission" in permission.message && "administrators" in permission.message)
        val unsupported = DynamicContractFallbackReason.UnsupportedContract.fallbackNotice()
        assertTrue("verified" !in unsupported.message)
        for (notice in listOf(authentication, permission, unsupported)) {
            assertTrue(listOf("http", "/", "401", "403").none { it in notice.message }, notice.message)
        }
        val inconclusive = DynamicContractFallbackReason.ServerProbeInconclusive.fallbackNotice()
        assertEquals("Try again", inconclusive.actionLabel)
        assertTrue("Neither" !in inconclusive.message)
    }

    @Test
    fun cancellationDuringAServerProbeIsNotRecorded() = runBlocking {
        val cancelled = CancellationException("stop")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { throw cancelled })
        })
    }

    private fun unreadableContract() = AcquiredOpenApiContract(
        appId = "example",
        appVersion = "1.0.0",
        contractVersion = "1.0.0",
        specFile = "openapi.json",
        document = "not json",
        packageUrl = "https://fixture.invalid/example.tar.gz",
        sourceUrl = "https://fixture.invalid/example.tar.gz#openapi.json",
        sourceKind = AcquiredOpenApiContractSourceKind.SignedAppPackage,
    )

    private fun server(probe: ServerProbe): suspend (NextcloudApiRequest) -> NextcloudApiResponse = { request ->
        val viewer = request.relativePath.contains("ocs_api_viewer")
        when (probe) {
            ServerProbe.Absent -> response(404)
            ServerProbe.EmptyCatalog -> if (viewer) response(200, "[]") else response(404)
            ServerProbe.Network -> throw IllegalStateException("synthetic transport failure")
            ServerProbe.ServerError -> response(503)
            ServerProbe.Throttled -> response(429)
            ServerProbe.MalformedStaticSpec -> if (viewer) response(404) else response(200, """{"openapi":"3.0""")
            ServerProbe.UnversionedStaticJson -> if (viewer) response(404) else response(200, """{"status":"ok"}""")
            ServerProbe.SwaggerStaticSpec -> if (viewer) response(404) else response(200, SWAGGER)
            ServerProbe.Unauthorized -> response(401, """{"message":"Current user is not logged in"}""")
            ServerProbe.Forbidden -> if (viewer) response(403) else response(404)
        }
    }

    private suspend fun discover(
        probe: ServerProbe,
        serverVersion: String? = "34.0.3",
        acquire: suspend () -> AppStoreContractAcquisition,
    ): DynamicDescriptorDiscovery = discoverDynamicAppDescriptor(
        services = services(server(probe), acquire),
        session = session,
        app = app,
        serverVersion = serverVersion,
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

            override suspend fun acquireSignedOpenApiContract(request: AppStoreContractRequest): AppStoreContractAcquisition =
                acquire()
        }
    }

    private companion object {
        const val INCONCLUSIVE = "server-probe-inconclusive"
        const val UNSUPPORTED = "server-contract-unsupported"
        const val DENIED = "server-probe-access-denied"
        const val SWAGGER = """{"swagger":"2.0","info":{"title":"Example","version":"1"},"paths":{}}"""

        fun response(status: Int, body: String = ""): NextcloudApiResponse =
            NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", etag = null)
    }
}
