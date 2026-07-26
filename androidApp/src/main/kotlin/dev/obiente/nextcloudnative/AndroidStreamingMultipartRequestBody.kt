package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.PreparedMultipartUpload
import dev.obiente.nextcloudnative.app.writePreparedMultipartUpload
import java.io.InputStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal class AndroidStreamingMultipartRequestBody(
    private val upload: PreparedMultipartUpload,
    private val openSource: () -> InputStream,
) : RequestBody() {
    override fun contentType(): MediaType = upload.contentType.toMediaType()

    override fun contentLength(): Long = upload.contentLength ?: -1L

    override fun writeTo(sink: BufferedSink) {
        openSource().use { source ->
            writePreparedMultipartUpload(
                upload = upload,
                readFile = source::read,
                write = { bytes, offset, count ->
                    sink.write(bytes, offset, count)
                },
            )
        }
    }
}
