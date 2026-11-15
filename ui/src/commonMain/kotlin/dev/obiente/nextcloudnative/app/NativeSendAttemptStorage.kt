package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeSendAttempt
import dev.obiente.nextcloudnative.nativeui.runtime.NativeSendAttemptStore
import kotlinx.serialization.json.Json

/**
 * Durable records of sends whose outcome is not known, one per send surface of one app. They go
 * to the platform's encrypted text draft storage, which binds them to the account and deletes
 * them when the account is removed. The record holds an attempt id, a time, a content-free
 * summary, and a payload fingerprint; never the content that was sent.
 *
 * Returns null for a surface when the platform keeps no draft storage.
 */
internal fun nativeSendAttemptStores(
    services: TextEditorDraftPlatformServices,
    session: NextcloudSession,
    appId: String,
): (surface: String) -> NativeSendAttemptStore? = { surface ->
    // App and surface names are not path-safe; their digest is.
    val path = SEND_ATTEMPT_PATH_PREFIX + publicContentSha256("$appId\u0000$surface".encodeToByteArray())
    services.textEditorDraftStore(session, path)?.let {
        TextDraftBackedSendAttemptStore { services.textEditorDraftStore(session, path) }
    }
}

/** Stores one send attempt as one encrypted text draft record. */
internal class TextDraftBackedSendAttemptStore(
    private val bind: () -> TextEditorDraftStore?,
) : NativeSendAttemptStore {
    /** Throws for a record that cannot be read, so the caller never treats it as "nothing sent". */
    override suspend fun load(): NativeSendAttempt? {
        val stored = bind()?.load() ?: return null
        return sendAttemptJson.decodeFromString<NativeSendAttempt>(stored.text)
    }

    override suspend fun save(attempt: NativeSendAttempt) {
        val store = checkNotNull(bind()) { "Send attempt storage is unavailable." }
        store.save(TextEditorDraft(originalText = "", text = sendAttemptJson.encodeToString(attempt), etag = null))
    }

    override suspend fun clear() {
        bind()?.remove()
    }
}

private val sendAttemptJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private const val SEND_ATTEMPT_PATH_PREFIX = "nextcloud-native:send-attempt:"
