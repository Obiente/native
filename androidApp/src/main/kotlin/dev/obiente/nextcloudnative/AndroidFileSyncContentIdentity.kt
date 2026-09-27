package dev.obiente.nextcloudnative

import java.io.InputStream
import java.security.MessageDigest

internal fun sha256SyncContentHash(
    input: InputStream,
    expectedBytes: Long,
    maximumBytes: Long,
    shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
): String? = sha256SyncContentHashRead(input, expectedBytes, maximumBytes, shouldContinue).contentHash

internal fun sha256SyncContentHashRead(
    input: InputStream,
    expectedBytes: Long,
    maximumBytes: Long,
    shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
): AndroidFileSyncContentHashRead {
    require(expectedBytes >= 0L)
    require(maximumBytes > 0L)
    if (expectedBytes > maximumBytes) return AndroidFileSyncContentHashRead(null, 0L)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        if (!shouldContinue() || Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("File identity verification cancelled.")
        }
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) return AndroidFileSyncContentHashRead(null, total)
        digest.update(buffer, 0, read)
    }
    if (total != expectedBytes) return AndroidFileSyncContentHashRead(null, total)
    return AndroidFileSyncContentHashRead(
        "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        total,
    )
}
