package dev.obiente.nextcloudnative.nativeui.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Version-2 device encoding of a semantic composer and its exactly-once attempt record. Strictly
 * decoded and bounded (version 1 still restores, without an attempt record): an unknown
 * version, mode, or oversized value is unreadable rather than partially restored.
 */
internal fun encodeNativeMailStoredComposer(value: NativeMailStoredComposer): String {
    val encoded = draftJson.encodeToString(
        StoredComposer.serializer(),
        StoredComposer(VERSION, value.draft.toStored(), value.unknownAttempt?.toStored(), value.attempt),
    )
    check(encoded.length <= MAX_STORED_CHARACTERS && encoded.encodeToByteArray().size <= MAX_STORED_BYTES) {
        "This draft is too large to keep on the device."
    }
    return encoded
}

/** Returns null for text that is not a valid version-1 or version-2 composer record. */
internal fun decodeNativeMailStoredComposer(text: String): NativeMailStoredComposer? {
    if (text.length > MAX_STORED_CHARACTERS) return null
    val stored = try {
        draftJson.decodeFromString(StoredComposer.serializer(), text)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    // Version 1 predates the attempt record; it still restores as "may have been sent".
    if (stored.version != VERSION && stored.version != 1) return null
    val draft = stored.draft.toDraft() ?: return null
    val attempt = stored.unknownAttempt?.let { it.toDraft() ?: return null }
    return NativeMailStoredComposer(draft, attempt, stored.attempt?.takeIf { attempt != null })
}

@Serializable
private data class StoredComposer(
    val version: Int,
    val draft: StoredDraft,
    val unknownAttempt: StoredDraft? = null,
    val attempt: NativeSendAttempt? = null,
)

@Serializable
private data class StoredDraft(
    val mode: String,
    val senderId: String? = null,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val quote: StoredQuote? = null,
    val includeQuote: Boolean = true,
    val inReplyTo: String? = null,
    val references: String? = null,
)

@Serializable
private data class StoredQuote(val header: String, val text: String, val forward: Boolean)

private fun NativeMailComposeDraft.toStored() = StoredDraft(
    mode = mode.name,
    senderId = senderId,
    to = to,
    cc = cc,
    bcc = bcc,
    subject = subject,
    body = body,
    quote = quote?.let { StoredQuote(it.header, it.text, it.forward) },
    includeQuote = includeQuote,
    inReplyTo = inReplyTo,
    references = references,
)

private fun StoredDraft.toDraft(): NativeMailComposeDraft? {
    val mode = NativeMailComposeMode.entries.firstOrNull { it.name == mode } ?: return null
    val shortFields = listOfNotNull(senderId, inReplyTo, quote?.header)
    if (shortFields.any { it.length > MAX_SHORT_FIELD }) return null
    if (listOf(to, cc, bcc, subject).any { it.length > MAX_RECIPIENT_OR_SUBJECT }) return null
    if ((references?.length ?: 0) > MAX_REFERENCES_LENGTH) return null
    if ((quote?.text?.length ?: 0) > MAX_NATIVE_MAIL_QUOTE_CHARACTERS) return null
    return NativeMailComposeDraft(
        mode = mode,
        senderId = senderId,
        to = to,
        cc = cc,
        bcc = bcc,
        subject = subject,
        body = body,
        quote = quote?.let { NativeMailQuote(it.header, it.text, it.forward) },
        includeQuote = includeQuote,
        inReplyTo = inReplyTo,
        references = references,
    )
}

private val draftJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

private const val VERSION = 2
private const val MAX_STORED_BYTES = 2 * 1024 * 1024
private const val MAX_STORED_CHARACTERS = MAX_STORED_BYTES
private const val MAX_SHORT_FIELD = 4_096
private const val MAX_RECIPIENT_OR_SUBJECT = 64_000
private const val MAX_REFERENCES_LENGTH = 24_000
