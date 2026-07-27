package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    fun reconciliationWaitsForAnActiveSyncInsteadOfBeingDropped() = runBlocking {
        val lock = Mutex(locked = true)
        val started = CompletableDeferred<Unit>()
        var reconciled = false
        val reconciliation = async {
            started.complete(Unit)
            reconcileWhenFileSyncIdle(lock) {
                reconciled = true
            }
        }

        started.await()
        assertFalse(reconciliation.isCompleted)
        assertFalse(reconciled)

        lock.unlock()
        reconciliation.await()

        assertTrue(reconciled)
    }
}
