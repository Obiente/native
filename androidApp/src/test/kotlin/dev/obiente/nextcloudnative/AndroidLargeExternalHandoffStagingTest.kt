package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AndroidLargeExternalHandoffStagingTest {
    private val file = NextcloudFile("Notes.txt", "Notes.txt", false, "text/plain", 3L, null, null, false, "v1")

    @Test
    fun staleProducerCannotCreateAnOperationOrStartDownloading(): Unit = runBlocking {
        withFixture { cache, store ->
            val generation = AndroidExternalFileHandoffRegistry.captureGeneration()
            AndroidExternalFileHandoffRegistry.clearPersisted(store)
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                stageAndroidLargeExternalHandoffCopy(cache, file, 3L, generation) { _, _ -> error("Stale download") }
            }
            assertFalse(File(cache, EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY).exists())
        }
    }

    @Test
    fun cleanupDuringDownloadPreventsPromotionAndRemovesTheOperation(): Unit = runBlocking {
        withFixture { cache, store ->
            val generation = AndroidExternalFileHandoffRegistry.captureGeneration()
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                stageAndroidLargeExternalHandoffCopy(cache, file, 3L, generation) { output, _ ->
                    output.write(byteArrayOf(1, 2, 3))
                    AndroidExternalFileHandoffRegistry.clearPersisted(store)
                    AndroidDetachedDownload(3L, "text/plain")
                }
            }
            assertEquals(emptyList(), File(cache, EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY).listFiles().orEmpty().toList())
            val fresh = AndroidExternalFileHandoffRegistry.captureGeneration()
            val staged = stageAndroidLargeExternalHandoffCopy(cache, file, 3L, fresh) { output, maximum ->
                assertEquals(3L, maximum)
                output.write(byteArrayOf(1, 2, 3))
                AndroidDetachedDownload(3L, "text/plain")
            }
            assertContentEquals(byteArrayOf(1, 2, 3), staged.file.readBytes())
        }
    }

    @Test
    fun cancelledDownloadRetainsCancellationAndDiscardsItsReservation(): Unit = runBlocking {
        withFixture { cache, _ ->
            val cancelled = CancellationException("fixture")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                stageAndroidLargeExternalHandoffCopy(cache, file, 3L,
                    AndroidExternalFileHandoffRegistry.captureGeneration()) { output, _ ->
                    output.write(1)
                    throw cancelled
                }
            })
            assertEquals(emptyList(), File(cache, EXTERNAL_LARGE_SHARE_CACHE_DIRECTORY).listFiles().orEmpty().toList())
        }
    }

    private inline fun withFixture(action: (File, AndroidExternalFileHandoffStore) -> Unit) {
        val root = Files.createTempDirectory("large-handoff-generation-").toFile()
        val store = AndroidExternalFileHandoffStore(root.resolve("handoffs.bin"))
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, 1L)
            action(root.resolve("cache"), store)
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            root.walkBottomUp().forEach { it.setWritable(true) }
            assertTrue(root.deleteRecursively())
        }
    }
}
