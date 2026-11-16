package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.toFileSyncActionDiagnosticSummary

internal fun AndroidNextcloudServices.recordFileSyncResult(
    accountIdentity: String,
    operation: String,
    fields: List<SupportDiagnosticFieldDraft>,
    result: FileSyncCenterActionResult,
) {
    val diagnostic = result.toFileSyncActionDiagnosticSummary()
    recordSupportDiagnosticForAccountIdentity(
        accountIdentity,
        SupportDiagnosticEventDraft(
            severity = diagnostic.severity,
            component = SupportDiagnosticComponent.Sync,
            operation = operation,
            outcome = diagnostic.outcome,
            message = diagnostic.message,
            fields = fields,
        ),
    )
}

