package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DesktopAccountOperationGuardTest {
    @Test
    fun abortedAccountSelectionAlwaysRestartsDesktopSync() = runBlocking {
        var restartCount = 0

        assertFailsWith<CancellationException> {
            restartDesktopSyncAfterSelection<String>(
                select = { throw CancellationException("selection cancelled") },
                restart = { restartCount += 1 },
            )
        }
        assertFailsWith<IllegalStateException> {
            restartDesktopSyncAfterSelection<String>(
                select = { error("credential persistence failed") },
                restart = { restartCount += 1 },
            )
        }

        assertEquals(2, restartCount)
    }

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
    fun synchronousRangeRegistrationCannotEnterDuringAccountMutation() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = async {
            guard.serialize {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()

        assertFalse(guard.tryActivateResource { true })

        releaseMutation.complete(Unit)
        mutation.await()
        assertTrue(guard.tryActivateResource { true })
    }

    @Test
    fun accountMutationObservesAResourceRegisteredJustBeforeItStarts() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val mutationEntered = CompletableDeferred<Unit>()
        val registration = thread {
            assertTrue(
                guard.tryActivateResource {
                    registrationEntered.countDown()
                    check(releaseRegistration.await(5, TimeUnit.SECONDS))
                    true
                },
            )
        }
        check(registrationEntered.await(5, TimeUnit.SECONDS))

        val mutation = async(Dispatchers.Default) {
            guard.serialize { mutationEntered.complete(Unit) }
        }
        yield()
        assertFalse(mutationEntered.isCompleted)

        releaseRegistration.countDown()
        registration.join()
        mutation.await()
        assertTrue(mutationEntered.isCompleted)
    }

    @Test
    fun resourceActivationRejectsAStaleAccountAfterWaitingForTheGuard() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")
        val guard = DesktopAccountOperationGuard()
        var hydrationRegistered = false

        assertTrue(desktopResourceActivationMatchesActiveSession(first, first.copy()))
        assertFalse(desktopResourceActivationMatchesActiveSession(second, first))
        assertFalse(desktopResourceActivationMatchesActiveSession(null, first))
        assertFalse(
            desktopResourceActivationMatchesActiveSession(
                first.copy(appPassword = "rotated"),
                first,
            ),
        )
        assertFalse(
            guard.tryActivateResource {
                desktopResourceActivationMatchesActiveSession(second, first) &&
                    true.also { hydrationRegistered = true }
            },
        )
        assertFalse(hydrationRegistered)
    }

    @Test
    fun resourceDeactivationRejectsAStaleAccountAndAnotherAccountsProvider() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")
        val firstIdentity = desktopFileCacheAccountId(first)
        val secondIdentity = desktopFileCacheAccountId(second)

        assertTrue(desktopResourceDeactivationTargetsCurrentProvider(first, first.copy(), firstIdentity))
        assertFalse(desktopResourceDeactivationTargetsCurrentProvider(second, first, secondIdentity))
        assertFalse(desktopResourceDeactivationTargetsCurrentProvider(first, first, secondIdentity))
        assertFalse(desktopResourceDeactivationTargetsCurrentProvider(null, first, firstIdentity))
    }

    @Test
    fun syncRunRejectsAStaleAccountAfterWaitingForSelection() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")

        assertTrue(desktopSyncRunMatchesActiveSession(first, first.copy()))
        assertFalse(desktopSyncRunMatchesActiveSession(second, first))
        assertFalse(desktopSyncRunMatchesActiveSession(activeSession = null, first))
        assertFalse(
            desktopSyncRunMatchesActiveSession(
                first.copy(appPassword = "rotated"),
                first,
            ),
        )
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
    fun activeCredentialReplacementRequiresLiveResourcesToClose() {
        val original = NextcloudSession("https://first.example.test", "alice", "one")

        assertFalse(desktopSessionSaveReplacesActiveCredential(activeSession = null, savedSession = original))
        assertFalse(desktopSessionSaveReplacesActiveCredential(original, original.copy()))
        assertTrue(
            desktopSessionSaveReplacesActiveCredential(
                original,
                original.copy(appPassword = "replacement-password"),
            ),
        )
        assertFalse(
            desktopSessionSaveReplacesActiveCredential(
                original,
                NextcloudSession("https://second.example.test", "alice", "replacement-password"),
            ),
        )
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

    @Test
    fun pairRemovalWaitsForTheSelectionSyncBoundary() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val selectionEntered = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()
        var removalEntered = false
        val selection = async {
            guard.serialize {
                guard.withSyncRunLock {
                    selectionEntered.complete(Unit)
                    releaseSelection.await()
                }
            }
        }
        selectionEntered.await()
        val removal = async {
            guard.withSyncRunLock { removalEntered = true }
        }
        yield()

        assertFalse(removalEntered)
        releaseSelection.complete(Unit)
        selection.await()
        removal.await()
        assertTrue(removalEntered)
    }
}
