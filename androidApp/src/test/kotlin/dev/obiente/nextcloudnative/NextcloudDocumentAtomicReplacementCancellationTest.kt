package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf

class NextcloudDocumentAtomicReplacementCancellationTest {
    @Test
    fun `atomic replacement observes cancellation while staging`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .headers(headersOf("ETag", "\"staged-1\""))
                    .headersDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(204)
                    .headersDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val source = Files.createTempFile("ncn-cancel-replace-", ".txt").toFile()
            val cancellation = TestCancellation()
            val executor = Executors.newSingleThreadExecutor()
            try {
                source.writeText("edited")
                val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
                val replacement = executor.submit<DocumentMutationResult> {
                    NextcloudDocumentWebDav().replaceFileAtomically(
                        session,
                        "alice",
                        "Documents/report.txt",
                        source,
                        "\"old-1\"",
                        cancellation,
                    )
                }

                val upload = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("PUT", upload.method)
                cancellation.cancel()
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    replacement.get(6, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is TestCancelledException)
                assertTrue(cancellation.detached.await(2, TimeUnit.SECONDS))
                val cleanup = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("DELETE", cleanup.method)
                assertEquals(upload.url.encodedPath, cleanup.url.encodedPath)
            } finally {
                executor.shutdownNow()
                source.delete()
            }
        }
    }

    @Test
    fun `atomic replacement cleans its stage when cancellation interrupts move`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(201)
                    .headers(headersOf("ETag", "\"staged-1\""))
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(201).headersDelay(30, TimeUnit.SECONDS).build())
            server.enqueue(MockResponse.Builder().code(204).build())
            server.start()
            val source = Files.createTempFile("ncn-cancel-move-", ".txt").toFile()
            val cancellation = TestCancellation()
            val executor = Executors.newSingleThreadExecutor()
            try {
                source.writeText("edited")
                val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
                val replacement = executor.submit<DocumentMutationResult> {
                    NextcloudDocumentWebDav().replaceFileAtomically(
                        session,
                        "alice",
                        "Documents/report.txt",
                        source,
                        "\"old-1\"",
                        cancellation,
                    )
                }

                val upload = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val move = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("PUT", upload.method)
                assertEquals("MOVE", move.method)
                cancellation.cancel()
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    replacement.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is TestCancelledException)
                val cleanup = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("DELETE", cleanup.method)
                assertEquals(upload.url.encodedPath, cleanup.url.encodedPath)
            } finally {
                executor.shutdownNow()
                source.delete()
            }
        }
    }

    private class TestCancellation : DocumentRequestCancellation {
        val attached = CountDownLatch(1)
        val detached = CountDownLatch(1)
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
            if (action == null) detached.countDown() else attached.countDown()
            if (cancelled) action?.invoke()
        }
    }

    private class TestCancelledException : RuntimeException()
}
