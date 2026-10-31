package dev.obiente.nextcloudnative.app

/*
 * Reviewed, version-pinned adapter for the Nextcloud Mail app.
 *
 * Sources reviewed for every supported release (appinfo/routes.php and lib/Controller):
 * - https://github.com/nextcloud/mail/blob/v5.0.0/appinfo/routes.php
 * - https://github.com/nextcloud/mail/blob/v5.10.9/appinfo/routes.php
 * - https://github.com/nextcloud/mail/blob/v5.11.7/appinfo/routes.php
 * - https://github.com/nextcloud/mail/blob/v5.12.6/appinfo/routes.php
 *
 * Mail publishes an OpenAPI description only for its OCS controllers (account list, message
 * read, and a synchronous send). Composing with undo, scheduling, drafts, attachments, message
 * state, mailbox management, and account settings is served by the routes the Mail web client
 * uses, declared in the signed package's routes.php. Those routes are marked as ignored by the
 * Mail OpenAPI generator, so this adapter treats them as a reviewed enhancement: every parameter
 * name and response shape below was read from the controllers of the releases listed above, and
 * writes are offered only when the installed Mail version is confirmed to be inside that range.
 */

internal const val MAIL_APP_ID = "mail"
internal const val MAIL_API_PATH = "/index.php/apps/mail/api"
internal const val MAIL_MESSAGE_PAGE_SIZE = 40

/** First Mail release whose routes and parameters were reviewed. */
internal val MAIL_MINIMUM_REVIEWED_VERSION = MailVersion(5, 0, 0)

/** First Mail release that has not been reviewed yet. Writes stay off from this version on. */
internal val MAIL_FIRST_UNREVIEWED_VERSION = MailVersion(5, 13, 0)

internal data class MailVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<MailVersion> {
    override fun compareTo(other: MailVersion): Int =
        compareValuesBy(this, other, MailVersion::major, MailVersion::minor, MailVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** Parses `5.12.6`, `5.12.0-rc.2`, or `5.12`. A prerelease counts as its release. */
        fun parse(value: String?): MailVersion? {
            val core = value?.trim()?.substringBefore('-')?.substringBefore('+') ?: return null
            val parts = core.split('.')
            if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.length > 4 || !it.all(Char::isDigit) }) {
                return null
            }
            return MailVersion(parts[0].toInt(), parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0)
        }
    }
}

internal enum class MailContractStatus {
    /** The installed version is known and inside the reviewed range. */
    Verified,

    /** The server did not reveal the installed version; reads work, writes stay off. */
    UnverifiedVersion,

    /** The installed version is known and outside the reviewed range. */
    UnsupportedVersion,
}

internal data class MailContract(
    val status: MailContractStatus,
    val installedVersion: MailVersion?,
) {
    val writesAllowed: Boolean get() = status == MailContractStatus.Verified

    val writeBlockedReason: String?
        get() = when (status) {
            MailContractStatus.Verified -> null
            MailContractStatus.UnverifiedVersion ->
                "The installed Mail version could not be confirmed, so changes are turned off. " +
                    "An administrator account can confirm it."
            MailContractStatus.UnsupportedVersion ->
                "Mail $installedVersion has not been reviewed for changes yet, so this view is read-only."
        }
}

internal fun mailContractFor(installedVersion: String?): MailContract {
    val version = MailVersion.parse(installedVersion) ?: return MailContract(MailContractStatus.UnverifiedVersion, null)
    val supported = version >= MAIL_MINIMUM_REVIEWED_VERSION && version < MAIL_FIRST_UNREVIEWED_VERSION
    return MailContract(
        status = if (supported) MailContractStatus.Verified else MailContractStatus.UnsupportedVersion,
        installedVersion = version,
    )
}

internal data class MailRecipient(
    val email: String,
    val label: String? = null,
) {
    val displayName: String get() = label?.takeIf(String::isNotBlank) ?: email
}

internal enum class MailEditorMode(val wireValue: String) {
    Plain("plaintext"),
    Rich("richtext"),
    ;

    companion object {
        fun fromWire(value: String?): MailEditorMode = entries.firstOrNull { it.wireValue == value } ?: Plain
    }
}

internal data class MailAlias(
    val id: Long,
    val email: String,
    val name: String?,
    val signature: String?,
    /** Provisioned aliases are managed by an administrator and cannot be edited by the user. */
    val provisioned: Boolean,
)

internal data class MailAccount(
    val id: Long,
    val name: String,
    val email: String,
    val signature: String?,
    val signatureAboveQuote: Boolean,
    val editorMode: MailEditorMode,
    val draftsMailboxId: Long?,
    val sentMailboxId: Long?,
    val trashMailboxId: Long?,
    val archiveMailboxId: Long?,
    val junkMailboxId: Long?,
    val aliases: List<MailAlias>,
    val delegated: Boolean,
)

/** IMAP rights from RFC 4314. A server without ACL support reports none, which allows everything. */
internal data class MailMailboxRights(val raw: String?) {
    private fun allows(right: Char): Boolean = raw == null || right in raw

    val canRename: Boolean get() = allows('x')
    val canDelete: Boolean get() = allows('x')
    val canCreateChild: Boolean get() = allows('k')
    val canChangeSeen: Boolean get() = allows('s')
    val canChangeFlags: Boolean get() = allows('w')
    val canInsert: Boolean get() = allows('i')
    val canDeleteMessages: Boolean get() = allows('t')
}

internal enum class MailMailboxRole {
    Inbox,
    Drafts,
    Sent,
    Archive,
    Junk,
    Trash,
    Other,
}

internal data class MailMailbox(
    val id: Long,
    val accountId: Long,
    /** Full IMAP name, including parents joined by [delimiter]. */
    val name: String,
    val delimiter: String?,
    val specialUse: Set<String>,
    val unread: Int,
    val rights: MailMailboxRights,
    val shared: Boolean,
) {
    val displayName: String
        get() = delimiter?.takeIf(String::isNotEmpty)?.let { name.substringAfterLast(it) }?.takeIf(String::isNotBlank) ?: name

    val parentName: String?
        get() = delimiter?.takeIf(String::isNotEmpty)?.let { separator ->
            name.substringBeforeLast(separator, missingDelimiterValue = "").takeIf(String::isNotEmpty)
        }

    val depth: Int
        get() = delimiter?.takeIf(String::isNotEmpty)?.let { separator -> name.split(separator).size - 1 } ?: 0

    fun role(account: MailAccount?): MailMailboxRole = when {
        name.equals("INBOX", ignoreCase = true) || "inbox" in specialUse -> MailMailboxRole.Inbox
        account?.draftsMailboxId == id || "drafts" in specialUse -> MailMailboxRole.Drafts
        account?.sentMailboxId == id || "sent" in specialUse -> MailMailboxRole.Sent
        account?.trashMailboxId == id || "trash" in specialUse -> MailMailboxRole.Trash
        account?.archiveMailboxId == id || "archive" in specialUse -> MailMailboxRole.Archive
        account?.junkMailboxId == id || "junk" in specialUse -> MailMailboxRole.Junk
        else -> MailMailboxRole.Other
    }
}

internal data class MailTag(
    val id: Long,
    val imapLabel: String,
    val displayName: String,
    val color: String?,
)

internal data class MailMessageFlags(
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val forwarded: Boolean = false,
    val draft: Boolean = false,
    val hasAttachments: Boolean = false,
    val important: Boolean = false,
    val junk: Boolean = false,
)

internal data class MailMessageSummary(
    val id: Long,
    /** IMAP UID inside [mailboxId]; needed to forward the message's own attachments. */
    val uid: Long?,
    val mailboxId: Long,
    val subject: String,
    val from: List<MailRecipient>,
    val to: List<MailRecipient>,
    val cc: List<MailRecipient>,
    val sentAtEpochSeconds: Long?,
    val flags: MailMessageFlags,
    val tags: List<MailTag>,
    val previewText: String?,
    /** RFC 5322 Message-ID header, used to thread replies. */
    val messageIdHeader: String?,
    val threadRootId: String?,
)

internal data class MailMessageAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

internal data class MailMessageDetail(
    val summary: MailMessageSummary,
    val accountId: Long,
    val replyTo: List<MailRecipient>,
    val bcc: List<MailRecipient>,
    val bodyHtml: String?,
    val bodyText: String?,
    val attachments: List<MailMessageAttachment>,
) {
    val id: Long get() = summary.id
}

internal data class MailMessagePage(
    val messages: List<MailMessageSummary>,
    val endReached: Boolean,
)

/** Mail's local outgoing message, held by the server until its send time. */
internal data class MailOutboxMessage(
    val id: Long,
    val accountId: Long,
    val subject: String,
    val sendAtEpochSeconds: Long?,
    val to: List<MailRecipient>,
    val cc: List<MailRecipient>,
    val bcc: List<MailRecipient>,
    val attachmentCount: Int,
    val failed: Boolean,
    val status: MailOutboxStatus,
)

/** `LocalMessage::STATUS_*` from lib/Db/LocalMessage.php. */
internal enum class MailOutboxStatus(val wireValue: Int) {
    Pending(0),
    NoSentMailbox(1),
    SignatureCertificateMissing(2),
    SignatureCertificateInvalid(3),
    SignatureFailed(4),
    EncryptionCertificateMissing(5),
    EncryptionCertificateInvalid(6),
    EncryptionFailed(7),
    TooManyRecipients(8),
    RateLimited(9),
    SmtpFailed(10),
    SentCopyFailed(11),
    Sent(12),
    Error(13),
    Unknown(-1),
    ;

    val explanation: String?
        get() = when (this) {
            Pending, Sent -> null
            NoSentMailbox -> "This account has no Sent folder. Choose one in the account settings, then send again."
            SignatureCertificateMissing, SignatureCertificateInvalid, SignatureFailed ->
                "The message could not be signed. Check the S/MIME settings in the Mail web settings."
            EncryptionCertificateMissing, EncryptionCertificateInvalid, EncryptionFailed ->
                "The message could not be encrypted for every recipient."
            TooManyRecipients -> "The mail server refused this many recipients."
            RateLimited -> "You sent too many messages in a short time. The server will try again later."
            SmtpFailed -> "The mail server did not accept the message. Nextcloud will try again."
            SentCopyFailed -> "The message was sent, but its copy could not be saved to the Sent folder yet."
            Error, Unknown -> "The server could not send this message."
        }

    companion object {
        fun fromWire(value: Int?): MailOutboxStatus = entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

internal data class MailAutocompleteMatch(
    val email: String,
    val label: String?,
) {
    fun toRecipient(): MailRecipient = MailRecipient(email, label)
}

internal data class MailUploadedAttachment(
    val id: Long,
    val fileName: String,
)

internal enum class MailFailureKind {
    Offline,
    Unauthorized,
    Forbidden,
    NotFound,
    Conflict,
    Throttled,
    Rejected,
    Server,
    Malformed,
}

internal data class MailFailure(val kind: MailFailureKind, val detail: String? = null) {
    val title: String
        get() = when (kind) {
            MailFailureKind.Offline -> "Can't reach your server"
            MailFailureKind.Unauthorized -> "Sign-in expired"
            MailFailureKind.Forbidden -> "Mail did not allow this"
            MailFailureKind.NotFound -> "It is no longer there"
            MailFailureKind.Conflict -> "Mail changed on the server"
            MailFailureKind.Throttled -> "The server asked to slow down"
            MailFailureKind.Rejected -> "Mail could not do that"
            MailFailureKind.Server -> "The server could not complete this"
            MailFailureKind.Malformed -> "Mail sent an unexpected response"
        }

    val message: String
        get() = detail ?: when (kind) {
            MailFailureKind.Offline -> "Check your connection, then try again."
            MailFailureKind.Unauthorized -> "Sign in to this account again."
            MailFailureKind.Forbidden -> "You may not have permission for this mailbox or account."
            MailFailureKind.NotFound -> "Refresh to see the current state."
            MailFailureKind.Conflict -> "Refresh, then try again."
            MailFailureKind.Throttled -> "Wait a moment before trying again."
            MailFailureKind.Rejected -> "Check the details and try again."
            MailFailureKind.Server -> "Try again later. If it keeps happening, ask your administrator."
            MailFailureKind.Malformed -> "Try again. If it keeps happening, the Mail app may need an update."
        }
}

internal fun mailFailureForStatus(status: Int): MailFailure? = when (status) {
    in 200..299 -> null
    400, 422 -> MailFailure(MailFailureKind.Rejected)
    401 -> MailFailure(MailFailureKind.Unauthorized)
    403 -> MailFailure(MailFailureKind.Forbidden)
    404, 410 -> MailFailure(MailFailureKind.NotFound)
    409, 412, 428 -> MailFailure(MailFailureKind.Conflict)
    429 -> MailFailure(MailFailureKind.Throttled)
    else -> MailFailure(MailFailureKind.Server)
}

/**
 * Outcome of one Mail write. A 5xx, a transport failure after the request may have left, or an
 * unreadable success body is [Unknown]: the change may have been stored, so it is never retried
 * blindly and callers verify the authoritative state first.
 */
internal sealed interface MailWriteOutcome<out T> {
    data class Confirmed<T>(val value: T) : MailWriteOutcome<T>
    data class Rejected(val failure: MailFailure, val responseBody: ByteArray? = null) : MailWriteOutcome<Nothing>
    data object Unknown : MailWriteOutcome<Nothing>
}

internal sealed interface MailReadOutcome<out T> {
    data class Loaded<T>(val value: T) : MailReadOutcome<T>
    data class Failed(val failure: MailFailure) : MailReadOutcome<Nothing>
}
