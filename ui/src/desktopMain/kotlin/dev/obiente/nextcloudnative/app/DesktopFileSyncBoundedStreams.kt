package dev.obiente.nextcloudnative.app

import java.io.FilterInputStream
import java.io.InputStream

internal class BoundedInputStream(
    input: InputStream,
    private val maximumBytes: Long,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) count(1L)
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int =
        super.read(destination, offset, length).also { read ->
            if (read > 0) count(read.toLong())
        }

    override fun skip(requested: Long): Long {
        if (requested <= 0L) return 0L
        val buffer = ByteArray(minOf(requested, 64L * 1024L).toInt())
        var skipped = 0L
        while (skipped < requested) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), requested - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }

    private fun count(bytes: Long) {
        consumed += bytes
        require(consumed <= maximumBytes) { "The server response exceeds its safe size limit." }
    }
}

internal class GuardedXmlInputStream(input: InputStream) : FilterInputStream(input) {
    private var insideMarkup = false
    private var markupBytes = 0
    private var quote: Byte? = null
    private var previous: Byte? = null
    private var markupCount = 0
    private var cdataPrefixIndex = -1
    private var insideCdata = false
    private var cdataClosingBrackets = 0

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) inspect(value.toByte())
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int =
        super.read(destination, offset, length).also { read ->
            if (read > 0) {
                for (index in offset until offset + read) inspect(destination[index])
            }
        }

    override fun skip(requested: Long): Long {
        if (requested <= 0L) return 0L
        val buffer = ByteArray(minOf(requested, 64L * 1024L).toInt())
        var skipped = 0L
        while (skipped < requested) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), requested - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }

    private fun inspect(value: Byte) {
        if (insideCdata) {
            when {
                value == ']'.code.toByte() -> cdataClosingBrackets = (cdataClosingBrackets + 1).coerceAtMost(2)
                value == '>'.code.toByte() && cdataClosingBrackets == 2 -> {
                    insideCdata = false
                    cdataClosingBrackets = 0
                }
                else -> cdataClosingBrackets = 0
            }
            return
        }
        if (!insideMarkup) {
            if (value == '<'.code.toByte()) {
                insideMarkup = true
                markupBytes = 1
                markupCount += 1
            }
            previous = value
            return
        }
        markupBytes += 1
        require(markupBytes <= MAX_XML_MARKUP_BYTES) { "A DAV XML token is too large." }
        if (cdataPrefixIndex >= 0) {
            require(value == CDATA_PREFIX[cdataPrefixIndex].code.toByte()) {
                "DAV XML declarations are not supported."
            }
            cdataPrefixIndex += 1
            if (cdataPrefixIndex == CDATA_PREFIX.length) {
                cdataPrefixIndex = -1
                insideMarkup = false
                insideCdata = true
                markupBytes = 0
            }
            previous = value
            return
        }
        if (previous == '<'.code.toByte()) {
            if (value == '!'.code.toByte()) {
                cdataPrefixIndex = 0
                previous = value
                return
            }
            require(value != '?'.code.toByte() || markupCount == 1) {
                "DAV XML processing instructions are not supported."
            }
        }
        if (quote == null && (value == '\''.code.toByte() || value == '"'.code.toByte())) {
            quote = value
        } else if (quote == value) {
            quote = null
        } else if (quote == null && value == '>'.code.toByte()) {
            insideMarkup = false
            markupBytes = 0
        }
        previous = value
    }

    private companion object {
        const val CDATA_PREFIX = "[CDATA["
    }
}

private const val MAX_XML_MARKUP_BYTES = 16_384
