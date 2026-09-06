package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudResponseTooLargeException
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun elapsedMillis(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L

internal fun InputStream.readBounded(maxBytes: Long, responseStatus: Int? = null): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, ANDROID_RESPONSE_BUFFER_BYTES.toLong()).toInt())
    val buffer = ByteArray(ANDROID_RESPONSE_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) throw NextcloudResponseTooLargeException(maxBytes, responseStatus)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private const val ANDROID_RESPONSE_BUFFER_BYTES = 8 * 1024
