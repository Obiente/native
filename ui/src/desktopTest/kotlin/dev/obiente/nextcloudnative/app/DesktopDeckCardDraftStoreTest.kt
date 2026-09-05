package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

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
                root.resolve(store.storageFileName(session, persisted(cardId = 10_000L + index).key))
                    .writeText("not-an-envelope")
            }

            assertFailsWith<DeckCardDraftCapacityException> {
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
    fun `each account has its own retention budget`() = withStore { root, _, store ->
        val alice = session()
        val bob = session(login = "bob")

        repeat(DeckCardDraftRetention.MAX_ENTRIES) { index ->
            store.save(alice, persisted(cardId = 20_000L + index))
            store.save(bob, persisted(cardId = 30_000L + index))
        }

        assertEquals(DeckCardDraftRetention.MAX_ENTRIES * 2, root.listFiles().orEmpty().size)
        assertEquals(persisted(cardId = 20_000L), store.load(alice, persisted(cardId = 20_000L).key))
        assertEquals(persisted(cardId = 30_000L), store.load(bob, persisted(cardId = 30_000L).key))
    }

    @Test
    fun `account removal is retryable and preserves another account`() {
        val root = Files.createTempDirectory("desktop-deck-drafts-removal").toFile()
        val key = ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES) { (it + 1).toByte() }
        val alice = session()
        val bob = session(login = "bob")
        val removed = persisted(cardId = 51L)
        val retained = persisted(cardId = 52L)
        try {
            val writer = DesktopDeckCardDraftStore(root, fixedKey(key))
            writer.save(alice, removed)
            writer.save(bob, retained)
            val aliceDraftName = writer.storageFileName(alice, removed.key)
            val aliceMarkerName = aliceDraftName
                .replaceFirst(DesktopDeckCardDraftStore.FILE_PREFIX, DesktopDeckCardDraftStore.SUBMITTED_FILE_PREFIX)
                .removeSuffix(DesktopDeckCardDraftStore.FILE_SUFFIX) + DesktopDeckCardDraftStore.SUBMITTED_FILE_SUFFIX
            root.resolve(aliceMarkerName).writeBytes(DesktopDeckCardDraftStore.SUBMITTED_MARKER_BYTES)
            val alicePrefix = "${DesktopDeckCardDraftStore.FILE_PREFIX}${alice.accountId.storageKey}_"
            val failing = DesktopDeckCardDraftStore(
                root = root,
                keyProvider = fixedKey(key),
                deleteFile = { file ->
                    if (file.name.startsWith(alicePrefix)) false
                    else Files.deleteIfExists(file.toPath()) || !file.exists()
                },
            )

            assertFailsWith<IllegalStateException> {
                failing.removeAccount(alice.accountId.storageKey, desktopFileCacheAccountId(alice))
            }

            writer.removeAccount(alice.accountId.storageKey, desktopFileCacheAccountId(alice))

            assertNull(writer.load(alice, removed.key))
            assertEquals(retained, writer.load(bob, retained.key))
            assertTrue(root.listFiles().orEmpty().none { it.name.contains(alice.accountId.storageKey) })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `completed legacy migration is not rolled back when deletion must retry`() {
        val root = Files.createTempDirectory("desktop-deck-drafts-migration").toFile()
        val key = ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES) { (it + 1).toByte() }
        val session = session()
        val original = persisted(title = "Legacy")
        try {
            val probe = DesktopDeckCardDraftStore(root, fixedKey(key))
            val legacy = root.resolve(
                probe.legacyStorageFileName(desktopFileCacheAccountId(session), original.key),
            )
            writeLegacyDraft(legacy, key, original)
            val failing = DesktopDeckCardDraftStore(
                root = root,
                keyProvider = fixedKey(key),
                deleteFile = { file ->
                    if (file == legacy) false else Files.deleteIfExists(file.toPath()) || !file.exists()
                },
            )

            failing.migrateLegacyEntries(session)
            val updated = original.copy(draft = original.draft.copy(title = "Newer"))
            failing.save(session, updated)

            assertEquals(updated, failing.load(session, original.key))
            assertTrue(legacy.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `account removal preserves unreadable and other account legacy drafts`() =
        withStore { root, key, store ->
            val alice = session()
            val bob = session(login = "bob")
            val aliceLegacy = root.resolve("draft_${"a".repeat(64)}.json.enc").apply {
                writeText("unreadable")
            }
            val bobDraft = persisted(cardId = 91L)
            val bobLegacy = root.resolve(
                store.legacyStorageFileName(desktopFileCacheAccountId(bob), bobDraft.key),
            )
            writeLegacyDraft(bobLegacy, key, bobDraft)
            val aliceDraft = persisted(cardId = 92L)
            val attributable = root.resolve(
                store.legacyStorageFileName(desktopFileCacheAccountId(alice), aliceDraft.key),
            )
            writeLegacyDraft(attributable, key, aliceDraft)

            store.removeAccount(alice.accountId.storageKey, desktopFileCacheAccountId(alice))

            assertTrue(aliceLegacy.exists())
            assertTrue(bobLegacy.exists())
            assertFalse(attributable.exists())
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
    fun `generated key is rejected when secure storage does not persist it`() {
        var saves = 0
        val secretStore = object : DesktopSecretStore {
            override fun load(reference: DesktopSecretReference): ByteArray? = null

            override fun save(
                reference: DesktopSecretReference,
                username: String?,
                secret: ByteArray,
            ) {
                saves += 1
            }

            override fun clear(reference: DesktopSecretReference) = Unit
        }
        val provider = PlatformDeckDraftKeyProvider(
            secretStore = secretStore,
            legacySecretRequired = { false },
        )

        repeat(2) {
            val failure = assertFailsWith<DesktopSecretStoreUnavailableException> {
                provider.encryptionKey()
            }
            assertTrue(failure.message.orEmpty().contains("could not be verified"))
        }

        assertEquals(2, saves)
    }

    @Test
    fun `generated key is rejected when secure storage readback changes it`() {
        var saved = false
        val replacement = Base64.getEncoder().encode(
            ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES) { 0x5a },
        )
        val secretStore = object : DesktopSecretStore {
            override fun load(reference: DesktopSecretReference): ByteArray? =
                replacement.copyOf().takeIf { saved }

            override fun save(
                reference: DesktopSecretReference,
                username: String?,
                secret: ByteArray,
            ) {
                saved = true
            }

            override fun clear(reference: DesktopSecretReference) = Unit
        }

        val failure = assertFailsWith<DesktopSecretStoreUnavailableException> {
            PlatformDeckDraftKeyProvider(
                secretStore = secretStore,
                legacySecretRequired = { false },
            ).encryptionKey()
        }

        assertTrue(failure.message.orEmpty().contains("changed during secure storage verification"))
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
    fun `publish and delete sync the draft directory entry`() {
        val root = Files.createTempDirectory("desktop-deck-drafts-directory-sync").toFile()
        val key = ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES) { (it + 1).toByte() }
        val session = session()
        val persisted = persisted()
        val syncedDraftPresence = mutableListOf<Boolean>()
        try {
            lateinit var draftFile: java.io.File
            val store = DesktopDeckCardDraftStore(
                root = root,
                keyProvider = fixedKey(key),
                syncDirectory = { directory ->
                    assertEquals(root.canonicalFile, directory.canonicalFile)
                    syncedDraftPresence += draftFile.exists()
                },
            )
            draftFile = root.resolve(store.storageFileName(session, persisted.key))

            store.save(session, persisted)
            store.clear(session, persisted.key)

            assertEquals(listOf(true, false), syncedDraftPresence)
        } finally {
            root.deleteRecursively()
        }
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

    private fun writeLegacyDraft(
        file: java.io.File,
        key: ByteArray,
        persisted: PersistedDeckCardDraft,
    ) {
        val plaintext = JSONObject()
            .put("version", DesktopDeckCardDraftStore.LEGACY_PLAINTEXT_FORMAT_VERSION)
            .put("updatedAtEpochMillis", 100L)
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
            .encodeToByteArray()
        val nonce = ByteArray(DesktopDeckCardDraftStore.GCM_NONCE_BYTES) { (it + 7).toByte() }
        val cipher = Cipher.getInstance(DesktopDeckCardDraftStore.CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, DesktopDeckCardDraftStore.AES_ALGORITHM),
            GCMParameterSpec(DesktopDeckCardDraftStore.GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(file.name.encodeToByteArray())
        file.writeText(
            JSONObject()
                .put("version", DesktopDeckCardDraftStore.ENVELOPE_FORMAT_VERSION)
                .put("nonce", Base64.getEncoder().encodeToString(nonce))
                .put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)))
                .toString(),
        )
    }

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
