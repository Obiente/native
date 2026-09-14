package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/** Redacted credential failures exclude cancellation from support diagnostics. */
internal fun desktopAccountCredentialDiagnostic(
    code: String,
    operation: String,
    failure: Throwable?,
): SupportDiagnosticEventDraft? {
    if (failure is CancellationException) return null
    return SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Warning,
        component = SupportDiagnosticComponent.Authentication,
        operation = operation,
        outcome = "failed",
        code = code,
        exception = failure?.toNonSecretSupportDiagnosticExceptionDraft(),
    )
}
