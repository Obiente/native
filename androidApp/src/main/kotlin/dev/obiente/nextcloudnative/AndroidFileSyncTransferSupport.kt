package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncContentVerificationSlice
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

internal inline fun streamAndroidFileSyncDownload(
    declaredByteCount: Long?,
    writeLocal: (((OutputStream) -> Unit) -> Unit),
    readRemote: (OutputStream, maximumBytes: Long) -> Unit,
) {
    require(declaredByteCount == null || declaredByteCount >= 0L)
    val maximumBytes = declaredByteCount?.coerceAtLeast(1L) ?: Long.MAX_VALUE
    writeLocal { destination -> readRemote(destination, maximumBytes) }
}
