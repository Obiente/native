package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json

/**
 * The durable record of one conversation's unresolved send. It holds the message text, so it goes
 * to the platform's encrypted text draft storage, which binds it to the account and deletes it
 * when the account is removed. Null when the platform keeps no draft storage; the unresolved
 * send then lives only as long as the open conversation.
 */
internal fun talkUnconfirmedSendStore(
    services: TextEditorDraftPlatformServices,
    session: NextcloudSession,
    conversationToken: String,
): TalkUnconfirmedSendStore? {
    // A conversation token is server-chosen text; its digest is path-safe.
    val path = TALK_UNCONFIRMED_SEND_PATH_PREFIX + publicContentSha256(conversationToken.encodeToByteArray())
    return services.textEditorDraftStore(session, path)?.let {
        TextDraftBackedTalkUnconfirmedSendStore { services.textEditorDraftStore(session, path) }
    }
}

/** Stores one unresolved send as one encrypted text draft record. */
internal class TextDraftBackedTalkUnconfirmedSendStore(
    private val bind: () -> TextEditorDraftStore?,
) : TalkUnconfirmedSendStore {
    /** Throws for a record that cannot be read, so the caller never treats it as "nothing sent". */
    override suspend fun load(): TalkUnconfirmedSend? {
        val stored = bind()?.load() ?: return null
        val attempt = talkUnconfirmedSendJson.decodeFromString<TalkUnconfirmedSend>(stored.text)
        check(attempt.text.isNotBlank()) { "The stored message is empty." }
        return attempt.copy(
            referenceId = attempt.referenceId?.takeIf(String::isTalkReferenceId),
            replyTo = attempt.replyTo?.takeIf { it > 0L },
        )
    }

    override suspend fun save(attempt: TalkUnconfirmedSend) {
        val store = checkNotNull(bind()) { "Message attempt storage is unavailable." }
        store.save(TextEditorDraft(originalText = "", text = talkUnconfirmedSendJson.encodeToString(attempt), etag = null))
    }

    override suspend fun clear() {
        bind()?.remove()
    }
}

private val talkUnconfirmedSendJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private const val TALK_UNCONFIRMED_SEND_PATH_PREFIX = "nextcloud-native:talk-unconfirmed-send:"
