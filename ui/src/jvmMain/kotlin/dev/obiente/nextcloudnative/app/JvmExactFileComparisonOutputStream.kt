package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Compares a streamed remote generation with one staged local file without retaining either. */
class JvmExactFileComparisonOutputStream(
    source: File,
    private val expectedBytes: Long,
) : OutputStream() {
    private val sourceInput = source.inputStream()
    private val sourceBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
    private var comparedBytes = 0L
    private var closed = false

    init {
        require(expectedBytes >= 0L)
        require(source.isFile && source.length() == expectedBytes)
    }

    override fun write(value: Int) {
        throwIfInterrupted()
        check(comparedBytes < expectedBytes) { "The assembled upload stage is larger than expected." }
        val expected = sourceInput.read()
        check(expected >= 0 && expected == value.and(0xff)) {
            "The assembled upload stage does not match the staged local file."
        }
        comparedBytes = Math.addExact(comparedBytes, 1L)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var remoteOffset = offset
        var remaining = length
        while (remaining > 0) {
            throwIfInterrupted()
            val requested = minOf(remaining, sourceBuffer.size)
            check(comparedBytes <= expectedBytes - requested) {
                "The assembled upload stage is larger than expected."
            }
            val localCount = sourceInput.readFullyOrEnd(sourceBuffer, requested)
            check(localCount == requested) { "The assembled upload stage is larger than expected." }
            check(bytes.regionMatches(remoteOffset, sourceBuffer, requested)) {
                "The assembled upload stage does not match the staged local file."
            }
            comparedBytes = Math.addExact(comparedBytes, requested.toLong())
            remoteOffset += requested
            remaining -= requested
        }
    }

    fun requireComplete() {
        check(comparedBytes == expectedBytes && sourceInput.read() == -1) {
            "The assembled upload stage is truncated."
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        sourceInput.close()
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("Upload stage verification cancelled.")
        }
    }
}

private fun InputStream.readFullyOrEnd(destination: ByteArray, requested: Int): Int {
    var total = 0
    while (total < requested) {
        val read = read(destination, total, requested - total)
        if (read < 0) break
        if (read == 0) continue
        total += read
    }
    return total
}

private fun ByteArray.regionMatches(offset: Int, expected: ByteArray, length: Int): Boolean {
    for (index in 0 until length) {
        if (this[offset + index] != expected[index]) return false
    }
    return true
}
