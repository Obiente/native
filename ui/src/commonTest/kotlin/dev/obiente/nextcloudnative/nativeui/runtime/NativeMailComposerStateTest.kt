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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMailComposerStateTest {
    @Test
    fun `a confirmed send closes the draft and reports it once`() = runBlocking<Unit> {
        val state = NativeMailComposerState().apply { open(readyDraft()) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Success() }
        var sent = 0
        state.send(capability(), executor) { sent += 1 }
        assertNull(state.draft)
        assertEquals("Message sent.", state.notice)
        assertEquals(1, executor.calls)
        assertEquals(1, sent)
    }

    @Test
    fun `a rejected send keeps the draft and allows editing and sending again`() = runBlocking<Unit> {
        val state = NativeMailComposerState().apply { open(readyDraft()) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Failure("Quota exceeded", NativeActionFailureOutcome.Rejected) }
        state.send(capability(), executor)
        assertIs<NativeMailSendPhase.Rejected>(state.phase)
        assertEquals("Quota exceeded", (state.phase as NativeMailSendPhase.Rejected).message)
        state.update { it.copy(body = "Shorter") }
        assertEquals(NativeMailSendPhase.Editing, state.phase)
        state.send(capability(), executor)
        assertEquals(2, executor.calls)
    }

    @Test
    fun `an unknown result is never resent without confirmation`() = runBlocking<Unit> {
        val state = NativeMailComposerState().apply { open(readyDraft()) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) }
        state.send(capability(), executor)
        assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertTrue(state.resendNeedsConfirmation)
        state.send(capability(), executor)
        assertEquals(1, executor.calls, "a second send without confirmation must not leave the device")
        state.update { it.copy(subject = "Edited") }
        assertIs<NativeMailSendPhase.Unknown>(state.phase, "editing does not clear the unknown outcome")
        state.send(capability(), executor, confirmedResend = true)
        assertEquals(2, executor.calls)
    }

    @Test
    fun `a thrown transport failure is treated as unknown`() = runBlocking<Unit> {
        val state = NativeMailComposerState().apply { open(readyDraft()) }
        state.send(capability(), RecordingExecutor { error("connection reset") })
        assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertEquals(readyDraft(), state.draft)
    }

    @Test
    fun `cancellation is rethrown and leaves the outcome unknown`() = runBlocking<Unit> {
        val state = NativeMailComposerState().apply { open(readyDraft()) }
        assertFailsWith<CancellationException> {
            state.send(capability(), RecordingExecutor { throw CancellationException("left") })
        }
        assertIs<NativeMailSendPhase.Unknown>(state.phase)
    }

    @Test
    fun `an invalid draft is rejected before any request`() = runBlocking<Unit> {
        val state = NativeMailComposerState().apply { open(readyDraft().copy(to = "not an address")) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Success() }
        state.send(capability(), executor)
        assertIs<NativeMailSendPhase.Rejected>(state.phase)
        assertEquals(0, executor.calls)
    }

    @Test
    fun `a single-address recipient field refuses several recipients`() {
        val single = capability().copy(to = NativeMailRecipientField("to", NativeMailRecipientEncoding.SingleAddress))
        val problems = single.problems(readyDraft().copy(to = "a@example.test, b@example.test"))
        assertTrue("This app accepts one address per recipient field." in problems, "$problems")
        assertEquals("grace@example.test", single.request(readyDraft()).values["to"])
    }

    @Test
    fun `cc is refused when the contract declares no cc field`() {
        val problems = capability().copy(cc = null).problems(readyDraft().copy(cc = "kai@example.test"))
        assertTrue("This app does not accept Cc recipients." in problems, "$problems")
    }

    private class RecordingExecutor(private val result: () -> NativeActionExecutionResult) : NativeActionExecutor {
        var calls = 0

        override suspend fun execute(request: NativeActionRequest): NativeActionExecutionResult {
            calls += 1
            return result()
        }
    }

    private fun readyDraft() = NativeMailComposeDraft(
        mode = NativeMailComposeMode.New,
        senderId = "7",
        to = "grace@example.test",
        subject = "Hello",
        body = "Text",
    )

    private fun capability() = NativeMailSendCapability(
        action = ActionSpec(
            id = "send",
            label = "Send",
            resourceId = "message",
            binding = ApiBinding(method = HttpMethod.POST, path = "/apps/synthetic/send", operationId = "send"),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        ),
        senders = listOf(NativeMailSender(id = "7", email = "ada@example.test", label = "Ada")),
        to = NativeMailRecipientField("to", NativeMailRecipientEncoding.ObjectList("email", "label")),
        cc = NativeMailRecipientField("cc", NativeMailRecipientEncoding.StringList),
        bcc = null,
        subjectField = "subject",
        bodyField = "body",
        htmlFlagField = null,
        senderIdentityFields = listOf("accountId"),
        senderAddressField = null,
        threadingField = null,
    )
}
