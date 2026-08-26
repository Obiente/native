package dev.obiente.nextcloudnative.app

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Coordinates temporary-file admission so concurrent transfers cannot each claim the same bytes.
 * The reservation is a safety policy derived from the live file store, not a product size limit.
 */
internal class DesktopStagingSpaceReservations(
    private val usableSpace: (File) -> Long = { root -> root.usableSpace.coerceAtLeast(0L) },
) {
    private val lock = Any()
    private val stores = mutableMapOf<DesktopStagingFileStoreKey, DesktopStagingFileStoreState>()

    fun reserve(
        root: File,
        declaredByteCount: Long?,
        reserveBytes: Long,
    ): DesktopStagingSpaceReservation {
        require(root.isDirectory) { "The staging root is not a directory." }
        require(declaredByteCount == null || declaredByteCount >= 0L)
        require(reserveBytes >= 0L)
        val store = Files.getFileStore(root.toPath())
        val key = DesktopStagingFileStoreKey(store.name(), store.type(), store.totalSpace)
        val currentUsable = usableSpace(root)
        synchronized(lock) {
            val state = stores.getOrPut(key) {
                DesktopStagingFileStoreState(
                    initialSafeBytes = subtractFloor(currentUsable, reserveBytes),
                )
            }
            val safeNow = subtractFloor(currentUsable, reserveBytes)
            val unclaimedFromInitial = subtractFloor(state.initialSafeBytes, state.reservedBytes)
            val available = min(safeNow, unclaimedFromInitial)
            check(declaredByteCount == null || declaredByteCount <= available) {
                "There is not enough free space for the temporary file copy."
            }
            check(available > 0L || declaredByteCount == 0L) {
                "There is not enough free space for the temporary file copy."
            }
            val claimedBytes = declaredByteCount ?: available
            state.reservedBytes = saturatingDesktopStagingAdd(state.reservedBytes, claimedBytes)
            state.activeReservations += 1
            return DesktopStagingSpaceReservation(
                maximumBytes = declaredByteCount?.coerceAtLeast(1L) ?: available,
                release = { release(key, claimedBytes) },
            )
        }
    }

    private fun release(key: DesktopStagingFileStoreKey, claimedBytes: Long) {
        synchronized(lock) {
            val state = requireNotNull(stores[key]) { "The staging-space reservation was already released." }
            state.reservedBytes = (state.reservedBytes - claimedBytes).coerceAtLeast(0L)
            state.activeReservations -= 1
            check(state.activeReservations >= 0)
            if (state.activeReservations == 0) stores.remove(key)
        }
    }
}

internal class DesktopStagingSpaceReservation(
    val maximumBytes: Long,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

private data class DesktopStagingFileStoreKey(
    val name: String,
    val type: String,
    val totalSpace: Long,
)

private data class DesktopStagingFileStoreState(
    val initialSafeBytes: Long,
    var reservedBytes: Long = 0L,
    var activeReservations: Int = 0,
)

private fun subtractFloor(left: Long, right: Long): Long =
    if (left >= right) left - right else 0L

private fun saturatingDesktopStagingAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

internal val sharedDesktopStagingSpaceReservations = DesktopStagingSpaceReservations()
