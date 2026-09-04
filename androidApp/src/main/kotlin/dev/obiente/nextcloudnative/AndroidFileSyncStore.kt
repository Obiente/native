package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.decodeFileSyncCoordinatorSnapshot
import dev.obiente.nextcloudnative.app.encodeFileSyncCoordinatorSnapshot
import dev.obiente.nextcloudnative.app.fileSyncOwnedUploads
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

internal data class AndroidFileSyncPersistedState(
    val coordinator: FileSyncCoordinatorState = FileSyncCoordinatorState(),
    val localDisplayNames: Map<String, String> = emptyMap(),
) {
    init {
        require(localDisplayNames.keys.all { id -> coordinator.pairs.any { it.id == id } })
        require(localDisplayNames.values.all { it.isNotBlank() && it.length <= 256 })
    }
}

internal fun removeAndroidFileSyncAccountPairs(
    state: AndroidFileSyncPersistedState,
    accountId: String,
): AndroidFileSyncPersistedState {
    require(accountId.isNotBlank())
    state.coordinator.pairs
        .filter { pair -> pair.accountId == accountId }
        .forEach { pair ->
            require(fileSyncOwnedUploads(pair).isEmpty()) {
                "Owned remote upload state must be recovered before removing this account's sync pairs."
            }
        }
    val retainedPairs = state.coordinator.pairs.filterNot { pair -> pair.accountId == accountId }
    val retainedPairIds = retainedPairs.mapTo(hashSetOf()) { pair -> pair.id }
    return AndroidFileSyncPersistedState(
        coordinator = FileSyncCoordinatorState(retainedPairs),
        localDisplayNames = state.localDisplayNames.filterKeys(retainedPairIds::contains),
    )
}

internal class AndroidFileSyncStore internal constructor(
    private val stateFile: File,
    private val maximumSnapshotBytes: Int = MAX_SNAPSHOT_BYTES,
) {
    init {
        require(maximumSnapshotBytes in 1..MAX_SNAPSHOT_BYTES)
    }

    private val uploadCleanupStore = AndroidFileSyncUploadCleanupStore(
        File(checkNotNull(stateFile.parentFile), "${stateFile.name}.upload-cleanups"),
    )

    constructor(context: Context) : this(File(context.filesDir, STATE_FILE_NAME))

    @Synchronized
    fun load(): AndroidFileSyncPersistedState {
        if (!stateFile.exists()) return AndroidFileSyncPersistedState()
        if (!stateFile.isFile || stateFile.length() !in 1..MAX_STATE_BYTES) {
            throw IllegalStateException("Folder sync state exceeds its safe storage limit.")
        }
        val stored = try {
            DataInputStream(BufferedInputStream(FileInputStream(stateFile))).use { input ->
                check(input.readInt() == MAGIC) { "Folder sync state has an invalid header." }
                check(input.readInt() == FORMAT_VERSION) { "Folder sync state version is unsupported." }
                val snapshotLength = input.readInt()
                check(snapshotLength in 1..maximumSnapshotBytes) { "Folder sync snapshot has an invalid size." }
                val coordinator = decodeFileSyncCoordinatorSnapshot(ByteArray(snapshotLength).also(input::readFully))
                val nameCount = input.readInt()
                check(nameCount in 0..MAX_PAIR_COUNT) { "Folder sync metadata contains too many pairs." }
                val names = buildMap {
                    repeat(nameCount) {
                        val pairId = input.readString()
                        val displayName = input.readString()
                        check(put(pairId, displayName) == null) { "Folder sync metadata has duplicate pair IDs." }
                    }
                }
                check(input.read() == -1) { "Folder sync state contains trailing data." }
                AndroidFileSyncPersistedState(coordinator, names)
            }
        } catch (failure: EOFException) {
            throw IllegalStateException("Folder sync state is truncated.", failure)
        } catch (failure: Throwable) {
            if (failure is IllegalStateException) throw failure
            throw IllegalStateException("Folder sync state is invalid.", failure)
        }
        val external = uploadCleanupStore.read()
        return stored.copy(
            coordinator = FileSyncCoordinatorState(
                stored.coordinator.pairs.map { pair ->
                    val externalCleanups = external[pair.id].orEmpty()
                    val externallyAbandonedIds = externalCleanups
                        .mapTo(mutableSetOf(), dev.obiente.nextcloudnative.app.FileSyncPendingUploadCleanup::uploadId)
                    pair.copy(
                        workItems = pair.workItems.map { work ->
                            if (work.uploadCheckpoint?.uploadId in externallyAbandonedIds) {
                                work.copy(uploadCheckpoint = null)
                            } else {
                                work
                            }
                        },
                        pendingUploadCleanups = (externalCleanups + pair.pendingUploadCleanups)
                            .distinctBy { it.uploadId },
                    )
                },
            ),
        )
    }

    @Synchronized
    fun save(state: AndroidFileSyncPersistedState) {
        val cleanups = state.coordinator.pairs.associate { it.id to it.pendingUploadCleanups }
        uploadCleanupStore.retain(cleanups)
        val snapshotCoordinator = FileSyncCoordinatorState(
            state.coordinator.pairs.map { it.copy(pendingUploadCleanups = emptyList()) },
        )
        val snapshot = encodeFileSyncCoordinatorSnapshot(snapshotCoordinator)
        check(snapshot.size <= maximumSnapshotBytes)
        val parent = checkNotNull(stateFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create folder sync storage." }
        val temporary = File.createTempFile("${stateFile.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeInt(snapshot.size)
                    output.write(snapshot)
                    output.writeInt(state.localDisplayNames.size)
                    state.localDisplayNames.toSortedMap().forEach { (pairId, displayName) ->
                        output.writeString(pairId)
                        output.writeString(displayName)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            check(temporary.length() <= MAX_STATE_BYTES) { "Folder sync state exceeds its safe storage limit." }
            try {
                Files.move(
                    temporary.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            uploadCleanupStore.replace(cleanups)
        } finally {
            temporary.delete()
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size in 1..MAX_STRING_BYTES) { "Folder sync metadata field is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        check(length in 1..MAX_STRING_BYTES) { "Folder sync metadata field has an invalid size." }
        val bytes = ByteArray(length).also(::readFully)
        val value = bytes.toString(StandardCharsets.UTF_8)
        check(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "Folder sync metadata is not valid UTF-8."
        }
        return value
    }

    private companion object {
        const val STATE_FILE_NAME = "file-sync-state.bin"
        const val MAGIC = 0x4E435359 // NCSY
        const val FORMAT_VERSION = 1
        const val MAX_PAIR_COUNT = 64
        const val MAX_STRING_BYTES = 4 * 1024
        const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
        const val MAX_STATE_BYTES = 17L * 1024L * 1024L
    }
}
