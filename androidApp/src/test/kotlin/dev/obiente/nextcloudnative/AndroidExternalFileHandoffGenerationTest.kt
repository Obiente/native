package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class AndroidExternalFileHandoffGenerationTest {
    private val session = NextcloudSession("https://cloud.example.test", "person", "password")
    private val file = NextcloudFile("Notes.txt", "Notes.txt", false, "text/plain", 4L, null, null, false, "v1")

    @Test
    fun successfulClearRejectsAProducerThatFinishesAfterCleanupAndAllowsFreshWork(): Unit = runBlocking {
        val root = Files.createTempDirectory("handoff-generation-").toFile()
        val store = AndroidExternalFileHandoffStore(root.resolve("handoffs.bin"))
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, 1L)
            val old = captureAndroidExternalFileHandoffGeneration(session) { session }
            AndroidExternalFileHandoffRegistry.clearPersisted(store)
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                AndroidExternalFileHandoffRegistry.register(session, file, 2L, old)
            }
            assertEquals(emptyList(), store.load())
            var published = false
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                AndroidExternalFileHandoffRegistry.withGeneration(old) { published = true }
            }
            assertFalse(published)
            val fresh = captureAndroidExternalFileHandoffGeneration(session) { session }
            val record = AndroidExternalFileHandoffRegistry.register(session, file, 3L, fresh)
            assertNotNull(AndroidExternalFileHandoffRegistry.peek(record.documentId, session, 4L))
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedDurableClearStillInvalidatesTheOldProducer() {
        val root = Files.createTempDirectory("handoff-failed-clear-generation-").toFile()
        val store = AndroidExternalFileHandoffStore(root.resolve("handoffs.bin"), deleteStateFile = { false })
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, 1L)
            val old = AndroidExternalFileHandoffRegistry.captureGeneration()
            AndroidExternalFileHandoffRegistry.register(session, file, 2L, old)
            assertFailsWith<AndroidExternalFileHandoffStoreException> { AndroidExternalFileHandoffRegistry.clearPersisted(store) }
            assertFailsWith<AndroidExternalFileHandoffRevokedException> {
                AndroidExternalFileHandoffRegistry.register(session, file, 3L, old)
            }
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            root.deleteRecursively()
        }
    }

    @Test
    fun removedOrRotatedSessionsCannotStartANewProducer(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { captureAndroidExternalFileHandoffGeneration(session) { null } }
        assertFailsWith<IllegalStateException> {
            captureAndroidExternalFileHandoffGeneration(session) { session.copy(appPassword = "replacement") }
        }
    }
}
