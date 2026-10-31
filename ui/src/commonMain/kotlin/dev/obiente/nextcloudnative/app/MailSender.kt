package dev.obiente.nextcloudnative.app

/*
 * Sending through Mail's Outbox.
 *
 * Mail stores an outgoing message on the server with a send time and sends it from there; its
 * background job sends every due message about every five minutes, and the client may ask for an
 * immediate send. That gives three guarantees this client relies on:
 *
 * - Undo: a message stored with a send time a few seconds ahead can be removed before it leaves.
 * - Durability: once stored, the message is sent by the server even if this app is closed.
 * - Verification: the Outbox can be listed, so an unknown result is checked against the server
 *   instead of being retried. A processed message leaves the Outbox; a failed one stays with its
 *   status.
 *
 * Storing and sending are not idempotent, so nothing here retries on its own.
 */

internal sealed interface MailStoreResult {
    data class Stored(val message: MailOutboxMessage, val missingAttachments: Int) : MailStoreResult
    data class Rejected(val failure: MailFailure) : MailStoreResult

    /** The request's outcome was unknown, and the Outbox shows that the server has no copy. */
    data object NotStored : MailStoreResult

    /** The outcome is unknown and could not be checked. The message may be in the Outbox. */
    data object Unknown : MailStoreResult
}

internal sealed interface MailSendResult {
    data object Sent : MailSendResult

    /** The server kept the message with a failure status; Mail retries some statuses itself. */
    data class Failed(val message: MailOutboxMessage) : MailSendResult

    /** The message is still waiting in the Outbox. */
    data class Waiting(val message: MailOutboxMessage) : MailSendResult
    data class Rejected(val failure: MailFailure) : MailSendResult
    data object Unknown : MailSendResult
}

internal sealed interface MailCancelResult {
    data object Cancelled : MailCancelResult

    /** The message was no longer in the Outbox, so it may already have been sent. */
    data object AlreadyGone : MailCancelResult
    data class Rejected(val failure: MailFailure) : MailCancelResult
    data object Unknown : MailCancelResult
}

internal class MailSender(private val transport: MailTransport) {
    suspend fun store(payload: MailOutgoingPayload): MailStoreResult =
        when (val outcome = transport.write(mailCreateOutboxRequest(payload), ::parseMailLocalMessage)) {
            is MailWriteOutcome.Confirmed -> {
                val stored = outcome.value
                if (stored.accountId != payload.accountId) {
                    MailStoreResult.Unknown
                } else {
                    MailStoreResult.Stored(stored, missingAttachments = (payload.attachments.size - stored.attachmentCount).coerceAtLeast(0))
                }
            }
            is MailWriteOutcome.Rejected -> MailStoreResult.Rejected(outcome.failure)
            MailWriteOutcome.Unknown -> when (val listed = outbox()) {
                is MailReadOutcome.Failed -> MailStoreResult.Unknown
                is MailReadOutcome.Loaded -> {
                    val matches = listed.value.filter { it.matches(payload) }
                    when (matches.size) {
                        0 -> MailStoreResult.NotStored
                        1 -> MailStoreResult.Stored(
                            matches.single(),
                            missingAttachments = (payload.attachments.size - matches.single().attachmentCount).coerceAtLeast(0),
                        )
                        // Two identical messages: do not guess which one belongs to this attempt.
                        else -> MailStoreResult.Unknown
                    }
                }
            }
        }

    suspend fun sendNow(outboxId: Long): MailSendResult =
        when (val outcome = transport.write(mailSendOutboxRequest(outboxId), ignoreMailBody)) {
            is MailWriteOutcome.Confirmed -> MailSendResult.Sent
            is MailWriteOutcome.Rejected -> if (outcome.failure.kind == MailFailureKind.NotFound) {
                verifySent(outboxId)
            } else {
                MailSendResult.Rejected(outcome.failure)
            }
            // A failed send also answers 500. The Outbox holds the authoritative status.
            MailWriteOutcome.Unknown -> verifySent(outboxId)
        }

    suspend fun verifySent(outboxId: Long): MailSendResult = when (val listed = outbox()) {
        is MailReadOutcome.Failed -> MailSendResult.Unknown
        is MailReadOutcome.Loaded -> {
            val message = listed.value.firstOrNull { it.id == outboxId }
            when {
                message == null -> MailSendResult.Sent
                message.failed || message.status.explanation != null -> MailSendResult.Failed(message)
                else -> MailSendResult.Waiting(message)
            }
        }
    }

    suspend fun cancel(outboxId: Long): MailCancelResult =
        when (val outcome = transport.write(mailDeleteOutboxRequest(outboxId), ignoreMailBody)) {
            is MailWriteOutcome.Confirmed -> MailCancelResult.Cancelled
            is MailWriteOutcome.Rejected -> if (outcome.failure.kind == MailFailureKind.NotFound) {
                MailCancelResult.AlreadyGone
            } else {
                MailCancelResult.Rejected(outcome.failure)
            }
            MailWriteOutcome.Unknown -> when (val listed = outbox()) {
                is MailReadOutcome.Failed -> MailCancelResult.Unknown
                is MailReadOutcome.Loaded ->
                    if (listed.value.any { it.id == outboxId }) MailCancelResult.Unknown else MailCancelResult.AlreadyGone
            }
        }

    suspend fun outbox(): MailReadOutcome<List<MailOutboxMessage>> = transport.read(mailOutboxRequest(), ::parseMailOutbox)
}

/** Identifies the message stored by an attempt whose response was lost. */
internal fun MailOutboxMessage.matches(payload: MailOutgoingPayload): Boolean {
    fun List<MailRecipient>.addresses() = mapTo(mutableSetOf()) { it.email.lowercase() }
    return accountId == payload.accountId &&
        subject == payload.subject &&
        sendAtEpochSeconds == payload.sendAtEpochSeconds &&
        to.addresses() == payload.to.addresses() &&
        cc.addresses() == payload.cc.addresses() &&
        bcc.addresses() == payload.bcc.addresses()
}

/** How long a message waits in the Outbox so it can be undone. */
internal enum class MailUndoDelay(val seconds: Long, val label: String) {
    Off(0, "Send immediately"),
    Short(5, "5 seconds"),
    Medium(10, "10 seconds"),
    Long(30, "30 seconds"),
}
