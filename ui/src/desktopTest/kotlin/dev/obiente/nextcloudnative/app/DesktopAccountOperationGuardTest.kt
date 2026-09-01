package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class DesktopAccountOperationGuardTest {
    @Test
    fun removalCannotPassAConcurrentSelection() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val selectionStarted = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val selection = async {
            guard.serialize {
                events += "selection-started"
                selectionStarted.complete(Unit)
                releaseSelection.await()
                events += "selection-finished"
            }
        }
        selectionStarted.await()

        val removal = async {
            guard.serialize { events += "removal" }
        }
        yield()

        assertFalse(removal.isCompleted)
        releaseSelection.complete(Unit)
        selection.await()
        removal.await()
        assertEquals(listOf("selection-started", "selection-finished", "removal"), events)
    }

    @Test
    fun accountMutationWaitsForAnIndependentSyncRun() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val releaseSync = CompletableDeferred<Unit>()
        val syncStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val sync = async {
            guard.withSyncRunLock {
                syncStarted.complete(Unit)
                releaseSync.await()
            }
        }
        syncStarted.await()
        val mutation = async {
            guard.serializeWhenSyncIdle {
                events += "account-mutated"
            }
        }
        yield()

        assertFalse(mutation.isCompleted)
        assertEquals(emptyList(), events)
        releaseSync.complete(Unit)
        sync.await()
        mutation.await()
        assertEquals(listOf("account-mutated"), events)
    }
}
