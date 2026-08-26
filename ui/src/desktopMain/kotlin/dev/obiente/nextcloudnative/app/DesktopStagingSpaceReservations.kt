package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files

/**
 * Coordinates temporary-file admission so concurrent transfers cannot each claim the same bytes.
 * The reservation is a safety policy derived from the live file store, not a product size limit.
 */
internal class DesktopStagingSpaceReservations(
    private val usableSpace: (File) -> Long = { root -> root.usableSpace.coerceAtLeast(0L) },
    private val ledger: JvmStagingSpaceReservations = JvmStagingSpaceReservations(),
) {
    fun reserve(
        root: File,
        declaredByteCount: Long?,
        reserveBytes: Long,
    ): JvmStagingSpaceReservation {
        require(root.isDirectory) { "The staging root is not a directory." }
        require(declaredByteCount == null || declaredByteCount >= 0L)
        require(reserveBytes >= 0L)
        val store = Files.getFileStore(root.toPath())
        return ledger.reserve(
            storageKey = listOf(store.name(), store.type(), store.totalSpace).joinToString("\u0000"),
            usableBytes = usableSpace(root),
            declaredByteCount = declaredByteCount,
            reserveBytes = reserveBytes,
        )
    }
}

internal val sharedDesktopStagingSpaceReservations = DesktopStagingSpaceReservations()
