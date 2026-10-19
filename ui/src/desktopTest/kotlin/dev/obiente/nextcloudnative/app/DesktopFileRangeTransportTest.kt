package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer

class DesktopFileRangeTransportTest {
    private val content = ByteArray(3 * 1024 * 1024 + 100) { (it % 251).toByte() }

    private fun partial(offset: Long, endInclusive: Long, body: ByteArray = content.copyOfRange(offset.toInt(), endInclusive.toInt() + 1)) =
        MockResponse.Builder()
            .code(206)
            .addHeader("Content-Range", "bytes $offset-$endInclusive/${content.size}")
            .body(Buffer().write(body))
            .build()

    private fun MockWebServer.transport(failures: MutableList<Throwable> = mutableListOf()) = DesktopFileRangeTransport(
        client = OkHttpClient(),
        url = url("/remote.php/dav/files/synthetic/photo.jpg").toString(),
        authorization = "Basic c3ludGhldGljOnNlY3JldA==",
        userAgent = "test",
        etag = "\"etag-01\"",
        size = content.size.toLong(),
        closed = AtomicBoolean(false),
    ) { _, _, failure -> failures += failure }

    @Test
    fun `a streamed range is one pinned request delivered in ordered chunks`() {
        MockWebServer().use { server ->
            server.enqueue(partial(0L, content.size - 1L))
            server.start()
            val chunks = mutableListOf<Pair<Long, ByteArray>>()

            server.transport().stream(0L, content.size.toLong(), 1024 * 1024) { offset, bytes -> chunks += offset to bytes }

            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("bytes=0-${content.size - 1}", request.headers["Range"])
            assertEquals("\"etag-01\"", request.headers["If-Match"])
            assertEquals(1, server.requestCount)
            assertEquals(listOf(0L, 1_048_576L, 2_097_152L, 3_145_728L), chunks.map { it.first })
            assertEquals(100, chunks.last().second.size)
            assertContentEquals(content, chunks.flatMap { it.second.asIterable() }.toByteArray())
        }
    }

    @Test
    fun `a bounded read still returns the exact range`() {
        MockWebServer().use { server ->
            server.enqueue(partial(4_096L, 8_191L))
            server.start()

            assertContentEquals(content.copyOfRange(4_096, 8_192), server.transport().read(4_096L, 4_096))
        }
    }

    @Test
    fun `responses that do not match the pinned range fail before any chunk is delivered`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(412).build())
            server.enqueue(partial(0L, 4_095L).newBuilder().setHeader("Content-Range", "bytes 0-4095/1").build())
            server.enqueue(partial(0L, 8_191L, body = content.copyOfRange(0, 4_096)))
            server.enqueue(partial(0L, 4_095L, body = content.copyOfRange(0, 8_192)))
            server.start()
            val failures = mutableListOf<Throwable>()
            val transport = server.transport(failures)
            val delivered = mutableListOf<Long>()

            assertEquals(412, assertFailsWith<NextcloudFileRangeHttpException> {
                transport.stream(0L, 4_096L, 4_096) { offset, _ -> delivered += offset }
            }.status)
            assertFailsWith<IllegalStateException> { transport.stream(0L, 4_096L, 4_096) { offset, _ -> delivered += offset } }
            assertFailsWith<JvmNetworkResponseTruncatedIOException> {
                transport.stream(0L, 8_192L, 4_096) { offset, _ -> delivered += offset }
            }
            assertFailsWith<IllegalStateException> { transport.stream(0L, 4_096L, 4_096) { offset, _ -> delivered += offset } }

            assertEquals(emptyList(), delivered)
            assertEquals(4, failures.size)
        }
    }

    @Test
    fun `a closed transport refuses new requests`() {
        MockWebServer().use { server ->
            server.start()
            val closed = AtomicBoolean(true)
            val transport = DesktopFileRangeTransport(
                OkHttpClient(), server.url("/f").toString(), "Basic x", "test", "\"e\"", content.size.toLong(), closed,
            ) { _, _, _ -> }

            assertFailsWith<IllegalStateException> { transport.stream(0L, 4_096L, 4_096) { _, _ -> } }
            assertTrue(server.requestCount == 0)
        }
    }
}
