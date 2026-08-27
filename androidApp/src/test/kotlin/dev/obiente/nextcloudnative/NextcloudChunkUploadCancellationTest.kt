package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class NextcloudChunkUploadCancellationTest {
    @Test
    fun chunkStagePublicationCancellationAbortsTheInflightMove() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(201).headersDelay(30, TimeUnit.SECONDS).build())
            val cancellation = TestCancellation()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Unit> {
                    NextcloudDocumentWebDav().publishChunkUploadStage(
                        NextcloudSession(server.url("/").toString().trimEnd('/'), "alice", "app-password"),
                        "alice",
                        "Shared/.nextcloud-native-01234567-89ab-cdef-0123-456789abcdef.upload",
                        "Shared/archive.bin",
                        stagedEtag = "stage-etag",
                        expectedRemoteEtag = null,
                        cancellation = cancellation,
                    )
                }
                assertTrue(cancellation.attached.await(2, TimeUnit.SECONDS))
                cancellation.cancel()
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    future.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is TestCancelledException)
                assertTrue(cancellation.detached.await(2, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun directoryBackupCancellationAbortsTheInflightMove() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(201).headersDelay(30, TimeUnit.SECONDS).build())
            val cancellation = TestCancellation()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Unit> {
                    NextcloudDocumentWebDav().moveDirectory(
                        NextcloudSession(server.url("/").toString().trimEnd('/'), "alice", "app-password"),
                        "alice",
                        "Shared/archive.bin",
                        "Shared/.nextcloud-native-backup-01234567-89ab-cdef-0123-456789abcdef",
                        expectedEtag = "directory-etag",
                        cancellation = cancellation,
                    )
                }
                assertTrue(cancellation.attached.await(2, TimeUnit.SECONDS))
                cancellation.cancel()
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    future.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is TestCancelledException)
                assertTrue(cancellation.detached.await(2, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
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
