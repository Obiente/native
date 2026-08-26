package dev.obiente.nextcloudnative

import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidIncomingShareStagingStorageTest {
    @Test
    fun cancellationClosesABlockedProviderStream() = runBlocking {
        val input = CloseAwareBlockingInputStream()
        val copy = launch(Dispatchers.IO) {
            input.useClosingOnCancellation { provider -> provider.read() }
        }
        assertTrue(input.readStarted.await(5, TimeUnit.SECONDS))

        copy.cancelAndJoin()

        assertTrue(input.closed.get())
    }

    @Test
    fun scheduledExpiryRemovesOnlyAbandonedStaging() {
        val root = Files.createTempDirectory("incoming-share-staging-expiry").toFile()
        try {
            val abandoned = root.resolve("abandoned").apply { mkdirs() }
            val marker = createIncomingShareStagingMarker(abandoned, ".staging")
            marker.setLastModified(1_000L)
            assertFalse(
                removeExpiredAbandonedIncomingShareStagingDirectory(
                    abandoned,
                    ".staging",
                    retentionMillis = 1_000L,
                    nowMillis = 1_999L,
                ),
            )
            assertTrue(
                removeExpiredAbandonedIncomingShareStagingDirectory(
                    abandoned,
                    ".staging",
                    retentionMillis = 1_000L,
                    nowMillis = 2_000L,
                ),
            )

            val durable = root.resolve("durable").apply { mkdirs() }
            createIncomingShareStagingMarker(durable, ".staging").setLastModified(1_000L)
            durable.resolve("request.json").writeText("{}")
            assertFalse(
                removeExpiredAbandonedIncomingShareStagingDirectory(
                    durable,
                    ".staging",
                    retentionMillis = 1_000L,
                    nowMillis = 3_000L,
                ),
            )
            assertTrue(durable.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}

private class CloseAwareBlockingInputStream : InputStream() {
    val readStarted = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    private val closeSignal = CountDownLatch(1)

    override fun read(): Int {
        readStarted.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!closed.get() && System.nanoTime() < deadline) {
            try {
                closeSignal.await(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Model a ContentProvider stream that ignores thread interruption.
            }
        }
        return -1
    }

    override fun close() {
        closed.set(true)
        closeSignal.countDown()
    }
}
