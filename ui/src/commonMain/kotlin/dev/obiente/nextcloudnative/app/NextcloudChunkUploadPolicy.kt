package dev.obiente.nextcloudnative.app

sealed interface NextcloudUploadTransferPlan {
    data object Direct : NextcloudUploadTransferPlan

    data class Chunked(
        val sizeBytes: Long,
        val chunkBytes: Long,
        val chunkCount: Int,
    ) : NextcloudUploadTransferPlan {
        init {
            require(sizeBytes > 0L)
            require(chunkBytes in MIN_NEXTCLOUD_CHUNK_BYTES..MAX_NEXTCLOUD_CHUNK_BYTES)
            require(chunkCount in 2..MAX_NEXTCLOUD_UPLOAD_CHUNKS)
            require((((sizeBytes - 1L) / chunkBytes) + 1L).toInt() == chunkCount)
        }
    }
}

data class NextcloudUploadChunk(
    val number: Int,
    val offsetBytes: Long,
    val sizeBytes: Long,
) {
    init {
        require(number in 1..MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        require(offsetBytes >= 0L)
        require(sizeBytes > 0L)
    }

    val remoteName: String
        get() = number.toString().padStart(NEXTCLOUD_CHUNK_NAME_DIGITS, '0')
}

fun isValidNextcloudChunkUploadId(value: String): Boolean = NEXTCLOUD_CHUNK_UPLOAD_ID.matches(value)

/**
 * Plans a Nextcloud chunking-v2 upload without a product-level maximum file size.
 * Direct is also returned when the protocol's 10,000 chunks cannot represent the file.
 */
fun nextcloudUploadTransferPlan(
    sizeBytes: Long,
    directThresholdBytes: Long = DIRECT_NEXTCLOUD_UPLOAD_BYTES,
    preferredChunkBytes: Long = PREFERRED_NEXTCLOUD_CHUNK_BYTES,
    maximumChunkBytes: Long = MAX_NEXTCLOUD_CHUNK_BYTES,
): NextcloudUploadTransferPlan {
    require(sizeBytes >= 0L)
    require(directThresholdBytes >= 0L)
    require(preferredChunkBytes in MIN_NEXTCLOUD_CHUNK_BYTES..MAX_NEXTCLOUD_CHUNK_BYTES)
    require(maximumChunkBytes in preferredChunkBytes..MAX_NEXTCLOUD_CHUNK_BYTES)
    if (sizeBytes <= directThresholdBytes) return NextcloudUploadTransferPlan.Direct

    val minimumForProtocol = ((sizeBytes - 1L) / MAX_NEXTCLOUD_UPLOAD_CHUNKS) + 1L
    val chunkBytes = maxOf(preferredChunkBytes, minimumForProtocol)
    if (chunkBytes > maximumChunkBytes) return NextcloudUploadTransferPlan.Direct
    val chunkCount = (((sizeBytes - 1L) / chunkBytes) + 1L).toInt()
    return NextcloudUploadTransferPlan.Chunked(sizeBytes, chunkBytes, chunkCount)
}

/**
 * Resolves one persisted zero-based progress position into the exact chunking-v2 byte range.
 *
 * Keeping this arithmetic in common code makes Android and desktop resume the same local file
 * generation at identical protocol boundaries. The plan is revalidated against [sizeBytes] so a
 * stale checkpoint cannot silently skip or repeat bytes after the local file changes.
 */
fun nextcloudUploadChunk(
    plan: NextcloudUploadTransferPlan.Chunked,
    sizeBytes: Long,
    uploadedChunks: Int,
): NextcloudUploadChunk {
    require(sizeBytes == plan.sizeBytes) { "The chunk plan does not match this file generation." }
    require(uploadedChunks in 0 until plan.chunkCount)

    val offset = uploadedChunks.toLong() * plan.chunkBytes
    val remaining = sizeBytes - offset
    require(remaining > 0L)
    return NextcloudUploadChunk(
        number = uploadedChunks + 1,
        offsetBytes = offset,
        sizeBytes = minOf(plan.chunkBytes, remaining),
    )
}

const val DIRECT_NEXTCLOUD_UPLOAD_BYTES = 20L * 1024L * 1024L
const val PREFERRED_NEXTCLOUD_CHUNK_BYTES = 10L * 1024L * 1024L
const val MIN_NEXTCLOUD_CHUNK_BYTES = 5L * 1024L * 1024L
const val MAX_NEXTCLOUD_CHUNK_BYTES = 5L * 1024L * 1024L * 1024L
const val MAX_NEXTCLOUD_UPLOAD_CHUNKS = 10_000
private const val NEXTCLOUD_CHUNK_NAME_DIGITS = 5
private val NEXTCLOUD_CHUNK_UPLOAD_ID =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
