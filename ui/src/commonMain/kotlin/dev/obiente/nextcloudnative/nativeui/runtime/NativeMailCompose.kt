package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One mail address with an optional display label. */
internal data class NativeMailAddress(
    val email: String,
    val label: String? = null,
) {
    /** RFC 5322 style form used in editable recipient text and quote headers. */
    val headerForm: String
        get() = label?.takeIf(String::isNotBlank)?.let { "$it <$email>" } ?: email
}

internal enum class NativeMailComposeMode {
    New,
    Reply,
    ReplyAll,
    Forward,
}

/** The quoted original of a reply or forward, kept apart from what the user writes. */
internal data class NativeMailQuote(
    val header: String,
    val text: String,
    val forward: Boolean,
)

/**
 * Everything the semantic composer edits.
 *
 * Recipient fields keep the text the user typed, so an entry that is not an address yet stays
 * visible instead of disappearing. [inReplyTo] is the original Message-ID header and [references]
 * the original's References chain followed by that Message-ID; each is only sent when the
 * installed app's send operation declares the matching threading field.
 */
internal data class NativeMailComposeDraft(
    val mode: NativeMailComposeMode,
    val senderId: String?,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val quote: NativeMailQuote? = null,
    val includeQuote: Boolean = true,
    val inReplyTo: String? = null,
    val references: String? = null,
) {
    val hasContent: Boolean
        get() = to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank() || subject.isNotBlank() || body.isNotBlank()
}

/** Semantic view of a message that a reply or forward starts from. */
internal data class NativeMailOriginal(
    val subject: String,
    val from: List<NativeMailAddress>,
    val to: List<NativeMailAddress>,
    val cc: List<NativeMailAddress>,
    val replyTo: List<NativeMailAddress>,
    val messageIdHeader: String?,
    /** Message-IDs of the original's own References header, oldest first. */
    val references: List<String> = emptyList(),
    val dateLabel: String?,
    val bodyText: String,
)

/**
 * Reads the reply-relevant roles of a message record from its field names and values only. Sender,
 * recipients, and Reply-To accept structured address objects, JSON-encoded address lists, or
 * `Name <address>` text. Values that are not plausible addresses are dropped, never guessed.
 */
internal fun nativeMailOriginal(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeMailOriginal? {
    val presentation = nativeMailMessageDetailPresentation(resource, record) ?: return null
    val body = presentation.body.orEmpty()
    return NativeMailOriginal(
        subject = presentation.subject,
        from = record.nativeMailAddresses("from", "sender", "fromemail", "author"),
        to = record.nativeMailAddresses("to", "recipients", "recipient"),
        cc = record.nativeMailAddresses("cc"),
        replyTo = record.nativeMailAddresses("replyto"),
        messageIdHeader = record.semanticScalar("messageid", "messageidheader", "internetmessageid")
            ?.trim()
            ?.takeIf { value -> value.length <= MAX_MESSAGE_ID_LENGTH && value.matches(MESSAGE_ID_HEADER) },
        references = record.semanticScalar("references", "referencesheader")
            ?.takeIf { it.length <= MAX_REFERENCES_FIELD_LENGTH }
            ?.let { raw -> MESSAGE_ID_IN_TEXT.findAll(raw).map { it.value }.filter { it.length <= MAX_MESSAGE_ID_LENGTH }.toList() }
            .orEmpty(),
        dateLabel = presentation.timestamp,
        bodyText = if (presentation.htmlBody || body.contains('<') && body.contains('>')) {
            emailBodyToPlainText(body)
        } else {
            body
        }.take(MAX_NATIVE_MAIL_QUOTE_CHARACTERS),
    )
}

/**
 * Builds a reply, reply-all, or forward of [original].
 *
 * Replies go to Reply-To when the sender set one. Reply-all adds the other recipients but never
 * the sending identity's own addresses. A message sent by the user replies to its recipients.
 */
internal fun nativeMailResponseDraft(
    mode: NativeMailComposeMode,
    original: NativeMailOriginal,
    senderId: String?,
    ownAddresses: Set<String>,
): NativeMailComposeDraft {
    require(mode != NativeMailComposeMode.New) { "Only replies and forwards start from a message." }
    val own = ownAddresses.mapTo(mutableSetOf(), String::lowercase)
    val sentByMe = original.from.any { it.email.lowercase() in own }
    val primary = when {
        sentByMe -> original.to
        original.replyTo.isNotEmpty() -> original.replyTo
        else -> original.from
    }
    val (to, cc) = when (mode) {
        NativeMailComposeMode.Reply -> primary.withoutOwn(own, keepIfOnlyOwn = sentByMe) to emptyList()
        NativeMailComposeMode.ReplyAll -> {
            val replyTo = (primary + if (sentByMe) emptyList() else original.to)
                .withoutOwn(own, keepIfOnlyOwn = sentByMe)
            replyTo to original.cc.withoutOwn(own).withoutAddresses(replyTo)
        }
        else -> emptyList<NativeMailAddress>() to emptyList()
    }
    val forward = mode == NativeMailComposeMode.Forward
    return NativeMailComposeDraft(
        mode = mode,
        senderId = senderId,
        to = to.joinToString(", ", transform = NativeMailAddress::headerForm),
        cc = cc.joinToString(", ", transform = NativeMailAddress::headerForm),
        subject = if (forward) {
            original.subject.withNativeMailSubjectPrefix("Fwd:", FORWARD_PREFIXES)
        } else {
            original.subject.withNativeMailSubjectPrefix("Re:", REPLY_PREFIXES)
        },
        quote = original.toQuote(forward),
        inReplyTo = original.messageIdHeader.takeUnless { forward },
        references = original.messageIdHeader?.takeUnless { forward }?.let { id -> nativeMailReferences(original.references, id) },
    )
}

/**
 * The References header of a reply: the original's chain followed by its Message-ID, without
 * duplicates. Long chains keep the first (thread root) and the most recent identifiers, as RFC 5322
 * clients conventionally trim them.
 */
internal fun nativeMailReferences(chain: List<String>, messageId: String): String {
    val ids = (chain + messageId).distinct()
    val kept = if (ids.size <= MAX_REFERENCES) ids else listOf(ids.first()) + ids.takeLast(MAX_REFERENCES - 1)
    return kept.joinToString(" ")
}

internal fun String.withNativeMailSubjectPrefix(prefix: String, existing: Set<String>): String {
    val trimmed = trim()
    val lead = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase().trim()
    if (lead in existing) return trimmed
    return if (trimmed.isEmpty()) prefix else "$prefix $trimmed"
}

/**
 * Plain-text body of the draft. A reply quotes each original line with `> `; a forward keeps its
 * header block unquoted, as mail clients conventionally do.
 */
internal fun nativeMailComposedBody(draft: NativeMailComposeDraft): String {
    val quote = draft.quote?.takeIf { draft.includeQuote }
    val quoted = quote?.let { q ->
        if (q.forward) {
            "${q.header}\n\n${q.text}"
        } else {
            "${q.header}\n" + q.text.lines().joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }
        }
    }
    return listOfNotNull(draft.body.trimEnd(), quoted).filter(String::isNotBlank).joinToString("\n\n")
}

/**
 * Splits typed recipient text such as `Ada <ada@example.test>, bob@example.test`. Entries that are
 * not addresses are returned separately so the composer can name them.
 */
internal fun parseNativeMailRecipients(input: String): Pair<List<NativeMailAddress>, List<String>> {
    val valid = mutableListOf<NativeMailAddress>()
    val invalid = mutableListOf<String>()
    input.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty).forEach { entry ->
        val bracket = ADDRESS_WITH_LABEL.find(entry)
        val label = bracket?.groupValues?.get(1)?.trim()?.trim('"')?.takeIf(String::isNotEmpty)
        val email = (bracket?.groupValues?.get(2) ?: entry).trim()
        if (isPlausibleNativeMailAddress(email)) valid += NativeMailAddress(email, label) else invalid += entry
    }
    return valid.distinctBy { it.email.lowercase() } to invalid
}

/** A conservative syntactic check; the server remains the authority on deliverability. */
internal fun isPlausibleNativeMailAddress(value: String): Boolean {
    if (value.length > MAX_ADDRESS_LENGTH || value.any { it.isWhitespace() || it.isISOControl() }) return false
    val at = value.lastIndexOf('@')
    if (at <= 0 || at == value.lastIndex) return false
    val local = value.substring(0, at)
    val domain = value.substring(at + 1)
    return local.length <= 64 &&
        local.none { it in "<>()[]\\,;:\"" } &&
        domain.contains('.') &&
        !domain.startsWith('.') &&
        !domain.endsWith('.') &&
        domain.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.all { it.isLetterOrDigit() || it == '-' }
        }
}

private fun NativeMailOriginal.toQuote(forward: Boolean): NativeMailQuote {
    val sender = from.firstOrNull()
    val header = if (forward) {
        buildString {
            appendLine("---------- Forwarded message ----------")
            sender?.let { appendLine("From: ${it.headerForm}") }
            dateLabel?.let { appendLine("Date: $it") }
            appendLine("Subject: $subject")
            if (to.isNotEmpty()) appendLine("To: ${to.joinToString(", ", transform = NativeMailAddress::headerForm)}")
            if (cc.isNotEmpty()) appendLine("Cc: ${cc.joinToString(", ", transform = NativeMailAddress::headerForm)}")
        }.trimEnd()
    } else {
        val who = sender?.label?.takeIf(String::isNotBlank) ?: sender?.email ?: "the sender"
        if (dateLabel != null) "On $dateLabel, $who wrote:" else "$who wrote:"
    }
    return NativeMailQuote(header = header, text = bodyText, forward = forward)
}

private fun List<NativeMailAddress>.withoutOwn(own: Set<String>, keepIfOnlyOwn: Boolean = false): List<NativeMailAddress> {
    val unique = distinctBy { it.email.lowercase() }
    val others = unique.filterNot { it.email.lowercase() in own }
    return if (others.isEmpty() && keepIfOnlyOwn) unique else others
}

private fun List<NativeMailAddress>.withoutAddresses(other: List<NativeMailAddress>): List<NativeMailAddress> {
    val taken = other.mapTo(mutableSetOf()) { it.email.lowercase() }
    return filterNot { it.email.lowercase() in taken }
}

internal fun NativeRecord.nativeMailAddresses(vararg roles: String): List<NativeMailAddress> {
    val wanted = roles.map(String::nativeMailRoleKey)
    val structured = structuredValues.entries.firstOrNull { (key, _) -> key.nativeMailRoleKey() in wanted }?.value
    if (structured != null) return structured.toNativeMailAddresses().take(MAX_ADDRESSES_PER_ROLE)
    val raw = (values.entries.firstOrNull { (key, value) -> key.nativeMailRoleKey() in wanted && value != null }?.value
        ?: displayValues.entries.firstOrNull { (key, _) -> key.nativeMailRoleKey() in wanted }?.value)
        ?.takeIf { it.length <= MAX_ADDRESS_FIELD_LENGTH }
        ?: return emptyList()
    val trimmed = raw.trim()
    if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
        val parsed = try {
            Json.parseToJsonElement(trimmed)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (parsed != null) return parsed.toNativeMailAddresses().take(MAX_ADDRESSES_PER_ROLE)
    }
    return parseNativeMailRecipients(trimmed).first.take(MAX_ADDRESSES_PER_ROLE)
}

private fun NativeRecord.semanticScalar(vararg roles: String): String? {
    val wanted = roles.map(String::nativeMailRoleKey)
    return values.entries.firstOrNull { (key, value) -> key.nativeMailRoleKey() in wanted && !value.isNullOrBlank() }?.value
        ?: displayValues.entries.firstOrNull { (key, value) -> key.nativeMailRoleKey() in wanted && value.isNotBlank() }?.value
}

private fun NativeStructuredValue.toNativeMailAddresses(): List<NativeMailAddress> = when (this) {
    is NativeStructuredValue.Scalar -> value?.let { parseNativeMailRecipients(it).first }.orEmpty()
    is NativeStructuredValue.ListValue -> items.flatMap { it.toNativeMailAddresses() }
    is NativeStructuredValue.ObjectValue -> {
        fun scalar(vararg keys: String): String? = entries.firstOrNull { entry ->
            entry.key.nativeMailRoleKey() in keys
        }?.value?.let { (it as? NativeStructuredValue.Scalar)?.value }?.trim()?.takeIf(String::isNotBlank)
        val email = scalar("email", "address", "mail", "emailaddress")
        val label = scalar("label", "name", "displayname", "personal")
        listOfNotNull(email?.takeIf(::isPlausibleNativeMailAddress)?.let { NativeMailAddress(it, label?.takeUnless { l -> l == it }) })
    }
}

private fun JsonElement.toNativeMailAddresses(): List<NativeMailAddress> = when (this) {
    is JsonArray -> flatMap { it.toNativeMailAddresses() }
    is JsonObject -> {
        fun scalar(vararg keys: String): String? = entries.firstOrNull { (key, _) -> key.nativeMailRoleKey() in keys }
            ?.value?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim()?.takeIf(String::isNotBlank)
        val email = scalar("email", "address", "mail", "emailaddress")
        val label = scalar("label", "name", "displayname", "personal")
        listOfNotNull(email?.takeIf(::isPlausibleNativeMailAddress)?.let { NativeMailAddress(it, label?.takeUnless { l -> l == it }) })
    }
    is JsonPrimitive -> contentOrNull?.let { parseNativeMailRecipients(it).first }.orEmpty()
}

internal fun String.nativeMailRoleKey(): String = lowercase().filter(Char::isLetterOrDigit)

private val ADDRESS_WITH_LABEL = Regex("^(.*)<([^<>]+)>$")
private val MESSAGE_ID_HEADER = Regex("^<[^<>\\s]+@[^<>\\s]+>$")
private val MESSAGE_ID_IN_TEXT = Regex("<[^<>\\s]+@[^<>\\s]+>")
private val REPLY_PREFIXES = setOf("re", "aw", "sv", "antw")
private val FORWARD_PREFIXES = setOf("fwd", "fw", "wg", "tr")
private const val MAX_ADDRESS_LENGTH = 254
private const val MAX_ADDRESS_FIELD_LENGTH = 64_000
private const val MAX_ADDRESSES_PER_ROLE = 200
private const val MAX_MESSAGE_ID_LENGTH = 998
private const val MAX_REFERENCES = 20
private const val MAX_REFERENCES_FIELD_LENGTH = 64_000
internal const val MAX_NATIVE_MAIL_QUOTE_CHARACTERS = 200_000
