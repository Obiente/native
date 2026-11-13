package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Request builders for the reviewed Mail routes (see MailProtocol.kt for sources and the version
 * range). Every request carries the OCS API header: Mail's web routes require a CSRF token, and
 * Nextcloud accepts that header in its place for app-password clients without a browser session.
 * Builders validate identities before a request exists, so a missing or non-positive ID can never
 * reach a path.
 */

internal fun mailAccountsRequest(): NextcloudApiRequest = mailRead("$MAIL_API_PATH/accounts", MAX_MAIL_SMALL_RESPONSE_BYTES)

internal fun mailMailboxesRequest(accountId: Long): NextcloudApiRequest = mailRead(
    "$MAIL_API_PATH/mailboxes",
    MAX_MAIL_SMALL_RESPONSE_BYTES,
    mapOf("accountId" to accountId.requirePositive("account").toString()),
)

/**
 * One page of a mailbox, newest first. Mail pages by cursor: the send time of the oldest message
 * already shown. `view=singleton` lists every message instead of collapsing threads.
 */
internal fun mailMessagesRequest(
    mailboxId: Long,
    cursorEpochSeconds: Long? = null,
    filter: String? = null,
    limit: Int = MAIL_MESSAGE_PAGE_SIZE,
): NextcloudApiRequest {
    require(limit in 1..MAX_MAIL_MESSAGE_PAGE_SIZE) { "The Mail page size is invalid." }
    require(cursorEpochSeconds == null || cursorEpochSeconds > 0L) { "The Mail page cursor is invalid." }
    return mailRead(
        "$MAIL_API_PATH/messages",
        MAX_MAIL_PAGE_RESPONSE_BYTES,
        buildMap {
            put("mailboxId", mailboxId.requirePositive("mailbox").toString())
            put("limit", limit.toString())
            put("view", "singleton")
            cursorEpochSeconds?.let { put("cursor", it.toString()) }
            filter?.trim()?.takeIf(String::isNotEmpty)?.let { put("filter", it.take(MAX_MAIL_FILTER_CHARACTERS)) }
        },
    )
}

internal fun mailMessageBodyRequest(messageId: Long): NextcloudApiRequest =
    mailRead("$MAIL_API_PATH/messages/${messageId.requirePositive("message")}/body", MAX_MAIL_BODY_RESPONSE_BYTES)

/** Asks Mail to fetch new and changed messages from IMAP. It changes only the server cache. */
internal fun mailMailboxSyncRequest(mailboxId: Long, initialize: Boolean): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.POST,
    "$MAIL_API_PATH/mailboxes/${mailboxId.requirePositive("mailbox")}/sync",
    buildJsonObject {
        put("ids", JsonArray(emptyList()))
        put("init", initialize)
        put("sortOrder", "newest")
    },
    MAX_MAIL_PAGE_RESPONSE_BYTES,
)

internal fun mailAutocompleteRequest(term: String): NextcloudApiRequest {
    val trimmed = term.trim()
    require(trimmed.length in 1..MAX_MAIL_AUTOCOMPLETE_TERM) { "The search term is invalid." }
    return mailRead("$MAIL_API_PATH/autoComplete", MAX_MAIL_SMALL_RESPONSE_BYTES, mapOf("term" to trimmed))
}

internal fun mailOutboxRequest(): NextcloudApiRequest = mailRead("$MAIL_API_PATH/outbox", MAX_MAIL_PAGE_RESPONSE_BYTES)

/** Sets IMAP flags such as `seen` and `flagged`. Setting a flag to its current value is a no-op. */
internal fun mailSetFlagsRequest(messageId: Long, flags: Map<String, Boolean>): NextcloudApiRequest {
    require(flags.isNotEmpty() && flags.keys.all { it in MAIL_WRITABLE_FLAGS }) { "The Mail flag change is invalid." }
    return mailJson(
        NextcloudApiMethod.PUT,
        "$MAIL_API_PATH/messages/${messageId.requirePositive("message")}/flags",
        buildJsonObject { put("flags", buildJsonObject { flags.forEach { (name, value) -> put(name, value) } }) },
    )
}

internal fun mailMoveMessageRequest(messageId: Long, destinationMailboxId: Long): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.POST,
    "$MAIL_API_PATH/messages/${messageId.requirePositive("message")}/move",
    buildJsonObject { put("destFolderId", destinationMailboxId.requirePositive("destination mailbox")) },
)

/** Moves a message to Trash, or removes it permanently when it is already in Trash. */
internal fun mailDeleteMessageRequest(messageId: Long): NextcloudApiRequest = mailBodyless(
    NextcloudApiMethod.DELETE,
    "$MAIL_API_PATH/messages/${messageId.requirePositive("message")}",
)

internal fun mailSetTagRequest(messageId: Long, imapLabel: String, applied: Boolean): NextcloudApiRequest = mailBodyless(
    if (applied) NextcloudApiMethod.PUT else NextcloudApiMethod.DELETE,
    "$MAIL_API_PATH/messages/${messageId.requirePositive("message")}/tags/${imapLabel.requireImapLabel().encodedMailLabel()}",
)

internal fun mailCreateTagRequest(displayName: String, color: String): NextcloudApiRequest {
    val name = displayName.trim()
    require(name.length in 1..MAX_MAIL_TAG_NAME) { "A tag needs a name of at most $MAX_MAIL_TAG_NAME characters." }
    require(color.matches(MAIL_TAG_COLOR)) { "The tag color is invalid." }
    return mailJson(NextcloudApiMethod.POST, "$MAIL_API_PATH/tags", buildJsonObject {
        put("displayName", name)
        put("color", color)
    })
}

internal fun mailCreateMailboxRequest(accountId: Long, fullName: String): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.POST,
    "$MAIL_API_PATH/mailboxes",
    buildJsonObject {
        put("accountId", accountId.requirePositive("account"))
        put("name", fullName.requireMailboxName())
    },
)

internal fun mailRenameMailboxRequest(mailboxId: Long, fullName: String): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.PATCH,
    "$MAIL_API_PATH/mailboxes/${mailboxId.requirePositive("mailbox")}",
    buildJsonObject { put("name", fullName.requireMailboxName()) },
)

internal fun mailDeleteMailboxRequest(mailboxId: Long): NextcloudApiRequest =
    mailBodyless(NextcloudApiMethod.DELETE, "$MAIL_API_PATH/mailboxes/${mailboxId.requirePositive("mailbox")}")

internal fun mailMarkMailboxReadRequest(mailboxId: Long): NextcloudApiRequest =
    mailBodyless(NextcloudApiMethod.POST, "$MAIL_API_PATH/mailboxes/${mailboxId.requirePositive("mailbox")}/read")

internal fun mailAccountSignatureRequest(accountId: Long, signature: String?): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.PUT,
    "$MAIL_API_PATH/accounts/${accountId.requirePositive("account")}/signature",
    buildJsonObject { putNullable("signature", signature.requireSignature()) },
)

internal fun mailAccountPreferencesRequest(
    accountId: Long,
    editorMode: MailEditorMode? = null,
    signatureAboveQuote: Boolean? = null,
): NextcloudApiRequest {
    require(editorMode != null || signatureAboveQuote != null) { "Nothing to change." }
    return mailJson(NextcloudApiMethod.PATCH, "$MAIL_API_PATH/accounts/${accountId.requirePositive("account")}", buildJsonObject {
        editorMode?.let { put("editorMode", it.wireValue) }
        signatureAboveQuote?.let { put("signatureAboveQuote", it) }
    })
}

internal fun mailCreateAliasRequest(accountId: Long, email: String, name: String): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.POST,
    "$MAIL_API_PATH/accounts/${accountId.requirePositive("account")}/aliases",
    buildJsonObject {
        put("alias", email.requireMailAddress())
        put("aliasName", name.trim().take(MAX_MAIL_DISPLAY_NAME))
    },
)

internal fun mailUpdateAliasRequest(accountId: Long, aliasId: Long, email: String, name: String): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.PUT,
    "$MAIL_API_PATH/accounts/${accountId.requirePositive("account")}/aliases/${aliasId.requirePositive("alias")}",
    buildJsonObject {
        put("alias", email.requireMailAddress())
        put("aliasName", name.trim().take(MAX_MAIL_DISPLAY_NAME))
    },
)

internal fun mailDeleteAliasRequest(accountId: Long, aliasId: Long): NextcloudApiRequest = mailBodyless(
    NextcloudApiMethod.DELETE,
    "$MAIL_API_PATH/accounts/${accountId.requirePositive("account")}/aliases/${aliasId.requirePositive("alias")}",
)

internal fun mailAliasSignatureRequest(accountId: Long, aliasId: Long, signature: String?): NextcloudApiRequest = mailJson(
    NextcloudApiMethod.PUT,
    "$MAIL_API_PATH/accounts/${accountId.requirePositive("account")}/aliases/${aliasId.requirePositive("alias")}/signature",
    buildJsonObject { putNullable("signature", signature.requireSignature()) },
)

/** Uploads one picker-selected file as a Mail local attachment (`LocalAttachmentsController::create`). */
internal fun mailAttachmentUploadRequest(accountId: Long, file: LocalUploadFile): NextcloudMultipartUploadRequest =
    NextcloudMultipartUploadRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "$MAIL_API_PATH/attachments",
        file = file,
        fileFieldName = "attachment",
        textFields = listOf(MultipartTextField("accountId", accountId.requirePositive("account").toString())),
        ocsApiRequest = true,
        maximumFileBytes = DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
        maximumResponseBytes = MAX_MAIL_SMALL_RESPONSE_BYTES,
    ).requireSafe()

/** Server-side payload for an outgoing or draft local message. */
internal data class MailOutgoingPayload(
    val accountId: Long,
    val aliasId: Long?,
    val subject: String,
    val bodyPlain: String,
    val bodyHtml: String?,
    val to: List<MailRecipient>,
    val cc: List<MailRecipient>,
    val bcc: List<MailRecipient>,
    val attachments: List<MailOutgoingAttachment>,
    val inReplyToMessageId: String?,
    val sendAtEpochSeconds: Long?,
    /** An IMAP draft that this message replaces; Mail removes it once the message is stored. */
    val replacesDraftMessageId: Long?,
) {
    init {
        accountId.requirePositive("account")
        aliasId?.requirePositive("alias")
        replacesDraftMessageId?.requirePositive("draft")
        require(subject.length <= MAX_MAIL_SUBJECT) { "The subject is too long." }
        require(bodyPlain.length <= MAX_MAIL_BODY_CHARACTERS) { "The message is too long." }
        require(bodyHtml == null || bodyHtml.length <= MAX_MAIL_BODY_CHARACTERS * 2) { "The message is too long." }
        require((to + cc + bcc).size <= MAX_MAIL_RECIPIENTS) { "There are too many recipients." }
        require(attachments.size <= MAX_MAIL_ATTACHMENTS) { "There are too many attachments." }
        require(inReplyToMessageId == null || inReplyToMessageId.length <= MAX_MAIL_HEADER_CHARACTERS) {
            "The reply reference is invalid."
        }
        require(sendAtEpochSeconds == null || sendAtEpochSeconds > 0L) { "The send time is invalid." }
    }

    val isHtml: Boolean get() = bodyHtml != null
}

internal sealed interface MailOutgoingAttachment {
    /** Uploaded through [mailAttachmentUploadRequest]. */
    data class Local(val attachmentId: Long) : MailOutgoingAttachment

    /** A file in the user's Nextcloud Files, copied by the server when the message is stored. */
    data class Cloud(val path: String) : MailOutgoingAttachment

    /** An attachment of an existing message, used when forwarding. */
    data class FromMessage(val mailboxId: Long, val messageUid: Long, val partId: String, val fileName: String) :
        MailOutgoingAttachment
}

internal fun mailCreateOutboxRequest(payload: MailOutgoingPayload): NextcloudApiRequest =
    mailJson(NextcloudApiMethod.POST, "$MAIL_API_PATH/outbox", payload.toJson(includeDraftId = true))

internal fun mailSendOutboxRequest(outboxId: Long): NextcloudApiRequest =
    mailBodyless(NextcloudApiMethod.POST, "$MAIL_API_PATH/outbox/${outboxId.requirePositive("outbox message")}")

internal fun mailDeleteOutboxRequest(outboxId: Long): NextcloudApiRequest =
    mailBodyless(NextcloudApiMethod.DELETE, "$MAIL_API_PATH/outbox/${outboxId.requirePositive("outbox message")}")

internal fun mailCreateDraftRequest(payload: MailOutgoingPayload): NextcloudApiRequest =
    mailJson(NextcloudApiMethod.POST, "$MAIL_API_PATH/drafts", payload.toJson(includeDraftId = true))

/** Stores a local draft in the account's IMAP Drafts folder and removes the local copy. */
internal fun mailMoveDraftToImapRequest(localDraftId: Long): NextcloudApiRequest =
    mailBodyless(NextcloudApiMethod.POST, "$MAIL_API_PATH/drafts/move/${localDraftId.requirePositive("draft")}")

private fun MailOutgoingPayload.toJson(includeDraftId: Boolean): JsonObject = buildJsonObject {
    put("accountId", accountId)
    putNullable("aliasId", aliasId)
    put("subject", subject)
    put("bodyPlain", bodyPlain)
    putNullable("bodyHtml", bodyHtml)
    put("editorBody", bodyHtml ?: bodyPlain)
    put("isHtml", isHtml)
    put("smimeSign", false)
    put("smimeEncrypt", false)
    put("requestMdn", false)
    put("isPgpMime", false)
    put("to", to.toJsonRecipients())
    put("cc", cc.toJsonRecipients())
    put("bcc", bcc.toJsonRecipients())
    put("attachments", buildJsonArray {
        attachments.forEach { attachment ->
            add(
                when (attachment) {
                    is MailOutgoingAttachment.Local -> buildJsonObject {
                        put("type", "local")
                        put("id", attachment.attachmentId)
                    }
                    is MailOutgoingAttachment.Cloud -> buildJsonObject {
                        put("type", "cloud")
                        put("fileName", attachment.path.requireCloudPath())
                    }
                    is MailOutgoingAttachment.FromMessage -> buildJsonObject {
                        put("type", "message-attachment")
                        put("mailboxId", attachment.mailboxId.requirePositive("mailbox"))
                        put("uid", attachment.messageUid.requirePositive("message"))
                        put("id", attachment.partId.take(MAX_MAIL_HEADER_CHARACTERS))
                        put("fileName", attachment.fileName.take(MAX_MAIL_DISPLAY_NAME))
                    }
                },
            )
        }
    })
    putNullable("inReplyToMessageId", inReplyToMessageId)
    putNullable("sendAt", sendAtEpochSeconds)
    if (includeDraftId) putNullable("draftId", replacesDraftMessageId)
}

private fun List<MailRecipient>.toJsonRecipients(): JsonArray = buildJsonArray {
    forEach { recipient ->
        add(buildJsonObject {
            put("label", recipient.label?.takeIf(String::isNotBlank) ?: recipient.email)
            put("email", recipient.email.requireMailAddress())
        })
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun mailRead(
    path: String,
    maximumResponseBytes: Long,
    query: Map<String, String> = emptyMap(),
): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = path,
    queryParameters = query,
    ocsApiRequest = true,
    maximumResponseBytes = maximumResponseBytes,
    cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
).requireSafe()

private fun mailJson(
    method: NextcloudApiMethod,
    path: String,
    body: JsonObject,
    maximumResponseBytes: Long = MAX_MAIL_SMALL_RESPONSE_BYTES,
): NextcloudApiRequest = NextcloudApiRequest(
    method = method,
    relativePath = path,
    contentType = "application/json",
    body = body.toString().encodeToByteArray(),
    ocsApiRequest = true,
    maximumResponseBytes = maximumResponseBytes,
    cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
).requireSafe()

private fun mailBodyless(method: NextcloudApiMethod, path: String): NextcloudApiRequest = NextcloudApiRequest(
    method = method,
    relativePath = path,
    ocsApiRequest = true,
    maximumResponseBytes = MAX_MAIL_SMALL_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
).requireSafe()

private fun Long.requirePositive(what: String): Long {
    require(this > 0L) { "The Mail $what is not known." }
    return this
}

private fun String.requireImapLabel(): String {
    require(matches(MAIL_IMAP_LABEL)) { "The tag label is invalid." }
    return this
}

private fun String.encodedMailLabel(): String = replace("$", "%24")

private fun String.requireMailboxName(): String {
    val trimmed = trim()
    require(trimmed.isNotEmpty() && trimmed.length <= MAX_MAIL_MAILBOX_NAME) { "A folder needs a name." }
    require(trimmed.none { it.isISOControl() }) { "The folder name contains invalid characters." }
    return trimmed
}

private fun String?.requireSignature(): String? {
    require(this == null || length <= MAX_MAIL_SIGNATURE) { "The signature is too long." }
    return this?.takeIf(String::isNotBlank)
}

internal fun String.requireMailAddress(): String {
    val trimmed = trim()
    require(isPlausibleMailAddress(trimmed)) { "\"$trimmed\" is not a valid email address." }
    return trimmed
}

private fun String.requireCloudPath(): String {
    require(startsWith('/') && length <= MAX_MAIL_CLOUD_PATH && none { it == '\u0000' || it.isISOControl() }) {
        "The Files path is invalid."
    }
    require(split('/').none { it == "." || it == ".." }) { "The Files path is invalid." }
    return this
}

/** A practical address check that matches what Mail's PHP `FILTER_VALIDATE_EMAIL` accepts in common use. */
internal fun isPlausibleMailAddress(value: String): Boolean {
    if (value.length !in 3..MAX_MAIL_ADDRESS || value.any { it.isWhitespace() || it.isISOControl() }) return false
    val at = value.lastIndexOf('@')
    if (at <= 0 || at == value.lastIndex || value.indexOf('@') != at) return false
    val local = value.substring(0, at)
    val domain = value.substring(at + 1)
    if (local.startsWith('.') || local.endsWith('.') || ".." in local) return false
    if (local.any { it in "()<>[]:;,\\\"" }) return false
    val labels = domain.split('.')
    return labels.size >= 2 && labels.all { label ->
        label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}

private val MAIL_WRITABLE_FLAGS = setOf("seen", "flagged", "important", "\$junk", "\$notjunk")
private val MAIL_IMAP_LABEL = Regex("^[A-Za-z0-9\$_.\\-]{1,64}$")
private val MAIL_TAG_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

internal const val MAX_MAIL_SMALL_RESPONSE_BYTES = 512L * 1024L
internal const val MAX_MAIL_PAGE_RESPONSE_BYTES = 4L * 1024L * 1024L
internal const val MAX_MAIL_BODY_RESPONSE_BYTES = 8L * 1024L * 1024L
internal const val MAX_MAIL_MESSAGE_PAGE_SIZE = 100
internal const val MAX_MAIL_FILTER_CHARACTERS = 256
internal const val MAX_MAIL_AUTOCOMPLETE_TERM = 128
internal const val MAX_MAIL_TAG_NAME = 128
internal const val MAX_MAIL_MAILBOX_NAME = 255
internal const val MAX_MAIL_SIGNATURE = 64 * 1024
internal const val MAX_MAIL_DISPLAY_NAME = 255
internal const val MAX_MAIL_ADDRESS = 320
internal const val MAX_MAIL_SUBJECT = 998
internal const val MAX_MAIL_BODY_CHARACTERS = 1_000_000
internal const val MAX_MAIL_RECIPIENTS = 200
internal const val MAX_MAIL_ATTACHMENTS = 40
internal const val MAX_MAIL_HEADER_CHARACTERS = 998
internal const val MAX_MAIL_CLOUD_PATH = 4096
