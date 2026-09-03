package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class DesktopAccountOperationGuardTest {
    @Test
    fun resourceActivationCannotPassAConcurrentAccountMutation() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        var resourceActivated = false

        val mutation = async {
            guard.serialize {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()
        val activation = async {
            guard.serializeResourceActivation { resourceActivated = true }
        }
        yield()

        assertFalse(resourceActivated)
        releaseMutation.complete(Unit)
        mutation.await()
        activation.await()
        assertTrue(resourceActivated)
    }

    @Test
    fun resourceActivationRejectsAStaleAccountAfterWaitingForTheGuard() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")

        assertTrue(desktopResourceActivationMatchesActiveAccount(first.accountId, first.accountId))
        assertFalse(desktopResourceActivationMatchesActiveAccount(second.accountId, first.accountId))
        assertFalse(desktopResourceActivationMatchesActiveAccount(null, first.accountId))
    }

    @Test
    fun differentAccountSaveRequiresTheSelectionTransition() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")

        assertFalse(desktopSessionSaveSwitchesAccount(null, first.accountId))
        assertFalse(desktopSessionSaveSwitchesAccount(first.accountId, first.accountId))
        assertTrue(desktopSessionSaveSwitchesAccount(first.accountId, second.accountId))
    }

    @Test
    fun blockedAccountSaveRecordsTheSelectionDiagnosticBeforeFailing() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        assertFailsWith<IllegalStateException> {
            requireDesktopSessionSaveAllowed(allowed = false, recordBlocked = diagnostics::add)
        }

        assertEquals(listOf("ACCOUNT_SELECTION_ACTIVE_RESOURCES"), diagnostics.map { it.code })
    }

    @Test
    fun retainedSelectionReopensTheDesktopSessionOnlyAfterSuccess() {
        var reopenCount = 0
        val session = NextcloudSession("https://first.example.test", "alice", "one")

        assertNull(reopenDesktopSessionAfterSelection<String>(null) { reopenCount += 1 })
        assertEquals(session, reopenDesktopSessionAfterSelection(session) { reopenCount += 1 })
        assertEquals(1, reopenCount)
    }
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
