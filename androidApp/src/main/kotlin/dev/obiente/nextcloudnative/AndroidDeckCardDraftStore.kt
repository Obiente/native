package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.DeckCardDraftKey
import dev.obiente.nextcloudnative.app.DeckCardDraftRetention
import dev.obiente.nextcloudnative.app.DeckUiCardDraft
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.PersistedDeckCardDraft
import org.json.JSONObject
import java.security.MessageDigest

/** Encrypted app-private storage for bounded Deck editor recovery. */
internal class AndroidDeckCardDraftStore(
    context: Context,
    preferencesName: String = PREFERENCES,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val cipher = SessionCipher()

    @Synchronized
    fun load(session: NextcloudSession, key: DeckCardDraftKey): PersistedDeckCardDraft? {
        val storedKey = storageKey(session, key)
        val encrypted = preferences.getString(storedKey, null) ?: return null
        return decode(encrypted)
            ?.takeIf { it.draft.key == key }
            ?.draft
            ?: run {
                preferences.edit().remove(storedKey).commit()
                null
            }
    }

    @Synchronized
    fun save(session: NextcloudSession, persisted: PersistedDeckCardDraft) {
        val storedKey = storageKey(session, persisted.key)
        val value = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("updatedAtEpochMillis", nowEpochMillis())
            .put("boardId", persisted.key.boardId)
            .put("stackId", persisted.key.stackId)
            .put("cardId", persisted.key.cardId)
            .put("title", persisted.draft.title)
            .put("descriptionMarkdown", persisted.draft.descriptionMarkdown)
            .put("dueDate", persisted.draft.dueDate)
            .put("dueTime", persisted.draft.dueTime)
            .put("dueAtBeforeEditing", persisted.draft.dueAtBeforeEditing)
            .put("dueFieldsEdited", persisted.draft.dueFieldsEdited)
            .toString()
        check(
            preferences.edit()
                .putString(storedKey, cipher.encrypt(value))
                .commit(),
        ) { "The Deck card draft could not be saved." }
        prune()
    }

    @Synchronized
    fun clear(session: NextcloudSession, key: DeckCardDraftKey) {
        check(preferences.edit().remove(storageKey(session, key)).commit()) {
            "The Deck card draft could not be cleared."
        }
    }

    private fun decode(encrypted: String): StoredDeckCardDraft? = runCatching {
        val value = JSONObject(cipher.decrypt(encrypted))
        require(value.getInt("version") == FORMAT_VERSION) {
            "The Deck draft format is unsupported."
        }
        StoredDeckCardDraft(
            draft = PersistedDeckCardDraft(
                key = DeckCardDraftKey(
                    boardId = value.getLong("boardId"),
                    stackId = value.getLong("stackId"),
                    cardId = if (value.isNull("cardId")) null else value.getLong("cardId"),
                ),
                draft = DeckUiCardDraft(
                    title = value.getString("title"),
                    descriptionMarkdown = value.getString("descriptionMarkdown"),
                    dueDate = value.getString("dueDate"),
                    dueTime = value.getString("dueTime"),
                    dueAtBeforeEditing = if (value.isNull("dueAtBeforeEditing")) {
                        null
                    } else {
                        value.getString("dueAtBeforeEditing")
                    },
                    dueFieldsEdited = value.getBoolean("dueFieldsEdited"),
                ),
            ),
            updatedAtEpochMillis = value.getLong("updatedAtEpochMillis"),
        )
    }.getOrNull()

    private fun prune() {
        val malformed = linkedSetOf<String>()
        val metadata = preferences.all.mapNotNull { (key, rawValue) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val stored = (rawValue as? String)?.let(::decode)
            if (stored == null) {
                malformed += key
                null
            } else {
                DeckCardDraftRetention.Entry(key, stored.updatedAtEpochMillis)
            }
        }
        val keysToRemove = malformed + DeckCardDraftRetention.keysToPrune(
            entries = metadata,
            maximumEntries = DeckCardDraftRetention.MAX_ENTRIES,
        )
        if (keysToRemove.isEmpty()) return
        val editor = preferences.edit()
        keysToRemove.forEach(editor::remove)
        check(editor.commit()) { "Old Deck card drafts could not be pruned." }
    }

    internal fun storageKey(session: NextcloudSession, key: DeckCardDraftKey): String {
        val scope = listOf(
            NextcloudDocumentIds.accountKey(session),
            key.boardId.toString(),
            key.stackId.toString(),
            key.cardId?.toString() ?: "new",
        ).joinToString(separator = ":")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return "$KEY_PREFIX$digest"
    }

    private data class StoredDeckCardDraft(
        val draft: PersistedDeckCardDraft,
        val updatedAtEpochMillis: Long,
    )

    internal companion object {
        const val PREFERENCES = "nextcloud_native_deck_drafts"
        const val KEY_PREFIX = "draft_"
        const val FORMAT_VERSION = 1
    }
}
