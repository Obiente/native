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

    fun load(session: NextcloudSession, key: DeckCardDraftKey): PersistedDeckCardDraft? =
        synchronized(STORAGE_LOCK) {
            migrateLegacyEntry(session.accountId.storageKey, NextcloudDocumentIds.accountKey(session), key)
            val storedKey = storageKey(session, key)
            if (isQuarantined(storedKey)) {
                storage.remove(setOf(storedKey, quarantineKey(storedKey)))
                return@synchronized null
            }
            val encrypted = storage.getString(storedKey) ?: return@synchronized null
            val stored = decode(encrypted)
            requireStorageSlot(stored, storedKey)
            requireStorageOwner(stored, session.accountId.storageKey)
            requireResource(stored, key)
            stored.draft
        }

    fun save(session: NextcloudSession, persisted: PersistedDeckCardDraft): Unit =
        synchronized(STORAGE_LOCK) {
            migrateLegacyEntries(session.accountId.storageKey, NextcloudDocumentIds.accountKey(session))
            val storedKey = storageKey(session, persisted.key)
            clearQuarantineBeforeSave(storedKey)
            val existing = storage.getString(storedKey)
            existing?.let {
                val stored = decode(existing)
                requireStorageSlot(stored, storedKey)
                requireStorageOwner(stored, session.accountId.storageKey)
                requireResource(stored, persisted.key)
            }
            if (existing == null) ensureCapacityForNewDraft(session)
            val updatedAtEpochMillis = nowEpochMillis()
            require(updatedAtEpochMillis >= 0L) { "The Deck draft timestamp is invalid." }
            val encrypted = encode(session.accountId.storageKey, storedKey, persisted, updatedAtEpochMillis)
            check(storage.putString(storedKey, encrypted)) { "The Deck card draft could not be saved." }
            prune(session)
        }

    /**
     * Adds authenticated slot metadata to legacy drafts that can be proven to belong to [session].
     *
     * This migration is deliberately best effort: a temporarily unavailable cipher or failed
     * preference write must leave the original recovery record available for a later attempt.
     */
    fun migrateLegacyEntries(session: NextcloudSession): Unit = synchronized(STORAGE_LOCK) {
        migrateLegacyEntries(session.accountId.storageKey, NextcloudDocumentIds.accountKey(session))
    }

    private fun migrateLegacyEntries(accountStorageKey: String, legacyAccountIdentity: String) {
        val entries = try {
            storage.entries()
        } catch (_: Exception) {
            return
        }
        entries.forEach { (storedKey, rawValue) ->
            if (!storedKey.matches(LEGACY_DRAFT_KEY_PATTERN)) return@forEach
            val stored = try {
                (rawValue as? String)?.let(::decode)
            } catch (_: AndroidDeckDraftRecoveryException) {
                null
            } ?: return@forEach
            if (
                stored.accountStorageKey != null ||
                stored.storageKey != null && stored.storageKey != storedKey ||
                legacyStorageKey(legacyAccountIdentity, stored.draft.key) != storedKey
            ) {
                return@forEach
            }
            try {
                migrateLegacyEntry(accountStorageKey, legacyAccountIdentity, stored.draft.key, stored)
            } catch (_: Exception) {
                // Preserve the legacy record so the migration can be retried.
            }
        }
    }

    private fun migrateLegacyEntry(
        accountStorageKey: String,
        legacyAccountIdentity: String,
        key: DeckCardDraftKey,
        decodedLegacy: StoredDeckCardDraft? = null,
    ) {
        val legacyKey = legacyStorageKey(legacyAccountIdentity, key)
        val legacyMarker = quarantineKey(legacyKey, LEGACY_KEY_PREFIX, LEGACY_QUARANTINE_PREFIX)
        val targetKey = storageKey(accountStorageKey, key)
        val targetMarker = quarantineKey(targetKey)
        val legacyEncrypted = storage.getString(legacyKey)
        if (legacyEncrypted == null) {
            val markerValue = storage.entries()[legacyMarker] as? String ?: return
            if (storage.putString(targetMarker, markerValue)) storage.remove(setOf(legacyMarker))
            return
        }
        val legacy = decodedLegacy ?: decode(legacyEncrypted)
        if (
            legacy.accountStorageKey != null ||
            legacy.storageKey != null && legacy.storageKey != legacyKey ||
            legacy.draft.key != key
        ) {
            throw AndroidDeckDraftRecoveryException(
                IllegalArgumentException("The legacy Deck draft identity does not match."),
            )
        }
        val migrated = encode(accountStorageKey, targetKey, legacy.draft, legacy.updatedAtEpochMillis)
        val markerValue = storage.entries()[legacyMarker] as? String
        if (markerValue != null && !storage.putString(targetMarker, markerValue)) return
        val existingTarget = storage.getString(targetKey)
        if (existingTarget == null) {
            if (!storage.putString(targetKey, migrated)) return
        } else {
            val existing = decode(existingTarget)
            requireStorageSlot(existing, targetKey)
            requireStorageOwner(existing, accountStorageKey)
            requireResource(existing, key)
        }
        storage.remove(setOf(legacyKey, legacyMarker))
    }

    private fun encode(
        accountStorageKey: String,
        storedKey: String,
        persisted: PersistedDeckCardDraft,
        updatedAtEpochMillis: Long,
    ): String {
        val value = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("accountStorageKey", accountStorageKey)
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
        requireStorageOwner(verified, accountStorageKey)
        requireResource(verified, persisted.key)
        check(verified.draft == persisted && verified.updatedAtEpochMillis == updatedAtEpochMillis) {
            "The Deck card draft could not be verified."
        }
        return encrypted
    }

    fun clear(
        session: NextcloudSession,
        key: DeckCardDraftKey,
        discardUnreadable: Boolean = false,
    ): Unit = synchronized(STORAGE_LOCK) {
        migrateLegacyEntry(session.accountId.storageKey, NextcloudDocumentIds.accountKey(session), key)
        val storedKey = storageKey(session, key)
        if (!discardUnreadable) {
            storage.getString(storedKey)?.let { existing ->
                val stored = decode(existing)
                requireStorageSlot(stored, storedKey)
                requireStorageOwner(stored, session.accountId.storageKey)
                requireResource(stored, key)
            }
        }
        check(storage.remove(setOf(storedKey, quarantineKey(storedKey)))) {
            "The Deck card draft could not be cleared."
        }
    }

    fun quarantineAfterSubmit(session: NextcloudSession, key: DeckCardDraftKey): Unit =
        synchronized(STORAGE_LOCK) {
            migrateLegacyEntry(session.accountId.storageKey, NextcloudDocumentIds.accountKey(session), key)
            val storedKey = storageKey(session, key)
            check(storage.putString(quarantineKey(storedKey), QUARANTINE_MARKER)) {
                "The submitted Deck card draft could not be quarantined."
            }
            if (!storage.remove(setOf(storedKey, quarantineKey(storedKey)))) return@synchronized
        }

    fun discardAll(): Unit = synchronized(STORAGE_LOCK) {
        val keys = storage.entries().keys.filterTo(linkedSetOf()) { key ->
            key.startsWith(KEY_PREFIX) || key.startsWith(QUARANTINE_PREFIX) ||
                key.matches(LEGACY_DRAFT_KEY_PATTERN) || key.matches(LEGACY_QUARANTINE_KEY_PATTERN)
        }
        if (keys.isEmpty()) return@synchronized
        check(storage.remove(keys)) { "Saved Deck card drafts could not be discarded." }
    }

    fun removeAccount(accountStorageKey: String, legacyAccountIdentity: String) = synchronized(STORAGE_LOCK) {
        require(ACCOUNT_STORAGE_KEY_PATTERN.matches(accountStorageKey))
        require(LEGACY_ACCOUNT_IDENTITY_PATTERN.matches(legacyAccountIdentity))
        val entries = storage.entries()
        val keys = entries.keys.filterTo(linkedSetOf()) { key ->
            key.startsWith(accountDraftPrefix(accountStorageKey)) ||
                key.startsWith(accountQuarantinePrefix(accountStorageKey))
        }
        entries.forEach { (storedKey, rawValue) ->
            if (!storedKey.matches(LEGACY_DRAFT_KEY_PATTERN)) return@forEach
            val stored = try {
                (rawValue as? String)?.let(::decode)
            } catch (_: AndroidDeckDraftRecoveryException) {
                null
            } ?: return@forEach
            if (
                stored.accountStorageKey == null &&
                (stored.storageKey == null || stored.storageKey == storedKey) &&
                legacyStorageKey(legacyAccountIdentity, stored.draft.key) == storedKey
            ) {
                keys += storedKey
                keys += quarantineKey(storedKey, LEGACY_KEY_PREFIX, LEGACY_QUARANTINE_PREFIX)
            }
        }
        if (keys.isNotEmpty()) {
            check(storage.remove(keys)) { "Saved Deck card drafts for the account could not be removed." }
        }
    }

    private fun decode(encrypted: String): StoredDeckCardDraft = try {
        val value = JSONObject(cipher.decrypt(encrypted))
        val version = value.getInt("version")
        require(version == LEGACY_FORMAT_VERSION || version == FORMAT_VERSION) {
            "The Deck draft format is unsupported."
        }
        val updatedAtEpochMillis = value.getLong("updatedAtEpochMillis")
        require(updatedAtEpochMillis >= 0L) { "The Deck draft timestamp is invalid." }
        val storageKey = value.optString("storageKey").takeIf(String::isNotBlank)
        val accountStorageKey = value.optString("accountStorageKey").takeIf(String::isNotBlank)
        require(
            version == LEGACY_FORMAT_VERSION && accountStorageKey == null ||
                version == FORMAT_VERSION && storageKey != null &&
                accountStorageKey?.matches(ACCOUNT_STORAGE_KEY_PATTERN) == true,
        ) { "The Deck draft account storage metadata is invalid." }
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
            storageKey = storageKey,
            accountStorageKey = accountStorageKey,
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

    private fun requireStorageOwner(stored: StoredDeckCardDraft, expected: String) {
        if (stored.accountStorageKey != expected) {
            throw AndroidDeckDraftRecoveryException(
                IllegalArgumentException("The Deck draft account identity does not match."),
            )
        }
    }

    private fun prune(session: NextcloudSession) {
        var unreadableEntries = 0
        val accountStorageKey = session.accountId.storageKey
        val metadata = storage.entries().mapNotNull { (key, rawValue) ->
            if (!key.startsWith(accountDraftPrefix(accountStorageKey))) return@mapNotNull null
            val stored = try {
                (rawValue as? String)?.let(::decode)
            } catch (_: AndroidDeckDraftRecoveryException) {
                null
            }
            if (stored != null && isReadableRetentionEntry(accountStorageKey, key, stored)) {
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
        val accountStorageKey = session.accountId.storageKey
        val draftEntries = storage.entries().filterKeys { it.startsWith(accountDraftPrefix(accountStorageKey)) }
        val overflow = draftEntries.size + 1 - DeckCardDraftRetention.MAX_ENTRIES
        if (overflow <= 0) return
        val readableEntries = draftEntries.count { (storedKey, rawValue) ->
            try {
                (rawValue as? String)
                    ?.let(::decode)
                    ?.let { stored -> isReadableRetentionEntry(accountStorageKey, storedKey, stored) } == true
            } catch (_: AndroidDeckDraftRecoveryException) {
                false
            }
        }
        if (readableEntries < overflow) throw DeckCardDraftCapacityException()
    }

    private fun isReadableRetentionEntry(
        accountStorageKey: String,
        storedKey: String,
        stored: StoredDeckCardDraft,
    ): Boolean = stored.storageKey == storedKey && stored.accountStorageKey == accountStorageKey

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
        quarantineKey(storedKey, KEY_PREFIX, QUARANTINE_PREFIX)

    private fun quarantineKey(storedKey: String, draftPrefix: String, markerPrefix: String): String =
        "$markerPrefix${storedKey.removePrefix(draftPrefix)}"

    internal fun storageKey(session: NextcloudSession, key: DeckCardDraftKey): String {
        return storageKey(session.accountId.storageKey, key)
    }

    private fun storageKey(accountStorageKey: String, key: DeckCardDraftKey): String {
        val scope = listOf(
            key.boardId.toString(),
            key.stackId.toString(),
            key.cardId?.toString() ?: "new",
        ).joinToString(separator = ":")
        return "${accountDraftPrefix(accountStorageKey)}${sha256(scope)}"
    }

    private fun legacyStorageKey(accountIdentity: String, key: DeckCardDraftKey): String {
        val scope = listOf(
            accountIdentity,
            key.boardId.toString(),
            key.stackId.toString(),
            key.cardId?.toString() ?: "new",
        ).joinToString(separator = ":")
        return "$LEGACY_KEY_PREFIX${sha256(scope)}"
    }

    internal fun legacyStorageKey(session: NextcloudSession, key: DeckCardDraftKey): String =
        legacyStorageKey(NextcloudDocumentIds.accountKey(session), key)

    private fun accountDraftPrefix(accountStorageKey: String) = "$KEY_PREFIX${accountStorageKey}_"

    private fun accountQuarantinePrefix(accountStorageKey: String) = "$QUARANTINE_PREFIX${accountStorageKey}_"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private data class StoredDeckCardDraft(
        val draft: PersistedDeckCardDraft,
        val updatedAtEpochMillis: Long,
        val storageKey: String?,
        val accountStorageKey: String?,
    )

    internal companion object {
        private val STORAGE_LOCK = Any()
        const val PREFERENCES = "nextcloud_native_deck_drafts"
        const val KEY_PREFIX = "draft_v2_"
        const val QUARANTINE_PREFIX = "submitted_v2_"
        const val LEGACY_KEY_PREFIX = "draft_"
        const val LEGACY_QUARANTINE_PREFIX = "submitted_"
        const val QUARANTINE_MARKER = "confirmed"
        const val LEGACY_FORMAT_VERSION = 1
        const val FORMAT_VERSION = 2
        private val ACCOUNT_STORAGE_KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val LEGACY_ACCOUNT_IDENTITY_PATTERN = Regex("[0-9a-f]{32}")
        private val LEGACY_DRAFT_KEY_PATTERN = Regex("^draft_[0-9a-f]{64}$")
        private val LEGACY_QUARANTINE_KEY_PATTERN = Regex("^submitted_[0-9a-f]{64}$")
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
