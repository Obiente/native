package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncNetworkPolicy
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncPendingUploadCleanup
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.encodeFileSyncCoordinatorSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidFileSyncSettingsTest {
    @Test fun settingsArePersistedBeforeSchedulingAndSurviveRestart() = runBlocking<Unit> {
        withStore { store ->
            val original = seed(store)
            val configuration = original.configuration.copy(networkPolicy = FileSyncNetworkPolicy.Unmetered)
            var scheduled = false
            val result = persistAndroidFileSyncSettings(store, "account", "pair", original.configuration, configuration,
                reschedule = { assertEquals(configuration, store.load().coordinator.pairs.single().configuration); scheduled = true },
                recoverSchedule = { error("Recovery is unnecessary") })
            assertIs<FileSyncCenterActionResult.Completed>(result)
            assertTrue(scheduled)
            val restored = store.load().coordinator.pairs.single()
            assertEquals(original.baselines, restored.baselines)
            assertEquals(original.localRootId, restored.localRootId)
            assertEquals(original.remoteRootPath, restored.remoteRootPath)
            assertNull(restored.lastScanEpochMillis)
            assertEquals("Documents", store.load().localDisplayNames["pair"])
        }
    }

    @Test fun staleReviewAndWrongAccountAreRejectedWithoutWrites() = runBlocking<Unit> {
        withStore { store ->
            val pair = seed(store)
            val before = store.load()
            for ((account, expected) in listOf("other" to pair.configuration, "account" to pair.configuration.copy(deviceLabel = "Older device"))) {
                assertIs<FileSyncCenterActionResult.Rejected>(persistAndroidFileSyncSettings(store, account, pair.id,
                    expected, pair.configuration.copy(networkPolicy = FileSyncNetworkPolicy.Unmetered),
                    reschedule = { error("Rejected change must not schedule") }, recoverSchedule = { error("No recovery") }))
                assertEquals(before, store.load())
            }
        }
    }

    @Test fun pendingUploadOwnershipCannotBeDiscardedByEditing() = runBlocking<Unit> {
        withStore { store ->
            val pair = seed(store).copy(pendingUploadCleanups = listOf(FileSyncPendingUploadCleanup(
                "01234567-89ab-cdef-0123-456789abcdef", "upload.txt",
            )))
            store.save(AndroidFileSyncPersistedState(FileSyncCoordinatorState(listOf(pair))))
            val before = store.load()
            assertIs<FileSyncCenterActionResult.Rejected>(persistAndroidFileSyncSettings(store, "account", pair.id,
                pair.configuration, pair.configuration.copy(direction = FileSyncDirection.DownloadOnly),
                reschedule = { error("Recovery must finish first") }, recoverSchedule = {}))
            assertEquals(before, store.load())
        }
    }

    @Test fun detectedMediaRootsCannotEnableWritesToTheDevice() = runBlocking<Unit> {
        withStore { store ->
            val pair = seed(store).copy(localRootId = MEDIA_STORE_SYNC_ROOT_PREFIX + "Pictures",
                configuration = FileSyncConfiguration(deviceLabel = "Device", direction = FileSyncDirection.UploadOnly))
            store.save(AndroidFileSyncPersistedState(FileSyncCoordinatorState(listOf(pair))))
            assertIs<FileSyncCenterActionResult.Rejected>(persistAndroidFileSyncSettings(store, "account", pair.id,
                pair.configuration, pair.configuration.copy(direction = FileSyncDirection.Bidirectional),
                reschedule = { error("Invalid direction") }, recoverSchedule = {}))
            assertEquals(pair.configuration, store.load().coordinator.pairs.single().configuration)
        }
    }

    @Test fun failedSchedulingKeepsCommittedSettingsAndQueuesRecovery() = runBlocking<Unit> {
        withStore { store ->
            val pair = seed(store)
            val changed = pair.configuration.copy(deviceLabel = "Updated device")
            var recovered = false
            val result = persistAndroidFileSyncSettings(store, "account", pair.id, pair.configuration, changed,
                reschedule = { throw IOException("Synthetic scheduler failure") }, recoverSchedule = { recovered = true })
            assertIs<FileSyncCenterActionResult.Completed>(result)
            assertTrue(recovered)
            assertEquals(changed, store.load().coordinator.pairs.single().configuration)
        }
    }

    @Test fun cancellationAfterCommitStillFinishesScheduleUpdate() = runBlocking<Unit> {
        withStore { store ->
            val pair = seed(store)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var scheduled = false
            val changed = pair.configuration.copy(deviceLabel = "Updated device")
            val saving = launch {
                persistAndroidFileSyncSettings(store, "account", pair.id, pair.configuration, changed,
                    reschedule = { entered.complete(Unit); release.await(); scheduled = true }, recoverSchedule = {})
            }
            entered.await()
            saving.cancel()
            release.complete(Unit)
            saving.join()
            assertTrue(scheduled)
            assertEquals(changed, store.load().coordinator.pairs.single().configuration)
        }
    }

    @Test fun diskPublicationFailureLeavesOldSettingsAndDoesNotSchedule() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("sync-settings-bound-").toFile()
        try {
            val pair = pair()
            val initial = AndroidFileSyncPersistedState(FileSyncCoordinatorState(listOf(pair)))
            val store = AndroidFileSyncStore(File(directory, "state.bin"), encodeFileSyncCoordinatorSnapshot(initial.coordinator).size)
            store.save(initial)
            var scheduled = false
            assertFailsWith<IllegalStateException> {
                persistAndroidFileSyncSettings(store, "account", pair.id, pair.configuration,
                    pair.configuration.copy(deviceLabel = "x".repeat(128)),
                    reschedule = { scheduled = true }, recoverSchedule = {})
            }
            assertFalse(scheduled)
            assertEquals(initial, store.load())
        } finally { directory.deleteRecursively() }
    }

    private fun pair() = FileSyncPair("pair", "account", "synthetic-root", "Documents", FileSyncConfiguration(deviceLabel = "Device"),
        baselines = listOf(FileSyncBaseline("existing.txt", SyncEntryKind.File, "local-revision", "remote-etag")), lastScanEpochMillis = 100)
    private fun seed(store: AndroidFileSyncStore): FileSyncPair = pair().also {
        store.save(AndroidFileSyncPersistedState(FileSyncCoordinatorState(listOf(it)), mapOf("pair" to "Documents")))
    }
    private inline fun withStore(block: (AndroidFileSyncStore) -> Unit) {
        val directory = Files.createTempDirectory("sync-settings-").toFile()
        try { block(AndroidFileSyncStore(File(directory, "state.bin"))) } finally { directory.deleteRecursively() }
    }
}
