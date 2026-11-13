package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/**
 * Whether repeating the exact same request after an unconfirmed result could change server state
 * twice. A create or send is [NonIdempotent]; a full replacement of the same values or a delete of
 * one identity is [Idempotent].
 */
internal enum class NativeReviewedWriteRetry {
    Idempotent,
    NonIdempotent,
}

internal sealed interface NativeReviewedWriteStatus {
    data object Idle : NativeReviewedWriteStatus

    data object Submitting : NativeReviewedWriteStatus

    /** The server refused the request. The draft is unchanged and may be corrected and sent again. */
    data class Rejected(val message: String) : NativeReviewedWriteStatus

    /**
     * The request may or may not have reached the server. For a non-idempotent write, sending it
     * again stays blocked until a fresh authoritative read arrives and the person has checked it.
     */
    data class Unconfirmed(
        val message: String,
        val retry: NativeReviewedWriteRetry,
        val awaitingRefresh: Boolean,
    ) : NativeReviewedWriteStatus

    data object Succeeded : NativeReviewedWriteStatus
}

/**
 * Screen-local outcome tracking for one reviewed editor (a Tables row, a Cospend bill, a Forms
 * submission). It owns no draft values, never retries by itself, and keeps cancellation as
 * control flow. Durable recovery across process death is not provided; an unconfirmed create is
 * reconciled by the authoritative refresh the caller triggers through [onRefreshRequested].
 */
@Stable
internal class NativeReviewedWriteSession {
    var status by mutableStateOf<NativeReviewedWriteStatus>(NativeReviewedWriteStatus.Idle)
        private set

    val submitting: Boolean get() = status == NativeReviewedWriteStatus.Submitting

    /** False while an unconfirmed non-idempotent write still waits for a fresh authoritative read. */
    val canSubmit: Boolean
        get() = when (val current = status) {
            NativeReviewedWriteStatus.Submitting -> false
            is NativeReviewedWriteStatus.Unconfirmed ->
                current.retry == NativeReviewedWriteRetry.Idempotent || !current.awaitingRefresh
            else -> true
        }

    /** Called when a new authoritative load has replaced the records this editor was opened from. */
    fun acceptAuthoritativeRefresh() {
        val current = status
        if (current is NativeReviewedWriteStatus.Unconfirmed && current.awaitingRefresh) {
            status = current.copy(
                message = "The list was refreshed. Check it for your change before you send it again.",
                awaitingRefresh = false,
            )
        }
    }

    /**
     * Clears the outcome when an editor closes. An unconfirmed create stays until an authoritative
     * refresh arrives, so closing and reopening the editor cannot send it a second time.
     */
    fun reset() {
        val current = status
        val unresolvedCreate = current is NativeReviewedWriteStatus.Unconfirmed &&
            current.retry == NativeReviewedWriteRetry.NonIdempotent && current.awaitingRefresh
        if (!submitting && !unresolvedCreate) status = NativeReviewedWriteStatus.Idle
    }

    /**
     * Sends [request] once. Returns true only when the server confirmed it.
     *
     * [onRefreshRequested] runs after a confirmed or unconfirmed result so the owning collection
     * re-reads authoritative state; it never runs after a rejection, which changed nothing.
     */
    suspend fun submit(
        executor: NativeActionExecutor,
        request: NativeActionRequest.Submit,
        retry: NativeReviewedWriteRetry,
        onRefreshRequested: () -> Unit,
    ): Boolean {
        if (!canSubmit) return false
        status = NativeReviewedWriteStatus.Submitting
        val result = try {
            executor.execute(request)
        } catch (cancellation: CancellationException) {
            // The request may have been sent. Treat it as unconfirmed rather than unsent.
            status = unconfirmed(retry)
            throw cancellation
        } catch (failure: Exception) {
            NativeActionExecutionResult.Failure(
                message = "The change could not be confirmed.",
                outcome = NativeActionFailureOutcome.Unknown,
            )
        }
        return when (result) {
            is NativeActionExecutionResult.Success -> {
                status = NativeReviewedWriteStatus.Succeeded
                onRefreshRequested()
                true
            }
            is NativeActionExecutionResult.Failure -> {
                status = when (result.outcome) {
                    NativeActionFailureOutcome.Rejected -> NativeReviewedWriteStatus.Rejected(result.message)
                    NativeActionFailureOutcome.Unknown -> unconfirmed(retry)
                }
                if (result.outcome == NativeActionFailureOutcome.Unknown) onRefreshRequested()
                false
            }
        }
    }

    private fun unconfirmed(retry: NativeReviewedWriteRetry) = NativeReviewedWriteStatus.Unconfirmed(
        message = when (retry) {
            NativeReviewedWriteRetry.NonIdempotent ->
                "The server did not confirm this change. Wait for the refreshed list and check it " +
                    "before you send it again, so it is not added twice."
            NativeReviewedWriteRetry.Idempotent ->
                "The server did not confirm this change. Sending the same values again is safe."
        },
        retry = retry,
        awaitingRefresh = retry == NativeReviewedWriteRetry.NonIdempotent,
    )
}
