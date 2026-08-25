package dev.obiente.nextcloudnative

import java.io.File
import java.io.FileOutputStream

internal fun createIncomingShareStagingMarker(directory: File, markerName: String): File =
    File(directory, markerName).also { marker -> FileOutputStream(marker).use { it.fd.sync() } }

internal fun requireIncomingShareStagingSpace(
    directory: File,
    declaredBytes: Long?,
    displayName: String,
    reserveBytes: Long,
) {
    require(declaredBytes == null || declaredBytes <= (directory.usableSpace - reserveBytes).coerceAtLeast(0L)) {
        "There is not enough free storage to stage $displayName safely."
    }
}

internal fun requireIncomingShareStreamingSpace(directory: File, nextBytes: Int, reserveBytes: Long) {
    require(directory.usableSpace - nextBytes >= reserveBytes) {
        "There is not enough free storage to finish staging the shared files safely."
    }
}

internal fun removeExpiredAbandonedIncomingShareStaging(
    root: File,
    markerName: String,
    retentionMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
) {
    root.listFiles().orEmpty().asSequence()
        .filter(File::isDirectory)
        .forEach { directory ->
            removeExpiredAbandonedIncomingShareStagingDirectory(
                directory,
                markerName,
                retentionMillis,
                nowMillis,
            )
        }
}

internal fun removeExpiredAbandonedIncomingShareStagingDirectory(
    directory: File,
    markerName: String,
    retentionMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    require(retentionMillis >= 0L)
    if (!directory.isDirectory || File(directory, "request.json").isFile) return false
    val marker = File(directory, markerName)
    val ageAnchor = marker.takeIf(File::isFile) ?: directory
    if (nowMillis - ageAnchor.lastModified() < retentionMillis) return false
    return directory.deleteRecursively()
}
