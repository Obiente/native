package dev.obiente.nextcloudnative.app

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class DesktopExternalFileCacheReservations {
    private val monitor = Any()
    private val reservedBytesByRoot = mutableMapOf<String, Long>()

    fun reserve(
        root: File,
        availableBytes: Long,
        declaredByteCount: Long?,
    ): DesktopExternalFileCacheReservation {
        require(root.isDirectory)
        require(availableBytes >= 0L)
        require(declaredByteCount == null || declaredByteCount >= 0L)
        val key = root.canonicalFile.path
        return synchronized(monitor) {
            val alreadyReserved = reservedBytesByRoot[key] ?: 0L
            val unreserved = (availableBytes - alreadyReserved).coerceAtLeast(0L)
            val reserved = declaredByteCount ?: unreserved
            check(reserved <= unreserved) {
                "Concurrent desktop external-file copies already use the cache limit."
            }
            if (reserved > 0L) reservedBytesByRoot[key] = alreadyReserved + reserved
            DesktopExternalFileCacheReservation(reserved) {
                if (reserved > 0L) {
                    synchronized(monitor) {
                        val remaining = requireNotNull(reservedBytesByRoot[key]) - reserved
                        if (remaining == 0L) reservedBytesByRoot.remove(key) else reservedBytesByRoot[key] = remaining
                    }
                }
            }
        }
    }
}

internal class DesktopExternalFileCacheReservation(
    val maximumBytes: Long,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal val sharedDesktopExternalFileCacheReservations = DesktopExternalFileCacheReservations()
