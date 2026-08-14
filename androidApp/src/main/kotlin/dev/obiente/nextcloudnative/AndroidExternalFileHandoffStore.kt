package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class AndroidExternalFileHandoffStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Durable, app-private metadata for temporary handoff capabilities. No session secret is stored. */
internal class AndroidExternalFileHandoffStore(
    internal val stateFile: File,
    internal val managedContentRoot: File? = null,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, STATE_DIRECTORY).resolve(STATE_FILE_NAME),
        androidExternalLargeShareCacheRoot(context.applicationContext.cacheDir),
    )

    @Synchronized
    fun load(): List<AndroidExternalFileHandoffRecord> {
        if (!stateFile.exists()) return emptyList()
        if (!stateFile.isFile || stateFile.length() !in 1L..MAX_STATE_BYTES) {
            throw AndroidExternalFileHandoffStoreException("External handoff state exceeds its safe storage limit.")
        }
        val loaded = try {
            DataInputStream(BufferedInputStream(FileInputStream(stateFile))).use { input ->
                requireStored(input.readInt() == MAGIC) { "External handoff state has an invalid header." }
                val formatVersion = input.readInt()
                requireStored(formatVersion in LEGACY_FORMAT_VERSION..FORMAT_VERSION) {
                    "External handoff state uses an unsupported format."
                }
                val count = input.readInt()
                requireStored(count in 0..MAX_RECORDS) { "External handoff state has too many records." }
                val records = List(count) { input.readRecord(formatVersion) }
                requireStored(input.read() == -1) { "External handoff state contains trailing data." }
                LoadedRecords(formatVersion, records)
            }
        } catch (failure: AndroidExternalFileHandoffStoreException) {
            throw failure
        } catch (failure: EOFException) {
            throw AndroidExternalFileHandoffStoreException("External handoff state is truncated.", failure)
        } catch (failure: Throwable) {
            throw AndroidExternalFileHandoffStoreException("External handoff state is invalid.", failure)
        }
        if (loaded.formatVersion < FORMAT_VERSION) save(loaded.records)
        return loaded.records
    }

    @Synchronized
    fun save(records: Collection<AndroidExternalFileHandoffRecord>) {
        requireStored(records.size <= MAX_RECORDS) { "External handoff state has too many records." }
        val parent = stateFile.parentFile
            ?: throw AndroidExternalFileHandoffStoreException("External handoff state has no parent directory.")
        if (records.isEmpty()) {
            if (stateFile.exists() && !stateFile.delete()) {
                throw AndroidExternalFileHandoffStoreException("Could not clear external handoff state.")
            }
            return
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw AndroidExternalFileHandoffStoreException("Could not create external handoff state storage.")
        }
        requireStored(parent.isDirectory) { "External handoff state parent is not a directory." }
        val temporary = File.createTempFile("${stateFile.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(records.size)
                records.sortedBy(AndroidExternalFileHandoffRecord::documentId).forEach { record ->
                    output.writeRecord(record)
                }
                output.flush()
                fileOutput.fd.sync()
            }
            requireStored(temporary.length() <= MAX_STATE_BYTES) {
                "External handoff state exceeds its safe storage limit."
            }
            publishAtomically(temporary, stateFile)
        } catch (failure: Throwable) {
            temporary.delete()
            if (failure is AndroidExternalFileHandoffStoreException) throw failure
            throw AndroidExternalFileHandoffStoreException("Could not persist external handoff state.", failure)
        }
    }

    fun deleteManagedContent(documentId: String) {
        val root = managedContentRoot ?: return
        val directory = androidExternalHandoffContentDirectory(root, documentId)
        if (directory.exists() && (!directory.deleteRecursively() || directory.exists())) {
            throw AndroidExternalFileHandoffStoreException("Could not clear managed external handoff content.")
        }
    }

    private fun DataOutputStream.writeRecord(record: AndroidExternalFileHandoffRecord) {
        writeBoundedString(record.documentId, MAX_DOCUMENT_ID_BYTES)
        writeBoundedString(record.accountId, MAX_ACCOUNT_ID_BYTES)
        writeBoundedString(record.file.path, MAX_PATH_BYTES)
        writeBoundedString(record.file.name, MAX_NAME_BYTES)
        writeNullableBoundedString(record.file.mimeType, MAX_MIME_TYPE_BYTES)
        writeLong(requireNotNull(record.file.size))
        writeNullableBoundedString(record.file.lastModified, MAX_LAST_MODIFIED_BYTES)
        writeBoolean(record.file.fileId != null)
        record.file.fileId?.let(::writeLong)
        writeBoolean(record.file.hasPreview)
        writeBoundedString(requireNotNull(record.file.etag), MAX_ETAG_BYTES)
        writeBoolean(record.file.originalAccessAllowed)
        writeBoolean(record.file.davPathAuthoritative)
        writeLong(record.createdAtEpochMillis)
        writeLong(record.expiresAtEpochMillis)
    }

    private fun DataInputStream.readRecord(formatVersion: Int): AndroidExternalFileHandoffRecord {
        val documentId = readBoundedString(MAX_DOCUMENT_ID_BYTES)
        val accountId = readBoundedString(MAX_ACCOUNT_ID_BYTES)
        val legacyUserId = if (formatVersion == LEGACY_FORMAT_VERSION) {
            readBoundedString(MAX_LEGACY_USER_ID_BYTES)
        } else {
            null
        }
        val path = readBoundedString(MAX_PATH_BYTES)
        val name = readBoundedString(MAX_NAME_BYTES)
        val mimeType = readNullableBoundedString(MAX_MIME_TYPE_BYTES)
        val size = readLong()
        val lastModified = readNullableBoundedString(MAX_LAST_MODIFIED_BYTES)
        val fileId = if (readBoolean()) readLong() else null
        val hasPreview = readBoolean()
        val etag = readBoundedString(MAX_ETAG_BYTES)
        val originalAccessAllowed = readBoolean()
        val davPathAuthoritative = readBoolean()
        val createdAtEpochMillis = readLong()
        val expiresAtEpochMillis = readLong()
        requireStored(
            AndroidExternalFileHandoffRegistry.isHandoffDocumentId(documentId) &&
                accountId.isNotBlank() &&
                legacyUserId?.isNotBlank() != false &&
                path.isNotBlank() &&
                name.isNotBlank() &&
                size >= 0L &&
                etag.isNotBlank() &&
                originalAccessAllowed &&
                davPathAuthoritative &&
                createdAtEpochMillis >= 0L &&
                expiresAtEpochMillis > createdAtEpochMillis,
        ) { "External handoff state contains an invalid record." }
        return AndroidExternalFileHandoffRecord(
            documentId = documentId,
            accountId = accountId,
            file = NextcloudFile(
                path = path,
                name = name,
                isDirectory = false,
                mimeType = mimeType,
                size = size,
                lastModified = lastModified,
                fileId = fileId,
                hasPreview = hasPreview,
                etag = etag,
                originalAccessAllowed = originalAccessAllowed,
                davPathAuthoritative = davPathAuthoritative,
            ),
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
    }

    private fun DataOutputStream.writeBoundedString(value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        requireStored(bytes.size <= maximumBytes) { "External handoff state contains an oversized value." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableBoundedString(value: String?, maximumBytes: Int) {
        writeBoolean(value != null)
        value?.let { writeBoundedString(it, maximumBytes) }
    }

    private fun DataInputStream.readBoundedString(maximumBytes: Int): String {
        val size = readInt()
        requireStored(size in 0..maximumBytes) { "External handoff state contains an oversized value." }
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableBoundedString(maximumBytes: Int): String? =
        if (readBoolean()) readBoundedString(maximumBytes) else null

    private fun publishAtomically(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun requireStored(condition: Boolean, message: () -> String) {
        if (!condition) throw AndroidExternalFileHandoffStoreException(message())
    }

    private companion object {
        data class LoadedRecords(
            val formatVersion: Int,
            val records: List<AndroidExternalFileHandoffRecord>,
        )

        const val MAGIC = 0x4e434848
        const val LEGACY_FORMAT_VERSION = 1
        const val FORMAT_VERSION = 2
        const val MAX_RECORDS = 32
        const val MAX_STATE_BYTES = 512L * 1024L
        const val MAX_DOCUMENT_ID_BYTES = 64
        const val MAX_ACCOUNT_ID_BYTES = 256
        const val MAX_LEGACY_USER_ID_BYTES = 4 * 1024
        const val MAX_PATH_BYTES = 64 * 1024
        const val MAX_NAME_BYTES = 4 * 1024
        const val MAX_MIME_TYPE_BYTES = 1024
        const val MAX_LAST_MODIFIED_BYTES = 1024
        const val MAX_ETAG_BYTES = 4 * 1024
        const val STATE_DIRECTORY = "external-handoff"
        const val STATE_FILE_NAME = "records.bin"
    }
}
