package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSyncPresentationTest {
    @Test
    fun `queued work is not shown as completed or ready`() {
        val pair = pair().copy(readyCount = 3)
        assertEquals(FileSyncObservedState.Queued, pair.observedSyncState())
        assertEquals("3 queued", pair.syncWorkSummary())
        assertEquals("3 queued", fileSyncWorkspaceSummary(listOf(pair)))
    }

    @Test
    fun `unchecked and idle are different from successful synchronization`() {
        assertEquals(FileSyncObservedState.Unchecked, pair().observedSyncState())
        assertEquals(FileSyncObservedState.Idle, pair().copy(lastScanEpochMillis = 0).observedSyncState())
        assertEquals("Not checked yet", fileSyncCheckedTime(null))
        assertEquals("1970-01-01 00:00 UTC", fileSyncCheckedTime(0))
        assertEquals("2024-01-01 00:00 UTC", fileSyncCheckedTime(1_704_067_200_000))
    }

    @Test
    fun `invalid cached check timestamps do not crash or imply a successful check`() {
        assertEquals("Check time unavailable", fileSyncCheckedTime(-1))
        assertEquals("Check time unavailable", fileSyncCheckedTime(Long.MIN_VALUE))
        assertEquals("Not checked yet", fileSyncCheckedTime(null))
    }

    @Test
    fun `waiting reasons take precedence over runnable work without hiding counts`() {
        val paused = pair().copy(readyCount = 2, runState = FileSyncPairRunState.Paused)
        val offline = pair().copy(readyCount = 2, networkState = FileSyncNetworkState.WaitingForNetwork)
        assertEquals(FileSyncObservedState.Paused, paused.observedSyncState())
        assertEquals(FileSyncObservedState.Offline, offline.observedSyncState())
        assertEquals("2 queued", paused.syncWorkSummary())
        assertEquals("2 queued", offline.syncWorkSummary())
    }

    @Test
    fun `attention keeps concurrent work visible and is selected by default`() {
        val healthy = pair()
        val failure = pair().copy(id = "failure", failedCount = 1, runningCount = 2, readyCount = 3)
        assertEquals(FileSyncObservedState.Attention, failure.observedSyncState())
        assertEquals("2 active / 3 queued / 1 failed", failure.syncWorkSummary())
        assertEquals("1 sync needs review / 2 active / 3 queued", fileSyncWorkspaceSummary(listOf(failure)))
        assertEquals(failure, inspectedFileSyncPair(listOf(healthy, failure), null))
        assertEquals(healthy, inspectedFileSyncPair(listOf(healthy, failure), healthy.id))
    }

    @Test
    fun `selection distinguishes a parent containing selected descendants`() {
        val selected = listOf("RAW/Day 1")
        assertEquals(FileSyncPathSelection.Partial, fileSyncPathSelection("RAW", selected))
        assertEquals(FileSyncPathSelection.Explicit, fileSyncPathSelection("RAW/Day 1", selected))
        assertEquals(FileSyncPathSelection.Inherited, fileSyncPathSelection("RAW/Day 1/image.dng", selected))
        assertEquals(FileSyncPathSelection.None, fileSyncPathSelection("RAW2", selected))
        assertEquals(FileSyncPathSelection.None, fileSyncPathSelection("RAW/Day 10", selected))
        assertEquals(listOf("RAW/Day 1"), selected)
    }

    @Test
    fun `guidance describes preserved original and skip scope`() {
        assertTrue(FileSyncDecisionChoice.KeepBoth.decisionGuidance().contains("Nextcloud version stays at the original path"))
        assertTrue(FileSyncDecisionChoice.Skip.decisionGuidance().contains("either side changes"))
        assertTrue(FileSyncDecisionChoice.PropagateDeletion.decisionGuidance().contains("Permanently delete"))
    }

    private fun pair() = FileSyncPairSummary(
        id = "pair", localDisplayName = "Example", remoteRootPath = "Example",
        configuration = FileSyncConfiguration(deviceLabel = "Test device"),
        readyCount = 0, runningCount = 0, conflicts = emptyList(), failedCount = 0,
        skippedCount = 0, lastScanEpochMillis = null,
    )
}
