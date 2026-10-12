package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/** The support diagnostic for local bookkeeping after a server-confirmed file change. */
fun fileMutationLocalFollowUpDiagnostic(
    outcome: String,
    code: String,
    failure: Throwable? = null,
): SupportDiagnosticEventDraft = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Warning,
    component = SupportDiagnosticComponent.Files,
    operation = "files.mutation-local-follow-up",
    outcome = outcome,
    code = code,
    exception = failure?.toSupportDiagnosticExceptionDraft(),
)

/**
 * Runs the local invalidation of every path after the server confirmed a change. Every path is
 * attempted; a failure is recorded with its cause and makes the result
 * [FileMutationLocalFollowUp.Failed], so the change is never reported as fully applied locally.
 * The server result stays authoritative, and cancellation propagates.
 */
fun invalidateAfterConfirmedFileMutation(
    paths: List<String>,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    invalidate: (path: String) -> Unit,
): FileMutationLocalFollowUp {
    var followUp = FileMutationLocalFollowUp.Completed
    paths.distinct().forEach { path ->
        try {
            invalidate(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            followUp = FileMutationLocalFollowUp.Failed
            try {
                recordDiagnostic(fileMutationLocalFollowUpDiagnostic("failed", "FILE_MUTATION_LOCAL_FOLLOW_UP_FAILED", failure))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (diagnosticFailure: Exception) {
                // Diagnostics must never change the reported mutation result.
                failure.addSuppressed(diagnosticFailure)
            }
        }
    }
    return followUp
}
