package dev.obiente.nextcloudnative.nativeui.runtime

/**
 * Runs the exact read that a contract pairs with an idempotent replacement write as its result
 * recovery. Hosts implement it next to their [NativeActionExecutor]; it never runs another
 * operation, and a write without a declared recovery read is [NativeResultRecoveryRead.Unavailable].
 */
fun interface NativeResultRecoveryReader {
    suspend fun readResult(request: NativeActionRequest.Submit): NativeResultRecoveryRead
}

sealed interface NativeResultRecoveryRead {
    /** The contract declares no recovery read for this write, or this host cannot run it. */
    data object Unavailable : NativeResultRecoveryRead

    /** The read did not answer; nothing is known about the write. */
    data class Failed(val message: String) : NativeResultRecoveryRead

    /** Scalar fields of the single authoritative record the read returned; empty when it returned none or several. */
    data class Read(val fields: Map<String, String>) : NativeResultRecoveryRead
}

/** What is known about a send whose result was not confirmed. */
internal sealed interface NativeMailSendRecovery {
    /** The contract declares no status read. The user has to check their sent messages. */
    data object Unavailable : NativeMailSendRecovery

    /** The status was not read yet, for example after the app was restarted. */
    data object NotChecked : NativeMailSendRecovery

    data object Checking : NativeMailSendRecovery

    /** The status read reports the attempt as sent. The composer closes the draft. */
    data class ShownAsSent(val status: String?) : NativeMailSendRecovery

    /** The status read reports the attempt as accepted but not finished, such as queued. */
    data class InProgress(val status: String) : NativeMailSendRecovery

    /** The status read answered and does not show the attempt as sent. */
    data class NotShownAsSent(val status: String?) : NativeMailSendRecovery

    /** The status read failed. Nothing is known, so sending again stays unavailable. */
    data class CheckFailed(val message: String) : NativeMailSendRecovery
}

/**
 * Interprets the contract's recovery read for [attempt]. A declared status field decides when it
 * names a sent or in-progress state. Otherwise the read shows the message as sent only when it
 * holds exactly the body (and subject, when both declare one) that the attempt replaced.
 */
internal fun NativeMailSendCapability.recoveryVerdict(
    attempt: NativeMailComposeDraft,
    read: NativeResultRecoveryRead,
): NativeMailSendRecovery = when (read) {
    NativeResultRecoveryRead.Unavailable -> NativeMailSendRecovery.Unavailable
    is NativeResultRecoveryRead.Failed -> NativeMailSendRecovery.CheckFailed(read.message)
    is NativeResultRecoveryRead.Read -> {
        val status = read.fields.entries.firstOrNull { (key, _) -> key.nativeMailRoleKey() in STATUS_ROLES }
            ?.value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_STATUS_LENGTH }
        val statusKey = status?.nativeMailRoleKey()
        when {
            status != null && statusKey in SENT_STATUS_WORDS -> NativeMailSendRecovery.ShownAsSent(status)
            status != null && statusKey in IN_PROGRESS_STATUS_WORDS -> NativeMailSendRecovery.InProgress(status)
            status != null -> NativeMailSendRecovery.NotShownAsSent(status)
            else -> when (attemptEvidence(attempt, read.fields)) {
                NativeMailAttemptEvidence.Holds -> NativeMailSendRecovery.ShownAsSent(null)
                NativeMailAttemptEvidence.Differs -> NativeMailSendRecovery.NotShownAsSent(null)
                // The same text may belong to an earlier message to someone else.
                NativeMailAttemptEvidence.RecipientsNotShown -> NativeMailSendRecovery.Unavailable
            }
        }
    }
}

private enum class NativeMailAttemptEvidence { Holds, Differs, RecipientsNotShown }

/**
 * Compares a status-less read with the attempt. The body and subject must be the ones sent, and
 * the read must name exactly the attempt's recipients; matching text alone is not this attempt.
 */
private fun NativeMailSendCapability.attemptEvidence(
    attempt: NativeMailComposeDraft,
    fields: Map<String, String>,
): NativeMailAttemptEvidence {
    val sent = try {
        request(attempt).values
    } catch (_: IllegalArgumentException) {
        // The contract changed since the attempt; its stored state cannot be compared.
        return NativeMailAttemptEvidence.Differs
    }
    if (bodyField !in fields) return NativeMailAttemptEvidence.Differs
    if (!listOfNotNull(bodyField, subjectField).filter { it in fields }.all { fields[it] == sent[it] }) {
        return NativeMailAttemptEvidence.Differs
    }
    val shownTo = fields[to.name] ?: return NativeMailAttemptEvidence.RecipientsNotShown
    // Every recipient field the attempt filled in must be shown too; a read that leaves one out
    // could be an earlier message with the same text to fewer people.
    val unshown = listOfNotNull(cc?.let { it to attempt.cc }, bcc?.let { it to attempt.bcc }).any { (field, drafted) ->
        field.name !in fields && parseNativeMailRecipients(drafted).first.isNotEmpty()
    }
    if (unshown) return NativeMailAttemptEvidence.RecipientsNotShown
    val recipients = listOfNotNull(
        shownTo to attempt.to,
        cc?.name?.let(fields::get)?.let { it to attempt.cc },
        bcc?.name?.let(fields::get)?.let { it to attempt.bcc },
    )
    val same = recipients.all { (shown, drafted) ->
        shown.mailAddresses() == parseNativeMailRecipients(drafted).first.mapTo(hashSetOf()) { it.email.lowercase() }
    }
    return if (same) NativeMailAttemptEvidence.Holds else NativeMailAttemptEvidence.Differs
}

/** Every address in a stored recipient value, whatever list or object encoding it uses. */
private fun String.mailAddresses(): Set<String> =
    split(*RECIPIENT_VALUE_SEPARATORS).map(String::trim).filter(::isPlausibleNativeMailAddress).mapTo(hashSetOf(), String::lowercase)

private val RECIPIENT_VALUE_SEPARATORS = charArrayOf(',', ';', '\n', ' ', '"', '[', ']', '{', '}', '<', '>')

/** User-facing explanation of this state, shown in the composer's status banner. */
internal fun NativeMailSendRecovery.description(): String = when (this) {
    NativeMailSendRecovery.Unavailable -> UNKNOWN_SEND_MESSAGE
    NativeMailSendRecovery.NotChecked -> "The last attempt to send this message was not confirmed. Check its status before sending it again."
    NativeMailSendRecovery.Checking -> "The server did not confirm the message. Checking whether it was sent..."
    is NativeMailSendRecovery.ShownAsSent -> "The app reports this message as sent."
    is NativeMailSendRecovery.InProgress ->
        "The app reports this message as \"$status\". Sending it again could deliver it twice."
    is NativeMailSendRecovery.NotShownAsSent ->
        "The app's status does not show this message as sent" + (status?.let { " (\"$it\")" } ?: "") +
            ". You can send it again."
    is NativeMailSendRecovery.CheckFailed -> "The server did not confirm the message, and its status could not be read. Check again before sending it again."
}

internal const val UNKNOWN_SEND_MESSAGE =
    "The server did not confirm whether this message was sent. Check your sent messages before sending it again."

private val STATUS_ROLES = setOf("status", "state", "sendstatus", "deliverystatus", "sendingstatus")
private val SENT_STATUS_WORDS = setOf("sent", "delivered", "done", "completed", "complete", "success", "succeeded")
private val IN_PROGRESS_STATUS_WORDS = setOf("queued", "pending", "sending", "scheduled", "processing", "inprogress")
private const val MAX_STATUS_LENGTH = 64
