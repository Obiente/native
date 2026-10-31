package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Keeps unsent Mail drafts across process death.
 *
 * Drafts live in the platform's encrypted, account-scoped text draft storage, which is bound to
 * the account digest and cleared when the account is removed. Each composer owns one slot keyed
 * by its random [MailComposeDraft.slotId]; a small index slot lists the open drafts so they can be
 * offered again after a restart. Records are bounded and strictly decoded: an unreadable record
 * is reported, never silently replaced.
 */
internal interface MailComposeDraftStore {
    suspend fun list(): List<MailComposeDraft>
    suspend fun save(draft: MailComposeDraft)
    suspend fun remove(slotId: String)
}

internal fun mailComposeDraftStore(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): MailComposeDraftStore? {
    services.textEditorDraftStore(session, MAIL_DRAFT_INDEX_PATH) ?: return null
    return TextDraftBackedMailComposeDraftStore { path -> services.textEditorDraftStore(session, path) }
}

internal class TextDraftBackedMailComposeDraftStore(
    private val bind: (String) -> TextEditorDraftStore?,
) : MailComposeDraftStore {
    private val mutex = Mutex()

    override suspend fun list(): List<MailComposeDraft> = mutex.withLock {
        readIndex().mapNotNull { slot -> bind(slotPath(slot))?.load()?.let { decodeMailComposeDraft(it.text) } }
    }

    override suspend fun save(draft: MailComposeDraft): Unit = mutex.withLock {
        require(draft.slotId.matches(MAIL_SLOT_PATTERN)) { "The draft slot is invalid." }
        val encoded = encodeMailComposeDraft(draft)
        val index = readIndex()
        check(draft.slotId in index || index.size < MAX_MAIL_COMPOSE_DRAFTS) {
            "You have $MAX_MAIL_COMPOSE_DRAFTS unsent drafts on this device. Send or discard one first."
        }
        val store = checkNotNull(bind(slotPath(draft.slotId))) { "Draft storage is unavailable." }
        store.save(TextEditorDraft(originalText = "", text = encoded, etag = null))
        if (draft.slotId !in index) writeIndex(index + draft.slotId)
    }

    override suspend fun remove(slotId: String): Unit = mutex.withLock {
        require(slotId.matches(MAIL_SLOT_PATTERN)) { "The draft slot is invalid." }
        bind(slotPath(slotId))?.remove()
        val index = readIndex()
        if (slotId in index) writeIndex(index - slotId)
    }

    private suspend fun readIndex(): List<String> {
        val stored = bind(MAIL_DRAFT_INDEX_PATH)?.load() ?: return emptyList()
        val slots = stored.text.split('\n').filter(String::isNotEmpty)
        check(slots.size <= MAX_MAIL_COMPOSE_DRAFTS && slots.all { it.matches(MAIL_SLOT_PATTERN) }) {
            "The saved Mail draft list is unreadable."
        }
        return slots.distinct()
    }

    private suspend fun writeIndex(slots: List<String>) {
        val store = checkNotNull(bind(MAIL_DRAFT_INDEX_PATH)) { "Draft storage is unavailable." }
        if (slots.isEmpty()) {
            store.remove()
        } else {
            store.save(TextEditorDraft(originalText = "", text = slots.joinToString("\n"), etag = null))
        }
    }

    private fun slotPath(slotId: String): String = "$MAIL_DRAFT_PATH_PREFIX$slotId"
}

internal fun encodeMailComposeDraft(draft: MailComposeDraft): String {
    val encoded = mailDraftJson.encodeToString(PersistedMailDraft.serializer(), draft.toPersisted())
    check(encoded.encodeToByteArray().size <= MAX_MAIL_PERSISTED_DRAFT_BYTES) {
        "This draft is too large to keep on the device."
    }
    return encoded
}

/** Returns null for a record that is not a valid version-1 Mail draft. */
internal fun decodeMailComposeDraft(text: String): MailComposeDraft? {
    if (text.length > MAX_MAIL_PERSISTED_DRAFT_BYTES) return null
    val persisted = try {
        mailDraftJson.decodeFromString(PersistedMailDraft.serializer(), text)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (persisted.version != 1 || !persisted.slotId.matches(MAIL_SLOT_PATTERN) || persisted.accountId <= 0L) return null
    return try {
        persisted.toDraft()
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Serializable
private data class PersistedMailDraft(
    val version: Int,
    val slotId: String,
    val mode: String,
    val accountId: Long,
    val aliasId: Long? = null,
    val to: List<PersistedMailRecipient> = emptyList(),
    val cc: List<PersistedMailRecipient> = emptyList(),
    val bcc: List<PersistedMailRecipient> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val rich: Boolean = false,
    val quote: PersistedMailQuote? = null,
    val includeQuote: Boolean = true,
    val includeSignature: Boolean = true,
    val inReplyToMessageId: String? = null,
    val replacesDraftMessageId: Long? = null,
    val attachments: List<PersistedMailAttachment> = emptyList(),
    val outboxMessageId: Long? = null,
    val storeAttemptSendAt: Long? = null,
)

@Serializable
private data class PersistedMailRecipient(val email: String, val label: String? = null)

@Serializable
private data class PersistedMailQuote(val header: String, val text: String, val html: String? = null, val forward: Boolean)

@Serializable
private data class PersistedMailAttachment(
    @SerialName("kind") val kind: String,
    val fileName: String,
    val attachmentId: Long? = null,
    val path: String? = null,
    val mailboxId: Long? = null,
    val messageUid: Long? = null,
    val partId: String? = null,
)

private fun MailComposeDraft.toPersisted(): PersistedMailDraft = PersistedMailDraft(
    version = 1,
    slotId = slotId,
    mode = mode.name,
    accountId = accountId,
    aliasId = aliasId,
    to = to.map { PersistedMailRecipient(it.email, it.label) },
    cc = cc.map { PersistedMailRecipient(it.email, it.label) },
    bcc = bcc.map { PersistedMailRecipient(it.email, it.label) },
    subject = subject,
    body = body,
    rich = rich,
    quote = quote?.let { PersistedMailQuote(it.header, it.text, it.html, it.forward) },
    includeQuote = includeQuote,
    includeSignature = includeSignature,
    inReplyToMessageId = inReplyToMessageId,
    replacesDraftMessageId = replacesDraftMessageId,
    attachments = attachments.map { attachment ->
        when (attachment) {
            is MailComposeAttachment.Uploaded -> PersistedMailAttachment("local", attachment.fileName, attachmentId = attachment.attachmentId)
            is MailComposeAttachment.FromFiles -> PersistedMailAttachment("cloud", attachment.fileName, path = attachment.path)
            is MailComposeAttachment.Forwarded -> PersistedMailAttachment(
                "forward",
                attachment.fileName,
                mailboxId = attachment.mailboxId,
                messageUid = attachment.messageUid,
                partId = attachment.partId,
            )
        }
    },
    outboxMessageId = outboxMessageId,
    storeAttemptSendAt = storeAttemptSendAt,
)

private fun PersistedMailDraft.toDraft(): MailComposeDraft {
    fun List<PersistedMailRecipient>.recipients() = map {
        require(isPlausibleMailAddress(it.email))
        MailRecipient(it.email, it.label)
    }
    return MailComposeDraft(
        slotId = slotId,
        mode = MailComposeMode.entries.first { it.name == mode },
        accountId = accountId,
        aliasId = aliasId?.also { require(it > 0L) },
        to = to.recipients(),
        cc = cc.recipients(),
        bcc = bcc.recipients(),
        subject = subject,
        body = body,
        rich = rich,
        quote = quote?.let { MailQuote(it.header, it.text, it.html, it.forward) },
        includeQuote = includeQuote,
        includeSignature = includeSignature,
        inReplyToMessageId = inReplyToMessageId,
        replacesDraftMessageId = replacesDraftMessageId?.also { require(it > 0L) },
        attachments = attachments.map { attachment ->
            when (attachment.kind) {
                "local" -> MailComposeAttachment.Uploaded(requireNotNull(attachment.attachmentId).also { require(it > 0L) }, attachment.fileName)
                "cloud" -> MailComposeAttachment.FromFiles(requireNotNull(attachment.path), attachment.fileName)
                "forward" -> MailComposeAttachment.Forwarded(
                    requireNotNull(attachment.mailboxId).also { require(it > 0L) },
                    requireNotNull(attachment.messageUid).also { require(it > 0L) },
                    requireNotNull(attachment.partId),
                    attachment.fileName,
                )
                else -> throw IllegalArgumentException("Unknown attachment kind.")
            }
        },
        outboxMessageId = outboxMessageId?.also { require(it > 0L) },
        storeAttemptSendAt = storeAttemptSendAt?.also { require(it > 0L) },
    )
}

private val mailDraftJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

private val MAIL_SLOT_PATTERN = Regex("^[a-z0-9]{24}$")
private const val MAIL_DRAFT_PATH_PREFIX = "nextcloud-native:mail-compose:"
private const val MAIL_DRAFT_INDEX_PATH = "nextcloud-native:mail-compose-index"
internal const val MAX_MAIL_COMPOSE_DRAFTS = 8
private const val MAX_MAIL_PERSISTED_DRAFT_BYTES = 2 * 1024 * 1024
