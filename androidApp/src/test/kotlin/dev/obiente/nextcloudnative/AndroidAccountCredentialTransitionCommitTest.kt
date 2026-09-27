package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AndroidAccountCredentialTransitionCommitTest {
    @Test
    fun rootsNotificationFailureKeepsAnActiveCredentialRemovalCommitted() = runBlocking {
        val events = mutableListOf<String>()

        removeAndroidAccountCredentialData(
            active = true,
            removeQueuedUploads = { events += "remove-uploads" },
            clearActiveAccount = {
                events += "commit-removal"
            },
            onActiveRemovalCommitted = {
                notifyAndroidDocumentRootsAfterCommittedTransition(
                    notify = { error("synthetic roots notification failure") },
                    recordFailure = { events += "diagnose-notification" },
                )
            },
            rollbackActiveRemoval = { events += "rollback-removal" },
            persistInactiveRemoval = {},
            rollbackInactiveRemoval = {},
        )

        assertEquals(listOf("commit-removal", "diagnose-notification", "remove-uploads"), events)
    }

    @Test
    fun successfulSelectionSurvivesNotificationFailure() = runBlocking {
        var resumed = false
        resumeAndroidQueuedUploadsAfterSelection(
            resume = { resumed = true },
            notifyDocumentRootsChanged = { error("synthetic observer failure") },
            recordFailure = { error("Unexpected upload diagnostic") },
        )
        assertEquals(true, resumed)
    }

    @Test
    fun failedResumeAndFailedDiagnosticStillNotifyWithoutFailingCommittedSelection() = runBlocking {
        val events = mutableListOf<String>()
        resumeAndroidQueuedUploadsAfterSelection(
            resume = { events += "resume"; error("synthetic resume failure") },
            notifyDocumentRootsChanged = { events += "notify" },
            recordFailure = { events += "diagnose"; error("synthetic diagnostic failure") },
        )
        assertEquals(listOf("resume", "diagnose", "notify"), events)
    }

    @Test
    fun originalResumeCancellationWinsOverObserverFailureOrCancellation() = runBlocking {
        for (observerFailure in listOf(IllegalStateException("observer"), CancellationException("observer"))) {
            val original = CancellationException("resume")
            assertSame(original, assertFailsWith<CancellationException> {
                resumeAndroidQueuedUploadsAfterSelection(
                    resume = { throw original },
                    notifyDocumentRootsChanged = { throw observerFailure },
                    recordFailure = { error("Cancellation must not be diagnosed as failure") },
                )
            })
        }
    }

    @Test
    fun callbackCancellationRemainsControlFlowAfterNotifying() = runBlocking {
        val cancelled = CancellationException("diagnostic")
        var notified = false
        assertSame(cancelled, assertFailsWith<CancellationException> {
            resumeAndroidQueuedUploadsAfterSelection(
                resume = { error("resume failure") },
                recordFailure = { throw cancelled },
                notifyDocumentRootsChanged = { notified = true; error("observer failure") },
            )
        })
        assertEquals(true, notified)
    }

    @Test
    fun removalObserverAndItsDiagnosticCannotUndoCommittedRemoval() = runBlocking {
        for (active in listOf(false, true)) {
            val events = mutableListOf<String>()
            fun notify() = notifyAndroidDocumentRootsAfterCommittedTransition(
                notify = { events += "notify"; error("observer failure") },
                recordFailure = { events += "diagnose"; error("diagnostic failure") },
            )
            removeAndroidAccountCredentialData(
                active = active,
                clearActiveAccount = { events += "commit" },
                onActiveRemovalCommitted = { notify() },
                persistInactiveRemoval = { events += "commit" },
                onInactiveRemovalCommitted = { notify() },
                rollbackActiveRemoval = { error("Committed removal must not roll back") },
                rollbackInactiveRemoval = { error("Committed removal must not roll back") },
                removeQueuedUploads = { events += "cleanup" },
                completeCommittedCleanup = { events += "complete" },
            )
            assertEquals(listOf("commit", "notify", "diagnose", "cleanup", "complete"), events)
        }
    }

    @Test
    fun cleanupDiagnosticFailureKeepsTheCommittedRemovalPending() = runBlocking {
        for (failCompletion in listOf(false, true)) {
            var cleanupCompleted = false
            removeAndroidAccountCredentialData(
                active = true,
                clearActiveAccount = {},
                persistInactiveRemoval = {},
                rollbackActiveRemoval = { error("Committed removal must not roll back") },
                rollbackInactiveRemoval = { error("Unused inactive rollback") },
                removeQueuedUploads = { if (!failCompletion) error("cleanup failure") },
                completeCommittedCleanup = { if (failCompletion) error("completion failure"); cleanupCompleted = true },
                recordCommittedCleanupFailure = { error("diagnostic failure") },
            )
            assertEquals(false, cleanupCompleted)
        }
    }


    @Test
    fun observerOrDiagnosticCancellationCannotRollBackActiveOrInactiveCommittedRemoval() = runBlocking {
        for (active in listOf(false, true)) for (diagnosticCancellation in listOf(false, true)) {
            var committed = false
            val cancelled = CancellationException("observer or diagnostic")
            fun notify() {
                if (diagnosticCancellation) error("notification failure") else throw cancelled
            }
            assertSame(cancelled, assertFailsWith<CancellationException> {
                removeAndroidAccountCredentialData(
                    active = active,
                    clearActiveAccount = { committed = true },
                    persistInactiveRemoval = { committed = true },
                    onActiveRemovalCommitted = ::notify,
                    onInactiveRemovalCommitted = ::notify,
                    rollbackActiveRemoval = { error("Committed removal must not roll back") },
                    rollbackInactiveRemoval = { error("Committed removal must not roll back") },
                    removeQueuedUploads = {},
                    recordCommittedCleanupFailure = { throw cancelled },
                )
            })
            assertEquals(true, committed)
        }
    }

    @Test
    fun committedCleanupCancellationIsNotDiagnosedOrConvertedToFailure() = runBlocking {
        for (duringCompletion in listOf(false, true)) {
            val cancelled = CancellationException("cleanup")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                removeAndroidAccountCredentialData(
                    active = true,
                    clearActiveAccount = {}, persistInactiveRemoval = {},
                    rollbackActiveRemoval = { error("Unexpected rollback") }, rollbackInactiveRemoval = {},
                    removeQueuedUploads = { if (!duringCompletion) throw cancelled },
                    completeCommittedCleanup = { throw cancelled },
                    recordCommittedCleanupFailure = { error("Cancellation is not a failure diagnostic") },
                )
            })
        }
    }

    @Test
    fun unavailableAndRecoveredRemovalNotifyOutsideRollbackBoundary() = runBlocking {
        for (recovered in listOf(false, true)) {
            var committed = false
            val cancelled = CancellationException("observer")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                if (recovered) {
                    removeRecoveredAndroidAccountCredentialData(
                        clearRecoveredAccount = { committed = true },
                        rollbackRecoveredAccount = { error("Committed recovery must not roll back") },
                        removeQueuedUploads = {}, onRemovalCommitted = { throw cancelled },
                    )
                } else {
                    removeUnavailableAndroidAccountCredentialData(
                        accountIdentity = "synthetic-account", active = true,
                        prepareAccountRemoval = {}, removeAccountOwnedWorkWithoutCredentials = {},
                        persistRemoval = { committed = true },
                        rollbackRemoval = { error("Committed recovery must not roll back") },
                        onActiveRemovalCommitted = { throw cancelled },
                    )
                }
            })
            assertEquals(true, committed)
        }
    }

}
