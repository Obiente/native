package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Encrypted, bounded Deck editor recovery in the desktop app's private state directory.
 *
 * The stable filename contains only a digest of the account and Deck resource identity. Draft
 * contents are authenticated and encrypted with a random key held by the desktop secret service.
 */
internal class DesktopDeckCardDraftStore(
    private val root: File = desktopDeckDraftDirectory(),
    private val keyProvider: DesktopDeckDraftKeyProvider = PlatformDeckDraftKeyProvider(
        legacySecretRequired = { desktopDeckLegacySecretRequired(root) },
    ),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val deleteFile: (File) -> Boolean = ::deleteDeckDraftFile,
    private val syncDirectory: (File) -> Unit = ::syncDeckDraftDirectory,
) {
    @Synchronized
    fun load(session: NextcloudSession, key: DeckCardDraftKey): PersistedDeckCardDraft? {
        migrateLegacyEntry(session.accountId.storageKey, desktopFileCacheAccountId(session), key)
        val file = draftFile(session, key)
        val quarantine = quarantineFile(file)
        if (quarantine.exists()) {
            if (deleteDurably(file)) deleteDurably(quarantine)
            return null
        }
        if (!file.exists()) return null
        val encryptionKey = keyProvider.encryptionKey()
        return readAuthenticated(file, encryptionKey, key, session.accountId.storageKey).draft
    }

    @Synchronized
    fun save(session: NextcloudSession, persisted: PersistedDeckCardDraft) {
        migrateLegacyEntries(session)
        migrateLegacyEntry(session.accountId.storageKey, desktopFileCacheAccountId(session), persisted.key)
        val updatedAtEpochMillis = nowEpochMillis()
        require(updatedAtEpochMillis >= 0L) { "The Deck draft timestamp is invalid." }
        val file = draftFile(session, persisted.key)
        val encryptionKey = keyProvider.encryptionKey()
        if (file.exists()) {
            readAuthenticated(file, encryptionKey, persisted.key, session.accountId.storageKey)
        } else {
            ensureCapacityForNewDraft(session.accountId.storageKey, encryptionKey)
        }
        val plaintext = encodePlaintext(
            persisted,
            updatedAtEpochMillis,
            session.accountId.storageKey,
            file.name,
        )
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "The Deck card draft is too large." }
        val envelope = encrypt(plaintext, file.name, encryptionKey)
        require(envelope.size.toLong() <= MAX_ENVELOPE_BYTES) { "The Deck card draft is too large." }
        val verified = decode(envelope, file.name, encryptionKey)
        requireStorageOwner(verified, session.accountId.storageKey, file.name)
        check(verified.draft == persisted && verified.updatedAtEpochMillis == updatedAtEpochMillis) {
            "The Deck card draft could not be verified."
        }
        ensurePrivateDirectory()
        clearQuarantineBeforeSave(file)
        publish(file, envelope)
        prune(session.accountId.storageKey, encryptionKey)
    }

    @Synchronized
    fun clear(
        session: NextcloudSession,
        key: DeckCardDraftKey,
        discardUnreadable: Boolean = false,
    ) {
        migrateLegacyEntry(session.accountId.storageKey, desktopFileCacheAccountId(session), key)
        val file = draftFile(session, key)
        if (file.exists() && !discardUnreadable) {
            val encryptionKey = keyProvider.encryptionKey()
            readAuthenticated(file, encryptionKey, key, session.accountId.storageKey)
        }
        check(!Files.isSymbolicLink(root.toPath())) {
            "Desktop Deck draft storage must not be a symbolic link."
        }
        check(deleteDurably(file) && deleteDurably(quarantineFile(file))) {
            "The Deck card draft could not be cleared."
        }
    }

    @Synchronized
    fun quarantineAfterSubmit(session: NextcloudSession, key: DeckCardDraftKey) {
        migrateLegacyEntry(session.accountId.storageKey, desktopFileCacheAccountId(session), key)
        val file = draftFile(session, key)
        val quarantine = quarantineFile(file)
        ensurePrivateDirectory()
        publish(quarantine, SUBMITTED_MARKER_BYTES)
        if (deleteDurably(file)) deleteDurably(quarantine)
    }

    @Synchronized
    fun discardAll() {
        if (!root.exists()) return
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Desktop Deck draft storage cannot be reset safely."
        }
        val files = checkNotNull(root.listFiles()) {
            "Desktop Deck draft storage cannot be inspected for reset."
        }.filter { file ->
            file.name.matches(DRAFT_FILE_PATTERN) || file.name.matches(SUBMITTED_FILE_PATTERN) ||
                file.name.matches(LEGACY_DRAFT_FILE_PATTERN) || file.name.matches(LEGACY_SUBMITTED_FILE_PATTERN)
        }
        check(files.all(::deleteDurably)) { "Saved Deck card drafts could not be discarded." }
    }

    @Synchronized
    fun migrateLegacyEntries(session: NextcloudSession) {
        val encryptionKey = try {
            keyProvider.encryptionKey()
        } catch (_: Exception) {
            return
        }
        root.listFiles().orEmpty()
            .filter { file -> file.name.matches(LEGACY_DRAFT_FILE_PATTERN) }
            .forEach { file ->
                val stored = try {
                    readAuthenticated(file, encryptionKey)
                } catch (_: DesktopDeckDraftRecoveryException) {
                    null
                } ?: return@forEach
                if (
                    stored.accountStorageKey == null &&
                    stored.storageFileName == null &&
                    legacyStorageFileName(desktopFileCacheAccountId(session), stored.draft.key) == file.name
                ) {
                    migrateLegacyEntry(
                        session.accountId.storageKey,
                        desktopFileCacheAccountId(session),
                        stored.draft.key,
                        stored,
                        encryptionKey,
                    )
                }
            }
    }

    @Synchronized
    fun removeAccount(accountStorageKey: String, legacyAccountIdentity: String) {
        require(ACCOUNT_STORAGE_KEY_PATTERN.matches(accountStorageKey))
        require(ACCOUNT_STORAGE_KEY_PATTERN.matches(legacyAccountIdentity))
        if (!root.exists()) return
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Desktop Deck draft storage cannot be removed safely."
        }
        val files = checkNotNull(root.listFiles()) {
            "Desktop Deck draft storage cannot be inspected for account removal."
        }
        val targets = files.filter { file ->
            file.name.startsWith(accountDraftPrefix(accountStorageKey)) ||
                file.name.startsWith(accountSubmittedPrefix(accountStorageKey))
        }.toMutableSet()
        val encryptionKey = try {
            keyProvider.encryptionKey()
        } catch (_: Exception) {
            null
        }
        if (encryptionKey != null) {
            files.filter { file -> file.name.matches(LEGACY_DRAFT_FILE_PATTERN) }.forEach { file ->
                val stored = try {
                    readAuthenticated(file, encryptionKey)
                } catch (_: DesktopDeckDraftRecoveryException) {
                    null
                } ?: return@forEach
                if (
                    stored.accountStorageKey == null &&
                    stored.storageFileName == null &&
                    legacyStorageFileName(legacyAccountIdentity, stored.draft.key) == file.name
                ) {
                    targets += file
                    targets += legacyQuarantineFile(file)
                }
            }
        }
        check(targets.all(::deleteDurably)) {
            "Saved Deck card drafts for the account could not be removed."
        }
    }

    internal fun storageFileName(session: NextcloudSession, key: DeckCardDraftKey): String {
        return storageFileName(session.accountId.storageKey, key)
    }

    private fun storageFileName(accountStorageKey: String, key: DeckCardDraftKey): String {
        val scope = listOf(
            key.boardId.toString(),
            key.stackId.toString(),
            key.cardId?.toString() ?: "new",
        ).joinToString(separator = ":")
        return "${accountDraftPrefix(accountStorageKey)}${sha256Hex(scope)}$FILE_SUFFIX"
    }

    internal fun legacyStorageFileName(accountIdentity: String, key: DeckCardDraftKey): String {
        val scope = listOf(
            accountIdentity,
            key.boardId.toString(),
            key.stackId.toString(),
            key.cardId?.toString() ?: "new",
        ).joinToString(separator = ":")
        return "$LEGACY_FILE_PREFIX${sha256Hex(scope)}$FILE_SUFFIX"
    }

    private fun draftFile(session: NextcloudSession, key: DeckCardDraftKey): File =
        File(root, storageFileName(session, key))

    private fun quarantineFile(draftFile: File): File {
        val identity = draftFile.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return File(root, "$SUBMITTED_FILE_PREFIX$identity$SUBMITTED_FILE_SUFFIX")
    }

    private fun legacyQuarantineFile(draftFile: File): File {
        val digest = draftFile.name.removePrefix(LEGACY_FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return File(root, "$LEGACY_SUBMITTED_FILE_PREFIX$digest$SUBMITTED_FILE_SUFFIX")
    }

    private fun migrateLegacyEntry(
        accountStorageKey: String,
        legacyAccountIdentity: String,
        key: DeckCardDraftKey,
        decodedLegacy: StoredDeckCardDraft? = null,
        providedEncryptionKey: ByteArray? = null,
    ) {
        val legacyFile = File(root, legacyStorageFileName(legacyAccountIdentity, key))
        val legacyMarker = legacyQuarantineFile(legacyFile)
        val target = File(root, storageFileName(accountStorageKey, key))
        val targetMarker = quarantineFile(target)
        if (!legacyFile.exists()) {
            if (!legacyMarker.exists()) return
            ensurePrivateDirectory()
            publish(targetMarker, SUBMITTED_MARKER_BYTES)
            deleteDurably(legacyMarker)
            return
        }
        val encryptionKey = providedEncryptionKey ?: keyProvider.encryptionKey()
        val legacy = decodedLegacy ?: readAuthenticated(legacyFile, encryptionKey, key)
        if (
            legacy.accountStorageKey != null || legacy.storageFileName != null ||
            legacy.draft.key != key
        ) {
            throw DesktopDeckDraftRecoveryException(
                IllegalArgumentException("The legacy Deck draft identity does not match."),
            )
        }
        val plaintext = encodePlaintext(
            legacy.draft,
            legacy.updatedAtEpochMillis,
            accountStorageKey,
            target.name,
        )
        val envelope = encrypt(plaintext, target.name, encryptionKey)
        ensurePrivateDirectory()
        if (legacyMarker.exists()) publish(targetMarker, SUBMITTED_MARKER_BYTES)
        if (target.exists()) {
            readAuthenticated(target, encryptionKey, key, accountStorageKey)
        } else {
            publish(target, envelope)
        }
        deleteDurably(legacyFile)
        deleteDurably(legacyMarker)
    }

    private fun clearQuarantineBeforeSave(draftFile: File) {
        val quarantine = quarantineFile(draftFile)
        if (!quarantine.exists()) return
        check(deleteDurably(draftFile) && deleteDurably(quarantine)) {
            "The submitted Deck card draft quarantine could not be cleared."
        }
    }

    private fun encodePlaintext(
        persisted: PersistedDeckCardDraft,
        updatedAtEpochMillis: Long,
        accountStorageKey: String,
        storageFileName: String,
    ): ByteArray = JSONObject()
        .put("version", PLAINTEXT_FORMAT_VERSION)
        .put("accountStorageKey", accountStorageKey)
        .put("storageFileName", storageFileName)
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
        .encodeToByteArray()

    private fun readAuthenticated(
        file: File,
        encryptionKey: ByteArray,
        expectedKey: DeckCardDraftKey? = null,
        expectedAccountStorageKey: String? = null,
    ): StoredDeckCardDraft = try {
        if (!file.isSafeRegularFile() || file.length() !in 1..MAX_ENVELOPE_BYTES) {
            throw DesktopDeckDraftRecoveryException(
                IllegalArgumentException("The Deck draft file is invalid."),
            )
        }
        val stored = decode(file.readBytes(), file.name, encryptionKey)
        if (expectedKey != null && stored.draft.key != expectedKey) {
            throw DesktopDeckDraftRecoveryException(
                IllegalArgumentException("The Deck draft resource identity does not match."),
            )
        }
        expectedAccountStorageKey?.let { requireStorageOwner(stored, it, file.name) }
        stored
    } catch (failure: DesktopDeckDraftRecoveryException) {
        throw failure
    } catch (failure: Exception) {
        throw DesktopDeckDraftRecoveryException(failure)
    }

    private fun decode(
        envelopeBytes: ByteArray,
        fileName: String,
        encryptionKey: ByteArray,
    ): StoredDeckCardDraft = try {
        require(encryptionKey.size == AES_KEY_BYTES) { "The Deck draft encryption key is invalid." }
        val envelope = JSONObject(envelopeBytes.decodeToString())
        require(envelope.getInt("version") == ENVELOPE_FORMAT_VERSION) {
            "The Deck draft envelope is unsupported."
        }
        val nonce = Base64.getDecoder().decode(envelope.getString("nonce"))
        require(nonce.size == GCM_NONCE_BYTES) { "The Deck draft nonce is invalid." }
        val ciphertext = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        require(ciphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "The Deck draft ciphertext is invalid."
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(encryptionKey, AES_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(fileName.encodeToByteArray())
        val plaintext = cipher.doFinal(ciphertext)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "The Deck draft is too large." }
        val value = JSONObject(plaintext.decodeToString())
        val version = value.getInt("version")
        require(version == LEGACY_PLAINTEXT_FORMAT_VERSION || version == PLAINTEXT_FORMAT_VERSION) {
            "The Deck draft format is unsupported."
        }
        val updatedAtEpochMillis = value.getLong("updatedAtEpochMillis")
        require(updatedAtEpochMillis >= 0L) { "The Deck draft timestamp is invalid." }
        val accountStorageKey = value.optString("accountStorageKey").takeIf(String::isNotBlank)
        val storageFileName = value.optString("storageFileName").takeIf(String::isNotBlank)
        require(
            version == LEGACY_PLAINTEXT_FORMAT_VERSION && accountStorageKey == null && storageFileName == null ||
                version == PLAINTEXT_FORMAT_VERSION &&
                accountStorageKey?.matches(ACCOUNT_STORAGE_KEY_PATTERN) == true &&
                storageFileName?.matches(DRAFT_FILE_PATTERN) == true,
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
            accountStorageKey = accountStorageKey,
            storageFileName = storageFileName,
        )
    } catch (failure: Exception) {
        throw DesktopDeckDraftRecoveryException(failure)
    }

    private fun requireStorageOwner(stored: StoredDeckCardDraft, expectedOwner: String, expectedFileName: String) {
        if (stored.accountStorageKey != expectedOwner || stored.storageFileName != expectedFileName) {
            throw DesktopDeckDraftRecoveryException(
                IllegalArgumentException("The Deck draft account storage identity does not match."),
            )
        }
    }

    private fun encrypt(
        plaintext: ByteArray,
        fileName: String,
        encryptionKey: ByteArray,
    ): ByteArray {
        require(encryptionKey.size == AES_KEY_BYTES) { "The Deck draft encryption key is invalid." }
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(encryptionKey, AES_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(fileName.encodeToByteArray())
        val ciphertext = cipher.doFinal(plaintext)
        return JSONObject()
            .put("version", ENVELOPE_FORMAT_VERSION)
            .put("nonce", Base64.getEncoder().encodeToString(nonce))
            .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
            .toString()
            .encodeToByteArray()
    }

    private fun prune(accountStorageKey: String, encryptionKey: ByteArray) {
        val files = root.listFiles().orEmpty()
            .filter { it.name.startsWith(accountDraftPrefix(accountStorageKey)) }
        val entries = files.mapNotNull { file ->
            val stored = try {
                readAuthenticated(file, encryptionKey, expectedAccountStorageKey = accountStorageKey)
            } catch (_: DesktopDeckDraftRecoveryException) {
                // A keyring or filesystem failure can make valid ciphertext temporarily unreadable.
                // Preserve it so a later app process can authenticate and recover the draft.
                null
            }
            stored?.let { DeckCardDraftRetention.Entry(file.name, it.updatedAtEpochMillis) }
        }
        val namesToPrune = DeckCardDraftRetention.keysToPrune(
            entries = entries,
            maximumEntries = (DeckCardDraftRetention.MAX_ENTRIES - (files.size - entries.size))
                .coerceAtLeast(0),
        )
        files.filter { it.name in namesToPrune }.forEach(::deleteDraft)
    }

    private fun ensureCapacityForNewDraft(accountStorageKey: String, encryptionKey: ByteArray) {
        val files = root.listFiles().orEmpty()
            .filter { it.name.startsWith(accountDraftPrefix(accountStorageKey)) }
        val overflow = files.size + 1 - DeckCardDraftRetention.MAX_ENTRIES
        if (overflow <= 0) return
        val readableFiles = files.count { file ->
            try {
                readAuthenticated(file, encryptionKey, expectedAccountStorageKey = accountStorageKey)
                true
            } catch (_: DesktopDeckDraftRecoveryException) {
                false
            }
        }
        if (readableFiles < overflow) throw DeckCardDraftCapacityException()
    }

    private fun ensurePrivateDirectory() {
        check(root.isDirectory || root.mkdirs()) { "Could not create desktop Deck draft storage." }
        check(!Files.isSymbolicLink(root.toPath())) {
            "Desktop Deck draft storage must not be a symbolic link."
        }
        setPrivatePermissions(
            root,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }

    private fun publish(file: File, bytes: ByteArray) {
        val temporary = File.createTempFile("${file.name}.", ".tmp", root)
        try {
            setPrivatePermissions(
                temporary,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            setPrivatePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            syncDirectory(root)
        } finally {
            temporary.delete()
        }
    }

    private fun deleteDraft(file: File) {
        check(deleteDurably(file)) {
            "An old Deck card draft could not be removed."
        }
    }

    private fun deleteDurably(file: File): Boolean {
        val existed = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        val deleted = deleteFile(file)
        if (deleted && existed) syncDirectory(root)
        return deleted
    }

    private fun File.isSafeRegularFile(): Boolean =
        Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun setPrivatePermissions(file: File, permissions: Set<PosixFilePermission>) {
        val path = file.toPath()
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private data class StoredDeckCardDraft(
        val draft: PersistedDeckCardDraft,
        val updatedAtEpochMillis: Long,
        val accountStorageKey: String?,
        val storageFileName: String?,
    )

    private fun accountDraftPrefix(accountStorageKey: String) = "$FILE_PREFIX${accountStorageKey}_"

    private fun accountSubmittedPrefix(accountStorageKey: String) = "$SUBMITTED_FILE_PREFIX${accountStorageKey}_"

    internal companion object {
        const val FILE_PREFIX = "draft_v2_"
        const val LEGACY_FILE_PREFIX = "draft_"
        const val FILE_SUFFIX = ".json.enc"
        const val SUBMITTED_FILE_PREFIX = "submitted_v2_"
        const val LEGACY_SUBMITTED_FILE_PREFIX = "submitted_"
        const val SUBMITTED_FILE_SUFFIX = ".marker"
        const val ENVELOPE_FORMAT_VERSION = 1
        const val LEGACY_PLAINTEXT_FORMAT_VERSION = 1
        const val PLAINTEXT_FORMAT_VERSION = 2
        const val AES_KEY_BYTES = 32
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        const val MAX_PLAINTEXT_BYTES = 128 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        const val MAX_ENVELOPE_BYTES = 256L * 1024L
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_ALGORITHM = "AES"
        val DRAFT_FILE_PATTERN = Regex("^draft_v2_[0-9a-f]{64}_[0-9a-f]{64}\\.json\\.enc$")
        val SUBMITTED_FILE_PATTERN = Regex("^submitted_v2_[0-9a-f]{64}_[0-9a-f]{64}\\.marker$")
        val LEGACY_DRAFT_FILE_PATTERN = Regex("^draft_[0-9a-f]{64}\\.json\\.enc$")
        val LEGACY_SUBMITTED_FILE_PATTERN = Regex("^submitted_[0-9a-f]{64}\\.marker$")
        private val ACCOUNT_STORAGE_KEY_PATTERN = Regex("[0-9a-f]{64}")
        val SUBMITTED_MARKER_BYTES = "confirmed\n".encodeToByteArray()
    }
}

private fun deleteDeckDraftFile(file: File): Boolean =
    Files.deleteIfExists(file.toPath()) || !file.exists()

private fun syncDeckDraftDirectory(directory: File) {
    if (Files.getFileAttributeView(directory.toPath(), PosixFileAttributeView::class.java) == null) return
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
}

internal class DesktopDeckDraftRecoveryException(
    cause: Throwable,
) : IllegalStateException("The saved Deck card draft could not be restored safely.", cause)

internal fun desktopDeckLegacySecretRequired(
    root: File,
    listFiles: (File) -> Array<File>? = File::listFiles,
): Boolean {
    if (!root.exists()) return false
    if (!root.isDirectory) return true
    val entries = listFiles(root) ?: return true
    return entries.any { file ->
        file.name.matches(DesktopDeckCardDraftStore.DRAFT_FILE_PATTERN) ||
            file.name.matches(DesktopDeckCardDraftStore.LEGACY_DRAFT_FILE_PATTERN)
    }
}

internal fun interface DesktopDeckDraftKeyProvider {
    fun encryptionKey(): ByteArray
}

internal class PlatformDeckDraftKeyProvider(
    private val secretStore: DesktopSecretStore = defaultDesktopSecretStore(),
    private val random: SecureRandom = SecureRandom(),
    private val legacySecretRequired: () -> Boolean = { true },
) : DesktopDeckDraftKeyProvider {
    @Volatile
    private var cached: ByteArray? = null

    @Synchronized
    override fun encryptionKey(): ByteArray {
        cached?.let { return it.copyOf() }
        val key = lookup() ?: create()
        require(key.size == DesktopDeckCardDraftStore.AES_KEY_BYTES) {
            "The desktop keyring returned an invalid Deck draft key."
        }
        cached = key.copyOf()
        return key.copyOf()
    }

    private fun create(): ByteArray {
        val generated = ByteArray(DesktopDeckCardDraftStore.AES_KEY_BYTES).also(random::nextBytes)
        val encoded = Base64.getEncoder().encodeToString(generated)
        secretStore.save(
            reference = desktopDeckDraftSecretReference(),
            username = null,
            secret = encoded.encodeToByteArray(),
        )
        val persisted = lookup() ?: throw DesktopSecretStoreUnavailableException(
            "The Deck draft encryption key could not be verified after saving.",
        )
        if (!MessageDigest.isEqual(generated, persisted)) {
            throw DesktopSecretStoreUnavailableException(
                "The Deck draft encryption key changed during secure storage verification.",
            )
        }
        return generated
    }

    private fun lookup(): ByteArray? {
        val stored = try {
            secretStore.load(desktopDeckDraftSecretReference())
        } catch (failure: NextcloudSessionLegacyMigrationUnavailableException) {
            if (legacySecretRequired()) throw failure
            null
        } catch (failure: DesktopSecretStoreUnavailableException) {
            if (legacySecretRequired()) throw failure
            null
        }
        val encoded = stored
            ?.let { value -> value.copyOf(minOf(value.size, MAX_ENCODED_KEY_BYTES)) }
            ?.decodeToString()
            ?.trim()
            ?: return missingKey()
        if (encoded.isBlank()) return missingKey()
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: missingKey()
    }

    private fun missingKey(): ByteArray? {
        if (legacySecretRequired()) {
            throw DeckCardDraftResetRequiredException(
                "The Deck draft encryption key is missing while encrypted drafts still exist.",
            )
        }
        return null
    }

    private companion object {
        const val MAX_ENCODED_KEY_BYTES = 128
    }
}

private fun desktopDeckDraftDirectory(): File {
    val xdgState = System.getenv("XDG_STATE_HOME")?.takeIf(String::isNotBlank)
    val root = xdgState?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/state")
    return File(root, "nextcloud-native/deck-drafts")
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
