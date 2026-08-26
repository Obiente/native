package dev.obiente.nextcloudnative.app

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Shared JVM ledger that prevents concurrent staging operations from claiming the same bytes. */
class JvmStagingSpaceReservations {
    private val lock = Any()
    private val stores = mutableMapOf<String, JvmStagingSpaceState>()

    fun reserve(
        storageKey: String,
        usableBytes: Long,
        declaredByteCount: Long?,
        reserveBytes: Long,
    ): JvmStagingSpaceReservation {
        require(storageKey.isNotBlank())
        require(usableBytes >= 0L)
        require(declaredByteCount == null || declaredByteCount >= 0L)
        require(reserveBytes >= 0L)
        synchronized(lock) {
            val safeNow = subtractJvmStagingFloor(usableBytes, reserveBytes)
            val state = stores.getOrPut(storageKey) { JvmStagingSpaceState(initialSafeBytes = safeNow) }
            val unclaimedFromInitial = subtractJvmStagingFloor(state.initialSafeBytes, state.reservedBytes)
            val available = min(safeNow, unclaimedFromInitial)
            check(declaredByteCount == null || declaredByteCount <= available) {
                "There is not enough free space for the temporary file copy."
            }
            check(available > 0L || declaredByteCount == 0L) {
                "There is not enough free space for the temporary file copy."
            }
            val claimedBytes = declaredByteCount ?: available
            state.reservedBytes = saturatingJvmStagingAdd(state.reservedBytes, claimedBytes)
            state.activeReservations += 1
            return JvmStagingSpaceReservation(
                maximumBytes = declaredByteCount?.coerceAtLeast(1L) ?: available,
                release = { release(storageKey, claimedBytes) },
            )
        }
    }

    private fun release(storageKey: String, claimedBytes: Long) {
        synchronized(lock) {
            val state = requireNotNull(stores[storageKey]) { "The staging-space reservation was already released." }
            state.reservedBytes = (state.reservedBytes - claimedBytes).coerceAtLeast(0L)
            state.activeReservations -= 1
            check(state.activeReservations >= 0)
            if (state.activeReservations == 0) stores.remove(storageKey)
        }
    }
}

class JvmStagingSpaceReservation(
    val maximumBytes: Long,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

private data class JvmStagingSpaceState(
    val initialSafeBytes: Long,
    var reservedBytes: Long = 0L,
    var activeReservations: Int = 0,
)

private fun subtractJvmStagingFloor(left: Long, right: Long): Long =
    if (left >= right) left - right else 0L

private fun saturatingJvmStagingAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

val sharedJvmStagingSpaceReservations = JvmStagingSpaceReservations()

/** Stable identity shared by every staging owner on one physical filesystem. */
fun jvmStagingStorageKey(root: File): String {
    require(root.isDirectory) { "The staging root is not a directory." }
    val store = Files.getFileStore(root.canonicalFile.toPath())
    return listOf(store.name(), store.type(), store.totalSpace).joinToString("\u0000")
}
