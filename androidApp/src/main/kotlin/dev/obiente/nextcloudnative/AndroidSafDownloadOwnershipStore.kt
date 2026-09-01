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
        override fun transactions(): List<AndroidSafOwnedDownloadTransaction> = synchronized(LOCK) {
            if (!directory.exists()) return@synchronized emptyList()
            check(directory.isDirectory) { "SAF download recovery storage is invalid." }
            checkNotNull(directory.listFiles()) { "Could not list SAF download recovery storage." }
                .filter { it.isFile && it.name.startsWith("$scope-") && it.name.endsWith(ROW_SUFFIX) }
                .map { file ->
                    readRow(file).also { transaction ->
                        check(file.name == rowName(scope, transaction.token)) {
                            "SAF download recovery row name is invalid."
                        }
                    }
                }
                .sortedWith(compareBy(AndroidSafOwnedDownloadTransaction::finalName).thenBy { it.token })
        }

        override fun add(transaction: AndroidSafOwnedDownloadTransaction) = synchronized(LOCK) {
            ensureDirectory()
            val destination = File(directory, rowName(scope, transaction.token))
            if (destination.exists()) {
                check(readRow(destination) == transaction) { "SAF download recovery ownership collided." }
                return@synchronized
            }
            val rowCount = checkNotNull(directory.listFiles()) {
                "Could not list SAF download recovery storage."
            }.count { it.isFile && it.name.endsWith(ROW_SUFFIX) }
            check(rowCount < MAX_ROWS) { "Too many SAF download recovery records are pending." }
            val temporary = File.createTempFile("ownership-", TEMP_SUFFIX, directory)
            try {
                FileOutputStream(temporary).use { fileOutput ->
                    DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                        output.writeInt(MAGIC)
                        output.writeInt(FORMAT_VERSION)
                        output.writeUtf8(transaction.finalName)
                        output.writeUtf8(transaction.token)
                        output.flush()
                        fileOutput.fd.sync()
                    }
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath())
                }
            } finally {
                temporary.delete()
            }
        }

        override fun remove(transaction: AndroidSafOwnedDownloadTransaction) = synchronized(LOCK) {
            val destination = File(directory, rowName(scope, transaction.token))
            if (!destination.exists()) return@synchronized
            check(readRow(destination) == transaction) { "SAF download recovery ownership collided." }
            check(destination.delete()) { "Could not retire SAF download recovery ownership." }
        }
    }

    private fun readRow(file: File): AndroidSafOwnedDownloadTransaction =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            check(input.readInt() == MAGIC && input.readInt() == FORMAT_VERSION) {
                "SAF download recovery storage has an invalid header."
            }
            val transaction = AndroidSafOwnedDownloadTransaction(
                finalName = input.readUtf8(),
                token = input.readUtf8(),
            )
            check(input.read() == -1) { "SAF download recovery storage contains trailing data." }
            transaction
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
        const val FORMAT_VERSION = 1
        const val MAX_FIELD_BYTES = 4 * 1024
        const val MAX_ROWS = 4_096
        const val ROW_SUFFIX = ".row"
        const val TEMP_SUFFIX = ".tmp"
    }
}
