package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncPairConfigurationTest {
    @Test fun changedPolicyInvalidatesOldQueueButPreservesBaselinesAndIdentity() {
        val pair = FileSyncPair("pair", "account", "root", "Documents", FileSyncConfiguration(deviceLabel = "Device"))
        val planned = scanFileSyncPair(FileSyncCoordinatorState(listOf(pair)), pair.id,
            listOf(LocalSyncEntry("new.txt", SyncEntryKind.File, "local-1")), emptyList(), 10)
        assertTrue(planned.pairs.single().workItems.isNotEmpty())
        val changed = pair.configuration.copy(direction = FileSyncDirection.DownloadOnly)
        val updated = updateFileSyncPairConfiguration(planned, pair.id, changed).pairs.single()
        assertTrue(updated.workItems.isEmpty())
        assertNull(updated.lastScanEpochMillis)
        assertEquals(pair.accountId, updated.accountId)
        assertEquals(pair.localRootId, updated.localRootId)
        assertEquals(pair.remoteRootPath, updated.remoteRootPath)
        assertEquals(pair.baselines, updated.baselines)
    }

    @Test fun unchangedConfigurationDoesNotDiscardTheQueue() {
        val pair = FileSyncPair("pair", "account", "root", "Documents", FileSyncConfiguration(deviceLabel = "Device"))
        val planned = scanFileSyncPair(FileSyncCoordinatorState(listOf(pair)), pair.id,
            listOf(LocalSyncEntry("new.txt", SyncEntryKind.File, "local-1")), emptyList(), 10)
        assertEquals(planned, updateFileSyncPairConfiguration(planned, pair.id, pair.configuration))
    }

    @Test fun uploadOwnershipIsPreservedUntilRecoveryFinishes() {
        val pair = FileSyncPair("pair", "account", "root", "Documents", FileSyncConfiguration(deviceLabel = "Device"),
            pendingUploadCleanups = listOf(FileSyncPendingUploadCleanup("01234567-89ab-cdef-0123-456789abcdef", "new.txt")))
        assertFailsWith<IllegalArgumentException> {
            updateFileSyncPairConfiguration(FileSyncCoordinatorState(listOf(pair)), pair.id, pair.configuration.copy(deviceLabel = "New device"))
        }
    }
}
