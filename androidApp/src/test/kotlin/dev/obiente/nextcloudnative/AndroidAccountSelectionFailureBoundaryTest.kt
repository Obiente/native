package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AndroidAccountSelectionFailureBoundaryTest {
    @Test fun observerAndDiagnosticFailuresDoNotSkipRequiredActivation(): Unit = runBlocking {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession({ "old" }, { it })
        val oldToken = requireNotNull(guard.capture("old"))
        val events = mutableListOf<String>()
        val observerFailure = IllegalStateException("observer unavailable")
        completeAndroidAccountSelectionTransition(
            transitionDispatcher = Dispatchers.Unconfined,
            commitTransition = { markCommitted ->
                guard.replaceSession("new", persist = { events += "persist"; markCommitted() },
                    cancelAll = { events += "cancel-old" },
                    publishAccount = { throw observerFailure },
                    restoreSchedules = { events += "restore-new" },
                    onScheduleMaintenanceFailure = {
                        assertSame(observerFailure, it)
                        throw IllegalArgumentException("diagnostics unavailable")
                    })
            },
            finishMaintenance = { events += "activate" },
        )
        assertEquals(listOf("persist", "cancel-old", "restore-new", "activate"), events)
        assertFalse(guard.runIfCurrent(oldToken) { error("Old work must be fenced") })
        assertTrue(guard.capture("new") != null)
    }

    @Test fun publicationCancellationSurvivesFailedSchedulingAndDiagnostics(): Unit = runBlocking {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        val cancellation = CancellationException("selection cancelled")
        val events = mutableListOf<String>()
        val caught = assertFailsWith<CancellationException> {
            completeAndroidAccountSelectionTransition(
                transitionDispatcher = Dispatchers.Unconfined,
                commitTransition = { markCommitted ->
                    guard.replaceSession("new", persist = { markCommitted() },
                        publishAccount = { throw cancellation },
                        cancelAll = { events += "cancel-old"; throw IllegalStateException("scheduler unavailable") },
                        restoreSchedules = { events += "restore-new"; throw IllegalStateException("restore unavailable") },
                        onScheduleMaintenanceFailure = { throw CancellationException("diagnostic observer cancelled") })
                },
                finishMaintenance = { events += "activate" },
            )
        }
        assertSame(cancellation, caught)
        assertEquals(listOf("cancel-old", "restore-new", "activate"), events)
        assertTrue(guard.capture("new") != null)
    }

    @Test fun precommitFailurePreservesOldSchedulingAndSkipsMaintenance(): Unit = runBlocking {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession({ "old" }, { it })
        val oldToken = requireNotNull(guard.capture("old"))
        val failure = IllegalStateException("commit rejected")
        val caught = assertFailsWith<IllegalStateException> {
            completeAndroidAccountSelectionTransition(
                transitionDispatcher = Dispatchers.Unconfined,
                commitTransition = { markCommitted ->
                    guard.replaceSession("new", persist = { throw failure },
                        cancelAll = { error("No cancellation before commit") },
                        publishAccount = { error("No publication before commit") },
                        restoreSchedules = { error("No restoration before commit") })
                    markCommitted()
                },
                finishMaintenance = { error("No activation before commit") },
            )
        }
        assertSame(failure, caught)
        assertTrue(guard.runIfCurrent(oldToken) {})
    }

    @Test fun committedCancellationRetainsFailedRequiredActivationAsSuppressed(): Unit = runBlocking {
        val cancellation = CancellationException("original cancellation")
        val activationFailure = IllegalStateException("activation unavailable")
        val caught = assertFailsWith<CancellationException> {
            completeAndroidAccountSelectionTransition(Dispatchers.Unconfined,
                commitTransition = { it(); throw cancellation },
                finishMaintenance = { throw activationFailure })
        }
        assertSame(cancellation, caught)
        assertEquals(1, caught.suppressed.size)
        assertCauseIdentity(caught.suppressed.single(), activationFailure)
    }

    @Test fun committedCancellationRetainsSecondaryActivationCancellation(): Unit = runBlocking {
        val original = CancellationException("original cancellation")
        val activation = CancellationException("activation cancelled")
        val caught = assertFailsWith<CancellationException> {
            completeAndroidAccountSelectionTransition(Dispatchers.Unconfined,
                commitTransition = { it(); throw original }, finishMaintenance = { throw activation })
        }
        assertSame(original, caught)
        assertEquals(1, caught.suppressed.size)
        assertCauseIdentity(caught.suppressed.single(), activation)
    }

    @Test fun requiredActivationFailureWithoutCancellationStillFails(): Unit = runBlocking {
        val failure = IllegalStateException("activation unavailable")
        val caught = assertFailsWith<IllegalStateException> {
            completeAndroidAccountSelectionTransition(Dispatchers.Unconfined,
                commitTransition = { it() }, finishMaintenance = { throw failure })
        }
        assertCauseIdentity(caught, failure)
    }

    @Test fun sameCancellationFromActivationDoesNotSuppressItself(): Unit = runBlocking {
        val cancellation = CancellationException("same cancellation")
        val caught = assertFailsWith<CancellationException> {
            completeAndroidAccountSelectionTransition(Dispatchers.Unconfined,
                commitTransition = { it(); throw cancellation }, finishMaintenance = { throw cancellation })
        }
        assertSame(cancellation, caught)
        assertTrue(caught.suppressed.isEmpty())
    }

    @Test fun handoffCancellationStillRunsRequiredSelectionMaintenance(): Unit = runBlocking {
        val cancellation = CancellationException("handoff cancelled")
        var activated = false
        val caught = assertFailsWith<CancellationException> {
            completeAndroidAccountSelectionTransition(Dispatchers.Unconfined,
                commitTransition = { mark ->
                    commitAndroidAccountTransitionBeforeHandoffCleanup(mark, { throw cancellation }, {})
                }, finishMaintenance = { activated = true })
        }
        assertSame(cancellation, caught)
        assertTrue(activated)
    }

    @Test fun previewCancellationStillResumesWorkAndPreservesFirstCancellation(): Unit = runBlocking {
        val first = CancellationException("preview cancelled")
        val later = CancellationException("resume cancelled")
        var resumed = false
        val caught = assertFailsWith<CancellationException> {
            finishAndroidAccountSelectionMaintenance({ throw first }, { resumed = true; throw later })
        }
        assertSame(first, caught)
        assertEquals(listOf(later), caught.suppressed.toList())
        assertTrue(resumed)
    }

    @Test fun previewDiagnosticCancellationStillResumesWork(): Unit = runBlocking {
        val previous = dev.obiente.nextcloudnative.app.NextcloudSession("https://cloud.example.test", "before", "synthetic")
        val selected = previous.copy(loginName = "after")
        val cancellation = CancellationException("diagnostic cancelled")
        var resumed = false
        val caught = assertFailsWith<CancellationException> {
            finishAndroidAccountSelectionMaintenance({
                clearAndroidPreviousPreviewAfterCommittedSelection(previous, selected,
                    { throw IllegalStateException("preview unavailable") }, { throw cancellation })
            }, { resumed = true })
        }
        assertSame(cancellation, caught)
        assertTrue(resumed)
    }

    @Test fun schedulingDiagnosticCancellationWaitsForActivation(): Unit = runBlocking {
        val cancellation = CancellationException("diagnostic cancelled")
        val events = mutableListOf<String>()
        val caught = assertFailsWith<CancellationException> {
            completeAndroidAccountSelectionTransition(Dispatchers.Unconfined,
                commitTransition = { mark -> AndroidFileSyncSessionSchedulingGuard().replaceSession("new", mark,
                    cancelAll = { events += "cancel" },
                    publishAccount = { throw IllegalStateException("observer unavailable") },
                    restoreSchedules = { events += "restore" },
                    onScheduleMaintenanceFailure = { throw cancellation }) },
                finishMaintenance = { events += "activate" })
        }
        assertSame(cancellation, caught)
        assertEquals(listOf("cancel", "restore", "activate"), events)
    }

    @Test fun committedClearRunsAllMaintenanceAndPreservesFirstCancellation() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession({ "old" }, { it })
        val first = CancellationException("publication cancelled")
        val later = CancellationException("work cancellation interrupted")
        var cancelledWork = false
        val caught = assertFailsWith<CancellationException> {
            guard.clearSession(persist = {},
                cancelAll = { cancelledWork = true; throw later },
                clearPublishedAccount = { throw first })
        }
        assertSame(first, caught)
        assertEquals(listOf(later), caught.suppressed.toList())
        assertTrue(cancelledWork)
        assertEquals(null, guard.capture("old"))
    }

    @Test fun copiedCancellationFromPreviewMaintenanceDoesNotCreateSuppressedCycle(): Unit = runBlocking {
        val original = CancellationException("original")
        val copied = CancellationException("copied").also { it.initCause(original) }
        val caught = assertFailsWith<CancellationException> {
            finishAndroidAccountSelectionMaintenance({ throw original }, { throw copied })
        }
        assertSame(original, caught)
        assertTrue(original.suppressed.isEmpty())
    }

    @Test fun distinctMaintenanceFailureRetainsItsCauseChain(): Unit = runBlocking {
        val original = CancellationException("original")
        val cause = IllegalArgumentException("distinct cause")
        val failure = IllegalStateException("distinct failure", cause)
        val caught = assertFailsWith<CancellationException> {
            finishAndroidAccountSelectionMaintenance({ throw original }, { throw failure })
        }
        assertSame(original, caught)
        assertSame(failure, caught.suppressed.single())
        assertSame(cause, caught.suppressed.single().cause)
    }

    @Test fun unrelatedCyclicFailureChainCannotHangCancellationPreservation(): Unit = runBlocking {
        val original = CancellationException("original")
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second", first)
        first.initCause(second)
        val caught = assertFailsWith<CancellationException> {
            finishAndroidAccountSelectionMaintenance({ throw original }, { throw first })
        }
        assertSame(original, caught)
        assertTrue(caught.suppressed.isEmpty())
    }

    @Test fun overlongFailureChainDoesNotAttachAnUnknownCycle(): Unit = runBlocking {
        val original = CancellationException("original")
        var failure: Exception = original
        repeat(40) { failure = IllegalStateException("bounded synthetic wrapper", failure) }
        val caught = assertFailsWith<CancellationException> {
            finishAndroidAccountSelectionMaintenance({ throw original }, { throw failure })
        }
        assertSame(original, caught)
        assertTrue(caught.suppressed.isEmpty())
    }

    @Test fun schedulingCancellationCopyDoesNotCreateSuppressedCycle() {
        val original = CancellationException("original")
        val copied = CancellationException("copied").also { it.initCause(original) }
        var restored = false
        val caught = assertFailsWith<CancellationException> {
            AndroidFileSyncSessionSchedulingGuard().replaceSession("synthetic-account", persist = {},
                publishAccount = { throw original }, cancelAll = { throw copied },
                restoreSchedules = { restored = true })
        }
        assertSame(original, caught)
        assertTrue(restored)
        assertTrue(original.suppressed.isEmpty())
    }

    private fun assertCauseIdentity(actual: Throwable, expected: Throwable) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = actual
        repeat(32) {
            val cause = current ?: return@repeat
            if (cause === expected) return
            if (!seen.add(cause)) return@repeat
            current = cause.cause
        }
        error("The recovered exception did not retain the expected cause identity")
    }
}
