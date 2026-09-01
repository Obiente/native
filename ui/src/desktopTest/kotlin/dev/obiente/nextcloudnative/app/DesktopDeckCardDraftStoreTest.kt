package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDeckCardDraftStoreTest {
    @Test
    fun `encrypted draft survives store recreation without exposing content or credentials`() =
        withStore { root, key, store ->
            val session = session(password = "private-app-password")
            val persisted = persisted(
                title = "Private roadmap title",
                description = "Unpublished details",
            )

            store.save(session, persisted)

            val stored = root.listFiles().orEmpty().single()
            val bytes = stored.readBytes()
            assertFalse(bytes.decodeToString().contains("Private roadmap title"))
            assertFalse(bytes.decodeToString().contains("Unpublished details"))
            assertFalse(bytes.decodeToString().contains("private-app-password"))
            assertFalse(stored.name.contains(session.loginName))
            assertFalse(stored.name.contains(session.serverUrl))
            assertEquals(
                persisted,
                DesktopDeckCardDraftStore(root, fixedKey(key)).load(session, persisted.key),
            )
        }

    @Test
    fun `storage identity is account scoped but survives an app password rotation`() =
        withStore { _, _, store ->
            val key = DeckCardDraftKey(boardId = 7L, stackId = 11L, cardId = 42L)
            val alice = session(password = "first")
            val rotated = session(password = "second")
            val bob = session(login = "bob", password = "first")

            assertEquals(
                store.storageFileName(alice, key),
                store.storageFileName(rotated, key),
            )
            assertFalse(store.storageFileName(alice, key) == store.storageFileName(bob, key))
            assertTrue(
                store.storageFileName(alice, key)
                    .matches(DesktopDeckCardDraftStore.DRAFT_FILE_PATTERN),
            )
        }

    @Test
    fun `corrupt ciphertext is discarded without affecting another draft`() =
        withStore { root, _, store ->
            val session = session()
            val damaged = persisted(cardId = 42L, title = "Damaged")
            val safe = persisted(cardId = 43L, title = "Safe")
            store.save(session, damaged)
            store.save(session, safe)
            val damagedFile = root.resolve(store.storageFileName(session, damaged.key))
            damagedFile.writeText("""{"version":1,"nonce":"bad","ciphertext":"bad"}""")

            assertNull(store.load(session, damaged.key))
            assertFalse(damagedFile.exists())
            assertEquals(safe, store.load(session, safe.key))
        }

    @Test
    fun `retention keeps only the newest bounded draft set`() {
        val root = Files.createTempDirectory("desktop-deck-drafts").toFile()
        val key = ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES) { it.toByte() }
        val clock = AtomicLong(1L)
        val store = DesktopDeckCardDraftStore(
            root = root,
            keyProvider = fixedKey(key),
            nowEpochMillis = clock::getAndIncrement,
        )
        val session = session()
        try {
            val saved = (1L..(DeckCardDraftRetention.MAX_ENTRIES + 3L)).map { cardId ->
                persisted(cardId = cardId, title = "Card $cardId").also {
                    store.save(session, it)
                }
            }

            assertEquals(DeckCardDraftRetention.MAX_ENTRIES, root.listFiles().orEmpty().size)
            saved.take(3).forEach { assertNull(store.load(session, it.key)) }
            saved.drop(3).forEach { assertEquals(it, store.load(session, it.key)) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `keyring failure does not delete a valid encrypted draft`() =
        withStore { root, _, store ->
            val session = session()
            val persisted = persisted()
            store.save(session, persisted)
            val file = root.resolve(store.storageFileName(session, persisted.key))
            val unavailable = DesktopDeckCardDraftStore(
                root = root,
                keyProvider = DesktopDeckDraftKeyProvider {
                    error("Desktop keyring is unavailable.")
                },
            )

            assertFailsWith<IllegalStateException> {
                unavailable.load(session, persisted.key)
            }
            assertTrue(file.isFile)
        }

    @Test
    fun `missing key does not replace or delete an existing encrypted draft`() =
        withStore { root, _, store ->
            val session = session()
            val persisted = persisted()
            store.save(session, persisted)
            val file = root.resolve(store.storageFileName(session, persisted.key))
            val missingSecrets = object : DesktopSecretStore {
                override fun load(reference: DesktopSecretReference): ByteArray? = null

                override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
                    error("A replacement key must not be saved.")
                }

                override fun clear(reference: DesktopSecretReference) = Unit
            }
            val unavailable = DesktopDeckCardDraftStore(
                root = root,
                keyProvider = PlatformDeckDraftKeyProvider(
                    secretStore = missingSecrets,
                    legacySecretRequired = { desktopDeckLegacySecretRequired(root) },
                ),
            )

            assertFailsWith<DesktopSecretStoreUnavailableException> {
                unavailable.load(session, persisted.key)
            }
            assertTrue(file.isFile)
        }

    @Test
    fun `uninspectable draft directory conservatively requires the legacy secret`() {
        val root = Files.createTempDirectory("desktop-deck-drafts-unreadable").toFile()
        try {
            assertTrue(desktopDeckLegacySecretRequired(root, listFiles = { null }))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `clear removes only the requested account resource`() =
        withStore { root, _, store ->
            val session = session()
            val first = persisted(cardId = 42L, title = "First")
            val second = persisted(cardId = 43L, title = "Second")
            store.save(session, first)
            store.save(session, second)

            store.clear(session, first.key)

            assertNull(store.load(session, first.key))
            assertEquals(second, store.load(session, second.key))
            assertEquals(1, root.listFiles().orEmpty().size)
        }

    private fun withStore(
        block: (root: java.io.File, key: ByteArray, store: DesktopDeckCardDraftStore) -> Unit,
    ) {
        val root = Files.createTempDirectory("desktop-deck-drafts").toFile()
        val key = ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES) { (it + 1).toByte() }
        try {
            block(root, key, DesktopDeckCardDraftStore(root, fixedKey(key)))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fixedKey(key: ByteArray): DesktopDeckDraftKeyProvider =
        DesktopDeckDraftKeyProvider { key.copyOf() }

    private fun session(
        login: String = "alice",
        password: String = "secret",
    ) = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = login,
        appPassword = password,
    )

    private fun persisted(
        cardId: Long = 42L,
        title: String = "Draft title",
        description: String = "Draft description",
    ) = PersistedDeckCardDraft(
        key = DeckCardDraftKey(boardId = 7L, stackId = 11L, cardId = cardId),
        draft = DeckUiCardDraft(
            title = title,
            descriptionMarkdown = description,
            dueDate = "2026-08-01",
            dueTime = "09:30",
            dueAtBeforeEditing = "2026-07-31T10:00:00Z",
            dueFieldsEdited = true,
        ),
    )
}
