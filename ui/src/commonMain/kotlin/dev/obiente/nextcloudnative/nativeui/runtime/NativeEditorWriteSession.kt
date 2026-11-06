package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/**
 * What may be sent again after an unconfirmed result. A create or send is [NonIdempotent]; a
 * change of named fields or a delete of one identity is [Idempotent]. A replacement is
 * [AfterRefresh]: it carries a complete copy taken from an earlier read, so it may only be sent
 * again once a fresh authoritative read has arrived, and its precondition then checks that copy.
 */
internal enum class NativeEditorWriteRetry {
    Idempotent,
    NonIdempotent,
    AfterRefresh,
}

internal sealed interface NativeEditorWriteStatus {
    data object Idle : NativeEditorWriteStatus

    data object Submitting : NativeEditorWriteStatus

    /** The server refused the request. The draft is unchanged and may be corrected and sent again. */
    data class Rejected(val message: String) : NativeEditorWriteStatus

    /**
     * Nothing was sent because the target changed after the editor read it. The draft is based
     * on stale values, so it cannot be sent; the person refreshes and edits the current values.
     */
    data class Changed(val message: String) : NativeEditorWriteStatus

    /**
     * The request may or may not have reached the server. For a non-idempotent write, sending it
     * again stays blocked until a fresh authoritative read arrives and the person has checked it.
     */
    data class Unconfirmed(
        val message: String,
        val retry: NativeEditorWriteRetry,
        val awaitingRefresh: Boolean,
    ) : NativeEditorWriteStatus

    data object Succeeded : NativeEditorWriteStatus

    /**
     * The change was saved, but the read-back shows other values of the record changed at the same
     * time. Writes to this route are paused; [restore] writes the values from before the edit back
     * when the person chooses to.
     */
    data class Drifted(val message: String, val restore: NativeActionRequest.Submit?) : NativeEditorWriteStatus
}

/**
 * Screen-local outcome tracking for one record editor (a grid row, a child record such as an
 * expense, or a questionnaire response). It owns no draft values, never retries by itself, and keeps cancellation as
 * control flow. Durable recovery across process death is not provided; an unconfirmed create is
 * reconciled by the authoritative refresh the caller triggers through [onRefreshRequested].
 */
@Stable
internal class NativeEditorWriteSession {
    var status by mutableStateOf<NativeEditorWriteStatus>(NativeEditorWriteStatus.Idle)
        private set

    val submitting: Boolean get() = status == NativeEditorWriteStatus.Submitting

    /** False while an unconfirmed non-idempotent write still waits for a fresh authoritative read. */
    val canSubmit: Boolean
        get() = when (val current = status) {
            NativeEditorWriteStatus.Submitting, is NativeEditorWriteStatus.Changed, is NativeEditorWriteStatus.Drifted -> false
            is NativeEditorWriteStatus.Unconfirmed ->
                current.retry == NativeEditorWriteRetry.Idempotent || !current.awaitingRefresh
            else -> true
        }

    /** Called when a new authoritative load has replaced the records this editor was opened from. */
    fun acceptAuthoritativeRefresh() {
        val current = status
        if (current is NativeEditorWriteStatus.Unconfirmed && current.awaitingRefresh) {
            status = current.copy(
                message = "The list was refreshed. Check it for your change before you send it again.",
                awaitingRefresh = false,
            )
        }
    }

    fun reset() {
        if (!submitting) status = NativeEditorWriteStatus.Idle
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
        retry: NativeEditorWriteRetry,
        onRefreshRequested: () -> Unit,
    ): Boolean {
        if (!canSubmit) return false
        status = NativeEditorWriteStatus.Submitting
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
                val drift = result.postcondition?.takeIf { it.drifted.isNotEmpty() }
                if (drift != null) {
                    restoreExecutor = executor
                    restoreRefresh = onRefreshRequested
                }
                status = drift?.let { NativeEditorWriteStatus.Drifted(result.message ?: "Other values changed at the same time.", it.restoreBaseline) }
                    ?: NativeEditorWriteStatus.Succeeded
                onRefreshRequested()
                drift == null
            }
            is NativeActionExecutionResult.Failure -> {
                status = when {
                    result.precondition == NativeWritePreconditionFailure.Changed -> NativeEditorWriteStatus.Changed(result.message)
                    result.outcome == NativeActionFailureOutcome.Rejected -> NativeEditorWriteStatus.Rejected(result.message)
                    else -> unconfirmed(retry)
                }
                if (result.outcome == NativeActionFailureOutcome.Unknown) onRefreshRequested()
                false
            }
        }
    }

    private var restoreExecutor: NativeActionExecutor? = null
    private var restoreRefresh: () -> Unit = {}

    /**
     * Sends the baseline restore offered after drift, once. It is a full-echo replacement of the
     * values from before the edit; it never runs unless the person chose it.
     */
    suspend fun restoreBaseline(): Boolean {
        val drifted = status as? NativeEditorWriteStatus.Drifted ?: return false
        val restore = drifted.restore ?: return false
        val executor = restoreExecutor ?: return false
        status = NativeEditorWriteStatus.Submitting
        val result = try {
            executor.execute(restore)
        } catch (cancellation: CancellationException) {
            status = unconfirmed(NativeEditorWriteRetry.AfterRefresh)
            throw cancellation
        } catch (_: Exception) {
            NativeActionExecutionResult.Failure("The restore could not be confirmed.", NativeActionFailureOutcome.Unknown)
        }
        status = when {
            result is NativeActionExecutionResult.Success -> NativeEditorWriteStatus.Succeeded
            result is NativeActionExecutionResult.Failure && result.precondition == NativeWritePreconditionFailure.Unverified ->
                drifted.copy(message = result.message)
            (result as NativeActionExecutionResult.Failure).outcome == NativeActionFailureOutcome.Rejected ->
                NativeEditorWriteStatus.Rejected(result.message)
            else -> unconfirmed(NativeEditorWriteRetry.AfterRefresh)
        }
        restoreRefresh()
        return result is NativeActionExecutionResult.Success
    }

    private fun unconfirmed(retry: NativeEditorWriteRetry) = NativeEditorWriteStatus.Unconfirmed(
        message = when (retry) {
            NativeEditorWriteRetry.NonIdempotent ->
                "The server did not confirm this change. Wait for the refreshed list and check it " +
                    "before you send it again, so it is not added twice."
            NativeEditorWriteRetry.Idempotent ->
                "The server did not confirm this change. Sending the same values again is safe."
            NativeEditorWriteRetry.AfterRefresh ->
                "The server did not confirm this change. Wait for the refreshed list before you save " +
                    "again; saving then first checks that nothing else changed."
        },
        retry = retry,
        awaitingRefresh = retry != NativeEditorWriteRetry.Idempotent,
    )
}
