package dev.obiente.nextcloudnative

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** App-private ownership records for SAF recovery names. */
internal class AndroidSafDownloadOwnershipStore(
    private val directory: File,
) {
    fun forDirectory(directoryIdentity: String): AndroidSafDownloadOwnership {
        require(directoryIdentity.isNotBlank())
        return ScopedOwnership(scopeDigest(directoryIdentity))
    }

    private inner class ScopedOwnership(
        private val scope: String,
    ) : AndroidSafDownloadOwnership {
        override fun transactions(
            observedNames: Set<String>,
        ): List<AndroidSafOwnedDownloadTransaction> = synchronized(LOCK) {
            ownershipRows(scope, observedRecoveryTokens(observedNames))
                .filter { row ->
                    row.scope == scope ||
                        row.transaction.stageName in observedNames ||
                        row.transaction.backupName in observedNames
                }
                .map(StoredOwnershipRow::transaction)
                .sortedWith(compareBy(AndroidSafOwnedDownloadTransaction::finalName).thenBy { it.token })
        }

        override fun add(transaction: AndroidSafOwnedDownloadTransaction) = synchronized(LOCK) {
            ensureDirectory()
            val existing = ownershipRows(tokens = setOf(transaction.token)).singleOrNull()
            if (existing != null) {
                check(existing.transaction == transaction) { "SAF download recovery ownership collided." }
                return@synchronized
            }
            val destination = File(directory, rowName(scope, transaction.token))
            val rowCount = ownershipFiles().size
            check(rowCount < MAX_ROWS) { "Too many SAF download recovery records are pending." }
            writeRow(destination, transaction, replace = false)
        }

        override fun replace(transaction: AndroidSafOwnedDownloadTransaction) = synchronized(LOCK) {
            val row = ownershipRows(tokens = setOf(transaction.token)).singleOrNull()
            checkNotNull(row) { "SAF download recovery ownership is missing." }
            val previous = row.transaction
            check(previous.finalName == transaction.finalName && previous.token == transaction.token) {
                "SAF download recovery ownership collided."
            }
            check(!previous.publicationAttempted || transaction.publicationAttempted) {
                "SAF download recovery publication attempt cannot be reverted."
            }
            check(!previous.publicationCompleted || transaction.publicationCompleted) {
                "SAF download recovery publication cannot be reverted."
            }
            check(
                !previous.publicationCompleted ||
                    previous.backupDisplayName == transaction.backupDisplayName,
            ) { "SAF download recovery backup identity cannot be changed." }
            check(
                !previous.publicationCompleted ||
                    previous.backupProtected == transaction.backupProtected,
            ) { "SAF download recovery backup protection cannot be changed." }
            if (previous != transaction) writeRow(row.file, transaction, replace = true)
        }

        override fun remove(transaction: AndroidSafOwnedDownloadTransaction) = synchronized(LOCK) {
            val row = ownershipRows(tokens = setOf(transaction.token)).singleOrNull()
                ?: return@synchronized
            check(row.transaction == transaction) { "SAF download recovery ownership collided." }
            check(row.file.delete()) { "Could not retire SAF download recovery ownership." }
        }
    }

    private fun ownershipRows(
        scope: String? = null,
        tokens: Set<String> = emptySet(),
    ): List<StoredOwnershipRow> = ownershipFiles()
        .mapNotNull(::ownershipReference)
        .filter { reference -> reference.scope == scope || reference.token in tokens }
        .map { reference ->
            val transaction = readRow(reference.file)
            check(transaction.token == reference.token) { "SAF download recovery row name is invalid." }
            StoredOwnershipRow(reference.file, reference.scope, transaction)
        }

    private fun ownershipFiles(): List<File> {
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "SAF download recovery storage is invalid." }
        return checkNotNull(directory.listFiles()) { "Could not list SAF download recovery storage." }
            .filter { file -> file.isFile && file.name.endsWith(ROW_SUFFIX) }
    }

    private fun ownershipReference(file: File): StoredOwnershipReference? {
        val name = file.name.removeSuffix(ROW_SUFFIX)
        if (name.length != SCOPE_HEX_CHARACTERS + 1 + TOKEN_CHARACTERS) return null
        if (name[SCOPE_HEX_CHARACTERS] != '-') return null
        val scope = name.take(SCOPE_HEX_CHARACTERS)
        if (scope.any { it !in HEX_CHARACTERS }) return null
        val token = runCatching { requireValidToken(name.takeLast(TOKEN_CHARACTERS)) }.getOrNull() ?: return null
        return StoredOwnershipReference(file, scope, token)
    }

    private fun observedRecoveryTokens(names: Set<String>): Set<String> = names.flatMapTo(mutableSetOf()) { name ->
        RECOVERY_TOKEN_PATTERN.findAll(name).mapNotNull { match ->
            runCatching { requireValidToken(match.value) }.getOrNull()
        }.toList()
    }

    private fun readRow(file: File): AndroidSafOwnedDownloadTransaction =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            check(input.readInt() == MAGIC)
            val formatVersion = input.readInt()
            check(formatVersion in 1..FORMAT_VERSION) {
                "SAF download recovery storage has an invalid header."
            }
            val finalName = input.readUtf8()
            val token = input.readUtf8()
            val publicationCompleted = formatVersion >= 2 && input.readBoolean()
            val publicationAttempted = if (formatVersion >= 3) input.readBoolean() else publicationCompleted
            val backupDisplayName = if (formatVersion >= 4 && input.readBoolean()) input.readUtf8() else null
            val backupProtected = if (formatVersion >= 5) input.readBoolean() else backupDisplayName != null
            val transaction = AndroidSafOwnedDownloadTransaction(
                finalName = finalName,
                token = token,
                publicationAttempted = publicationAttempted,
                publicationCompleted = publicationCompleted,
                backupDisplayName = backupDisplayName,
                backupProtected = backupProtected,
            )
            check(input.read() == -1) { "SAF download recovery storage contains trailing data." }
            transaction
        }

    private fun writeRow(
        destination: File,
        transaction: AndroidSafOwnedDownloadTransaction,
        replace: Boolean,
    ) {
        val temporary = File.createTempFile("ownership-", TEMP_SUFFIX, directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeUtf8(transaction.finalName)
                    output.writeUtf8(transaction.token)
                    output.writeBoolean(transaction.publicationCompleted)
                    output.writeBoolean(transaction.publicationAttempted)
                    output.writeBoolean(transaction.backupDisplayName != null)
                    transaction.backupDisplayName?.let { displayName -> output.writeUtf8(displayName) }
                    output.writeBoolean(transaction.backupProtected)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            moveRow(temporary, destination, replace)
        } finally {
            temporary.delete()
        }
    }

    private fun moveRow(temporary: File, destination: File, replace: Boolean) {
        try {
            if (replace) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(temporary.toPath(), destination.toPath())
            }
        }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size in 1..MAX_FIELD_BYTES) { "SAF download recovery metadata is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf8(): String {
        val length = readInt()
        check(length in 1..MAX_FIELD_BYTES) { "SAF download recovery metadata has an invalid size." }
        val bytes = ByteArray(length).also(::readFully)
        val value = bytes.toString(StandardCharsets.UTF_8)
        check(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "SAF download recovery metadata is not valid UTF-8."
        }
        return value
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create SAF download recovery storage."
        }
    }

    private fun scopeDigest(value: String): String = sha256(value)

    private fun rowName(scope: String, token: String): String = "$scope-$token$ROW_SUFFIX"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val LOCK = Any()
        const val MAGIC = 0x4E435344 // NCSD
        const val FORMAT_VERSION = 5
        const val SCOPE_HEX_CHARACTERS = 64
        const val TOKEN_CHARACTERS = 36
        const val MAX_FIELD_BYTES = 4 * 1024
        const val MAX_ROWS = 4_096
        const val ROW_SUFFIX = ".row"
        const val TEMP_SUFFIX = ".tmp"
        val HEX_CHARACTERS = '0'..'9' union 'a'..'f'
        val RECOVERY_TOKEN_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    }

    private data class StoredOwnershipReference(
        val file: File,
        val scope: String,
        val token: String,
    )

    private data class StoredOwnershipRow(
        val file: File,
        val scope: String,
        val transaction: AndroidSafOwnedDownloadTransaction,
    )
}
