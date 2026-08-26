package dev.obiente.nextcloudnative

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class AndroidIncomingShareFile(
    val id: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val stagedName: String,
    val contentHash: String? = null,
) {
    init {
        require(contentHash == null || INCOMING_SHARE_CONTENT_HASH_PATTERN.matches(contentHash))
    }
}

private val INCOMING_SHARE_CONTENT_HASH_PATTERN = Regex("sha256:[0-9a-f]{64}")

/**
 * Closes a provider-owned stream as soon as its coroutine is cancelled.
 *
 * A blocking ContentProvider read is not required to react to thread interruption. The sibling
 * cancellation watcher therefore closes the descriptor from another IO worker so Android can
 * release the provider request and the staging directory can be reclaimed.
 */
internal suspend fun <T> InputStream.useClosingOnCancellation(
    block: suspend (InputStream) -> T,
): T = coroutineScope {
    val input = this@useClosingOnCancellation
    val cancellationCloser = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            runCatching { input.close() }
        }
    }
    try {
        input.use { block(it) }
    } finally {
        cancellationCloser.cancel()
    }
}

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
