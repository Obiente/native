package dev.obiente.nextcloudnative.app

data class FileSyncActionDiagnosticSummary(
    val severity: SupportDiagnosticSeverity,
    val outcome: String,
    val message: String?,
)

fun FileSyncCenterActionResult.toFileSyncActionDiagnosticSummary(): FileSyncActionDiagnosticSummary =
    when (this) {
        is FileSyncCenterActionResult.Completed ->
            FileSyncActionDiagnosticSummary(SupportDiagnosticSeverity.Info, "completed", null)
        is FileSyncCenterActionResult.Stopped ->
            FileSyncActionDiagnosticSummary(SupportDiagnosticSeverity.Info, "stopped", message)
        is FileSyncCenterActionResult.Rejected ->
            FileSyncActionDiagnosticSummary(SupportDiagnosticSeverity.Warning, "rejected", reason)
        is FileSyncCenterActionResult.Unsupported ->
            FileSyncActionDiagnosticSummary(SupportDiagnosticSeverity.Warning, "unsupported", reason)
    }
