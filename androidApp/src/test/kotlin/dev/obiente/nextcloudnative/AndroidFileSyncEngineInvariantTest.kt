package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

class AndroidFileSyncEngineInvariantTest {
    @Test
    fun pairRemovalDoesNotPersistOrCancelWhenLedgerCleanupFails() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeConfiguredFileSyncPair(
                cleanLedger = {
                    events += "clean"
                    error("ledger unavailable")
                },
                persistRemoval = { events += "persist" },
                cancelSchedule = { events += "cancel" },
            )
        }

        assertEquals(listOf("clean"), events)
    }

    @Test
    fun pairRemovalPersistsBeforeCancellingItsSchedule() = runBlocking {
        val events = mutableListOf<String>()

        removeConfiguredFileSyncPair(
            cleanLedger = { events += "clean" },
            persistRemoval = { events += "persist" },
            cancelSchedule = { events += "cancel" },
        )

        assertEquals(listOf("clean", "persist", "cancel"), events)
    }

    @Test
    fun reconciliationSkipsAnActiveSyncWithoutBlockingTransferHistory() = runBlocking {
        val lock = Mutex(locked = true)
        var reconciled = false
        val completed = runWhenFileSyncIdle(lock) {
            reconciled = true
        }

        assertFalse(completed)
        assertFalse(reconciled)
        lock.unlock()
    }

    @Test
    fun reconciliationRunsWhenFileSyncIsIdle() = runBlocking {
        val lock = Mutex()
        var reconciled = false
        val completed = runWhenFileSyncIdle(lock) {
            reconciled = true
        }

        assertTrue(completed)
        assertTrue(reconciled)
    }

    @Test
    fun idleGateReleasesTheEngineLockWhenItsActionFails() = runBlocking {
        val lock = Mutex()

        assertFailsWith<IllegalStateException> {
            runWhenFileSyncIdle(lock) {
                error("synthetic reconciliation failure")
            }
        }

        assertFalse(lock.isLocked)
    }

    @Test
    fun staleSnapshotReadDuringRemovalNeverReschedulesTheRemovedPair() {
        val lock = Mutex(locked = true)
        var persistedPairIds = listOf("pair-1")
        val schedulingSnapshots = mutableListOf<List<String>>()

        val snapshotWhileRemovalOwnsLock = loadFileSyncPresentationSnapshot(
            lock = lock,
            load = { persistedPairIds.toList() },
            scheduleWhenIdle = { schedulingSnapshots += it.toList() },
        )

        assertEquals(listOf("pair-1"), snapshotWhileRemovalOwnsLock)
        assertTrue(schedulingSnapshots.isEmpty())

        persistedPairIds = emptyList()
        lock.unlock()
        val snapshotAfterRemoval = loadFileSyncPresentationSnapshot(
            lock = lock,
            load = { persistedPairIds.toList() },
            scheduleWhenIdle = { schedulingSnapshots += it.toList() },
        )

        assertTrue(snapshotAfterRemoval.isEmpty())
        assertEquals(listOf(emptyList()), schedulingSnapshots)
    }
}
