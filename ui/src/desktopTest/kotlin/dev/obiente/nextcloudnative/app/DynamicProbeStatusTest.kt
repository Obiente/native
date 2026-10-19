package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only a definitive absence records nothing; every other unsuccessful status is a typed probe result. */
class DynamicProbeStatusTest {
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    private enum class ProbeKind { Catalog, Spec, Static }

    @Test
    fun `unrecognized statuses are inconclusive for every probe kind`() = runBlocking {
        for (kind in ProbeKind.entries) {
            for (status in listOf(100, 301, 302, 304, 400, 405, 408, 409, 412, 418)) {
                val discovery = discover(kind, status)
                val label = "$kind $status"
                assertEquals(DynamicContractFallbackReason.ServerProbeInconclusive, discovery.fallbackReason, label)
                assertTrue("The server API probe was inconclusive: UnexpectedStatus." in discovery.diagnostics, label)
                assertStageDiagnostic(kind, status, discovery, label)
            }
        }
    }

    @Test
    fun `statuses with a specific meaning keep their kinds`() = runBlocking {
        val expected = listOf(
            401 to ("The server API probe was not authorized: AuthenticationRequired." to
                DynamicContractFallbackReason.ServerAuthenticationRequired),
            403 to ("The server API probe was not authorized: PermissionDenied." to
                DynamicContractFallbackReason.ServerPermissionDenied),
            429 to ("The server API probe was inconclusive: Throttled." to DynamicContractFallbackReason.ServerProbeInconclusive),
            500 to ("The server API probe was inconclusive: ServerError." to DynamicContractFallbackReason.ServerProbeInconclusive),
            503 to ("The server API probe was inconclusive: ServerError." to DynamicContractFallbackReason.ServerProbeInconclusive),
        )
        for (kind in ProbeKind.entries) {
            for ((status, outcome) in expected) {
                val discovery = discover(kind, status)
                val label = "$kind $status"
                assertEquals(outcome.second, discovery.fallbackReason, label)
                assertTrue(outcome.first in discovery.diagnostics, label)
                assertStageDiagnostic(kind, status, discovery, label)
            }
        }
    }

    @Test
    fun `404 and 410 stay definitive absence for every probe kind`() = runBlocking {
        for (kind in ProbeKind.entries) {
            for (status in listOf(404, 410)) {
                val discovery = discover(kind, status)
                assertEquals(null, discovery.fallbackReason, "$kind $status")
                assertTrue(discovery.diagnostics.none { "server API probe" in it }, "$kind $status")
            }
        }
    }

    /** Catalog and spec probes name the stage and only the status class; static probes add no stage line of their own. */
    private fun assertStageDiagnostic(kind: ProbeKind, status: Int, discovery: DynamicDescriptorDiscovery, label: String) {
        val statusClass = "HTTP ${status / 100}xx"
        when (kind) {
            ProbeKind.Catalog -> assertTrue("OCS API Viewer is unavailable (stage: catalog, $statusClass)." in discovery.diagnostics, label)
            ProbeKind.Spec -> assertTrue(
                "OCS API Viewer could not load example (stage: spec, $statusClass)." in discovery.diagnostics,
                label,
            )
            // The catalog's own 404 line names its stage; the static probe adds none.
            ProbeKind.Static -> assertTrue(
                discovery.diagnostics.none { "stage: spec" in it || "unavailable (stage" in it },
                label,
            )
        }
        assertTrue(discovery.diagnostics.none { "$status" in it && "HTTP $status" in it }, label)
    }

    private suspend fun discover(kind: ProbeKind, status: Int) =
        discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            when {
                request.relativePath == CATALOG -> when (kind) {
                    ProbeKind.Catalog -> response(status)
                    ProbeKind.Spec -> response(200, """[{"id":"example","specs":["example"]}]""")
                    ProbeKind.Static -> response(404)
                }
                request.relativePath == "$CATALOG/example" && kind == ProbeKind.Spec -> response(status)
                request.relativePath == "/apps/example/openapi.json" && kind == ProbeKind.Static -> response(status)
                else -> response(404)
            }
        })

    private companion object {
        const val CATALOG = "/index.php/apps/ocs_api_viewer/apps"

        fun response(status: Int, body: String = ""): NextcloudApiResponse =
            NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", etag = null)
    }
}
