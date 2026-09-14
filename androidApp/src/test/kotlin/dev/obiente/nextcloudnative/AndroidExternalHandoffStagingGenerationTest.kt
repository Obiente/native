package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
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

class AndroidExternalHandoffStagingGenerationTest {
    @Test
    fun aMemoryDownloadFinishingAfterCleanupCannotCreateAPrivateCopy() = withFixture { cache, store ->
        val beforeDownload = AndroidExternalFileHandoffRegistry.captureGeneration()
        val downloadedBytes = byteArrayOf(1, 2, 3)
        AndroidExternalFileHandoffRegistry.clearPersisted(store)
        assertFailsWith<AndroidExternalFileHandoffRevokedException> {
            stageAndroidExternalHandoffBytes(cache, "Notes.txt", downloadedBytes, beforeDownload)
        }
        assertFalse(File(cache, EXTERNAL_SHARE_CACHE_DIRECTORY).exists())
        val fresh = AndroidExternalFileHandoffRegistry.captureGeneration()
        val result = stageAndroidExternalHandoffBytes(cache, "Notes.txt", downloadedBytes, fresh)
        assertContentEquals(downloadedBytes, result.readBytes())
    }

    @Test
    fun aStreamFinishingAfterCleanupDiscardsItsTemporaryCopy(): Unit = runBlocking {
        withFixture { cache, store ->
            val generation = AndroidExternalFileHandoffRegistry.captureGeneration()
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                stageAndroidExternalHandoffStream(cache, "Notes.txt", "text/plain", null, generation) { output, _ ->
                    output.write(byteArrayOf(1, 2, 3))
                    AndroidExternalFileHandoffRegistry.clearPersisted(store)
                    AndroidDetachedDownload(3L, "text/plain")
                }
            }
            assertEquals(emptyList(), File(cache, EXTERNAL_SHARE_CACHE_DIRECTORY).listFiles().orEmpty().toList())
        }
    }

    @Test
    fun failedCleanupStillRejectsAStreamProducerBeforeCreatingFiles(): Unit = runBlocking {
        withFixture(failClear = true) { cache, store ->
            val generation = AndroidExternalFileHandoffRegistry.captureGeneration()
            // Ensure durable state exists so the injected delete failure is exercised.
            AndroidExternalFileHandoffRegistry.register(
                NextcloudSession("https://cloud.example.test", "alice", "synthetic"),
                NextcloudFile("Notes.txt", "Notes.txt", false, "text/plain", 3L, null, null, false, "v1"),
                nowEpochMillis = 2L,
            )
            assertTrue(store.stateFile.isFile)
            assertFailsWith<AndroidExternalFileHandoffStoreException> { AndroidExternalFileHandoffRegistry.clearPersisted(store) }
            var downloaded = false
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                stageAndroidExternalHandoffStream(cache, "Notes.txt", "text/plain", null, generation) { _, _ ->
                    downloaded = true
                    AndroidDetachedDownload(0L, "text/plain")
                }
            }
            assertFalse(downloaded)
            assertFalse(File(cache, EXTERNAL_SHARE_CACHE_DIRECTORY).exists())
        }
    }

    @Test
    fun cancelledStreamPreservesCancellationAndRemovesItsCopy(): Unit = runBlocking {
        withFixture { cache, _ ->
            val cancelled = CancellationException("synthetic stream cancellation")
            val failure = assertFailsWith<CancellationException> {
                stageAndroidExternalHandoffStream(cache, "Notes.txt", "text/plain", null,
                    AndroidExternalFileHandoffRegistry.captureGeneration()) { output, _ ->
                    output.write(1)
                    throw cancelled
                }
            }
            assertSame(cancelled, failure)
            assertEquals(emptyList(), File(cache, EXTERNAL_SHARE_CACHE_DIRECTORY).listFiles().orEmpty().toList())
        }
    }

    @Test
    fun completedFreshStreamHasExactContentAndLaterCleanupRejectsLaunch(): Unit = runBlocking {
        withFixture { cache, store ->
            val generation = AndroidExternalFileHandoffRegistry.captureGeneration()
            val staged = stageAndroidExternalHandoffStream(cache, "Notes.txt", "text/plain", 3L, generation) { output, _ ->
                output.write(byteArrayOf(1, 2, 3))
                AndroidDetachedDownload(3L, "text/plain")
            }
            assertContentEquals(byteArrayOf(1, 2, 3), staged.file.readBytes())
            assertEquals("text/plain", staged.mimeType)
            AndroidExternalFileHandoffRegistry.clearPersisted(store)
            var launched = false
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                AndroidExternalFileHandoffRegistry.withGeneration(generation) { launched = true }
            }
            assertFalse(launched)
        }
    }

    private inline fun withFixture(failClear: Boolean = false, action: (File, AndroidExternalFileHandoffStore) -> Unit) {
        val root = Files.createTempDirectory("handoff-copy-generation-").toFile()
        val store = AndroidExternalFileHandoffStore(root.resolve("handoffs.bin"), deleteStateFile = { !failClear && it.delete() })
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
