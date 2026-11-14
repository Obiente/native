package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeEditorWriteSessionTest {
    private val request = NativeActionRequest.Submit(
        ActionSpec("create", "Create", "rows", ApiBinding(HttpMethod.POST, "/api/rows", "create"),
            ActionIntent.create, ActionRisk.mutating, true, Confidence.high),
        mapOf("data" to "{}"),
        confirmed = true,
    )

    @Test
    fun successRefreshesAndRejectionKeepsTheDraftRetryable() = runBlocking {
        var refreshes = 0
        val session = NativeEditorWriteSession()
        assertTrue(session.submit({ NativeActionExecutionResult.Success() }, request, NativeEditorWriteRetry.NonIdempotent) { refreshes++ })
        assertEquals(1, refreshes)
        val rejected = session.submit({
            NativeActionExecutionResult.Failure("No permission.", NativeActionFailureOutcome.Rejected)
        }, request, NativeEditorWriteRetry.NonIdempotent) { refreshes++ }
        assertFalse(rejected)
        assertEquals(1, refreshes, "A rejected request changed nothing and needs no refresh.")
        assertIs<NativeEditorWriteStatus.Rejected>(session.status)
        assertTrue(session.canSubmit)
    }

    @Test
    fun unconfirmedCreateBlocksResubmissionUntilAnAuthoritativeRefresh() = runBlocking {
        var calls = 0
        var refreshes = 0
        val session = NativeEditorWriteSession()
        val unknown = NativeActionExecutor {
            calls++
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        assertFalse(session.submit(unknown, request, NativeEditorWriteRetry.NonIdempotent) { refreshes++ })
        assertEquals(1, refreshes)
        assertFalse(session.canSubmit)
        assertFalse(session.submit(unknown, request, NativeEditorWriteRetry.NonIdempotent) { refreshes++ })
        assertEquals(1, calls, "A blocked resubmission never reaches the executor.")
        session.acceptAuthoritativeRefresh()
        assertTrue(session.canSubmit)
    }

    @Test
    fun closingTheEditorKeepsAnUnconfirmedCreateBlocked() = runBlocking {
        var calls = 0
        val session = NativeEditorWriteSession()
        val unknown = NativeActionExecutor {
            calls++
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        session.submit(unknown, request, NativeEditorWriteRetry.NonIdempotent) {}
        session.reset()
        assertFalse(session.canSubmit, "Reopening the editor must not allow a second create.")
        assertFalse(session.submit(unknown, request, NativeEditorWriteRetry.NonIdempotent) {})
        assertEquals(1, calls)
        session.acceptAuthoritativeRefresh()
        session.reset()
        assertEquals(NativeEditorWriteStatus.Idle, session.status)
        assertTrue(session.canSubmit)
    }

    @Test
    fun unconfirmedIdempotentWriteMayBeRepeated() = runBlocking {
        val session = NativeEditorWriteSession()
        session.submit({ NativeActionExecutionResult.Failure("x", NativeActionFailureOutcome.Unknown) },
            request, NativeEditorWriteRetry.Idempotent) {}
        assertTrue(session.canSubmit)
    }

    @Test
    fun cancellationIsRethrownAndLeavesTheResultUnconfirmed() = runBlocking {
        val session = NativeEditorWriteSession()
        assertFailsWith<CancellationException> {
            session.submit({ throw CancellationException("left") }, request, NativeEditorWriteRetry.NonIdempotent) {}
        }
        val status = assertIs<NativeEditorWriteStatus.Unconfirmed>(session.status)
        assertTrue(status.awaitingRefresh)
    }

    @Test
    fun anExecutorFaultIsUnconfirmedNotUnsent() = runBlocking {
        val session = NativeEditorWriteSession()
        assertFalse(session.submit({ error("transport broke") }, request, NativeEditorWriteRetry.NonIdempotent) {})
        assertIs<NativeEditorWriteStatus.Unconfirmed>(session.status)
        assertFalse(session.canSubmit)
    }

    @Test
    fun driftKeepsTheEditorOpenAndRestoresTheBaselineOnlyWhenChosen() = runBlocking<Unit> {
        val session = NativeEditorWriteSession()
        val restore = request.copy(values = mapOf("restore" to "baseline"))
        val sent = mutableListOf<NativeActionRequest>()
        var refreshes = 0
        val drifted = NativeActionExecutionResult.Success(
            "Saved, but other values changed.",
            NativeWritePostcondition(adjusted = emptySet(), drifted = setOf("body"), restoreBaseline = restore),
        )
        val closed = session.submit({ sent += it; drifted }, request, NativeEditorWriteRetry.AfterRefresh) { refreshes++ }
        assertFalse(closed, "The editor stays open so the person can decide.")
        assertIs<NativeEditorWriteStatus.Drifted>(session.status)
        assertFalse(session.canSubmit, "The change is saved; saving again is not offered.")
        assertEquals(1, refreshes)
        assertEquals(listOf<NativeActionRequest>(request), sent, "Nothing is restored automatically.")

        assertTrue(session.restoreBaseline())
        assertEquals(listOf<NativeActionRequest>(request, restore), sent)
        assertEquals(NativeEditorWriteStatus.Succeeded, session.status)
        assertEquals(2, refreshes)
        assertFalse(session.restoreBaseline(), "A restore is sent at most once.")
    }

    @Test
    fun aCancelledRestoreIsUnconfirmed() = runBlocking<Unit> {
        val session = NativeEditorWriteSession()
        val drifted = NativeActionExecutionResult.Success(
            null, NativeWritePostcondition(emptySet(), setOf("body"), request.copy(values = mapOf("restore" to "baseline"))),
        )
        var first = true
        session.submit({ if (first) { first = false; drifted } else throw CancellationException("left") }, request, NativeEditorWriteRetry.AfterRefresh) {}
        assertFailsWith<CancellationException> { session.restoreBaseline() }
        assertIs<NativeEditorWriteStatus.Unconfirmed>(session.status)
    }

    @Test
    fun anUnverifiedRestorePreReadKeepsTheRestoreActionWithoutSendingItAutomatically() = runBlocking<Unit> {
        val session = NativeEditorWriteSession()
        val restore = request.copy(values = mapOf("restore" to "baseline"))
        val replies = ArrayDeque(listOf(
            NativeActionExecutionResult.Success(null, NativeWritePostcondition(emptySet(), setOf("body"), restore)),
            NativeActionExecutionResult.Failure(
                "The current values could not be checked.", NativeActionFailureOutcome.Rejected,
                precondition = NativeWritePreconditionFailure.Unverified,
            ),
            NativeActionExecutionResult.Success("Saved."),
        ))
        val sent = mutableListOf<NativeActionRequest>()
        session.submit({ sent += it; replies.removeFirst() }, request, NativeEditorWriteRetry.AfterRefresh) {}
        assertFalse(session.restoreBaseline())
        val retryable = assertIs<NativeEditorWriteStatus.Drifted>(session.status)
        assertEquals(restore, retryable.restore)
        assertEquals("The current values could not be checked.", retryable.message)
        assertEquals(listOf<NativeActionRequest>(request, restore), sent)
        assertTrue(session.restoreBaseline())
        assertEquals(listOf<NativeActionRequest>(request, restore, restore), sent)
        assertEquals(NativeEditorWriteStatus.Succeeded, session.status)
    }
}
