package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidFileSyncPresentationTest {
    @Test fun passiveReadsPreserveLiveWorkAndRecoverOnlyAbandonedWork() {
        val directory = Files.createTempDirectory("sync-live-presentation-").toFile()
        try {
            val stateFile = File(directory, "state.bin")
            val store = AndroidFileSyncStore(stateFile)
            val pair = FileSyncPair("pair", "account", "synthetic-root", "Documents", FileSyncConfiguration(deviceLabel = "Device"))
            val scanned = scanFileSyncPair(FileSyncCoordinatorState(listOf(pair)), pair.id,
                listOf(LocalSyncEntry("note.txt", SyncEntryKind.File, "local-1")), emptyList(), 10)
            val running = claimNextFileSyncOperation(scanned, pair.id, 20).state
            store.save(AndroidFileSyncPersistedState(running, mapOf(pair.id to "Documents")))
            val bytes = stateFile.readBytes()
            assertEquals(FileSyncExecutionState.Running, store.loadForPresentation(setOf(pair.id)).coordinator.pairs.single().workItems.single().state)
            assertEquals(FileSyncExecutionState.Failed, store.loadForPresentation(emptySet()).coordinator.pairs.single().workItems.single().state)
            assertEquals(FileSyncExecutionState.Failed, store.load().coordinator.pairs.single().workItems.single().state)
            assertTrue(bytes.contentEquals(stateFile.readBytes()), "Status reads must not rewrite the durable queue")
        } finally { directory.deleteRecursively() }
    }
}
