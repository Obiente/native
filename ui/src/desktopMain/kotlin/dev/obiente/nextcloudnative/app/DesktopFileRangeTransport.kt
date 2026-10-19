package dev.obiente.nextcloudnative.app

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/**
 * Generation-pinned Files WebDAV range requests for one [NextcloudFileRangeSession].
 *
 * Every request sends If-Match with the pinned ETag, requires HTTP 206 with exactly the requested
 * Content-Range, and fails when the body is shorter or longer than that range. [stream] serves a
 * whole range with one response and hands it over chunk by chunk as bytes arrive, so a large
 * hydration costs one server round trip instead of one per chunk.
 */
internal class DesktopFileRangeTransport(
    private val client: OkHttpClient,
    private val url: String,
    private val authorization: String,
    private val userAgent: String,
    private val etag: String,
    private val size: Long,
    private val closed: AtomicBoolean,
    private val recordFailure: (startedNanos: Long, attempt: JvmNetworkRequestAttempt, failure: Throwable) -> Unit,
) {
    private val activeCall = AtomicReference<Call?>(null)

    fun cancel() {
        activeCall.get()?.cancel()
    }

    fun read(offset: Long, length: Int): ByteArray = execute(offset, length.toLong()) { body ->
        body.byteStream().use { input -> input.readExactly(length).also { input.requireEnd() } }
    }

    fun stream(offset: Long, length: Long, chunkBytes: Int, onChunk: (offset: Long, bytes: ByteArray) -> Unit) {
        require(chunkBytes > 0) { "The file range chunk size must be greater than zero." }
        execute(offset, length) { body ->
            body.byteStream().use { input ->
                var position = 0L
                while (position < length) {
                    val chunk = input.readExactly(minOf(chunkBytes.toLong(), length - position).toInt())
                    onChunk(offset + position, chunk)
                    position += chunk.size
                }
                input.requireEnd()
            }
        }
    }

    private fun <T> execute(offset: Long, length: Long, consume: (ResponseBody) -> T): T {
        check(!closed.get()) { "The file range session is closed." }
        require(offset >= 0L) { "The file range offset must not be negative." }
        require(length > 0L) { "The file range length must be greater than zero." }
        require(offset <= size && length <= size - offset) { "The requested file range exceeds the file size." }
        val endInclusive = Math.addExact(offset, length - 1L)
        val started = System.nanoTime()
        val networkAttempt = JvmNetworkRequestAttempt()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/octet-stream")
            .header("Range", "bytes=$offset-$endInclusive")
            .header("If-Match", etag)
            .header("User-Agent", userAgent)
            .header("Authorization", authorization)
            .tag(JvmNetworkRequestAttempt::class.java, networkAttempt)
            .build()
        val call = client.newCall(request)
        check(activeCall.compareAndSet(null, call)) { "Only one file range read can run at a time." }
        if (closed.get()) call.cancel()
        try {
            call.execute().use { response ->
                if (response.code != 206) throw NextcloudFileRangeHttpException(response.code)
                check(isExactHttpByteContentRange(response.header("Content-Range"), offset, endInclusive)) {
                    "The server returned a different file range than requested."
                }
                val contentLength = response.body.contentLength()
                if (contentLength in 0 until length) throw JvmNetworkResponseTruncatedIOException()
                check(contentLength == -1L || contentLength == length) { "The server returned an incomplete file range." }
                return consume(response.body)
            }
        } catch (failure: Throwable) {
            recordFailure(started, networkAttempt, failure)
            throw failure
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = read(bytes, filled, length - filled)
            if (read < 0) throw JvmNetworkResponseTruncatedIOException()
            filled += read
        }
        return bytes
    }

    private fun InputStream.requireEnd() {
        check(read() == -1) { "The server returned more data than the requested file range." }
    }
}
