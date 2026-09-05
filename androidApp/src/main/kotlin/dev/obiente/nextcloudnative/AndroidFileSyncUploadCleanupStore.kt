package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncPendingUploadCleanup
import dev.obiente.nextcloudnative.app.decodeFileSyncPendingUploadCleanupRecord
import dev.obiente.nextcloudnative.app.encodeFileSyncPendingUploadCleanupRecord
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

internal class AndroidFileSyncUploadCleanupStore(
    private val directory: File,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    fun read(): Map<String, List<FileSyncPendingUploadCleanup>> {
        if (!directory.exists()) return emptyMap()
        check(directory.isDirectory) { "Folder sync cleanup storage is invalid." }
        return checkNotNull(directory.listFiles()) { "Could not list folder sync cleanup storage." }
            .filter { it.isFile && it.name.endsWith(ROW_SUFFIX) }
            .map(::readRow)
            .groupBy(AndroidFileSyncUploadCleanupRow::pairId)
            .mapValues { (_, rows) ->
                rows.sortedWith(
                    compareBy<AndroidFileSyncUploadCleanupRow> { it.position }
                        .thenBy { it.cleanup.uploadId },
                ).map(AndroidFileSyncUploadCleanupRow::cleanup)
            }
    }

    /** Upserts first so a failed snapshot write cannot lose newly abandoned ownership. */
    fun retain(cleanups: Map<String, List<FileSyncPendingUploadCleanup>>) {
        if (cleanups.values.all { it.isEmpty() }) return
        ensureDirectory()
        cleanups.forEach { (pairId, rows) ->
            rows.forEachIndexed { position, cleanup -> writeRow(pairId, position, cleanup) }
        }
    }

    /** Removes obsolete rows only after the matching coordinator snapshot is durable. */
    fun replace(cleanups: Map<String, List<FileSyncPendingUploadCleanup>>) {
        if (!directory.exists() && cleanups.values.all { it.isEmpty() }) return
        ensureDirectory()
        val retainedNames = buildSet {
            cleanups.forEach { (pairId, rows) ->
                rows.forEach { cleanup ->
                    add(rowName(pairId, cleanup.uploadId))
                }
            }
        }
        checkNotNull(directory.listFiles()) { "Could not list folder sync cleanup storage." }
            .filter { it.isFile && it.name.endsWith(ROW_SUFFIX) && it.name !in retainedNames }
            .forEach { stale -> check(deleteFile(stale)) { "Could not remove obsolete sync cleanup ownership." } }
    }

    private fun readRow(file: File): AndroidFileSyncUploadCleanupRow =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            check(input.readInt() == MAGIC && input.readInt() == FORMAT_VERSION) {
                "Folder sync cleanup storage has an invalid header."
            }
            val pairId = input.readUtf8()
            val position = input.readInt().also { check(it >= 0) }
            val recordLength = input.readInt()
            check(recordLength in 1..MAX_RECORD_BYTES) { "Folder sync cleanup record has an invalid size." }
            val cleanup = decodeFileSyncPendingUploadCleanupRecord(ByteArray(recordLength).also(input::readFully))
            check(input.read() == -1) { "Folder sync cleanup record contains trailing data." }
            check(file.name == rowName(pairId, cleanup.uploadId)) { "Folder sync cleanup row name is invalid." }
            AndroidFileSyncUploadCleanupRow(pairId, position, cleanup)
        }

    private fun writeRow(pairId: String, position: Int, cleanup: FileSyncPendingUploadCleanup) {
        val record = encodeFileSyncPendingUploadCleanupRecord(cleanup)
        val destination = File(directory, rowName(pairId, cleanup.uploadId))
        val temporary = File.createTempFile("cleanup-", TEMP_SUFFIX, directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeUtf8(pairId)
                    output.writeInt(position)
                    output.writeInt(record.size)
                    output.write(record)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            try {
                Files.move(
                    temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size in 1..MAX_PAIR_ID_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf8(): String {
        val length = readInt()
        check(length in 1..MAX_PAIR_ID_BYTES)
        return ByteArray(length).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun rowName(pairId: String, uploadId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$pairId\u0000$uploadId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$digest$ROW_SUFFIX"
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create folder sync cleanup storage." }
    }

    private companion object {
        const val MAGIC = 0x4E435543 // NCUC
        const val FORMAT_VERSION = 1
        const val MAX_PAIR_ID_BYTES = 4 * 1024
        const val MAX_RECORD_BYTES = 128 * 1024
        const val ROW_SUFFIX = ".row"
        const val TEMP_SUFFIX = ".tmp"
    }
}

private data class AndroidFileSyncUploadCleanupRow(
    val pairId: String,
    val position: Int,
    val cleanup: FileSyncPendingUploadCleanup,
)
