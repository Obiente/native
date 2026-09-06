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
) : AutoCloseable {
    init {
        require(size > 0L) { "A file range session must have a positive size." }
    }

    suspend fun read(offset: Long, length: Int): ByteArray = readBlock(offset, length)

    fun beginUse(): (() -> Unit)? = beginUseBlock()

    override fun close() = closeBlock()
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
