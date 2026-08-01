package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class DesktopFileSyncRootRecord(
    val id: String,
    val absolutePath: String,
    val displayName: String,
)

internal data class DesktopFileSyncPersistedState(
    val coordinator: FileSyncCoordinatorState = FileSyncCoordinatorState(),
    val roots: List<DesktopFileSyncRootRecord> = emptyList(),
) {
    init {
        require(roots.size <= 64)
        require(roots.map(DesktopFileSyncRootRecord::id).distinct().size == roots.size)
        require(roots.all {
            it.id.isNotBlank() && it.id.length <= 256 &&
                it.absolutePath.isNotBlank() && it.absolutePath.length <= 8_192 &&
                it.displayName.isNotBlank() && it.displayName.length <= 256
        })
        require(coordinator.pairs.all { pair -> roots.any { it.id == pair.localRootId } })
    }
}

internal class DesktopFileSyncStore(private val stateFile: File = desktopFileSyncStateFile()) {
    private val transactionKey = runCatching(stateFile::getCanonicalPath).getOrElse {
        stateFile.toPath().toAbsolutePath().normalize().toString()
    }

    /** Serializes one complete load-mutate-save transaction across app processes. */
    fun <T> withExclusiveAccess(block: () -> T): T = processLocks
        .computeIfAbsent(transactionKey) { ReentrantLock() }
        .withLock {
            val parent = requireNotNull(stateFile.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Could not create desktop folder sync storage." }
            val lockFile = File(parent, "${stateFile.name}.lock")
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use { block() }
            }
        }

    @Synchronized
    fun load(): DesktopFileSyncPersistedState {
        if (!stateFile.exists()) return DesktopFileSyncPersistedState()
        require(stateFile.isFile && stateFile.length() in 1..MAX_STATE_BYTES) {
            "Desktop folder sync state exceeds its safe storage limit."
        }
        val snapshot = stateJson.decodeFromString<DesktopFileSyncSnapshotV1>(stateFile.readText())
        require(snapshot.schemaVersion == FORMAT_VERSION) { "Desktop folder sync state version is unsupported." }
        val coordinator = decodeFileSyncCoordinatorSnapshot(Base64.getDecoder().decode(snapshot.coordinatorBase64))
        val roots = snapshot.roots.map {
            DesktopFileSyncRootRecord(it.id, it.absolutePath, it.displayName)
        }
        return DesktopFileSyncPersistedState(coordinator, roots)
    }

    @Synchronized
    fun save(state: DesktopFileSyncPersistedState) {
        val snapshot = DesktopFileSyncSnapshotV1(
            coordinatorBase64 = Base64.getEncoder().encodeToString(
                encodeFileSyncCoordinatorSnapshot(state.coordinator),
            ),
            roots = state.roots.sortedBy(DesktopFileSyncRootRecord::id).map {
                DesktopFileSyncRootSnapshotV1(it.id, it.absolutePath, it.displayName)
            },
        )
        val bytes = stateJson.encodeToString(snapshot).encodeToByteArray()
        require(bytes.size.toLong() <= MAX_STATE_BYTES) { "Desktop folder sync state is too large." }
        val parent = requireNotNull(stateFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create desktop folder sync storage." }
        val temporary = File.createTempFile("${stateFile.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
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
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val processLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

@Serializable
private data class DesktopFileSyncSnapshotV1(
    val schemaVersion: Int = FORMAT_VERSION,
    val coordinatorBase64: String,
    val roots: List<DesktopFileSyncRootSnapshotV1>,
)

@Serializable
private data class DesktopFileSyncRootSnapshotV1(
    val id: String,
    val absolutePath: String,
    val displayName: String,
)

private fun desktopFileSyncStateFile(): File {
    val xdgState = System.getenv("XDG_STATE_HOME")?.takeIf(String::isNotBlank)
    val root = xdgState?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/state")
    return File(root, "nextcloud-native/file-sync-state.json")
}

private val stateJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

private const val FORMAT_VERSION = 1
private const val MAX_STATE_BYTES = 17L * 1024L * 1024L
