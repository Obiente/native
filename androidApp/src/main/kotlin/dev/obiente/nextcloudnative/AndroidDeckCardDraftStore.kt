package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.DeckCardDraftCapacityException
import dev.obiente.nextcloudnative.app.DeckCardDraftKey
import dev.obiente.nextcloudnative.app.DeckCardDraftRetention
import dev.obiente.nextcloudnative.app.DeckUiCardDraft
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.PersistedDeckCardDraft
import org.json.JSONObject
import java.security.MessageDigest

/** Encrypted app-private storage for bounded Deck editor recovery. */
internal class AndroidDeckCardDraftStore(
    private val storage: AndroidDeckDraftStorage,
    private val cipher: AndroidDeckDraftCipher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        preferencesName: String = PREFERENCES,
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        storage = SharedPreferencesDeckDraftStorage(context, preferencesName),
        cipher = SessionDeckDraftCipher(),
        nowEpochMillis = nowEpochMillis,
    )

    @Synchronized
    fun load(session: NextcloudSession, key: DeckCardDraftKey): PersistedDeckCardDraft? {
        val storedKey = storageKey(session, key)
        if (isQuarantined(storedKey)) {
            storage.remove(setOf(storedKey, quarantineKey(storedKey)))
            return null
        }
        val encrypted = storage.getString(storedKey) ?: return null
        val stored = decode(encrypted)
        requireStorageSlot(stored, storedKey)
        requireResource(stored, key)
        return stored.draft
    }

    @Synchronized
    fun save(session: NextcloudSession, persisted: PersistedDeckCardDraft) {
        val storedKey = storageKey(session, persisted.key)
        clearQuarantineBeforeSave(storedKey)
        val existing = storage.getString(storedKey)
        existing?.let {
            val stored = decode(existing)
            requireStorageSlot(stored, storedKey)
            requireResource(stored, persisted.key)
        }
        if (existing == null) ensureCapacityForNewDraft(session)
        val updatedAtEpochMillis = nowEpochMillis()
        require(updatedAtEpochMillis >= 0L) { "The Deck draft timestamp is invalid." }
        val value = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("storageKey", storedKey)
            .put("updatedAtEpochMillis", updatedAtEpochMillis)
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
        val encrypted = cipher.encrypt(value)
        val verified = decode(encrypted)
        requireStorageSlot(verified, storedKey)
        requireResource(verified, persisted.key)
        check(verified.draft == persisted && verified.updatedAtEpochMillis == updatedAtEpochMillis) {
            "The Deck card draft could not be verified."
        }
        check(storage.putString(storedKey, encrypted)) { "The Deck card draft could not be saved." }
        prune(session)
    }

    @Synchronized
    fun clear(
        session: NextcloudSession,
        key: DeckCardDraftKey,
        discardUnreadable: Boolean = false,
    ) {
        val storedKey = storageKey(session, key)
        if (!discardUnreadable) {
            storage.getString(storedKey)?.let { existing ->
                val stored = decode(existing)
                requireStorageSlot(stored, storedKey)
                requireResource(stored, key)
            }
        }
        check(storage.remove(setOf(storedKey, quarantineKey(storedKey)))) {
            "The Deck card draft could not be cleared."
        }
    }

    @Synchronized
    fun quarantineAfterSubmit(session: NextcloudSession, key: DeckCardDraftKey) {
        val storedKey = storageKey(session, key)
        check(storage.putString(quarantineKey(storedKey), QUARANTINE_MARKER)) {
            "The submitted Deck card draft could not be quarantined."
        }
        storage.remove(setOf(storedKey, quarantineKey(storedKey)))
    }

    @Synchronized
    fun discardAll() {
        val keys = storage.entries().keys.filterTo(linkedSetOf()) { key ->
            key.startsWith(KEY_PREFIX) || key.startsWith(QUARANTINE_PREFIX)
        }
        if (keys.isEmpty()) return
        check(storage.remove(keys)) { "Saved Deck card drafts could not be discarded." }
    }

    private fun decode(encrypted: String): StoredDeckCardDraft = try {
        val value = JSONObject(cipher.decrypt(encrypted))
        require(value.getInt("version") == FORMAT_VERSION) {
            "The Deck draft format is unsupported."
        }
        val updatedAtEpochMillis = value.getLong("updatedAtEpochMillis")
        require(updatedAtEpochMillis >= 0L) { "The Deck draft timestamp is invalid." }
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
            updatedAtEpochMillis = updatedAtEpochMillis,
            storageKey = value.optString("storageKey").takeIf(String::isNotBlank),
        )
    } catch (failure: Exception) {
        throw AndroidDeckDraftRecoveryException(failure)
    }

    private fun requireResource(stored: StoredDeckCardDraft, key: DeckCardDraftKey) {
        if (stored.draft.key != key) {
            throw AndroidDeckDraftRecoveryException(
                IllegalArgumentException("The Deck draft resource identity does not match."),
            )
        }
    }

    private fun requireStorageSlot(stored: StoredDeckCardDraft, expected: String) {
        val recorded = stored.storageKey ?: return
        if (recorded != expected) {
            throw AndroidDeckDraftRecoveryException(
                IllegalArgumentException("The Deck draft storage slot does not match."),
            )
        }
    }

    private fun prune(session: NextcloudSession) {
        var unreadableEntries = 0
        val metadata = storage.entries().mapNotNull { (key, rawValue) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val stored = try {
                (rawValue as? String)?.let(::decode)
            } catch (_: AndroidDeckDraftRecoveryException) {
                null
            }
            if (stored != null && isReadableRetentionEntry(session, key, stored)) {
                DeckCardDraftRetention.Entry(key, stored.updatedAtEpochMillis)
            } else {
                // A Keystore or provider failure can make valid ciphertext temporarily unreadable.
                // Leave it in place so a later app process can recover it.
                unreadableEntries += 1
                null
            }
        }
        val maximumReadableEntries = DeckCardDraftRetention.MAX_ENTRIES - unreadableEntries
        if (maximumReadableEntries <= 0) return
        val keysToRemove = DeckCardDraftRetention.keysToPrune(
            entries = metadata,
            maximumEntries = maximumReadableEntries,
        )
        if (keysToRemove.isEmpty()) return
        check(storage.remove(keysToRemove)) { "Old Deck card drafts could not be pruned." }
    }

    private fun ensureCapacityForNewDraft(session: NextcloudSession) {
        val draftEntries = storage.entries().filterKeys { it.startsWith(KEY_PREFIX) }
        val overflow = draftEntries.size + 1 - DeckCardDraftRetention.MAX_ENTRIES
        if (overflow <= 0) return
        val readableEntries = draftEntries.count { (storedKey, rawValue) ->
            try {
                (rawValue as? String)
                    ?.let(::decode)
                    ?.let { stored -> isReadableRetentionEntry(session, storedKey, stored) } == true
            } catch (_: AndroidDeckDraftRecoveryException) {
                false
            }
        }
        if (readableEntries < overflow) throw DeckCardDraftCapacityException()
    }

    private fun isReadableRetentionEntry(
        session: NextcloudSession,
        storedKey: String,
        stored: StoredDeckCardDraft,
    ): Boolean = stored.storageKey?.let { recorded -> recorded == storedKey }
        ?: (storageKey(session, stored.draft.key) == storedKey)

    private fun isQuarantined(storedKey: String): Boolean =
        quarantineKey(storedKey) in storage.entries()

    private fun clearQuarantineBeforeSave(storedKey: String) {
        val markerKey = quarantineKey(storedKey)
        if (markerKey !in storage.entries()) return
        check(storage.remove(setOf(storedKey, markerKey))) {
            "The submitted Deck card draft quarantine could not be cleared."
        }
    }

    private fun quarantineKey(storedKey: String): String =
        "$QUARANTINE_PREFIX${storedKey.removePrefix(KEY_PREFIX)}"

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
        val storageKey: String?,
    )

    internal companion object {
        const val PREFERENCES = "nextcloud_native_deck_drafts"
        const val KEY_PREFIX = "draft_"
        const val QUARANTINE_PREFIX = "submitted_"
        const val QUARANTINE_MARKER = "confirmed"
        const val FORMAT_VERSION = 1
    }
}

internal class AndroidDeckDraftRecoveryException(
    cause: Throwable,
) : IllegalStateException("The saved Deck card draft could not be restored safely.", cause)

internal interface AndroidDeckDraftCipher {
    fun encrypt(value: String): String

    fun decrypt(value: String): String
}

private class SessionDeckDraftCipher : AndroidDeckDraftCipher {
    private val delegate = SessionCipher()

    override fun encrypt(value: String): String = delegate.encrypt(value)

    override fun decrypt(value: String): String = delegate.decrypt(value)
}

internal interface AndroidDeckDraftStorage {
    fun getString(key: String): String?

    fun entries(): Map<String, Any?>

    fun putString(key: String, value: String): Boolean

    fun remove(keys: Set<String>): Boolean
}

private class SharedPreferencesDeckDraftStorage(
    context: Context,
    preferencesName: String,
) : AndroidDeckDraftStorage {
    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun entries(): Map<String, Any?> = preferences.all

    override fun putString(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()

    override fun remove(keys: Set<String>): Boolean {
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }
}
