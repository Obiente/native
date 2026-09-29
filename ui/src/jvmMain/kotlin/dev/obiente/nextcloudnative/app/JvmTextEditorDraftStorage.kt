package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded encrypted recovery files. Callers also serialize access against credential retirement. */
class JvmTextEditorDraftStorage(
    private val root: File,
    private val keyProvider: () -> SecretKey,
    private val publish: (File, File) -> Unit = { temporary, target ->
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    private class StorageLifetime {
        val generations = mutableMapOf<String, Long>()
    }

    private val lifetime = synchronized(lifetimes) {
        lifetimes.getOrPut(root.absoluteFile.normalize().path) { StorageLifetime() }
    }

    fun bind(accountStorageKey: String, path: String): TextEditorDraftStore {
        require(accountStorageKey.matches(Regex("[0-9a-f]{64}")))
        require(path.isNotEmpty() && path.length <= 16_384 && path.none { it == '\u0000' })
        val file = File(root, "${accountStorageKey}_${digest(path)}.draft")
        val generation = synchronized(lifetime) { lifetime.generations[accountStorageKey] ?: 0L }
        return object : TextEditorDraftStore {
            private suspend fun <T> access(action: () -> T): T = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                synchronized(lifetime) {
                    check((lifetime.generations[accountStorageKey] ?: 0L) == generation) {
                        "The account changed before text draft recovery could complete."
                    }
                    requireSafeRoot()
                    action()
                }
            }

            override suspend fun load(): TextEditorDraft? = access {
                if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) null else read(file)
            }

            override suspend fun save(draft: TextEditorDraft): Unit = access {
                val plaintext = encode(draft)
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    read(file) // Do not replace unreadable recovery with an unverified draft.
                }
                ensureDirectory()
                val entries = checkNotNull(root.listFiles()) { "Text draft storage cannot be inspected." }
                val retained = entries.filter { it != file }
                check(retained.count { it.name.endsWith(".draft") } < MAX_DRAFTS) {
                    "Text draft storage is full. Finish or discard another draft first."
                }
                check(retained.sumOf { it.length().coerceAtLeast(0L) } + plaintext.size + 64 <= MAX_TOTAL_BYTES) {
                    "Text draft storage is full. Finish or discard another draft first."
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
                cipher.updateAAD(file.name.encodeToByteArray())
                val envelope = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
                check(cipher.iv.size == IV_BYTES)
                val temporary = Files.createTempFile(root.toPath(), "${accountStorageKey}_", ".tmp").toFile()
                try {
                    restrictPermissions(temporary, false)
                    FileOutputStream(temporary).use { output ->
                        output.write(envelope)
                        output.fd.sync()
                    }
                    // Never weaken atomic publication to a delete-then-copy fallback.
                    publish(temporary, file)
                    syncDirectory()
                } finally {
                    Files.deleteIfExists(temporary.toPath())
                }
            }

            override suspend fun remove(): Unit = access {
                check(!Files.isSymbolicLink(file.toPath())) { "Text draft storage must not be a symbolic link." }
                if (Files.deleteIfExists(file.toPath())) syncDirectory()
            }
        }
    }

    fun removeAccount(accountStorageKey: String) = synchronized(lifetime) {
        require(accountStorageKey.matches(Regex("[0-9a-f]{64}")))
        lifetime.generations[accountStorageKey] = (lifetime.generations[accountStorageKey] ?: 0L) + 1L
        requireSafeRoot()
        if (root.exists()) {
            checkNotNull(root.listFiles()).filter { it.name.startsWith("${accountStorageKey}_") }.forEach {
                Files.delete(it.toPath())
            }
            syncDirectory()
        }
    }

    private fun read(file: File): TextEditorDraft {
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid text draft file." }
        val length = file.length()
        check(length in (1L + IV_BYTES + TAG_BYTES)..MAX_ENVELOPE_BYTES) { "Invalid text draft size." }
        val envelope = DataInputStream(file.inputStream()).use { input ->
            ByteArray(length.toInt()).also {
                input.readFully(it)
                check(input.read() == -1) { "Text draft changed while reading." }
            }
        }
        check(envelope.size.toLong() <= MAX_ENVELOPE_BYTES && envelope.firstOrNull() == VERSION) {
            "Unsupported text draft envelope."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BYTES * 8, envelope.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(file.name.encodeToByteArray())
        val plaintext = cipher.doFinal(envelope, 1 + IV_BYTES, envelope.size - 1 - IV_BYTES)
        return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            val original = input.readBoundedText(MAX_EDITABLE_TEXT_BYTES.toInt())
            val text = input.readBoundedText(MAX_EDITABLE_TEXT_BYTES.toInt())
            val etag = if (input.readBoolean()) input.readBoundedText(MAX_ETAG_BYTES) else null
            val verificationRequired = input.readBoolean()
            check(input.read() == -1) { "Unexpected text draft fields." }
            TextEditorDraft(original, text, etag, verificationRequired)
        }
    }

    private fun encode(draft: TextEditorDraft): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeBoundedText(draft.originalText, MAX_EDITABLE_TEXT_BYTES.toInt())
            output.writeBoundedText(draft.text, MAX_EDITABLE_TEXT_BYTES.toInt())
            output.writeBoolean(draft.etag != null)
            draft.etag?.let { output.writeBoundedText(it, MAX_ETAG_BYTES) }
            output.writeBoolean(draft.verificationRequired)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeBoundedText(value: String, maximum: Int) {
        require(value.length <= maximum) { "Text draft content is too large." }
        val bytes = value.encodeToByteArray()
        require(bytes.size <= maximum) { "Text draft content is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedText(maximum: Int): String {
        val length = readInt()
        require(length in 0..maximum && length <= available()) { "Invalid text draft content size." }
        return ByteArray(length).also(::readFully).decodeToString(throwOnInvalidSequence = true)
    }

    private fun requireSafeRoot() {
        check(!Files.isSymbolicLink(root.toPath())) { "Text draft storage must not be a symbolic link." }
        check(!root.exists() || root.isDirectory) { "Text draft storage is not a directory." }
        if (root.exists()) {
            val parent = checkNotNull(root.absoluteFile.parentFile) { "Invalid text draft storage root." }
            val expected = parent.toPath().toRealPath().resolve(root.name)
            check(root.toPath().toRealPath() == expected) { "Text draft storage must not be a directory alias." }
        }
    }

    private fun ensureDirectory() {
        Files.createDirectories(root.toPath())
        requireSafeRoot()
        restrictPermissions(root, true)
    }

    private fun restrictPermissions(file: File, directory: Boolean) {
        if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        }
    }

    private fun syncDirectory() {
        if (Files.getFileAttributeView(root.toPath(), PosixFileAttributeView::class.java) != null) {
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private companion object {
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_ETAG_BYTES = 8192
        const val MAX_ENVELOPE_BYTES = MAX_EDITABLE_TEXT_BYTES * 2 + MAX_ETAG_BYTES + 64
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_DRAFTS = 32
        val lifetimes = mutableMapOf<String, StorageLifetime>()
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
