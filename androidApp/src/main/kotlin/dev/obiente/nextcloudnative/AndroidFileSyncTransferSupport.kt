package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncContentVerificationSlice
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationCandidate
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationResult
import dev.obiente.nextcloudnative.app.JvmFileSyncContentSliceOutcome
import dev.obiente.nextcloudnative.app.completeJvmFileSyncContentSlice
import java.io.OutputStream

internal fun verifyAndroidFileSyncSlice(
    slice: FileSyncContentVerificationSlice,
    local: AndroidFileSyncLocalTree,
    remote: AndroidFileSyncRemoteTree,
): JvmFileSyncContentSliceOutcome? {
    val candidate = slice.candidate
    val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
    val localHash = local.contentRangeHash(
        candidate.relativePath,
        candidate.localRevision,
        expectedBytes,
        slice.offset,
        slice.length,
    ) ?: return null
    val remoteHash = remote.contentRangeHash(
        candidate.relativePath,
        candidate.remoteEtag,
        expectedBytes,
        slice.offset,
        slice.length,
    )
    return completeJvmFileSyncContentSlice(slice, localHash, remoteHash)
}

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
