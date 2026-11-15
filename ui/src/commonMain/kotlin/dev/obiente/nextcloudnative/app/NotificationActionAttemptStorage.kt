package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json

/**
 * Durable, account-scoped record of the notifications whose action was sent without a known
 * result. A notification action may accept an invitation or a share, so it is recorded before
 * its request leaves the device and stays off, across restarts, until the server answers
 * definitely or the notification is gone.
 */
interface NotificationActionAttemptStore {
    suspend fun load(): Set<Long>

    suspend fun save(notificationIds: Set<Long>)
}

/** At most this many unresolved actions are remembered; older ones belong to stale notifications. */
internal const val MAX_RECORDED_ACTION_ATTEMPTS = 200

/**
 * The record holds notification ids only, never their content. It goes to the platform's
 * encrypted text draft storage, which binds it to the account and deletes it when the account is
 * removed. Null when the platform keeps no draft storage.
 */
internal fun notificationActionAttemptStore(
    services: TextEditorDraftPlatformServices,
    session: NextcloudSession,
): NotificationActionAttemptStore? =
    services.textEditorDraftStore(session, NOTIFICATION_ACTION_ATTEMPTS_PATH)?.let {
        TextDraftBackedNotificationActionAttemptStore { services.textEditorDraftStore(session, NOTIFICATION_ACTION_ATTEMPTS_PATH) }
    }

/** Stores the unresolved notification ids as one encrypted text draft record. */
internal class TextDraftBackedNotificationActionAttemptStore(
    private val bind: () -> TextEditorDraftStore?,
) : NotificationActionAttemptStore {
    /** Throws for a record that cannot be read, so the caller never treats it as "nothing pending". */
    override suspend fun load(): Set<Long> {
        val stored = bind()?.load() ?: return emptySet()
        val ids = notificationAttemptJson.decodeFromString<List<Long>>(stored.text)
        check(ids.size <= MAX_RECORDED_ACTION_ATTEMPTS && ids.all { it > 0L }) { "The stored notification actions are invalid." }
        return ids.toSet()
    }

    override suspend fun save(notificationIds: Set<Long>) {
        val store = checkNotNull(bind()) { "Notification action storage is unavailable." }
        if (notificationIds.isEmpty()) {
            store.remove()
        } else {
            store.save(TextEditorDraft(originalText = "", text = notificationAttemptJson.encodeToString(notificationIds.toList()), etag = null))
        }
    }
}

private val notificationAttemptJson = Json { ignoreUnknownKeys = true }

private const val NOTIFICATION_ACTION_ATTEMPTS_PATH = "nextcloud-native:notification-action-attempts"
