package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardWidgetsAcquisitionTest {
    @Test
    fun `supported widgets contract returns an authoritative result without diagnostics`() = runBlocking {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val result = acquireDashboardWidgets(
            cachedAvailable = false,
            executeResponse = { ocsResponse("{}") },
            onDiagnostic = diagnostics::add,
        )

        assertTrue(result.authoritative)
        assertTrue(result.widgets.isEmpty())
        assertEquals(DashboardWidgetsResponseClassification.Supported, result.responseClassification)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `missing HTTP widgets route keeps cached dashboard and records a safe classification`() = runBlocking {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val cached = NativeDashboardSnapshot(
            widgets = listOf(widget("calendar")),
            itemsByWidget = mapOf("calendar" to emptyList()),
        )

        val result = acquireDashboardWidgets(
            cachedAvailable = true,
            executeResponse = { rawResponse(status = 404, body = "not found") },
            onDiagnostic = diagnostics::add,
        )

        assertFalse(result.authoritative)
        assertEquals(DashboardWidgetsResponseClassification.HttpRouteMissing, result.responseClassification)
        assertEquals(cached, dashboardSnapshotForUnavailableWidgets(cached))
        assertEquals(
            mapOf(
                "stage" to "widgets",
                "cached_available" to "true",
                "route_variant" to "ocs_v2_dashboard_widgets_v1",
                "response_classification" to "http_route_missing",
                "http_status_family" to "4xx",
            ),
            diagnostics.single().fields.associate { it.name to it.value },
        )
        assertEquals("DASHBOARD_WIDGETS_UNAVAILABLE", diagnostics.single().code)
        assertEquals("unsupported", diagnostics.single().outcome)
    }

    @Test
    fun `missing OCS widgets route returns a truthful empty unsupported state`() = runBlocking {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val result = acquireDashboardWidgets(
            cachedAvailable = false,
            executeResponse = {
                rawResponse(
                    status = 200,
                    body = """{"ocs":{"meta":{"statuscode":404},"data":[]}}""",
                )
            },
            onDiagnostic = diagnostics::add,
        )

        assertFalse(result.authoritative)
        assertTrue(dashboardSnapshotForUnavailableWidgets(null).widgets.isEmpty())
        assertEquals(DashboardWidgetsResponseClassification.OcsRouteMissing, result.responseClassification)
        assertEquals(
            "ocs_route_missing",
            diagnostics.single().fields.single { it.name == "response_classification" }.value,
        )
        assertEquals(
            "2xx",
            diagnostics.single().fields.single { it.name == "http_status_family" }.value,
        )
    }

    @Test
    fun `malformed successful response stays failed and records no response content`() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        assertFailsWith<Exception> {
            runBlocking {
                acquireDashboardWidgets(
                    cachedAvailable = false,
                    executeResponse = { rawResponse(status = 200, body = "{") },
                    onDiagnostic = diagnostics::add,
                )
            }
        }

        assertEquals("DASHBOARD_WIDGETS_FAILED", diagnostics.single().code)
        assertEquals(
            "malformed_response",
            diagnostics.single().fields.single { it.name == "response_classification" }.value,
        )
        assertTrue(diagnostics.single().message == null)
        assertTrue(diagnostics.single().exception == null)
    }

    @Test
    fun `HTTP failure remains distinct from an unsupported route`() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        assertFailsWith<IllegalStateException> {
            runBlocking {
                acquireDashboardWidgets(
                    cachedAvailable = false,
                    executeResponse = { rawResponse(status = 503, body = "unavailable") },
                    onDiagnostic = diagnostics::add,
                )
            }
        }

        assertEquals("failed", diagnostics.single().outcome)
        assertEquals(
            "http_failure",
            diagnostics.single().fields.single { it.name == "response_classification" }.value,
        )
        assertEquals(
            "5xx",
            diagnostics.single().fields.single { it.name == "http_status_family" }.value,
        )
    }

    @Test
    fun `cancellation remains control flow and produces no diagnostic`() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        assertFailsWith<CancellationException> {
            runBlocking {
                acquireDashboardWidgets(
                    cachedAvailable = false,
                    executeResponse = { throw CancellationException("Dashboard left composition") },
                    onDiagnostic = diagnostics::add,
                )
            }
        }

        assertTrue(diagnostics.isEmpty())
    }

    private fun ocsResponse(data: String): NextcloudApiResponse = rawResponse(
        status = 200,
        body = """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":$data}}""",
    )

    private fun rawResponse(status: Int, body: String): NextcloudApiResponse = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )

    private fun widget(id: String): NativeDashboardWidget = NativeDashboardWidget(
        id = id,
        title = id,
        order = 1,
        iconUrl = null,
        iconClass = null,
        widgetUrl = null,
        itemApiVersions = setOf(1),
        itemIconsRound = false,
        reloadIntervalSeconds = null,
        actions = emptyList(),
    )
}
