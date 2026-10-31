package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface NativeMailSendPhase {
    data object Editing : NativeMailSendPhase

    data object Sending : NativeMailSendPhase

    /** The server answered that it did not send the message. Editing and sending again is safe. */
    data class Rejected(val message: String) : NativeMailSendPhase

    /**
     * The request may have reached the server, but no answer confirmed it. Sending is not
     * idempotent, so the draft stays open and another send needs an explicit confirmation.
     */
    data class Unknown(val message: String) : NativeMailSendPhase
}

/**
 * Owns the open draft and the send state machine for one mailbox workspace.
 *
 * Drafts are ordinary in-memory state: they survive pane and view changes inside the workspace but
 * not process death. Message content is private and unbounded, so it is never written to saved
 * instance state.
 */
@Stable
internal class NativeMailComposerState {
    var draft: NativeMailComposeDraft? by mutableStateOf(null)
        private set
    var phase: NativeMailSendPhase by mutableStateOf(NativeMailSendPhase.Editing)
        private set
    var notice: String? by mutableStateOf(null)
        private set

    val sending: Boolean get() = phase == NativeMailSendPhase.Sending

    /** True when the previous attempt's outcome is unknown and a resend must be confirmed. */
    val resendNeedsConfirmation: Boolean get() = phase is NativeMailSendPhase.Unknown

    fun open(draft: NativeMailComposeDraft) {
        if (sending) return
        this.draft = draft
        phase = NativeMailSendPhase.Editing
        notice = null
    }

    fun update(transform: (NativeMailComposeDraft) -> NativeMailComposeDraft) {
        if (sending) return
        draft = draft?.let(transform)
        if (phase is NativeMailSendPhase.Rejected) phase = NativeMailSendPhase.Editing
    }

    fun discard() {
        if (sending) return
        draft = null
        phase = NativeMailSendPhase.Editing
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * Sends the open draft once. [confirmedResend] must be true after an unknown outcome; the
     * caller asks the user first, because the earlier message may already have been delivered.
     */
    suspend fun send(
        capability: NativeMailSendCapability,
        executor: NativeActionExecutor,
        confirmedResend: Boolean = false,
        onSent: () -> Unit = {},
    ) {
        val current = draft ?: return
        if (sending) return
        if (resendNeedsConfirmation && !confirmedResend) return
        val request = try {
            capability.request(current)
        } catch (failure: IllegalArgumentException) {
            phase = NativeMailSendPhase.Rejected(failure.message ?: "The message is not ready to send.")
            return
        }
        phase = NativeMailSendPhase.Sending
        val result = try {
            executor.execute(request)
        } catch (cancelled: CancellationException) {
            // The request may already be on its way; treat the outcome as unknown, never as unsent.
            phase = NativeMailSendPhase.Unknown(UNKNOWN_SEND_MESSAGE)
            throw cancelled
        } catch (failure: Exception) {
            NativeActionExecutionResult.Failure(
                message = failure.message.orEmpty(),
                outcome = NativeActionFailureOutcome.Unknown,
            )
        }
        when (result) {
            is NativeActionExecutionResult.Success -> {
                draft = null
                phase = NativeMailSendPhase.Editing
                notice = result.message?.takeIf(String::isNotBlank) ?: "Message sent."
                onSent()
            }
            is NativeActionExecutionResult.Failure -> phase = when (result.outcome) {
                NativeActionFailureOutcome.Rejected -> NativeMailSendPhase.Rejected(
                    result.message.takeIf(String::isNotBlank) ?: "The server did not send this message.",
                )
                NativeActionFailureOutcome.Unknown -> NativeMailSendPhase.Unknown(UNKNOWN_SEND_MESSAGE)
            }
        }
    }
}

internal const val UNKNOWN_SEND_MESSAGE =
    "The server did not confirm whether this message was sent. Check your sent messages before sending it again."
