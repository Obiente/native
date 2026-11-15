package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncRunFailureTest {
    @Test fun failureRoundTripDoesNotPretendTheScanSucceeded() {
        val failure = FileSyncRunFailure(FileSyncRunFailureKind.ServerUnavailable, 200)
        val pair = pair().copy(runFailure = failure)
        val restored = decodeFileSyncCoordinatorSnapshot(encodeFileSyncCoordinatorSnapshot(FileSyncCoordinatorState(listOf(pair)))).pairs.single()
        assertEquals(failure, restored.runFailure)
        assertNull(restored.lastScanEpochMillis)
        val summary = restored.toCenterSummary("Documents", runState = FileSyncPairRunState.Active, networkState = FileSyncNetworkState.Available)
        assertEquals(0, summary.failedCount, "A failed scan is separate from failed file transfers")
        assertEquals(FileSyncObservedState.Attention, summary.observedSyncState())
        assertEquals(listOf(summary), filterFileSyncPairs(listOf(summary), FileSyncListFilter.Attention, ""))
        assertEquals("1 sync needs review", fileSyncWorkspaceSummary(listOf(summary)))
        assertTrue(summary.syncWorkSummary().contains("Check failed"))
    }

    @Test fun successfulFreshScanClearsOnlyTheRunFailure() {
        val initial = FileSyncCoordinatorState(listOf(pair().copy(runFailure = FileSyncRunFailure(FileSyncRunFailureKind.Network, 10))))
        val scanned = scanFileSyncPair(initial, "pair", emptyList(), emptyList(), 20).pairs.single()
        assertNull(scanned.runFailure)
        assertEquals(20L, scanned.lastScanEpochMillis)
    }

    @Test fun oldSnapshotWithoutRunFailureRemainsReadable() {
        val encoded = encodeFileSyncCoordinatorSnapshot(FileSyncCoordinatorState(listOf(pair()))).decodeToString()
        val old = encoded.replace(",\"runFailure\":null", "")
        assertNull(decodeFileSyncCoordinatorSnapshot(old.encodeToByteArray()).pairs.single().runFailure)
    }

    @Test fun malformedFailureAndUnsupportedKindAreRejected() {
        assertFails { FileSyncRunFailure(FileSyncRunFailureKind.Unknown, -1) }
        val state = FileSyncCoordinatorState(listOf(pair().copy(runFailure = FileSyncRunFailure(FileSyncRunFailureKind.Network, 10))))
        val encoded = encodeFileSyncCoordinatorSnapshot(state).decodeToString()
        assertFails { decodeFileSyncCoordinatorSnapshot(encoded.replace("\"Network\"", "\"FutureUnknownKind\"").encodeToByteArray()) }
        assertFails { decodeFileSyncCoordinatorSnapshot(encoded.replace("\"occurredAtEpochMillis\":10", "\"occurredAtEpochMillis\":-1").encodeToByteArray()) }
    }

    @Test fun liveSnapshotPreservesRunningButRestartRequiresReconciliation() {
        val initial = FileSyncCoordinatorState(listOf(pair()))
        val planned = scanFileSyncPair(initial, "pair", listOf(LocalSyncEntry("note.txt", SyncEntryKind.File, "local-1")), emptyList(), 10)
        val running = claimNextFileSyncOperation(planned, "pair", 20).state
        val bytes = encodeFileSyncCoordinatorSnapshot(running)
        assertEquals(FileSyncExecutionState.Running, decodeLiveFileSyncCoordinatorSnapshot(bytes).pairs.single().workItems.single().state)
        assertEquals(FileSyncExecutionState.Failed, decodeFileSyncCoordinatorSnapshot(bytes).pairs.single().workItems.single().state)
    }

    private fun pair() = FileSyncPair("pair", "account", "synthetic-root", "Documents", FileSyncConfiguration(deviceLabel = "Device"))
}
