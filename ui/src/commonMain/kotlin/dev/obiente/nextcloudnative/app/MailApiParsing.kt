package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Boundary parsers for the reviewed Mail routes. Each parser reads only the fields the native
 * workflow needs and validates them into typed values. Account responses also carry IMAP and
 * SMTP host and user names; those fields are deliberately never read.
 */

internal class MailProtocolException(message: String) : IllegalStateException(message)

private val mailJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

internal fun parseMailAccounts(body: ByteArray): List<MailAccount> =
    mailRootArray(body).mapNotNull { (it as? JsonObject)?.toMailAccount() }.distinctBy(MailAccount::id)

private fun JsonObject.toMailAccount(): MailAccount? {
    val id = positiveLong("accountId") ?: positiveLong("id") ?: return null
    val email = text("emailAddress")?.takeIf(::isPlausibleMailAddress) ?: return null
    return MailAccount(
        id = id,
        name = text("name") ?: email,
        email = email,
        signature = rawText("signature", MAX_MAIL_SIGNATURE),
        signatureAboveQuote = boolean("signatureAboveQuote"),
        editorMode = MailEditorMode.fromWire(text("editorMode")),
        draftsMailboxId = positiveLong("draftsMailboxId"),
        sentMailboxId = positiveLong("sentMailboxId"),
        trashMailboxId = positiveLong("trashMailboxId"),
        archiveMailboxId = positiveLong("archiveMailboxId"),
        junkMailboxId = positiveLong("junkMailboxId"),
        aliases = (this["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toMailAlias() },
        delegated = boolean("isDelegated"),
    )
}

internal fun parseMailAlias(body: ByteArray): MailAlias =
    (mailRoot(body) as? JsonObject)?.toMailAlias() ?: throw MailProtocolException("Mail did not return the alias.")

private fun JsonObject.toMailAlias(): MailAlias? {
    val id = positiveLong("id") ?: return null
    val email = (text("alias") ?: text("email"))?.takeIf(::isPlausibleMailAddress) ?: return null
    return MailAlias(
        id = id,
        email = email,
        name = text("name"),
        signature = rawText("signature", MAX_MAIL_SIGNATURE),
        provisioned = boolean("provisioned"),
    )
}

internal fun parseMailMailboxes(body: ByteArray, accountId: Long): List<MailMailbox> {
    val root = mailRoot(body) as? JsonObject ?: throw MailProtocolException("Mail did not list the folders.")
    val delimiter = root.text("delimiter")
    val mailboxes = root["mailboxes"] as? JsonArray ?: throw MailProtocolException("Mail did not list the folders.")
    return mailboxes.mapNotNull { element -> (element as? JsonObject)?.toMailMailbox(accountId, delimiter) }
        .distinctBy(MailMailbox::id)
}

internal fun parseMailMailbox(body: ByteArray, accountId: Long): MailMailbox =
    (mailRoot(body) as? JsonObject)?.toMailMailbox(accountId, null)
        ?: throw MailProtocolException("Mail did not return the folder.")

private fun JsonObject.toMailMailbox(expectedAccountId: Long, fallbackDelimiter: String?): MailMailbox? {
    val id = positiveLong("databaseId") ?: return null
    val accountId = positiveLong("accountId") ?: expectedAccountId
    // A folder of another account must never appear inside this account's tree.
    if (accountId != expectedAccountId) return null
    val name = text("name") ?: return null
    return MailMailbox(
        id = id,
        accountId = accountId,
        name = name,
        delimiter = (this["delimiter"] as? JsonPrimitive)?.contentOrNull?.take(4) ?: fallbackDelimiter,
        specialUse = (this["specialUse"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.removePrefix("\\")?.take(32) }
            .toSet(),
        unread = nonNegativeInt("unread"),
        rights = MailMailboxRights((this["myAcls"] as? JsonPrimitive)?.contentOrNull?.take(32)),
        shared = boolean("shared"),
    )
}

internal fun parseMailMessagePage(body: ByteArray, mailboxId: Long, requestedLimit: Int): MailMessagePage {
    val elements = mailRootArray(body)
    val messages = elements.mapNotNull { (it as? JsonObject)?.toMailSummary(mailboxId) }.distinctBy(MailMessageSummary::id)
    return MailMessagePage(messages = messages, endReached = elements.size < requestedLimit)
}

private fun JsonObject.toMailSummary(expectedMailboxId: Long?): MailMessageSummary? {
    val id = positiveLong("databaseId") ?: return null
    val mailboxId = positiveLong("mailboxId") ?: expectedMailboxId ?: return null
    if (expectedMailboxId != null && mailboxId != expectedMailboxId) return null
    val flags = this["flags"] as? JsonObject
    return MailMessageSummary(
        id = id,
        uid = positiveLong("uid"),
        mailboxId = mailboxId,
        subject = text("subject").orEmpty(),
        from = recipients("from"),
        to = recipients("to"),
        cc = recipients("cc"),
        sentAtEpochSeconds = positiveLong("dateInt"),
        flags = MailMessageFlags(
            seen = flags.flag("seen"),
            flagged = flags.flag("flagged"),
            answered = flags.flag("answered"),
            forwarded = flags.flag("forwarded"),
            draft = flags.flag("draft"),
            hasAttachments = flags.flag("hasAttachments"),
            important = flags.flag("important"),
            junk = flags.flag("\$junk"),
        ),
        tags = parseMailTags(this["tags"]),
        previewText = text("previewText")?.take(MAX_MAIL_PREVIEW_CHARACTERS),
        messageIdHeader = text("messageId")?.take(MAX_MAIL_HEADER_CHARACTERS),
        threadRootId = text("threadRootId")?.take(MAX_MAIL_HEADER_CHARACTERS),
    )
}

/** Mail sends tags as an object keyed by IMAP label, or as an empty array when there are none. */
private fun parseMailTags(element: JsonElement?): List<MailTag> {
    val values = when (element) {
        is JsonObject -> element.values
        is JsonArray -> element
        else -> emptyList()
    }
    return values.mapNotNull { (it as? JsonObject)?.toMailTag() }.distinctBy(MailTag::imapLabel)
}

internal fun parseMailTag(body: ByteArray): MailTag =
    (mailRoot(body) as? JsonObject)?.toMailTag() ?: throw MailProtocolException("Mail did not return the tag.")

private fun JsonObject.toMailTag(): MailTag? {
    val id = positiveLong("id") ?: return null
    val label = text("imapLabel")?.takeIf { it.length <= 64 } ?: return null
    return MailTag(
        id = id,
        imapLabel = label,
        displayName = text("displayName") ?: label,
        color = text("color")?.takeIf { it.matches(Regex("^#[0-9A-Fa-f]{3,8}$")) },
    )
}

internal fun parseMailMessageDetail(body: ByteArray, expectedMessageId: Long): MailMessageDetail {
    val root = mailRoot(body) as? JsonObject ?: throw MailProtocolException("Mail did not return the message.")
    val summary = root.toMailSummary(expectedMailboxId = null)
        ?: throw MailProtocolException("Mail did not return the message.")
    // The body must belong to the message that was opened, never a neighbor.
    if (summary.id != expectedMessageId) throw MailProtocolException("Mail returned a different message.")
    val accountId = root.positiveLong("accountId") ?: throw MailProtocolException("Mail did not name the account.")
    val content = root.rawText("body", MAX_MAIL_BODY_CHARACTERS * 4)
    val html = root.boolean("hasHtmlBody")
    return MailMessageDetail(
        summary = summary,
        accountId = accountId,
        replyTo = root.recipients("replyTo"),
        bcc = root.recipients("bcc"),
        bodyHtml = content.takeIf { html },
        bodyText = content.takeUnless { html },
        attachments = (root["attachments"] as? JsonArray).orEmpty().mapNotNull { element ->
            val attachment = element as? JsonObject ?: return@mapNotNull null
            val partId = attachment.text("id")?.take(MAX_MAIL_HEADER_CHARACTERS) ?: return@mapNotNull null
            MailMessageAttachment(
                id = partId,
                fileName = attachment.text("fileName") ?: "Attachment",
                mimeType = attachment.text("mime")?.take(128),
                sizeBytes = attachment.positiveLong("size"),
            )
        },
    )
}

internal fun parseMailAutocomplete(body: ByteArray): List<MailAutocompleteMatch> =
    mailRootArray(body).mapNotNull { element ->
        val match = element as? JsonObject ?: return@mapNotNull null
        // Group suggestions use a namespaced identifier instead of an address; only addresses are recipients.
        val email = match.text("email")?.takeIf(::isPlausibleMailAddress) ?: return@mapNotNull null
        MailAutocompleteMatch(email = email, label = match.text("label")?.takeUnless { it == email })
    }.distinctBy { it.email.lowercase() }.take(MAX_MAIL_AUTOCOMPLETE_RESULTS)

internal fun parseMailUploadedAttachment(body: ByteArray): MailUploadedAttachment {
    val root = mailRoot(body) as? JsonObject ?: throw MailProtocolException("Mail did not return the attachment.")
    val id = root.positiveLong("id") ?: throw MailProtocolException("Mail did not return the attachment.")
    return MailUploadedAttachment(id = id, fileName = root.text("fileName") ?: "Attachment")
}

internal fun parseMailOutbox(body: ByteArray): List<MailOutboxMessage> {
    val data = mailEnvelopeData(body) as? JsonObject ?: throw MailProtocolException("Mail did not list the Outbox.")
    val messages = data["messages"] as? JsonArray ?: throw MailProtocolException("Mail did not list the Outbox.")
    return messages.mapNotNull { (it as? JsonObject)?.toMailOutboxMessage() }.distinctBy(MailOutboxMessage::id)
}

/** Parses the local message returned by creating an outbox message or a draft. */
internal fun parseMailLocalMessage(body: ByteArray): MailOutboxMessage =
    (mailEnvelopeData(body) as? JsonObject)?.toMailOutboxMessage()
        ?: throw MailProtocolException("Mail did not return the stored message.")

private fun JsonObject.toMailOutboxMessage(): MailOutboxMessage? {
    val id = positiveLong("id") ?: return null
    val accountId = positiveLong("accountId") ?: return null
    return MailOutboxMessage(
        id = id,
        accountId = accountId,
        subject = text("subject").orEmpty(),
        sendAtEpochSeconds = positiveLong("sendAt"),
        to = recipients("to"),
        cc = recipients("cc"),
        bcc = recipients("bcc"),
        attachmentCount = (this["attachments"] as? JsonArray)?.size ?: 0,
        failed = boolean("failed"),
        status = MailOutboxStatus.fromWire((this["status"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0),
    )
}

/** Mail's own wrapper: `{"status":"success","data":...}`. Anything else is not a success. */
private fun mailEnvelopeData(body: ByteArray): JsonElement? {
    val root = mailRoot(body) as? JsonObject ?: throw MailProtocolException("Mail sent an unexpected response.")
    val status = root.text("status")
    if (status != "success") throw MailProtocolException("Mail did not confirm the request.")
    return root["data"]
}

private fun mailRoot(body: ByteArray): JsonElement {
    if (body.isEmpty()) throw MailProtocolException("Mail sent an empty response.")
    return try {
        mailJson.parseToJsonElement(body.decodeToString())
    } catch (malformed: IllegalArgumentException) {
        throw MailProtocolException("Mail sent a response that is not valid JSON.")
    }
}

private fun mailRootArray(body: ByteArray): JsonArray =
    mailRoot(body) as? JsonArray ?: throw MailProtocolException("Mail sent an unexpected response.")

private fun JsonObject.recipients(key: String): List<MailRecipient> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
        val recipient = element as? JsonObject ?: return@mapNotNull null
        val email = recipient.text("email")?.takeIf(::isPlausibleMailAddress) ?: return@mapNotNull null
        MailRecipient(email = email, label = recipient.text("label")?.takeUnless { it == email })
    }.take(MAX_MAIL_RECIPIENTS)

private fun JsonObject?.flag(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.positiveLong(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return (primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull())?.takeIf { it > 0L }
}

private fun JsonObject.nonNegativeInt(key: String): Int =
    ((this[key] as? JsonPrimitive)?.longOrNull ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/** Trimmed single-line text, bounded and free of control characters. */
private fun JsonObject.text(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull || !primitive.isString) return null
    return primitive.content
        .filterNot { it.isISOControl() }
        .trim()
        .take(MAX_MAIL_TEXT_FIELD)
        .takeIf(String::isNotEmpty)
}

/** Multi-line text such as a body or signature, kept as written but bounded. */
private fun JsonObject.rawText(key: String, limit: Int): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull || !primitive.isString) return null
    return primitive.content.take(limit).takeIf(String::isNotBlank)
}

private const val MAX_MAIL_TEXT_FIELD = 2_048
private const val MAX_MAIL_PREVIEW_CHARACTERS = 512
private const val MAX_MAIL_AUTOCOMPLETE_RESULTS = 20
