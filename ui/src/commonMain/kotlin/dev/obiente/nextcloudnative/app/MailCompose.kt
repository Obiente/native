package dev.obiente.nextcloudnative.app

import com.fleeksoft.ksoup.Ksoup
import dev.obiente.nextcloudnative.nativeui.runtime.emailBodyToPlainText
import dev.obiente.nextcloudnative.nativeui.runtime.sanitizeNativeMailHtml
import kotlin.random.Random
import kotlin.time.Instant

internal enum class MailComposeMode {
    New,
    Reply,
    ReplyAll,
    Forward,
    EditDraft,
}

/** An attachment chosen in the composer. Each kind maps to one reviewed Mail attachment type. */
internal sealed interface MailComposeAttachment {
    val fileName: String
    val key: String

    data class Uploaded(val attachmentId: Long, override val fileName: String) : MailComposeAttachment {
        override val key: String get() = "local:$attachmentId"
    }

    data class FromFiles(val path: String, override val fileName: String) : MailComposeAttachment {
        override val key: String get() = "cloud:$path"
    }

    data class Forwarded(
        val mailboxId: Long,
        val messageUid: Long,
        val partId: String,
        override val fileName: String,
    ) : MailComposeAttachment {
        override val key: String get() = "forward:$mailboxId:$messageUid:$partId"
    }
}

/** The quoted original of a reply or forward, kept apart from what the user writes. */
internal data class MailQuote(
    val header: String,
    val text: String,
    /** Sanitized original markup when the original was HTML. */
    val html: String?,
    val forward: Boolean,
)

/**
 * Everything the composer edits. [slotId] is a random local identity for the draft store, never
 * derived from an address, subject, or server URL. [outboxMessageId] is recovery state: once set,
 * the message is held in the server Outbox and must not be stored again.
 */
internal data class MailComposeDraft(
    val slotId: String,
    val mode: MailComposeMode,
    val accountId: Long,
    val aliasId: Long?,
    val to: List<MailRecipient>,
    val cc: List<MailRecipient>,
    val bcc: List<MailRecipient>,
    val subject: String,
    val body: String,
    val rich: Boolean,
    val quote: MailQuote?,
    val includeQuote: Boolean = true,
    val includeSignature: Boolean = true,
    val inReplyToMessageId: String?,
    val replacesDraftMessageId: Long?,
    val attachments: List<MailComposeAttachment>,
    val outboxMessageId: Long? = null,
    /**
     * Send time of a store request whose outcome is not known yet. It is written before the
     * request leaves, so after a lost response or a restart the Outbox can be checked for exactly
     * this message instead of storing it twice.
     */
    val storeAttemptSendAt: Long? = null,
) {
    val recipients: List<MailRecipient> get() = to + cc + bcc

    val hasContent: Boolean
        get() = recipients.isNotEmpty() || subject.isNotBlank() || body.isNotBlank() || attachments.isNotEmpty()
}

internal fun newMailComposeSlotId(random: Random = Random.Default): String =
    buildString { repeat(24) { append(MAIL_SLOT_ALPHABET[random.nextInt(MAIL_SLOT_ALPHABET.length)]) } }

internal fun newMailCompose(account: MailAccount, slotId: String = newMailComposeSlotId()): MailComposeDraft = MailComposeDraft(
    slotId = slotId,
    mode = MailComposeMode.New,
    accountId = account.id,
    aliasId = null,
    to = emptyList(),
    cc = emptyList(),
    bcc = emptyList(),
    subject = "",
    body = "",
    rich = account.editorMode == MailEditorMode.Rich,
    quote = null,
    inReplyToMessageId = null,
    replacesDraftMessageId = null,
    attachments = emptyList(),
)

/**
 * Builds a reply, reply-all, or forward of [original].
 *
 * Replies thread through the original Message-ID; Mail sets both `In-Reply-To` and `References`
 * from it. Replies go to Reply-To when the sender set one. Reply-all adds the other recipients
 * but never this account's own addresses. Forwards carry the original attachments by reference,
 * so the server copies them without downloading them to the device.
 */
internal fun mailResponseCompose(
    mode: MailComposeMode,
    original: MailMessageDetail,
    account: MailAccount,
    slotId: String = newMailComposeSlotId(),
    formatDate: (Long) -> String = ::mailQuoteDate,
): MailComposeDraft {
    require(mode == MailComposeMode.Reply || mode == MailComposeMode.ReplyAll || mode == MailComposeMode.Forward) {
        "Only replies and forwards start from a message."
    }
    val own = account.ownAddresses()
    val summary = original.summary
    val sentByMe = summary.from.any { it.email.lowercase() in own }
    val primary = when {
        sentByMe -> summary.to
        original.replyTo.isNotEmpty() -> original.replyTo
        else -> summary.from
    }
    val (to, cc) = when (mode) {
        MailComposeMode.Reply -> primary.withoutOwn(own, keepIfOnlyOwn = sentByMe) to emptyList()
        MailComposeMode.ReplyAll -> {
            val replyTo = (primary + if (sentByMe) emptyList() else summary.to).withoutOwn(own, keepIfOnlyOwn = sentByMe)
            replyTo to summary.cc.withoutOwn(own).withoutAddresses(replyTo)
        }
        else -> emptyList<MailRecipient>() to emptyList()
    }
    val alias = account.aliases.firstOrNull { alias ->
        (summary.to + summary.cc).any { it.email.equals(alias.email, ignoreCase = true) }
    }
    val forward = mode == MailComposeMode.Forward
    return MailComposeDraft(
        slotId = slotId,
        mode = mode,
        accountId = account.id,
        aliasId = alias?.id,
        to = to,
        cc = cc,
        bcc = emptyList(),
        subject = if (forward) summary.subject.withSubjectPrefix("Fwd:", MAIL_FORWARD_PREFIXES) else summary.subject.withSubjectPrefix("Re:", MAIL_REPLY_PREFIXES),
        body = "",
        rich = account.editorMode == MailEditorMode.Rich,
        quote = original.toQuote(forward, formatDate),
        inReplyToMessageId = summary.messageIdHeader.takeUnless { forward },
        replacesDraftMessageId = null,
        attachments = if (forward) original.forwardableAttachments() else emptyList(),
    )
}

/** Opens a message from the Drafts folder for editing; sending it replaces that draft. */
internal fun mailEditDraftCompose(
    original: MailMessageDetail,
    account: MailAccount,
    slotId: String = newMailComposeSlotId(),
): MailComposeDraft {
    val summary = original.summary
    val fromAlias = account.aliases.firstOrNull { alias -> summary.from.any { it.email.equals(alias.email, ignoreCase = true) } }
    val html = original.bodyHtml
    return MailComposeDraft(
        slotId = slotId,
        mode = MailComposeMode.EditDraft,
        accountId = account.id,
        aliasId = fromAlias?.id,
        to = summary.to,
        cc = summary.cc,
        bcc = original.bcc,
        subject = summary.subject,
        body = if (html != null) sanitizeNativeMailHtml(html) else original.bodyText?.let(::emailBodyToPlainText).orEmpty(),
        rich = html != null,
        quote = null,
        inReplyToMessageId = null,
        replacesDraftMessageId = original.id,
        attachments = original.forwardableAttachments(),
    )
}

internal fun MailAccount.ownAddresses(): Set<String> =
    (listOf(email) + aliases.map(MailAlias::email)).mapTo(mutableSetOf()) { it.lowercase() }

private fun List<MailRecipient>.withoutOwn(own: Set<String>, keepIfOnlyOwn: Boolean = false): List<MailRecipient> {
    val unique = distinctBy { it.email.lowercase() }
    val others = unique.filterNot { it.email.lowercase() in own }
    return if (others.isEmpty() && keepIfOnlyOwn) unique else others
}

private fun List<MailRecipient>.withoutAddresses(other: List<MailRecipient>): List<MailRecipient> {
    val taken = other.mapTo(mutableSetOf()) { it.email.lowercase() }
    return filterNot { it.email.lowercase() in taken }
}

internal fun String.withSubjectPrefix(prefix: String, existing: Set<String>): String {
    val trimmed = trim()
    val lead = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase().trim()
    if (lead in existing) return trimmed
    return if (trimmed.isEmpty()) prefix else "$prefix $trimmed"
}

private fun MailMessageDetail.forwardableAttachments(): List<MailComposeAttachment> {
    val uid = summary.uid ?: return emptyList()
    return attachments.map { attachment ->
        MailComposeAttachment.Forwarded(summary.mailboxId, uid, attachment.id, attachment.fileName)
    }
}

private fun MailMessageDetail.toQuote(forward: Boolean, formatDate: (Long) -> String): MailQuote {
    val summary = summary
    val sender = summary.from.firstOrNull()
    val date = summary.sentAtEpochSeconds?.let(formatDate)
    val header = if (forward) {
        buildString {
            appendLine("---------- Forwarded message ----------")
            sender?.let { appendLine("From: ${it.headerForm()}") }
            date?.let { appendLine("Date: $it") }
            appendLine("Subject: ${summary.subject}")
            if (summary.to.isNotEmpty()) appendLine("To: ${summary.to.joinToString(", ") { it.headerForm() }}")
            if (summary.cc.isNotEmpty()) appendLine("Cc: ${summary.cc.joinToString(", ") { it.headerForm() }}")
        }.trimEnd()
    } else {
        val who = sender?.displayName ?: "the sender"
        if (date != null) "On $date, $who wrote:" else "$who wrote:"
    }
    val text = bodyHtml?.let(::emailBodyToPlainText) ?: bodyText?.let(::emailBodyToPlainText).orEmpty()
    return MailQuote(
        header = header,
        text = text.take(MAX_MAIL_QUOTE_CHARACTERS),
        html = bodyHtml?.let(::sanitizeNativeMailHtml)?.take(MAX_MAIL_QUOTE_CHARACTERS),
        forward = forward,
    )
}

private fun MailRecipient.headerForm(): String =
    label?.takeIf(String::isNotBlank)?.let { "$it <$email>" } ?: email

/** Wall-clock date of the original for a quote header, in the device time zone. */
internal fun mailQuoteDate(epochSeconds: Long): String =
    deckInstantToLocalDateTime(Instant.fromEpochSeconds(epochSeconds).toString())
        ?.let { "${it.date} ${it.time}" }
        ?: Instant.fromEpochSeconds(epochSeconds).toString().take(16).replace('T', ' ')

/** The bodies Mail stores: plain text always, HTML when the composer is in rich mode. */
internal data class MailComposedBody(
    val plain: String,
    val html: String?,
)

/**
 * Assembles the final body from what the user wrote, the identity's signature, and the quote.
 *
 * Plain text quotes each original line with `> `; a forward keeps its header block unquoted, as
 * mail clients conventionally do. The signature uses the standard `-- ` delimiter and goes above
 * or below the quote as the account prefers.
 */
internal fun composeMailBody(
    draft: MailComposeDraft,
    signature: String?,
    signatureAboveQuote: Boolean,
): MailComposedBody {
    val quote = draft.quote?.takeIf { draft.includeQuote }
    val sign = signature?.takeIf { draft.includeSignature && it.isNotBlank() }
    val userText = if (draft.rich) emailBodyToPlainText(draft.body) else draft.body.trimEnd()
    val plainSignature = sign?.let { "-- \n${emailBodyToPlainText(it).trimEnd()}" }
    val plainQuote = quote?.let { q ->
        if (q.forward) {
            "${q.header}\n\n${q.text}"
        } else {
            "${q.header}\n" + q.text.lines().joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }
        }
    }
    val plain = mailBodySections(userText, plainSignature, plainQuote, signatureAboveQuote).joinToString("\n\n")
    if (!draft.rich) return MailComposedBody(plain = plain, html = null)

    val htmlSignature = sign?.let { "<div class=\"signature\">-- <br>${it.signatureAsHtml()}</div>" }
    val htmlQuote = quote?.let { q ->
        val original = q.html ?: q.text.escapeMailHtml().replace("\n", "<br>")
        val header = q.header.escapeMailHtml().replace("\n", "<br>")
        if (q.forward) {
            "<div>$header</div><div>$original</div>"
        } else {
            "<div>$header</div><blockquote type=\"cite\">$original</blockquote>"
        }
    }
    val html = mailBodySections(sanitizeNativeMailHtml(draft.body), htmlSignature, htmlQuote, signatureAboveQuote)
        .joinToString("<br>")
    return MailComposedBody(plain = plain, html = "<html><body>$html</body></html>")
}

private fun mailBodySections(
    text: String,
    signature: String?,
    quote: String?,
    signatureAboveQuote: Boolean,
): List<String> = buildList {
    add(text)
    if (signatureAboveQuote) {
        signature?.let(::add)
        quote?.let(::add)
    } else {
        quote?.let(::add)
        signature?.let(::add)
    }
}.filter(String::isNotBlank)

/** Signatures may be stored as HTML by the web editor or as plain text. */
private fun String.signatureAsHtml(): String =
    if (Ksoup.parse(this).body().children().isNotEmpty()) sanitizeNativeMailHtml(this) else escapeMailHtml().replace("\n", "<br>")

internal fun String.escapeMailHtml(): String = buildString(length) {
    this@escapeMailHtml.forEach { char ->
        when (char) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}

/** Every reason the draft cannot be sent yet, in display order. Empty means it can be sent. */
internal fun mailComposeProblems(draft: MailComposeDraft, account: MailAccount?): List<String> = buildList {
    if (account == null || account.id != draft.accountId) add("Choose the account to send from.")
    if (draft.aliasId != null && account?.aliases?.none { it.id == draft.aliasId } == true) {
        add("The selected sending address is no longer available.")
    }
    if (draft.to.isEmpty()) add("Add at least one recipient.")
    draft.recipients.firstOrNull { !isPlausibleMailAddress(it.email) }?.let { add("\"${it.email}\" is not a valid address.") }
    if (draft.recipients.size > MAX_MAIL_RECIPIENTS) add("There are too many recipients.")
    if (draft.attachments.size > MAX_MAIL_ATTACHMENTS) add("There are too many attachments.")
    if (draft.subject.length > MAX_MAIL_SUBJECT) add("The subject is too long.")
}

/**
 * Splits typed recipient text such as `Ada <ada@example.test>, bob@example.test` into
 * recipients. Entries that are not addresses are returned separately so the field can keep them.
 */
internal fun parseMailRecipientInput(input: String): Pair<List<MailRecipient>, List<String>> {
    val valid = mutableListOf<MailRecipient>()
    val invalid = mutableListOf<String>()
    input.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty).forEach { entry ->
        val bracket = Regex("^(.*)<([^<>]+)>$").find(entry)
        val label = bracket?.groupValues?.get(1)?.trim()?.trim('"')?.takeIf(String::isNotEmpty)
        val email = (bracket?.groupValues?.get(2) ?: entry).trim()
        if (isPlausibleMailAddress(email)) valid += MailRecipient(email, label) else invalid += entry
    }
    return valid.distinctBy { it.email.lowercase() } to invalid
}

internal fun MailComposeDraft.toOutgoingPayload(
    account: MailAccount,
    sendAtEpochSeconds: Long?,
): MailOutgoingPayload {
    val alias = aliasId?.let { id -> account.aliases.firstOrNull { it.id == id } }
    val body = composeMailBody(
        draft = this,
        signature = alias?.signature ?: account.signature,
        signatureAboveQuote = account.signatureAboveQuote,
    )
    return MailOutgoingPayload(
        accountId = account.id,
        aliasId = alias?.id,
        subject = subject.trim(),
        bodyPlain = body.plain,
        bodyHtml = body.html,
        to = to,
        cc = cc,
        bcc = bcc,
        attachments = attachments.map { attachment ->
            when (attachment) {
                is MailComposeAttachment.Uploaded -> MailOutgoingAttachment.Local(attachment.attachmentId)
                is MailComposeAttachment.FromFiles -> MailOutgoingAttachment.Cloud(attachment.path)
                is MailComposeAttachment.Forwarded -> MailOutgoingAttachment.FromMessage(
                    attachment.mailboxId,
                    attachment.messageUid,
                    attachment.partId,
                    attachment.fileName,
                )
            }
        },
        inReplyToMessageId = inReplyToMessageId,
        sendAtEpochSeconds = sendAtEpochSeconds,
        replacesDraftMessageId = replacesDraftMessageId,
    )
}

private val MAIL_REPLY_PREFIXES = setOf("re", "aw", "sv", "antw")
private val MAIL_FORWARD_PREFIXES = setOf("fwd", "fw", "wg", "tr")
private const val MAIL_SLOT_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val MAX_MAIL_QUOTE_CHARACTERS = 200_000
