package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DashboardWidgetsLoad(
    val widgets: List<NativeDashboardWidget>,
    val authoritative: Boolean,
    val responseClassification: DashboardWidgetsResponseClassification,
)

internal enum class DashboardWidgetsResponseClassification(val diagnosticValue: String) {
    Supported("supported"),
    HttpRouteMissing("http_route_missing"),
    OcsRouteMissing("ocs_route_missing"),
    OcsFailure("ocs_failure"),
    HttpFailure("http_failure"),
    MalformedResponse("malformed_response"),
    TransportFailure("transport_failure"),
}

internal suspend fun acquireDashboardWidgets(
    cachedAvailable: Boolean,
    executeResponse: suspend () -> NextcloudApiResponse,
    onDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
): DashboardWidgetsLoad {
    var classification = DashboardWidgetsResponseClassification.TransportFailure
    var statusFamily = "none"
    return try {
        val response = executeResponse()
        statusFamily = response.status.dashboardHttpStatusFamily()
        classification = withContext(Dispatchers.Default) {
            classifyDashboardWidgetsResponse(response)
        }
        when (classification) {
            DashboardWidgetsResponseClassification.HttpRouteMissing,
            DashboardWidgetsResponseClassification.OcsRouteMissing,
            -> {
                recordDashboardWidgetsDiagnosticSafely(
                    onDiagnostic = onDiagnostic,
                    diagnostic = dashboardWidgetsDiagnostic(
                        classification = classification,
                        statusFamily = statusFamily,
                        cachedAvailable = cachedAvailable,
                        outcome = "unsupported",
                        code = "DASHBOARD_WIDGETS_UNAVAILABLE",
                        severity = SupportDiagnosticSeverity.Info,
                    ),
                )
                DashboardWidgetsLoad(emptyList(), authoritative = false, classification)
            }

            DashboardWidgetsResponseClassification.HttpFailure ->
                throw DashboardWidgetsHttpFailure(response.status)

            DashboardWidgetsResponseClassification.OcsFailure ->
                throw DashboardWidgetsOcsFailure()

            DashboardWidgetsResponseClassification.Supported -> {
                val widgets = try {
                    withContext(Dispatchers.Default) { parseDashboardWidgets(response) }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    classification = DashboardWidgetsResponseClassification.MalformedResponse
                    throw failure
                }
                DashboardWidgetsLoad(widgets, authoritative = true, classification)
            }

            DashboardWidgetsResponseClassification.MalformedResponse,
            DashboardWidgetsResponseClassification.TransportFailure,
            -> error("Unexpected Dashboard widgets response classification.")
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        recordDashboardWidgetsDiagnosticSafely(
            onDiagnostic = onDiagnostic,
            diagnostic = dashboardWidgetsDiagnostic(
                classification = classification,
                statusFamily = statusFamily,
                cachedAvailable = cachedAvailable,
                outcome = "failed",
                code = "DASHBOARD_WIDGETS_FAILED",
                severity = SupportDiagnosticSeverity.Error,
            ),
        )
        throw failure
    }
}

internal fun dashboardSnapshotForUnavailableWidgets(
    previousSnapshot: NativeDashboardSnapshot?,
): NativeDashboardSnapshot = previousSnapshot ?: NativeDashboardSnapshot(emptyList(), emptyMap())

private fun classifyDashboardWidgetsResponse(
    response: NextcloudApiResponse,
): DashboardWidgetsResponseClassification {
    val ocsStatusCode = dashboardOcsStatusCode(response)
    return when {
        response.status == 404 -> DashboardWidgetsResponseClassification.HttpRouteMissing
        response.status !in 200..299 -> DashboardWidgetsResponseClassification.HttpFailure
        ocsStatusCode == 404 -> DashboardWidgetsResponseClassification.OcsRouteMissing
        ocsStatusCode != null && ocsStatusCode !in setOf(100, 200) ->
            DashboardWidgetsResponseClassification.OcsFailure
        else -> DashboardWidgetsResponseClassification.Supported
    }
}

private fun dashboardWidgetsDiagnostic(
    classification: DashboardWidgetsResponseClassification,
    statusFamily: String,
    cachedAvailable: Boolean,
    outcome: String,
    code: String,
    severity: SupportDiagnosticSeverity,
): SupportDiagnosticEventDraft = SupportDiagnosticEventDraft(
    severity = severity,
    component = SupportDiagnosticComponent.App,
    operation = "dashboard.load",
    outcome = outcome,
    code = code,
    fields = listOf(
        SupportDiagnosticFieldDraft("stage", "widgets"),
        SupportDiagnosticFieldDraft("cached_available", cachedAvailable.toString()),
        SupportDiagnosticFieldDraft("route_variant", "ocs_v2_dashboard_widgets_v1"),
        SupportDiagnosticFieldDraft("response_classification", classification.diagnosticValue),
        SupportDiagnosticFieldDraft("http_status_family", statusFamily),
    ),
)

private fun recordDashboardWidgetsDiagnosticSafely(
    onDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    diagnostic: SupportDiagnosticEventDraft,
) {
    try {
        onDiagnostic(diagnostic)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        // Diagnostic persistence cannot make the optional Dashboard feed fail.
    }
}

private fun Int.dashboardHttpStatusFamily(): String = when (this) {
    in 200..299 -> "2xx"
    in 300..399 -> "3xx"
    in 400..499 -> "4xx"
    in 500..599 -> "5xx"
    else -> "other"
}

private class DashboardWidgetsHttpFailure(status: Int) : IllegalStateException(
    "The Dashboard widgets request failed with HTTP $status.",
)

private class DashboardWidgetsOcsFailure : IllegalStateException(
    "The Dashboard widgets request was rejected by OCS.",
)
