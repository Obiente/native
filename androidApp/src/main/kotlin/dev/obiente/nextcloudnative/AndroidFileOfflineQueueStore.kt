package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileOfflineDescriptor
import dev.obiente.nextcloudnative.app.FileOfflineIntent
import dev.obiente.nextcloudnative.app.FileOfflineJob
import dev.obiente.nextcloudnative.app.FileOfflineJobOperation
import dev.obiente.nextcloudnative.app.FileOfflineJobStatus
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileOfflinePinRecord
import dev.obiente.nextcloudnative.app.FileOfflineQueueState
import dev.obiente.nextcloudnative.app.FileSyncDecisionReason
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

internal class OfflineQueueStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class AndroidFileOfflinePersistedState(
    val queue: FileOfflineQueueState = FileOfflineQueueState(),
    val folders: AndroidOfflineFolderState = AndroidOfflineFolderState(),
)

/**
 * Versioned app-private persistence for offline-pin intent and queue metadata.
 *
 * Content blobs deliberately live elsewhere. Saving writes and fsyncs a sibling temporary file,
 * then publishes it with an atomic move where the filesystem supports one. A corrupt or unknown
 * state is reported rather than silently discarded, protecting the user's pin intent.
 */
internal class AndroidFileOfflineQueueStore private constructor(
    private val stateFile: File,
) {
    constructor(context: Context) : this(File(context.filesDir, STATE_FILE_NAME))

    @Synchronized
    fun load(): AndroidFileOfflinePersistedState {
        if (!stateFile.exists()) return AndroidFileOfflinePersistedState()
        if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) {
            throw OfflineQueueStoreException("Offline queue state exceeds its safe storage limit.")
        }
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(stateFile))).use { input ->
                requireStored(input.readInt() == MAGIC) { "Offline queue state has an invalid header." }
                val version = input.readInt()
                requireStored(version in 1..FORMAT_VERSION) {
                    "Unsupported offline queue state version $version."
                }
                val nextJobId = input.readLong()
                val records = List(input.readBoundedCount("record")) { input.readRecord() }
                val jobs = List(input.readBoundedCount("job")) { input.readJob() }
                val queue = FileOfflineQueueState(records = records, jobs = jobs, nextJobId = nextJobId)
                val folders = if (version >= 2) {
                    input.readFolderState()
                } else {
                    AndroidOfflineFolderState(
                        directPins = records
                            .filter { it.intent == FileOfflineIntent.Pinned }
                            .mapTo(linkedSetOf()) { it.descriptor.key },
                    )
                }
                requireStored(input.read() == -1) { "Offline queue state contains trailing data." }
                AndroidFileOfflinePersistedState(queue, folders)
            }
        } catch (failure: OfflineQueueStoreException) {
            throw failure
        } catch (failure: EOFException) {
            throw OfflineQueueStoreException("Offline queue state is truncated.", failure)
        } catch (failure: Throwable) {
            throw OfflineQueueStoreException("Offline queue state is invalid.", failure)
        }
    }

    @Synchronized
    fun save(state: AndroidFileOfflinePersistedState) {
        val parent = stateFile.parentFile ?: throw OfflineQueueStoreException("Offline queue path has no parent.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw OfflineQueueStoreException("Could not create offline queue storage.")
        }
        require(parent.isDirectory) { "Offline queue parent is not a directory." }
        val temporary = File.createTempFile("${stateFile.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeLong(state.queue.nextJobId)
                output.writeInt(state.queue.records.size.requireStoredCount("record"))
                state.queue.records.sortedBy { it.descriptor.key }.forEach { output.writeRecord(it) }
                output.writeInt(state.queue.jobs.size.requireStoredCount("job"))
                state.queue.jobs.sortedBy(FileOfflineJob::id).forEach { output.writeJob(it) }
                output.writeFolderState(state.folders)
                output.flush()
                fileOutput.fd.sync()
            }
            requireStored(temporary.length() <= MAX_STATE_BYTES) {
                "Offline queue state exceeds its safe storage limit."
            }
            publishAtomically(temporary, stateFile)
        } catch (failure: Throwable) {
            temporary.delete()
            if (failure is OfflineQueueStoreException) throw failure
            throw OfflineQueueStoreException("Could not persist offline queue state.", failure)
        }
    }

    private fun DataOutputStream.writeRecord(record: FileOfflinePinRecord) {
        writeDescriptor(record.descriptor)
        writeEnum(record.intent)
        writeNullableString(record.localRevision)
        writeNullableString(record.syncedRemoteEtag)
        writeNullableEnum(record.attentionReason)
        writeLong(record.updatedAtEpochMillis)
    }

    private fun DataInputStream.readRecord(): FileOfflinePinRecord = FileOfflinePinRecord(
        descriptor = readDescriptor(),
        intent = readEnum<FileOfflineIntent>(),
        localRevision = readNullableString(),
        syncedRemoteEtag = readNullableString(),
        attentionReason = readNullableEnum<FileSyncDecisionReason>(),
        updatedAtEpochMillis = readLong(),
    )

    private fun DataOutputStream.writeDescriptor(descriptor: FileOfflineDescriptor) {
        writeKey(descriptor.key)
        writeStoredString(descriptor.displayName)
        writeStoredString(descriptor.remoteEtag)
        writeNullableLong(descriptor.size)
        writeNullableString(descriptor.mimeType)
    }

    private fun DataInputStream.readDescriptor(): FileOfflineDescriptor = FileOfflineDescriptor(
        key = readKey(),
        displayName = readStoredString(),
        remoteEtag = readStoredString(),
        size = readNullableLong(),
        mimeType = readNullableString(),
    )

    private fun DataOutputStream.writeJob(job: FileOfflineJob) {
        writeLong(job.id)
        writeKey(job.key)
        writeEnum(job.operation)
        writeNullableString(job.expectedRemoteEtag)
        writeNullableString(job.expectedLocalRevision)
        writeEnum(job.status)
        writeInt(job.attemptCount)
        writeLong(job.enqueuedAtEpochMillis)
        writeNullableString(job.failureMessage)
    }

    private fun DataInputStream.readJob(): FileOfflineJob = FileOfflineJob(
        id = readLong(),
        key = readKey(),
        operation = readEnum<FileOfflineJobOperation>(),
        expectedRemoteEtag = readNullableString(),
        expectedLocalRevision = readNullableString(),
        status = readEnum<FileOfflineJobStatus>(),
        attemptCount = readInt(),
        enqueuedAtEpochMillis = readLong(),
        failureMessage = readNullableString(),
    )

    private fun DataOutputStream.writeFolderState(state: AndroidOfflineFolderState) {
        writeInt(state.directPins.size.requireStoredCount("direct pin"))
        state.directPins.sorted().forEach { writeKey(it) }
        writeInt(state.roots.size.requireStoredCount("folder root"))
        state.roots.sortedWith(compareBy(AndroidOfflineFolderRoot::accountId, AndroidOfflineFolderRoot::rootPath))
            .forEach { root ->
                writeStoredString(root.accountId)
                writeStoredString(root.rootPath)
                writeStoredString(root.rootDisplayName)
                writeInt(root.directories.size.requireStoredCount("offline directory"))
                root.directories.sortedBy(AndroidOfflineDirectory::path).forEach { directory ->
                    writeStoredString(directory.path)
                    writeStoredString(directory.displayName)
                    writeNullableString(directory.remoteEtag)
                    writeNullableString(directory.lastModified)
                    writeNullableLong(directory.fileId)
                }
                writeInt(root.filePaths.size.requireStoredCount("folder file"))
                root.filePaths.sorted().forEach { writeStoredString(it) }
            }
    }

    private fun DataInputStream.readFolderState(): AndroidOfflineFolderState {
        val directPins = List(readBoundedCount("direct pin")) { readKey() }.toSet()
        val roots = List(readBoundedCount("folder root")) {
            val accountId = readStoredString()
            val rootPath = readStoredString()
            val displayName = readStoredString()
            val directories = List(readBoundedCount("offline directory")) {
                AndroidOfflineDirectory(
                    path = readStoredString(),
                    displayName = readStoredString(),
                    remoteEtag = readNullableString(),
                    lastModified = readNullableString(),
                    fileId = readNullableLong(),
                )
            }
            val filePaths = List(readBoundedCount("folder file")) { readStoredString() }
            AndroidOfflineFolderRoot(accountId, rootPath, displayName, directories, filePaths)
        }
        return AndroidOfflineFolderState(directPins, roots)
    }

    private fun DataOutputStream.writeKey(key: FileOfflineKey) {
        writeStoredString(key.accountId)
        writeStoredString(key.relativePath)
    }

    private fun DataInputStream.readKey() = FileOfflineKey(readStoredString(), readStoredString())

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        value?.let(::writeLong)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        value?.let { writeStoredString(it) }
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readStoredString() else null

    private inline fun <reified T : Enum<T>> DataOutputStream.writeNullableEnum(value: T?) {
        writeBoolean(value != null)
        value?.let { writeEnum(it) }
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) readEnum<T>() else null

    private fun DataOutputStream.writeEnum(value: Enum<*>) = writeStoredString(value.name)

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val name = readStoredString()
        return enumValues<T>().firstOrNull { it.name == name }
            ?: throw OfflineQueueStoreException("Unknown ${T::class.simpleName} value in offline queue state.")
    }

    private fun DataOutputStream.writeStoredString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        requireStored(encoded.size <= MAX_STRING_BYTES) { "Offline queue field exceeds its safe size limit." }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readStoredString(): String {
        val length = readInt()
        requireStored(length in 0..MAX_STRING_BYTES) { "Offline queue field length is invalid." }
        val bytes = ByteArray(length)
        readFully(bytes)
        val value = bytes.toString(StandardCharsets.UTF_8)
        requireStored(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "Offline queue field is not canonical UTF-8."
        }
        return value
    }

    private fun DataInputStream.readBoundedCount(label: String): Int = readInt().also {
        requireStored(it in 0..MAX_ITEM_COUNT) { "Offline queue $label count is invalid." }
    }

    private fun Int.requireStoredCount(label: String): Int = also {
        requireStored(it in 0..MAX_ITEM_COUNT) { "Offline queue $label count exceeds its safe limit." }
    }

    private fun publishAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun requireStored(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) throw OfflineQueueStoreException(lazyMessage())
    }

    internal companion object {
        private const val STATE_FILE_NAME = "offline-file-queue-v1.bin"
        private const val MAGIC = 0x4e434f46 // NCOF
        private const val FORMAT_VERSION = 2
        private const val MAX_STATE_BYTES = 16L * 1024L * 1024L
        private const val MAX_STRING_BYTES = 16 * 1024
        private const val MAX_ITEM_COUNT = 100_000

        fun forTesting(stateFile: File) = AndroidFileOfflineQueueStore(stateFile)
    }
}
