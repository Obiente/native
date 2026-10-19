package dev.obiente.nextcloudnative.app

/**
 * Read-only handle for one immutable remote file generation.
 *
 * Closing the handle is idempotent from the caller's perspective. Platform implementations own
 * the synchronization needed to reject new reads and cancel an active request.
 */
class NextcloudFileRangeSession(
    val size: Long,
    private val readBlock: suspend (offset: Long, length: Int) -> ByteArray,
    private val closeBlock: () -> Unit = {},
    private val beginUseBlock: () -> (() -> Unit)? = { {} },
    private val streamBlock: (
        suspend (offset: Long, length: Long, chunkBytes: Int, onChunk: (Long, ByteArray) -> Unit) -> Unit
    )? = null,
) : AutoCloseable {
    init {
        require(size > 0L) { "A file range session must have a positive size." }
    }

    suspend fun read(offset: Long, length: Int): ByteArray = readBlock(offset, length)

    /**
     * Delivers [length] bytes from [offset] in order, as chunks of [chunkBytes] except a shorter
     * final chunk. A platform with streaming support serves the whole range with one request;
     * otherwise this falls back to one bounded read per chunk.
     */
    suspend fun stream(offset: Long, length: Long, chunkBytes: Int, onChunk: (offset: Long, bytes: ByteArray) -> Unit) {
        require(offset >= 0L && length > 0L && chunkBytes > 0) { "The file range stream is invalid." }
        require(offset <= size && length <= size - offset) { "The requested file range exceeds the file size." }
        streamBlock?.let { stream -> return stream(offset, length, chunkBytes, onChunk) }
        var position = 0L
        while (position < length) {
            val chunk = minOf(chunkBytes.toLong(), length - position).toInt()
            onChunk(offset + position, read(offset + position, chunk))
            position += chunk
        }
    }

    fun beginUse(): (() -> Unit)? = beginUseBlock()

    override fun close() = closeBlock()
}

/**
 * A bounded range read got [status] instead of a partial response, such as 401 or 403 for lost
 * access or 412 when the pinned ETag no longer matches. It stays an [IllegalStateException] so
 * existing range readers keep their failure handling.
 */
class NextcloudFileRangeHttpException(
    val status: Int,
) : IllegalStateException("The server did not honor the bounded file range request (HTTP $status).") {
    init {
        require(status in 100..599 && status != 206) { "A range HTTP failure requires a non-206 status." }
    }
}

const val MAX_FILE_RANGE_ETAG_LENGTH = 1_024

fun requireSafeFileRangeEtag(value: String): String {
    require(value == value.trim() && value.isNotEmpty() && value.length <= MAX_FILE_RANGE_ETAG_LENGTH) {
        "A safe current strong ETag is required for a file range read."
    }
    if (value.first() == '"' || value.last() == '"') {
        require(
            value.length >= 2 &&
                value.first() == '"' &&
                value.last() == '"' &&
                value.substring(1, value.lastIndex).all(::isHttpEntityTagCharacter),
        ) {
            "A safe current strong ETag is required for a file range read."
        }
        return value
    }
    require(
        value != "*" &&
            value.length <= MAX_FILE_RANGE_ETAG_LENGTH - 2 &&
            value.all(::isHttpEntityTagCharacter),
    ) {
        "A safe current strong ETag is required for a file range read."
    }
    return "\"$value\""
}

private fun isHttpEntityTagCharacter(character: Char): Boolean =
    character.code == 0x21 ||
        character.code in 0x23..0x7E ||
        character.code in 0x80..0xFF
