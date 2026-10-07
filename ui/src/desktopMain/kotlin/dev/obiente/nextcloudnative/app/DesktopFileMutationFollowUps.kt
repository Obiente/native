package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local cache and virtual-file bookkeeping after a remote file mutation.
 *
 * The remote result is authoritative and must reach the caller even when this bookkeeping stalls,
 * for example while a virtual-file provider holds its monitor across platform calls. A monitor wait
 * can be neither cancelled nor interrupted, so the work never runs on the caller's coroutine. It
 * runs in submission order on one background worker: a stalled lock occupies one thread instead of
 * one per mutation, and later invalidations still apply after earlier ones.
 */
internal class DesktopFileMutationFollowUps(
    private val scope: CoroutineScope,
    private val completionWaitMillis: Long = DEFAULT_FILE_MUTATION_FOLLOW_UP_WAIT_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val recordDiagnostic: (accountId: String, SupportDiagnosticEventDraft) -> Unit = { _, _ -> },
) {
    init {
        require(completionWaitMillis > 0L)
    }

    /**
     * Runs [work] after the server confirmed a mutation and waits at most [completionWaitMillis].
     * [work] returns the first local failure it recovered from, or null when every update applied.
     */
    suspend fun afterConfirmedMutation(accountId: String, work: () -> Throwable?): FileMutationLocalFollowUp {
        val pending = submit(accountId, work)
        val followUp = try {
            withTimeoutOrNull(completionWaitMillis) { pending.await() }
                ?: FileMutationLocalFollowUp.StillRunning
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            // The service scope closed before the bookkeeping ran; the caller is still active.
            FileMutationLocalFollowUp.Failed
        }
        if (followUp == FileMutationLocalFollowUp.StillRunning) {
            recordOutcome(accountId, "still-running", "FILE_MUTATION_LOCAL_FOLLOW_UP_DELAYED")
        }
        return followUp
    }

    /** Queues [work] after an unknown remote result without delaying the caller's failure. */
    fun afterUnknownResult(accountId: String, work: () -> Throwable?) {
        submit(accountId, work)
    }

    private fun submit(
        accountId: String,
        work: () -> Throwable?,
    ): Deferred<FileMutationLocalFollowUp> = scope.async(dispatcher) {
        // Worker fault boundary: [work] is blocking and does not suspend, so it cannot observe
        // cancellation; an unexpected failure is reported instead of escaping to the service scope.
        val failure = try {
            work()
        } catch (failure: Exception) {
            failure
        }
        if (failure == null) {
            FileMutationLocalFollowUp.Completed
        } else {
            recordOutcome(accountId, "failed", "FILE_MUTATION_LOCAL_FOLLOW_UP_FAILED", failure)
            FileMutationLocalFollowUp.Failed
        }
    }

    private fun recordOutcome(accountId: String, outcome: String, code: String, failure: Throwable? = null) {
        try {
            recordDiagnostic(
                accountId,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Files,
                    operation = "files.mutation-local-follow-up",
                    outcome = outcome,
                    code = code,
                    exception = failure?.toSupportDiagnosticExceptionDraft(),
                ),
            )
        } catch (diagnosticFailure: Exception) {
            // Diagnostics must never change the reported mutation result.
            failure?.addSuppressed(diagnosticFailure)
        }
    }
}

/**
 * How long a caller waits for local bookkeeping before reporting a server-confirmed change.
 * Ordinary cache invalidation takes milliseconds; anything slower is reported as still running.
 */
internal const val DEFAULT_FILE_MUTATION_FOLLOW_UP_WAIT_MILLIS = 3_000L
