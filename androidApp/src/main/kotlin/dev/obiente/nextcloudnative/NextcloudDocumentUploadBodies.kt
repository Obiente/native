package dev.obiente.nextcloudnative

import java.io.File
import java.io.RandomAccessFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal fun fileRangeRequestBody(
    source: File,
    offset: Long,
    length: Long,
    cancellation: DocumentRequestCancellation,
): RequestBody = object : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(source, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(UPLOAD_BODY_BUFFER_BYTES)
            var remaining = length
            while (remaining > 0L) {
                cancellation.throwIfCancelled()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(read > 0) { "The staged upload changed while a chunk was being read." }
                sink.write(buffer, 0, read)
                remaining -= read
            }
            cancellation.throwIfCancelled()
        }
    }
}

private const val UPLOAD_BODY_BUFFER_BYTES = 32 * 1024
