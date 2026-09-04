package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DeckCardDraftKey
import dev.obiente.nextcloudnative.app.DeckCardDraftCapacityException
import dev.obiente.nextcloudnative.app.DeckCardDraftRetention
import dev.obiente.nextcloudnative.app.DeckUiCardDraft
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.PersistedDeckCardDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDeckCardDraftStoreTest {
    @Test
    fun `transient cipher failure preserves a draft for restart recovery`() {
        val storage = MemoryDeckDraftStorage()
        val persisted = persisted()
        val writer = store(storage, IdentityDeckDraftCipher)
        writer.save(session, persisted)
        val storageKey = writer.storageKey(session, persisted.key)
        val encrypted = storage.values.getValue(storageKey)

        val unavailable = store(
            storage,
            object : AndroidDeckDraftCipher {
                override fun encrypt(value: String): String = value

                override fun decrypt(value: String): String {
                    error("Android Keystore is temporarily unavailable.")
                }
            },
        )

        assertFailsWith<AndroidDeckDraftRecoveryException> {
            unavailable.load(session, persisted.key)
        }
        assertEquals(encrypted, storage.values[storageKey])
        assertFailsWith<AndroidDeckDraftRecoveryException> {
            unavailable.save(session, persisted.copy(draft = persisted.draft.copy(title = "Changed")))
        }
        assertEquals(encrypted, storage.values[storageKey])
        assertFailsWith<AndroidDeckDraftRecoveryException> {
            unavailable.clear(session, persisted.key)
        }
        assertEquals(encrypted, storage.values[storageKey])

        val restarted = store(storage, IdentityDeckDraftCipher)
        assertEquals(persisted, restarted.load(session, persisted.key))
    }

    @Test
    fun `new draft is not committed when ciphertext cannot be verified`() {
        val storage = MemoryDeckDraftStorage()
        val unavailable = store(
            storage,
            object : AndroidDeckDraftCipher {
                override fun encrypt(value: String): String = value

                override fun decrypt(value: String): String {
                    error("Android Keystore is temporarily unavailable.")
                }
            },
        )

        assertFailsWith<AndroidDeckDraftRecoveryException> {
            unavailable.save(session, persisted())
        }
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun `malformed data remains available for later recovery`() {
        val storage = MemoryDeckDraftStorage()
        val store = store(storage, IdentityDeckDraftCipher)
        val key = persisted().key
        val storageKey = store.storageKey(session, key)
        storage.values[storageKey] = "not-json"

        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.load(session, key)
        }
        assertEquals("not-json", storage.values[storageKey])
        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.clear(session, key)
        }
        assertEquals("not-json", storage.values[storageKey])

        store.save(session, persisted(cardId = 43L, title = "Another draft"))
        assertEquals("not-json", storage.values[storageKey])
    }

    @Test
    fun `explicit discard removes a permanently unreadable draft`() {
        val storage = MemoryDeckDraftStorage()
        val store = store(storage, IdentityDeckDraftCipher)
        val key = persisted().key
        val storageKey = store.storageKey(session, key)
        storage.values[storageKey] = "not-json"

        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.load(session, key)
        }

        store.clear(session, key, discardUnreadable = true)

        assertNull(storage.values[storageKey])
    }

    @Test
    fun `retention prunes readable drafts without deleting unreadable recovery data`() {
        val storage = MemoryDeckDraftStorage()
        var now = 0L
        val store = AndroidDeckCardDraftStore(
            storage = storage,
            cipher = IdentityDeckDraftCipher,
            nowEpochMillis = { ++now },
        )
        val unreadableKey = "${AndroidDeckCardDraftStore.KEY_PREFIX}unreadable"
        storage.values[unreadableKey] = "not-json"
        val saved = (1L..(DeckCardDraftRetention.MAX_ENTRIES + 3L)).map { cardId ->
            persisted(cardId = cardId, title = "Draft $cardId").also {
                store.save(session, it)
            }
        }

        assertEquals("not-json", storage.values[unreadableKey])
        assertEquals(DeckCardDraftRetention.MAX_ENTRIES, storage.values.size)
        saved.take(4).forEach { assertNull(store.load(session, it.key)) }
        saved.drop(4).forEach { assertEquals(it, store.load(session, it.key)) }
    }

    @Test
    fun `retention preserves ciphertext copied into a mismatched storage slot`() {
        val storage = MemoryDeckDraftStorage()
        var now = 0L
        val store = AndroidDeckCardDraftStore(
            storage = storage,
            cipher = IdentityDeckDraftCipher,
            nowEpochMillis = { ++now },
        )
        val source = persisted(cardId = 42L)
        store.save(session, source)
        val sourceKey = store.storageKey(session, source.key)
        val mismatchedKey = store.storageKey(session, persisted(cardId = 99L).key)
        storage.values[mismatchedKey] = storage.values.getValue(sourceKey)
        storage.values.remove(sourceKey)

        repeat(DeckCardDraftRetention.MAX_ENTRIES + 2) { index ->
            store.save(session, persisted(cardId = 1_000L + index, title = "Draft $index"))
        }

        assertTrue(mismatchedKey in storage.values)
        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.load(session, persisted(cardId = 99L).key)
        }
        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.clear(session, persisted(cardId = 99L).key)
        }
        assertTrue(mismatchedKey in storage.values)
    }

    @Test
    fun `unreadable drafts can fill but cannot exceed the retention ceiling`() {
        val storage = MemoryDeckDraftStorage()
        repeat(DeckCardDraftRetention.MAX_ENTRIES) { index ->
            storage.values["${AndroidDeckCardDraftStore.KEY_PREFIX}unreadable-$index"] = "not-json"
        }
        val store = store(storage, IdentityDeckDraftCipher)

        assertFailsWith<DeckCardDraftCapacityException> {
            store.save(session, persisted())
        }

        assertEquals(DeckCardDraftRetention.MAX_ENTRIES, storage.values.size)
    }

    @Test
    fun `all unreadable entries after commit do not pass a zero retention limit`() {
        val storage = MemoryDeckDraftStorage()
        val writer = store(storage, IdentityDeckDraftCipher)
        repeat(DeckCardDraftRetention.MAX_ENTRIES) { index ->
            writer.save(session, persisted(cardId = 1_000L + index))
        }
        var decryptions = 0
        val temporarilyUnavailable = store(
            storage,
            object : AndroidDeckDraftCipher {
                override fun encrypt(value: String): String = value

                override fun decrypt(value: String): String {
                    decryptions += 1
                    if (decryptions > 2) error("Android Keystore became unavailable.")
                    return value
                }
            },
        )
        val updated = persisted(cardId = 1_000L, title = "Updated safely")

        temporarilyUnavailable.save(session, updated)

        assertEquals(DeckCardDraftRetention.MAX_ENTRIES, storage.values.size)
        assertEquals(updated, writer.load(session, updated.key))
    }

    @Test
    fun `explicit reset restores capacity after unreadable drafts fill the store`() {
        val storage = MemoryDeckDraftStorage()
        repeat(DeckCardDraftRetention.MAX_ENTRIES) { index ->
            storage.values["${AndroidDeckCardDraftStore.KEY_PREFIX}unreadable-$index"] = "not-json"
        }
        val store = store(storage, IdentityDeckDraftCipher)

        assertFailsWith<DeckCardDraftCapacityException> { store.save(session, persisted()) }
        store.discardAll()
        store.save(session, persisted())

        assertEquals(persisted(), store.load(session, persisted().key))
    }

    @Test
    fun `submitted draft quarantine survives cleanup failure and blocks recovery`() {
        val storage = MemoryDeckDraftStorage()
        val store = store(storage, IdentityDeckDraftCipher)
        val persisted = persisted()
        store.save(session, persisted)
        val storedKey = store.storageKey(session, persisted.key)
        val markerKey = AndroidDeckCardDraftStore.QUARANTINE_PREFIX +
            storedKey.removePrefix(AndroidDeckCardDraftStore.KEY_PREFIX)
        storage.removeSucceeds = false

        store.quarantineAfterSubmit(session, persisted.key)

        assertTrue(storedKey in storage.values)
        assertEquals(AndroidDeckCardDraftStore.QUARANTINE_MARKER, storage.values[markerKey])
        assertNull(store.load(session, persisted.key))
        assertTrue(storedKey in storage.values)
        storage.removeSucceeds = true
        assertNull(store.load(session, persisted.key))
        assertTrue(storedKey !in storage.values)
        assertTrue(markerKey !in storage.values)
    }

    @Test
    fun `submitted draft quarantine fails closed when its marker value is corrupted`() {
        val storage = MemoryDeckDraftStorage()
        val store = store(storage, IdentityDeckDraftCipher)
        val persisted = persisted()
        store.save(session, persisted)
        val storedKey = store.storageKey(session, persisted.key)
        val markerKey = AndroidDeckCardDraftStore.QUARANTINE_PREFIX +
            storedKey.removePrefix(AndroidDeckCardDraftStore.KEY_PREFIX)
        storage.values[markerKey] = "truncated"
        storage.removeSucceeds = false

        assertNull(store.load(session, persisted.key))
        assertTrue(storedKey in storage.values)
        assertEquals("truncated", storage.values[markerKey])
        storage.values.remove(markerKey)
        assertEquals(persisted, store.load(session, persisted.key))
    }

    @Test
    fun `resource mismatch does not destroy the stored ciphertext`() {
        val storage = MemoryDeckDraftStorage()
        val store = store(storage, IdentityDeckDraftCipher)
        val expected = persisted()
        store.save(session, expected)
        val storageKey = store.storageKey(session, expected.key)
        val ciphertext = storage.values.getValue(storageKey) as String
        storage.values[storageKey] = ciphertext.replace(
            oldValue = "\"cardId\":42",
            newValue = "\"cardId\":99",
        )

        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.load(session, expected.key)
        }
        assertTrue(storageKey in storage.values)
    }

    @Test
    fun `invalid retention metadata cannot be loaded overwritten or cleared`() {
        val storage = MemoryDeckDraftStorage()
        val store = store(storage, IdentityDeckDraftCipher)
        val expected = persisted()
        store.save(session, expected)
        val storageKey = store.storageKey(session, expected.key)
        val malformed = storage.values.getValue(storageKey).toString().replace(
            oldValue = "\"updatedAtEpochMillis\":100",
            newValue = "\"updatedAtEpochMillis\":-1",
        )
        storage.values[storageKey] = malformed

        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.load(session, expected.key)
        }
        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.save(session, expected.copy(draft = expected.draft.copy(title = "Changed")))
        }
        assertFailsWith<AndroidDeckDraftRecoveryException> {
            store.clear(session, expected.key)
        }
        assertEquals(malformed, storage.values[storageKey])
    }

    private fun store(
        storage: AndroidDeckDraftStorage,
        cipher: AndroidDeckDraftCipher,
    ) = AndroidDeckCardDraftStore(
        storage = storage,
        cipher = cipher,
        nowEpochMillis = { 100L },
    )

    private fun persisted(
        cardId: Long = 42L,
        title: String = "Draft title",
    ) = PersistedDeckCardDraft(
        key = DeckCardDraftKey(boardId = 7L, stackId = 11L, cardId = cardId),
        draft = DeckUiCardDraft(
            title = title,
            descriptionMarkdown = "Unpublished details",
            dueDate = "2026-09-02",
            dueTime = "09:30",
            dueAtBeforeEditing = "2026-09-02T07:30:00Z",
            dueFieldsEdited = true,
        ),
    )

    private class MemoryDeckDraftStorage : AndroidDeckDraftStorage {
        val values = linkedMapOf<String, Any?>()
        var removeSucceeds = true

        override fun getString(key: String): String? = values[key] as? String

        override fun entries(): Map<String, Any?> = values.toMap()

        override fun putString(key: String, value: String): Boolean {
            values[key] = value
            return true
        }

        override fun remove(keys: Set<String>): Boolean {
            if (!removeSucceeds) return false
            keys.forEach(values::remove)
            return true
        }
    }

    private object IdentityDeckDraftCipher : AndroidDeckDraftCipher {
        override fun encrypt(value: String): String = value

        override fun decrypt(value: String): String = value
    }

    private companion object {
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test",
            loginName = "alice",
            appPassword = "test-password",
        )
    }
}
