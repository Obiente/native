package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.RandomAccessFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Streams one exact local-file range without materializing the chunk in memory. */
fun jvmFileRangeRequestBody(
    source: File,
    offsetBytes: Long,
    sizeBytes: Long,
    ensureActive: () -> Unit = {},
): RequestBody {
    require(source.isFile)
    require(offsetBytes >= 0L && sizeBytes > 0L)
    require(offsetBytes <= source.length() - sizeBytes)
    return object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = sizeBytes

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(source, "r").use { input ->
                input.seek(offsetBytes)
                val buffer = ByteArray(JVM_UPLOAD_BODY_BUFFER_BYTES)
                var remaining = sizeBytes
                while (remaining > 0L) {
                    ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read > 0) { "The staged upload changed while a chunk was being read." }
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
                ensureActive()
            }
        }
    }
}

private const val JVM_UPLOAD_BODY_BUFFER_BYTES = 32 * 1024
