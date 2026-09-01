package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
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
) {
    @Synchronized
    fun load(session: NextcloudSession, key: DeckCardDraftKey): PersistedDeckCardDraft? {
        val file = draftFile(session, key)
        if (!file.exists()) return null
        if (!file.isSafeRegularFile() || file.length() !in 1..MAX_ENVELOPE_BYTES) {
            deleteInvalid(file)
            return null
        }
        val encryptionKey = keyProvider.encryptionKey()
        return decode(file.readBytes(), file.name, encryptionKey)
            ?.takeIf { it.draft.key == key }
            ?.draft
            ?: run {
                deleteInvalid(file)
                null
            }
    }

    @Synchronized
    fun save(session: NextcloudSession, persisted: PersistedDeckCardDraft) {
        val updatedAtEpochMillis = nowEpochMillis()
        require(updatedAtEpochMillis >= 0L) { "The Deck draft timestamp is invalid." }
        val file = draftFile(session, persisted.key)
        val encryptionKey = keyProvider.encryptionKey()
        val plaintext = encodePlaintext(persisted, updatedAtEpochMillis)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "The Deck card draft is too large." }
        val envelope = encrypt(plaintext, file.name, encryptionKey)
        require(envelope.size.toLong() <= MAX_ENVELOPE_BYTES) { "The Deck card draft is too large." }
        ensurePrivateDirectory()
        publish(file, envelope)
        prune(encryptionKey)
    }

    @Synchronized
    fun clear(session: NextcloudSession, key: DeckCardDraftKey) {
        val file = draftFile(session, key)
        check(Files.deleteIfExists(file.toPath()) || !file.exists()) {
            "The Deck card draft could not be cleared."
        }
    }

    internal fun storageFileName(session: NextcloudSession, key: DeckCardDraftKey): String {
        val scope = listOf(
            desktopFileCacheAccountId(session),
            key.boardId.toString(),
            key.stackId.toString(),
            key.cardId?.toString() ?: "new",
        ).joinToString(separator = ":")
        return "$FILE_PREFIX${sha256Hex(scope)}$FILE_SUFFIX"
    }

    private fun draftFile(session: NextcloudSession, key: DeckCardDraftKey): File =
        File(root, storageFileName(session, key))

    private fun encodePlaintext(
        persisted: PersistedDeckCardDraft,
        updatedAtEpochMillis: Long,
    ): ByteArray = JSONObject()
        .put("version", PLAINTEXT_FORMAT_VERSION)
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

    private fun decode(
        envelopeBytes: ByteArray,
        fileName: String,
        encryptionKey: ByteArray,
    ): StoredDeckCardDraft? = runCatching {
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
        require(value.getInt("version") == PLAINTEXT_FORMAT_VERSION) {
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
        )
    }.getOrNull()

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

    private fun prune(encryptionKey: ByteArray) {
        val files = root.listFiles().orEmpty()
            .filter { it.name.matches(DRAFT_FILE_PATTERN) }
        val malformed = linkedSetOf<File>()
        val entries = files.mapNotNull { file ->
            val stored = if (
                file.isSafeRegularFile() &&
                file.length() in 1..MAX_ENVELOPE_BYTES
            ) {
                decode(file.readBytes(), file.name, encryptionKey)
            } else {
                null
            }
            if (stored == null) {
                malformed += file
                null
            } else {
                DeckCardDraftRetention.Entry(file.name, stored.updatedAtEpochMillis)
            }
        }
        val namesToPrune = DeckCardDraftRetention.keysToPrune(
            entries = entries,
            maximumEntries = DeckCardDraftRetention.MAX_ENTRIES,
        )
        (malformed + files.filter { it.name in namesToPrune }).forEach(::deleteInvalid)
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
        } finally {
            temporary.delete()
        }
    }

    private fun deleteInvalid(file: File) {
        check(Files.deleteIfExists(file.toPath()) || !file.exists()) {
            "An invalid Deck card draft could not be removed."
        }
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
    )

    internal companion object {
        const val FILE_PREFIX = "draft_"
        const val FILE_SUFFIX = ".json.enc"
        const val ENVELOPE_FORMAT_VERSION = 1
        const val PLAINTEXT_FORMAT_VERSION = 1
        const val AES_KEY_BYTES = 32
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        const val MAX_PLAINTEXT_BYTES = 128 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        const val MAX_ENVELOPE_BYTES = 256L * 1024L
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_ALGORITHM = "AES"
        val DRAFT_FILE_PATTERN = Regex("^draft_[0-9a-f]{64}\\.json\\.enc$")
    }
}

internal fun desktopDeckLegacySecretRequired(
    root: File,
    listFiles: (File) -> Array<File>? = File::listFiles,
): Boolean {
    if (!root.exists()) return false
    if (!root.isDirectory) return true
    val entries = listFiles(root) ?: return true
    return entries.any { file -> file.name.matches(DesktopDeckCardDraftStore.DRAFT_FILE_PATTERN) }
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
        return lookup() ?: generated
    }

    private fun lookup(): ByteArray? {
        val stored = try {
            secretStore.load(desktopDeckDraftSecretReference())
        } catch (failure: DesktopSecretStoreUnavailableException) {
            if (legacySecretRequired()) throw failure
            null
        }
        val encoded = stored
            ?.let { value -> value.copyOf(minOf(value.size, MAX_ENCODED_KEY_BYTES)) }
            ?.decodeToString()
            ?.trim()
            ?: return null
        if (encoded.isBlank()) return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
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
