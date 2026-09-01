package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.hashExactJvmFileSyncSlice
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class NextcloudDocumentRangeHashTransportTest {
    @Test
    fun `range hash follows an account-bound 307 with credentials and range`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(307)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/redirected.bin")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder().code(206)
                    .addHeader("ETag", "range-etag")
                    .addHeader("Content-Range", "bytes 2-4/8")
                    .body("cde")
                    .build(),
            )
            server.start()
            val hash = NextcloudDocumentWebDav(OkHttpClient()).readFileRangeHash(
                session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                userId = "alice",
                path = "source.bin",
                expectedEtag = "range-etag",
                expectedBytes = 8,
                offset = 2,
                length = 3,
            )

            assertEquals(hashExactJvmFileSyncSlice(ByteArrayInputStream("cde".encodeToByteArray()), 3), hash)
            val initial = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val redirected = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(listOf("GET", "GET"), listOf(initial.method, redirected.method))
            assertEquals("bytes=2-4", initial.headers["Range"])
            assertEquals(initial.headers["Range"], redirected.headers["Range"])
            assertEquals("Basic YWxpY2U6c2VjcmV0", initial.headers["Authorization"])
            assertEquals(initial.headers["Authorization"], redirected.headers["Authorization"])
        }
    }

    @Test
    fun `range hash rejects a redirect outside the account base path before another call`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(307)
                    .addHeader("Location", "/outside/remote.php/dav/files/alice/source.bin")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes 2-4/8")
                    .body("cde")
                    .build(),
            )
            server.start()

            assertFails {
                NextcloudDocumentWebDav(OkHttpClient()).readFileRangeHash(
                    session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                    userId = "alice",
                    path = "source.bin",
                    expectedEtag = "range-etag",
                    expectedBytes = 8,
                    offset = 2,
                    length = 3,
                )
            }

            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `cancellation stops the active redirected range request`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(307)
                    .addHeader("Location", "/cloud/remote.php/dav/files/alice/redirected.bin")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes 2-4/8")
                    .headersDelay(30, TimeUnit.SECONDS)
                    .body("cde")
                    .build(),
            )
            server.start()
            val cancellation = TestCancellation()
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "android-range-redirect-cancellation-test") {
                runCatching {
                    NextcloudDocumentWebDav(OkHttpClient()).readFileRangeHash(
                        session = NextcloudSession(server.url("/cloud").toString(), "alice", "secret"),
                        userId = "alice",
                        path = "source.bin",
                        expectedEtag = "range-etag",
                        expectedBytes = 8,
                        offset = 2,
                        length = 3,
                        cancellation = cancellation,
                    )
                }.exceptionOrNull()?.let(failure::set)
            }

            repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            cancellation.cancel()
            worker.join(2_000L)

            assertTrue(!worker.isAlive, "The cancelled redirected range request did not stop promptly.")
            assertIs<TestCancelledException>(failure.get())
        }
    }

    private class TestCancellation : DocumentRequestCancellation {
        @Volatile private var cancelled = false
        @Volatile private var cancelAction: (() -> Unit)? = null

        fun cancel() {
            cancelled = true
            cancelAction?.invoke()
        }

        override fun throwIfCancelled() {
            if (cancelled) throw TestCancelledException()
        }

        override fun setOnCancelAction(action: (() -> Unit)?) {
            cancelAction = action
            if (cancelled) action?.invoke()
        }
    }

    private class TestCancelledException : RuntimeException()
}
