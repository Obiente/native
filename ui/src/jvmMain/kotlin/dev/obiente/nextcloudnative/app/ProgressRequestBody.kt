package dev.obiente.nextcloudnative.app

import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer

internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val forwarding = object : okio.ForwardingSink(sink) {
            var uploaded = 0L
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                uploaded += byteCount
                onProgress(uploaded, total)
            }
        }
        val buffered = forwarding.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

internal fun Long.saturatingAdd(increment: Long): Long =
    if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment
