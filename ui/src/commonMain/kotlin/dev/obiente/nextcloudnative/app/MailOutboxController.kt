package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class MailOutboxUiState(
    val messages: List<MailOutboxMessage>? = null,
    val failure: MailFailure? = null,
    val loading: Boolean = false,
    val pendingIds: Set<Long> = emptySet(),
    val notice: String? = null,
)

/**
 * The account's Outbox: scheduled messages, messages waiting to be sent, and failed ones.
 *
 * "Send now" asks Mail to send a message it already holds; Mail's per-message status decides what
 * that does, so it never sends a processed message twice. Cancelling is destructive and is
 * confirmed by the caller. Every unknown result is reconciled by listing the Outbox again.
 */
internal class MailOutboxController(
    private val scope: CoroutineScope,
    transport: MailTransport,
) {
    private val sender = MailSender(transport)

    var state by mutableStateOf(MailOutboxUiState())
        private set

    private var generation = 0L

    fun load() {
        val current = ++generation
        state = state.copy(loading = true)
        scope.launch {
            val outcome = sender.outbox()
            if (current != generation) return@launch
            state = when (outcome) {
                is MailReadOutcome.Loaded -> state.copy(
                    messages = outcome.value.sortedBy { it.sendAtEpochSeconds ?: Long.MAX_VALUE },
                    failure = null,
                    loading = false,
                )
                is MailReadOutcome.Failed -> state.copy(failure = outcome.failure, loading = false)
            }
        }
    }

    fun sendNow(message: MailOutboxMessage) {
        if (message.id in state.pendingIds || message.status == MailOutboxStatus.Error) return
        state = state.copy(pendingIds = state.pendingIds + message.id, notice = null)
        scope.launch {
            val notice = when (val result = sender.sendNow(message.id)) {
                MailSendResult.Sent -> "Message sent."
                is MailSendResult.Waiting -> "The message is waiting to be sent."
                is MailSendResult.Failed -> result.message.status.explanation ?: "The server could not send this message."
                is MailSendResult.Rejected -> "${result.failure.title}. ${result.failure.message}"
                MailSendResult.Unknown -> "Could not confirm whether the message was sent. Check again before retrying."
            }
            state = state.copy(pendingIds = state.pendingIds - message.id, notice = notice)
            load()
        }
    }

    /** Removes a message from the Outbox so it is not sent. The caller has already confirmed. */
    fun cancel(message: MailOutboxMessage) {
        if (message.id in state.pendingIds) return
        state = state.copy(pendingIds = state.pendingIds + message.id, notice = null)
        scope.launch {
            val notice = when (val result = sender.cancel(message.id)) {
                MailCancelResult.Cancelled -> "The message will not be sent."
                MailCancelResult.AlreadyGone -> "The message had already left the Outbox, so it was probably sent."
                is MailCancelResult.Rejected -> "${result.failure.title}. The message is still in the Outbox."
                MailCancelResult.Unknown -> "Could not confirm the change. Check the Outbox again."
            }
            state = state.copy(pendingIds = state.pendingIds - message.id, notice = notice)
            load()
        }
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }
}
