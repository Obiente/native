package dev.obiente.nextcloudnative.app

fun LoginPollResult.toLoginPollFailureDiagnostic(): SupportDiagnosticEventDraft? {
    val (severity, outcome, code, safeToRetry, ambiguous) = when (this) {
        LoginPollResult.Pending,
        is LoginPollResult.Approved,
        -> return null
        is LoginPollResult.RetryablePreExchangeFailure -> LoginPollDiagnosticValues(
            severity = SupportDiagnosticSeverity.Warning,
            outcome = "transient-network",
            code = code,
            safeToRetry = true,
            ambiguous = false,
        )
        is LoginPollResult.FatalFailure -> LoginPollDiagnosticValues(
            severity = SupportDiagnosticSeverity.Error,
            outcome = "fatal",
            code = code,
            safeToRetry = false,
            ambiguous = false,
        )
        is LoginPollResult.AmbiguousAfterExchangeFailure -> LoginPollDiagnosticValues(
            severity = SupportDiagnosticSeverity.Error,
            outcome = "ambiguous",
            code = code,
            safeToRetry = false,
            ambiguous = true,
        )
    }
    return SupportDiagnosticEventDraft(
        severity = severity,
        component = SupportDiagnosticComponent.Authentication,
        operation = "login.poll",
        outcome = outcome,
        code = code,
        fields = listOf(
            SupportDiagnosticFieldDraft("safe_to_retry", safeToRetry.toString()),
            SupportDiagnosticFieldDraft("exchange_ambiguous", ambiguous.toString()),
        ),
    )
}

private data class LoginPollDiagnosticValues(
    val severity: SupportDiagnosticSeverity,
    val outcome: String,
    val code: String?,
    val safeToRetry: Boolean,
    val ambiguous: Boolean,
)
