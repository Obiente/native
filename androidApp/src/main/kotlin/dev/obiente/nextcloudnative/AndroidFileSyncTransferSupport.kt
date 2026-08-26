package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationCandidate
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationResult
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Verifies one complete SAF generation in one background pass.
 *
 * SAF revision metadata is not strong enough to reuse partial hashes across scans. Reading one
 * candidate completely gives the comparison a content-backed local token without excluding large
 * files or retaining slices from a potentially different local generation.
 */
internal fun verifyAndroidFileSyncGeneration(
    candidate: FileSyncContentVerificationCandidate,
    readLocal: (expectedBytes: Long, maximumBytes: Long) -> AndroidFileSyncContentHashRead,
    verifyRemote: (expectedContentHash: String, expectedBytes: Long, maximumBytes: Long) -> Boolean,
): FileSyncContentVerificationResult {
    val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
    val maximumBytes = maxOf(1L, expectedBytes)
    val localRead = readLocal(expectedBytes, maximumBytes)
    check(localRead.bytesRead == expectedBytes) { "The local file changed during content verification." }
    val localHash = requireNotNull(localRead.contentHash) {
        "The local file could not be verified completely."
    }
    val matches = verifyRemote(localHash, expectedBytes, maximumBytes)
    return FileSyncContentVerificationResult(
        candidate = candidate,
        localContentHash = localHash,
        matchingContentHash = localHash.takeIf { matches },
    )
}

internal fun streamAndroidFileSyncDownload(
    declaredByteCount: Long?,
    writeLocal: (((OutputStream) -> Unit) -> Unit),
    readRemote: (OutputStream, maximumBytes: Long) -> Unit,
) {
    require(declaredByteCount == null || declaredByteCount >= 0L)
    val maximumBytes = declaredByteCount?.coerceAtLeast(1L) ?: Long.MAX_VALUE
    writeLocal { destination -> readRemote(destination, maximumBytes) }
}

internal fun normalizeRemoteRoot(path: String): String {
    val normalized = path.trim().trim('/')
    if (normalized.isEmpty()) return ""
    require(normalized.length <= 8_192)
    require(normalized.split('/').all {
        it.isNotBlank() && it !in setOf(".", "..") && it.none(Char::isISOControl)
    }) { "The Nextcloud folder path is invalid." }
    return normalized
}

internal fun safeFailureMessage(failure: Throwable, fallback: String): String =
    failure.message
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.trim()
        ?.take(1_024)
        ?.takeIf(String::isNotBlank)
        ?: fallback

internal fun androidFileSyncHttpClient(context: Context): OkHttpClient = OkHttpClient.Builder()
    .useAndroidNextcloudCertificateTrust(context)
    .readTimeout(FILE_SYNC_NETWORK_INACTIVITY_MINUTES, TimeUnit.MINUTES)
    .writeTimeout(FILE_SYNC_NETWORK_INACTIVITY_MINUTES, TimeUnit.MINUTES)
    .callTimeout(0L, TimeUnit.MILLISECONDS)
    .build()

private const val FILE_SYNC_NETWORK_INACTIVITY_MINUTES = 30L
