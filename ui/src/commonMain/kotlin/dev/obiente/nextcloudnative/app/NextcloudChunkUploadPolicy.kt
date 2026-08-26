package dev.obiente.nextcloudnative.app

sealed interface NextcloudUploadTransferPlan {
    data object Direct : NextcloudUploadTransferPlan

    data class Chunked(
        val chunkBytes: Long,
        val chunkCount: Int,
    ) : NextcloudUploadTransferPlan {
        init {
            require(chunkBytes in MIN_NEXTCLOUD_CHUNK_BYTES..MAX_NEXTCLOUD_CHUNK_BYTES)
            require(chunkCount in 2..MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        }
    }
}

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
    return NextcloudUploadTransferPlan.Chunked(chunkBytes, chunkCount)
}

const val DIRECT_NEXTCLOUD_UPLOAD_BYTES = 20L * 1024L * 1024L
const val PREFERRED_NEXTCLOUD_CHUNK_BYTES = 10L * 1024L * 1024L
const val MIN_NEXTCLOUD_CHUNK_BYTES = 5L * 1024L * 1024L
const val MAX_NEXTCLOUD_CHUNK_BYTES = 5L * 1024L * 1024L * 1024L
const val MAX_NEXTCLOUD_UPLOAD_CHUNKS = 10_000
