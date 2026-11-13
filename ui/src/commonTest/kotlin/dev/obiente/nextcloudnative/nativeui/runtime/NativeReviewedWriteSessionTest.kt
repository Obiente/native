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

class NativeReviewedWriteSessionTest {
    private val request = NativeActionRequest.Submit(
        ActionSpec("create", "Create", "rows", ApiBinding(HttpMethod.POST, "/api/rows", "create"),
            ActionIntent.create, ActionRisk.mutating, true, Confidence.high),
        mapOf("data" to "{}"),
        confirmed = true,
    )

    @Test
    fun successRefreshesAndRejectionKeepsTheDraftRetryable() = runBlocking {
        var refreshes = 0
        val session = NativeReviewedWriteSession()
        assertTrue(session.submit({ NativeActionExecutionResult.Success() }, request, NativeReviewedWriteRetry.NonIdempotent) { refreshes++ })
        assertEquals(1, refreshes)
        val rejected = session.submit({
            NativeActionExecutionResult.Failure("No permission.", NativeActionFailureOutcome.Rejected)
        }, request, NativeReviewedWriteRetry.NonIdempotent) { refreshes++ }
        assertFalse(rejected)
        assertEquals(1, refreshes, "A rejected request changed nothing and needs no refresh.")
        assertIs<NativeReviewedWriteStatus.Rejected>(session.status)
        assertTrue(session.canSubmit)
    }

    @Test
    fun unconfirmedCreateBlocksResubmissionUntilAnAuthoritativeRefresh() = runBlocking {
        var calls = 0
        var refreshes = 0
        val session = NativeReviewedWriteSession()
        val unknown = NativeActionExecutor {
            calls++
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        assertFalse(session.submit(unknown, request, NativeReviewedWriteRetry.NonIdempotent) { refreshes++ })
        assertEquals(1, refreshes)
        assertFalse(session.canSubmit)
        assertFalse(session.submit(unknown, request, NativeReviewedWriteRetry.NonIdempotent) { refreshes++ })
        assertEquals(1, calls, "A blocked resubmission never reaches the executor.")
        session.acceptAuthoritativeRefresh()
        assertTrue(session.canSubmit)
    }

    @Test
    fun closingTheEditorKeepsAnUnconfirmedCreateBlocked() = runBlocking {
        var calls = 0
        val session = NativeReviewedWriteSession()
        val unknown = NativeActionExecutor {
            calls++
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        session.submit(unknown, request, NativeReviewedWriteRetry.NonIdempotent) {}
        session.reset()
        assertFalse(session.canSubmit, "Reopening the editor must not allow a second create.")
        assertFalse(session.submit(unknown, request, NativeReviewedWriteRetry.NonIdempotent) {})
        assertEquals(1, calls)
        session.acceptAuthoritativeRefresh()
        session.reset()
        assertEquals(NativeReviewedWriteStatus.Idle, session.status)
        assertTrue(session.canSubmit)
    }

    @Test
    fun unconfirmedIdempotentWriteMayBeRepeated() = runBlocking {
        val session = NativeReviewedWriteSession()
        session.submit({ NativeActionExecutionResult.Failure("x", NativeActionFailureOutcome.Unknown) },
            request, NativeReviewedWriteRetry.Idempotent) {}
        assertTrue(session.canSubmit)
    }

    @Test
    fun cancellationIsRethrownAndLeavesTheResultUnconfirmed() = runBlocking {
        val session = NativeReviewedWriteSession()
        assertFailsWith<CancellationException> {
            session.submit({ throw CancellationException("left") }, request, NativeReviewedWriteRetry.NonIdempotent) {}
        }
        val status = assertIs<NativeReviewedWriteStatus.Unconfirmed>(session.status)
        assertTrue(status.awaitingRefresh)
    }

    @Test
    fun anExecutorFaultIsUnconfirmedNotUnsent() = runBlocking {
        val session = NativeReviewedWriteSession()
        assertFalse(session.submit({ error("transport broke") }, request, NativeReviewedWriteRetry.NonIdempotent) {})
        assertIs<NativeReviewedWriteStatus.Unconfirmed>(session.status)
        assertFalse(session.canSubmit)
    }
}
