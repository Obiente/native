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
}
