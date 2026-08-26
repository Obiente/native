package dev.obiente.nextcloudnative.app

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

fun hashExactJvmFileSyncSlice(
    input: InputStream,
    length: Int,
    shouldContinue: () -> Boolean = { true },
    requireExhausted: Boolean = false,
): String {
    require(length >= 0)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = length
    while (remaining > 0) {
        if (!shouldContinue()) throw CancellationException("File identity verification cancelled.")
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        check(read >= 0) { "The file range ended before the expected byte count." }
        digest.update(buffer, 0, read)
        remaining -= read
    }
    if (requireExhausted) check(input.read() == -1) { "The file range exceeded the expected byte count." }
    return digest.digest().toSyncSha256()
}

fun advanceJvmFileSyncContentAggregate(previousHash: String, chunkHash: String): String {
    require(normalizeSyncSha256(previousHash) == previousHash)
    require(normalizeSyncSha256(chunkHash) == chunkHash)
    return MessageDigest.getInstance("SHA-256")
        .digest((previousHash + chunkHash).encodeToByteArray())
        .toSyncSha256()
}

data class JvmFileSyncContentSliceOutcome(
    val progress: FileSyncContentVerificationProgress? = null,
    val result: FileSyncContentVerificationResult? = null,
) {
    init {
        require((progress == null) != (result == null))
    }
}

fun completeJvmFileSyncContentSlice(
    slice: FileSyncContentVerificationSlice,
    localChunkHash: String,
    remoteChunkHash: String,
): JvmFileSyncContentSliceOutcome {
    require(normalizeSyncSha256(localChunkHash) == localChunkHash)
    require(normalizeSyncSha256(remoteChunkHash) == remoteChunkHash)
    if (localChunkHash != remoteChunkHash) {
        return JvmFileSyncContentSliceOutcome(
            result = FileSyncContentVerificationResult(slice.candidate, localChunkHash, null),
        )
    }
    val aggregate = advanceJvmFileSyncContentAggregate(slice.aggregateHash, localChunkHash)
    val verifiedBytes = slice.offset + slice.length
    return if (verifiedBytes == requireNotNull(slice.candidate.expectedSizeBytes)) {
        JvmFileSyncContentSliceOutcome(
            result = FileSyncContentVerificationResult(slice.candidate, aggregate, aggregate),
        )
    } else {
        JvmFileSyncContentSliceOutcome(
            progress = FileSyncContentVerificationProgress(slice.candidate, verifiedBytes, aggregate),
        )
    }
}

private fun ByteArray.toSyncSha256(): String =
    "sha256:" + joinToString("") { byte -> "%02x".format(byte) }
