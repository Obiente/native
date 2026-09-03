package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun `malformed ciphertext is preserved across load save clear and pruning`() =
        withStore { root, _, store ->
            val session = session()
            val damaged = persisted(cardId = 42L, title = "Damaged")
            val safe = persisted(cardId = 43L, title = "Safe")
            store.save(session, damaged)
            val damagedFile = root.resolve(store.storageFileName(session, damaged.key))
            damagedFile.writeText("""{"version":1,"nonce":"bad","ciphertext":"bad"}""")
            val malformedBytes = damagedFile.readBytes()

            assertFailsWith<DesktopDeckDraftRecoveryException> {
                store.load(session, damaged.key)
            }
            assertFailsWith<DesktopDeckDraftRecoveryException> {
                store.save(session, damaged.copy(draft = damaged.draft.copy(title = "Replacement")))
            }
            assertFailsWith<DesktopDeckDraftRecoveryException> {
                store.clear(session, damaged.key)
            }
            assertContentEquals(malformedBytes, damagedFile.readBytes())

            store.save(session, safe)

            assertContentEquals(malformedBytes, damagedFile.readBytes())
            assertEquals(safe, store.load(session, safe.key))
        }

    @Test
    fun `explicit discard removes permanently unreadable ciphertext`() =
        withStore { root, _, store ->
            val session = session()
            val damaged = persisted()
            store.save(session, damaged)
            val damagedFile = root.resolve(store.storageFileName(session, damaged.key))
            damagedFile.writeText("not-an-envelope")

            assertFailsWith<DesktopDeckDraftRecoveryException> {
                store.load(session, damaged.key)
            }

            store.clear(session, damaged.key, discardUnreadable = true)

            assertFalse(damagedFile.exists())
        }

    @Test
    fun `unreadable drafts count toward the retention ceiling`() =
        withStore { root, _, store ->
            val session = session()
            val damaged = persisted(cardId = 1L)
            store.save(session, damaged)
            root.resolve(store.storageFileName(session, damaged.key)).writeText("not-an-envelope")

            (2L..(DeckCardDraftRetention.MAX_ENTRIES + 3L)).forEach { cardId ->
                store.save(session, persisted(cardId = cardId))
            }

            assertEquals(DeckCardDraftRetention.MAX_ENTRIES, root.listFiles().orEmpty().size)
            assertTrue(root.resolve(store.storageFileName(session, damaged.key)).exists())
        }

    @Test
    fun `unreadable drafts cannot grow beyond the retention ceiling`() =
        withStore { root, _, store ->
            val session = session()
            repeat(DeckCardDraftRetention.MAX_ENTRIES) { index ->
                root.resolve("${DesktopDeckCardDraftStore.FILE_PREFIX}${index.toString(16).padStart(64, '0')}" +
                    DesktopDeckCardDraftStore.FILE_SUFFIX).writeText("not-an-envelope")
            }

            assertFailsWith<IllegalStateException> {
                store.save(session, persisted())
            }

            assertEquals(DeckCardDraftRetention.MAX_ENTRIES, root.listFiles().orEmpty().size)
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

            assertFailsWith<DeckCardDraftResetRequiredException> {
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
    fun `transient secret store failure preserves draft through restart save and dismissal clear`() {
        val root = Files.createTempDirectory("desktop-deck-drafts").toFile()
        val secretStore = ToggleSecretStore()
        val session = session()
        val persisted = persisted()
        try {
            val initial = storeWithPlatformKey(root, secretStore)
            initial.save(session, persisted)
            val file = root.resolve(initial.storageFileName(session, persisted.key))
            val originalBytes = file.readBytes()
            secretStore.failure = DesktopSecretStoreUnavailableException(
                "Synthetic locked keyring.",
            )
            val restarted = storeWithPlatformKey(root, secretStore)

            assertFailsWith<DesktopSecretStoreUnavailableException> {
                restarted.load(session, persisted.key)
            }
            assertFailsWith<DesktopSecretStoreUnavailableException> {
                restarted.save(
                    session,
                    persisted.copy(draft = persisted.draft.copy(title = "Replacement")),
                )
            }
            assertFailsWith<DesktopSecretStoreUnavailableException> {
                restarted.clear(session, persisted.key)
            }
            assertContentEquals(originalBytes, file.readBytes())

            secretStore.failure = null
            assertEquals(
                persisted,
                storeWithPlatformKey(root, secretStore).load(session, persisted.key),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing key is not replaced while an encrypted draft exists`() {
        val root = Files.createTempDirectory("desktop-deck-drafts").toFile()
        val secretStore = ToggleSecretStore()
        val session = session()
        val persisted = persisted()
        try {
            val firstStore = storeWithPlatformKey(root, secretStore)
            firstStore.save(session, persisted)
            val file = root.resolve(firstStore.storageFileName(session, persisted.key))
            val originalBytes = file.readBytes()
            secretStore.secret = null

            val failure = assertFailsWith<DeckCardDraftResetRequiredException> {
                storeWithPlatformKey(root, secretStore).save(
                    session,
                    persisted.copy(draft = persisted.draft.copy(title = "Replacement")),
                )
            }

            assertTrue(failure.message.orEmpty().contains("encrypted drafts still exist"))
            assertNull(secretStore.secret)
            assertContentEquals(originalBytes, file.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unreadable draft directory requires the existing secret`() {
        val root = Files.createTempDirectory("desktop-deck-drafts").toFile()
        try {
            assertTrue(desktopDeckLegacySecretRequired(root) { null })
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

    @Test
    fun `submitted draft quarantine blocks recovery when immediate deletion fails`() {
        val root = Files.createTempDirectory("desktop-deck-drafts-quarantine").toFile()
        val key = ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES) { (it + 1).toByte() }
        val session = session()
        val persisted = persisted()
        try {
            val writer = DesktopDeckCardDraftStore(root, fixedKey(key))
            writer.save(session, persisted)
            val draftName = writer.storageFileName(session, persisted.key)
            val failedCleanup = DesktopDeckCardDraftStore(
                root = root,
                keyProvider = fixedKey(key),
                deleteFile = { file ->
                    if (file.name == draftName) false else Files.deleteIfExists(file.toPath()) || !file.exists()
                },
            )

            failedCleanup.quarantineAfterSubmit(session, persisted.key)

            assertTrue(root.resolve(draftName).isFile)
            assertTrue(
                root.listFiles().orEmpty()
                    .any { it.name.matches(DesktopDeckCardDraftStore.SUBMITTED_FILE_PATTERN) },
            )
            assertNull(DesktopDeckCardDraftStore(root, fixedKey(key)).load(session, persisted.key))
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `explicit reset removes every draft and submitted quarantine`() {
        withStore { root, _, store ->
            val session = session()
            val first = persisted(cardId = 42L)
            val second = persisted(cardId = 43L)
            store.save(session, first)
            store.save(session, second)

            store.discardAll()

            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertNull(store.load(session, first.key))
            assertNull(store.load(session, second.key))
        }
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

    private fun storeWithPlatformKey(
        root: java.io.File,
        secretStore: ToggleSecretStore,
    ): DesktopDeckCardDraftStore = DesktopDeckCardDraftStore(
        root = root,
        keyProvider = PlatformDeckDraftKeyProvider(
            secretStore = secretStore,
            legacySecretRequired = { desktopDeckLegacySecretRequired(root) },
        ),
    )

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

    private class ToggleSecretStore : DesktopSecretStore {
        var secret: ByteArray? = null
        var failure: RuntimeException? = null

        override fun load(reference: DesktopSecretReference): ByteArray? {
            failure?.let { throw it }
            return secret?.copyOf()
        }

        override fun save(
            reference: DesktopSecretReference,
            username: String?,
            secret: ByteArray,
        ) {
            failure?.let { throw it }
            this.secret = secret.copyOf()
        }

        override fun clear(reference: DesktopSecretReference) {
            failure?.let { throw it }
            secret = null
        }
    }
}
