package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeMailComposerStateTest {
    @Test
    fun `a confirmed send closes the draft and reports it once`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
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
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
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
    fun `an unknown result without a status read is never resent without confirmation`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) }
        state.send(capability(), executor)
        val phase = assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertEquals(NativeMailSendRecovery.Unavailable, phase.recovery)
        assertTrue(state.resendNeedsConfirmation)
        assertTrue(state.sendAvailable, "without a status read the user decides after checking sent messages")
        state.send(capability(), executor)
        assertEquals(1, executor.calls, "a second send without confirmation must not leave the device")
        state.update { it.copy(subject = "Edited") }
        assertIs<NativeMailSendPhase.Unknown>(state.phase, "editing does not clear the unknown outcome")
        state.send(capability(), executor, confirmedResend = true)
        assertEquals(2, executor.calls)
    }

    @Test
    fun `an unknown result reads the declared status and closes the draft when it shows the message as sent`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        val executor = RecoveringExecutor(
            send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
            read = { NativeResultRecoveryRead.Read(mapOf("status" to "sent")) },
        )
        var sent = 0
        state.send(recoverableCapability(), executor) { sent += 1 }
        assertEquals(1, executor.reads)
        assertEquals("7", executor.readRequests.single().values["accountId"], "the status read binds the attempt's account")
        assertNull(state.draft)
        assertEquals(NativeMailSendPhase.Editing, state.phase)
        assertEquals("The app reports this message as sent.", state.notice)
        assertEquals(1, sent)
        assertEquals(1, executor.sends)
    }

    @Test
    fun `a status read holding exactly the attempted message counts as sent`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        val executor = RecoveringExecutor(
            send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
            read = { NativeResultRecoveryRead.Read(mapOf("subject" to "Hello", "body" to "Text", "to" to "[{\"email\":\"Grace@example.test\"}]")) },
        )
        state.send(recoverableCapability(), executor)
        assertNull(state.draft)
    }

    @Test
    fun `the same text sent to someone else is not this attempt`() = runBlocking<Unit> {
        val other = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        other.send(
            recoverableCapability(),
            RecoveringExecutor(
                send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
                read = { NativeResultRecoveryRead.Read(mapOf("subject" to "Hello", "body" to "Text", "to" to "alan@example.test")) },
            ),
        )
        assertIs<NativeMailSendRecovery.NotShownAsSent>(assertIs<NativeMailSendPhase.Unknown>(other.phase).recovery)
        assertNotNull(other.draft)

        val unnamed = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        unnamed.send(
            recoverableCapability(),
            RecoveringExecutor(
                send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
                read = { NativeResultRecoveryRead.Read(mapOf("subject" to "Hello", "body" to "Text")) },
            ),
        )
        assertEquals(
            NativeMailSendRecovery.Unavailable,
            assertIs<NativeMailSendPhase.Unknown>(unnamed.phase).recovery,
            "Matching text without recipients does not prove this attempt was sent.",
        )
        assertNotNull(unnamed.draft)
    }

    @Test
    fun `a status that does not show the message as sent allows a confirmed resend`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        val executor = RecoveringExecutor(
            send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
            read = { NativeResultRecoveryRead.Read(mapOf("status" to "failed")) },
        )
        state.send(recoverableCapability(), executor)
        val phase = assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertEquals(NativeMailSendRecovery.NotShownAsSent("failed"), phase.recovery)
        assertTrue(state.sendAvailable)
        state.send(recoverableCapability(), executor)
        assertEquals(1, executor.sends, "still not without confirmation")
        state.send(recoverableCapability(), executor, confirmedResend = true)
        assertEquals(2, executor.sends)
    }

    @Test
    fun `a queued or unreadable status keeps sending again unavailable`() = runBlocking<Unit> {
        listOf(
            NativeResultRecoveryRead.Read(mapOf("status" to "queued")) to NativeMailSendRecovery.InProgress("queued"),
            NativeResultRecoveryRead.Failed("The status could not be read.") to
                NativeMailSendRecovery.CheckFailed("The status could not be read."),
        ).forEach { (read, expected) ->
            val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
            val executor = RecoveringExecutor(
                send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
                read = { read },
            )
            state.send(recoverableCapability(), executor)
            assertEquals(expected, assertIs<NativeMailSendPhase.Unknown>(state.phase).recovery)
            assertFalse(state.sendAvailable)
            assertTrue(state.statusCheckAvailable)
            state.send(recoverableCapability(), executor, confirmedResend = true)
            assertEquals(1, executor.sends, "an unverified attempt is never resent")
        }
    }

    @Test
    fun `a thrown status read is a failed check, not a sent message`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        val executor = RecoveringExecutor(
            send = { error("connection reset") },
            read = { error("connection reset") },
        )
        state.send(recoverableCapability(), executor)
        assertIs<NativeMailSendRecovery.CheckFailed>(assertIs<NativeMailSendPhase.Unknown>(state.phase).recovery)
        assertEquals(readyDraft(), state.draft)
    }

    @Test
    fun `a thrown transport failure is treated as unknown`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        state.send(capability(), RecordingExecutor { error("connection reset") })
        assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertEquals(readyDraft(), state.draft)
    }

    @Test
    fun `cancellation is rethrown and leaves the outcome unknown`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        assertFailsWith<CancellationException> {
            state.send(capability(), RecordingExecutor { throw CancellationException("left") })
        }
        val phase = assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertEquals(NativeMailSendRecovery.NotChecked, phase.recovery)
        assertEquals(readyDraft(), phase.attempt)
    }

    @Test
    fun `an invalid draft is rejected before any request`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft().copy(to = "not an address")) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Success() }
        state.send(capability(), executor)
        assertIs<NativeMailSendPhase.Rejected>(state.phase)
        assertEquals(0, executor.calls)
    }

    @Test
    fun `a send owned by the retained holder outlives the screen that started it`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val retention = NativeMailComposerRetention(owner) { MemoryDraftStore() }
        val state = retention.stateFor("synthetic_mail")
        state.open(readyDraft())
        val answer = CompletableDeferred<NativeActionExecutionResult>()
        val executor = RecordingExecutor { answer.await() }
        // A screen-local scope only asks; the send itself runs in the owner's scope.
        val screen = CoroutineScope(coroutineContext + Job())
        state.launchSend(capability(), executor, confirmedResend = false) {}
        repeat(3) { yield() }
        assertTrue(state.sending)
        screen.cancel()
        // The recreated screen gets the same state for the same app.
        assertSame(state, retention.stateFor("synthetic_mail"))
        answer.complete(NativeActionExecutionResult.Success())
        owner.settle()
        assertNull(state.draft)
        assertEquals("Message sent.", state.notice)
        assertEquals(1, executor.calls)
        owner.cancel()
    }

    @Test
    fun `drafts are saved on the device and restored after process death`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = MemoryDraftStore()
        val first = NativeMailComposerState(owner, store, saveDelayMillis = 0)
        first.open(readyDraft())
        first.update { it.copy(body = "Edited text") }
        owner.settle()
        assertEquals(NativeMailStoredComposer(readyDraft().copy(body = "Edited text")), store.value)

        val restored = NativeMailComposerState(owner, store, saveDelayMillis = 0)
        restored.restore()
        assertEquals("Edited text", restored.draft?.body)
        assertEquals(NativeMailSendPhase.Editing, restored.phase)

        restored.discard()
        owner.settle()
        assertNull(store.value, "a discarded draft leaves no device copy")
        owner.cancel()
    }

    @Test
    fun `an unconfirmed attempt on the device is kept when a draft was opened before restoration`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = MemoryDraftStore()
        store.value = NativeMailStoredComposer(readyDraft(), unknownAttempt = readyDraft())
        val state = NativeMailComposerState(owner, store, saveDelayMillis = 0)
        state.expectRestore()
        assertFalse(state.sendAvailable, "Nothing is sent before the device copy is known.")
        state.open(readyDraft().copy(body = "A new message"))
        val executor = RecordingExecutor { error("Nothing may be sent before restoration.") }
        state.send(capability(), executor)
        assertEquals(0, executor.calls)

        state.restore()
        owner.settle()
        assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertEquals(readyDraft(), state.draft)
        assertTrue(state.resendNeedsConfirmation)
        assertNotNull(state.notice)
        assertEquals(readyDraft(), store.value?.unknownAttempt, "The queued save of the newer draft does not erase the evidence.")
        owner.cancel()
    }

    @Test
    fun `an unreadable device copy is neither overwritten nor bypassed by a send`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val earlier = NativeMailStoredComposer(readyDraft(), unknownAttempt = readyDraft())
        val store = MemoryDraftStore(value = earlier, failLoads = true)
        val state = NativeMailComposerState(owner, store, saveDelayMillis = 0)
        state.expectRestore()
        state.restore()
        assertNotNull(state.storageProblem)

        state.open(readyDraft().copy(body = "A new message"))
        owner.settle()
        assertEquals(earlier, store.value, "The copy that could not be read is not overwritten.")
        val executor = RecordingExecutor { error("Nothing may be sent while earlier evidence is unreadable.") }
        state.send(capability(), executor)
        assertEquals(0, executor.calls)
        assertIs<NativeMailSendPhase.Rejected>(state.phase)

        store.failLoads = false
        state.restore()
        assertIs<NativeMailSendPhase.Unknown>(state.phase)
        assertTrue(state.resendNeedsConfirmation)
        owner.cancel()
    }

    @Test
    fun `a send whose attempt could not be recorded can be sent once storage works again`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = MemoryDraftStore(failSaves = true)
        val state = NativeMailComposerState(owner, store, saveDelayMillis = 0).apply { open(readyDraft()) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Success() }
        state.send(capability(), executor)
        assertEquals(0, executor.calls, "An attempt that cannot be recorded is not sent.")
        assertIs<NativeMailSendPhase.Rejected>(state.phase)

        store.failSaves = false
        state.send(capability(), executor)
        assertEquals(1, executor.calls, "The unrecorded attempt does not block the next send.")
        assertNull(state.draft)
        owner.cancel()
    }

    @Test
    fun `the attempt is stored before its request starts and comes back unconfirmed`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = MemoryDraftStore()
        val state = NativeMailComposerState(owner, store, saveDelayMillis = 0).apply { open(readyDraft()) }
        var storedDuringRequest: NativeMailStoredComposer? = null
        val never = CompletableDeferred<NativeActionExecutionResult>()
        state.launchSend(capability(), RecordingExecutor { storedDuringRequest = store.value; never.await() }, false) {}
        repeat(5) { yield() }
        assertEquals(readyDraft(), storedDuringRequest?.draft)
        assertEquals(readyDraft(), storedDuringRequest?.unknownAttempt)
        val attempt = assertNotNull(storedDuringRequest?.attempt, "The exactly-once attempt record is durable with the draft.")
        assertEquals(NativeSendAttemptState.Prepared, attempt.state)
        assertEquals("1 recipient", attempt.targetSummary)
        // The process dies while the request is open.
        owner.cancel()

        val restartOwner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val restored = NativeMailComposerState(restartOwner, store, saveDelayMillis = 0)
        restored.restore()
        val phase = assertIs<NativeMailSendPhase.Unknown>(restored.phase)
        assertEquals(NativeMailSendRecovery.NotChecked, phase.recovery)
        assertEquals(NativeSendUnknownReason.Interrupted, phase.record?.unknownReason, "The evidence says the attempt was interrupted.")
        assertTrue(restored.statusCheckAvailable)
        assertTrue(restored.resendNeedsConfirmation)
        restartOwner.cancel()
    }

    @Test
    fun `a confirmed resend of the same message reuses its declared idempotency key and shows the evidence`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        val keys = mutableListOf<String?>()
        val executor = NativeActionExecutor { request ->
            keys += (request as NativeActionRequest.Submit).sendAttempt?.idempotencyKey
            NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown)
        }
        val keyed = capability().let { it.copy(action = it.action.copy(idempotencyKeyHeader = "Idempotency-Key")) }
        state.send(keyed, executor)
        val record = assertNotNull(assertIs<NativeMailSendPhase.Unknown>(state.phase).record)
        assertTrue("not confirmed" in record.evidence(), record.evidence())
        state.send(keyed, executor)
        assertEquals(1, keys.size, "Nothing is resent without confirmation.")
        state.send(keyed, executor, confirmedResend = true)
        assertEquals(2, keys.size)
        assertNotNull(keys[0])
        assertEquals(keys[0], keys[1])
    }

    @Test
    fun `a confirmed send clears the device copy`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = MemoryDraftStore()
        val state = NativeMailComposerState(owner, store, saveDelayMillis = 0).apply { open(readyDraft()) }
        owner.settle()
        assertNotNull(store.value)
        state.send(capability(), RecordingExecutor { NativeActionExecutionResult.Success() })
        assertNull(store.value)
        owner.cancel()
    }

    @Test
    fun `a message is not sent when its attempt cannot be recorded`() = runBlocking<Unit> {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = MemoryDraftStore(failSaves = true)
        val state = NativeMailComposerState(owner, store, saveDelayMillis = 0).apply { open(readyDraft()) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Success() }
        state.send(capability(), executor)
        assertEquals(0, executor.calls)
        assertIs<NativeMailSendPhase.Rejected>(state.phase)
        assertEquals("This draft could not be saved on this device.", state.storageProblem)
        owner.cancel()
    }

    @Test
    fun `a restored draft does not replace one the user already started`() = runBlocking<Unit> {
        val store = MemoryDraftStore(NativeMailStoredComposer(readyDraft().copy(body = "Saved")))
        val state = NativeMailComposerState(null, store).apply { open(readyDraft().copy(body = "Typed")) }
        state.restore()
        assertEquals("Typed", state.draft?.body)
    }

    @Test
    fun `stored composers round-trip and reject unknown versions`() {
        val stored = NativeMailStoredComposer(
            readyDraft().copy(
                mode = NativeMailComposeMode.Reply,
                quote = NativeMailQuote("Grace wrote:", "Original", forward = false),
                inReplyTo = "<a@example.test>",
                references = "<root@example.test> <a@example.test>",
            ),
            unknownAttempt = readyDraft(),
        )
        val encoded = encodeNativeMailStoredComposer(stored)
        assertEquals(stored, decodeNativeMailStoredComposer(encoded))
        assertNull(decodeNativeMailStoredComposer(encoded.replace("\"version\":2", "\"version\":3")))
        assertEquals(stored, decodeNativeMailStoredComposer(encoded.replace("\"version\":2", "\"version\":1")), "Version 1 still restores.")
        val withAttempt = stored.copy(attempt = NativeSendAttempt("a".repeat(32), 5, "1 recipient", "f".repeat(64), "b".repeat(32)))
        assertEquals(withAttempt, decodeNativeMailStoredComposer(encodeNativeMailStoredComposer(withAttempt)))
        assertNull(decodeNativeMailStoredComposer(encodeNativeMailStoredComposer(withAttempt).replace("f".repeat(64), "not-a-fingerprint")))
        assertNull(decodeNativeMailStoredComposer(encoded.replace("\"Reply\"", "\"Unknown\"")))
        assertNull(decodeNativeMailStoredComposer("{"))
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

    @Test
    fun `in-reply-to and references each get their own value`() {
        val threaded = capability().copy(inReplyToField = "inReplyTo", referencesField = "references")
        val values = threaded.request(
            readyDraft().copy(inReplyTo = "<b@example.test>", references = "<a@example.test> <b@example.test>"),
        ).values
        assertEquals("<b@example.test>", values["inReplyTo"])
        assertEquals("<a@example.test> <b@example.test>", values["references"])
    }

    private class RecordingExecutor(private val result: suspend () -> NativeActionExecutionResult) : NativeActionExecutor {
        var calls = 0

        override suspend fun execute(request: NativeActionRequest): NativeActionExecutionResult {
            calls += 1
            return result()
        }
    }

    private class RecoveringExecutor(
        private val send: () -> NativeActionExecutionResult,
        private val read: () -> NativeResultRecoveryRead,
    ) : NativeActionExecutor, NativeResultRecoveryReader {
        var sends = 0
        val readRequests = mutableListOf<NativeActionRequest.Submit>()
        val reads: Int get() = readRequests.size

        override suspend fun execute(request: NativeActionRequest): NativeActionExecutionResult {
            sends += 1
            return send()
        }

        override suspend fun readResult(request: NativeActionRequest.Submit): NativeResultRecoveryRead {
            readRequests += request
            return read()
        }
    }

    @Test
    fun `without durable storage the draft stays in memory and nothing is sent`() = runBlocking<Unit> {
        val state = NativeMailComposerState().apply { open(readyDraft()) }
        val executor = RecordingExecutor { NativeActionExecutionResult.Success() }
        state.send(capability(), executor)
        assertEquals(0, executor.calls, "An attempt that cannot be recorded durably is never sent.")
        assertTrue("not sent" in assertIs<NativeMailSendPhase.Rejected>(state.phase).message)
        assertNotNull(state.draft, "The draft is kept.")
    }

    private class MemoryDraftStore(
        var value: NativeMailStoredComposer? = null,
        var failSaves: Boolean = false,
        var failLoads: Boolean = false,
    ) : NativeMailDraftStore {
        override suspend fun load(): NativeMailStoredComposer? {
            check(!failLoads) { "Synthetic storage is unreadable." }
            return value
        }

        override suspend fun save(value: NativeMailStoredComposer) {
            check(!failSaves) { "Synthetic storage is full." }
            this.value = value
        }

        override suspend fun clear() {
            value = null
        }
    }

    private suspend fun CoroutineScope.settle() {
        val job = checkNotNull(coroutineContext[Job])
        while (true) {
            val active = job.children.filter(Job::isActive).toList()
            if (active.isEmpty()) return
            active.forEach { it.join() }
        }
    }

    @Test
    fun `a status read that leaves out the copied recipients does not confirm the send`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft().copy(cc = "alan@example.test")) }
        state.send(
            recoverableCapability(),
            RecoveringExecutor(
                send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
                read = { NativeResultRecoveryRead.Read(mapOf("subject" to "Hello", "body" to "Text", "to" to "grace@example.test")) },
            ),
        )
        assertEquals(
            NativeMailSendRecovery.Unavailable,
            assertIs<NativeMailSendPhase.Unknown>(state.phase).recovery,
            "The same text to the same To address may be an earlier message without the copy.",
        )
        assertNotNull(state.draft)
    }

    @Test
    fun `edits made after an unconfirmed send survive learning that it was sent`() = runBlocking<Unit> {
        val state = NativeMailComposerState(store = MemoryDraftStore()).apply { open(readyDraft()) }
        var answer: NativeResultRecoveryRead = NativeResultRecoveryRead.Failed("offline")
        val executor = RecoveringExecutor(
            send = { NativeActionExecutionResult.Failure("timeout", NativeActionFailureOutcome.Unknown) },
            read = { answer },
        )
        state.send(recoverableCapability(), executor)
        assertIs<NativeMailSendPhase.Unknown>(state.phase)
        state.update { it.copy(body = "A different message") }

        answer = NativeResultRecoveryRead.Read(mapOf("subject" to "Hello", "body" to "Text", "to" to "grace@example.test"))
        state.checkOutcome(recoverableCapability(), executor)
        assertEquals("A different message", state.draft?.body, "What was written after the send was never sent.")
        assertIs<NativeMailSendPhase.Editing>(state.phase)
        assertFalse(state.resendNeedsConfirmation)
    }

    @Test
    fun `a draft whose chosen sender is gone is not sent from the remaining account`() {
        val capability = capability()
        assertEquals("7", capability.senderFor(readyDraft())?.id)
        assertEquals("7", capability.senderFor(readyDraft().copy(senderId = null))?.id, "A draft that names no sender uses the only one.")

        val moved = readyDraft().copy(senderId = "3")
        assertNull(capability.senderFor(moved), "The only remaining account is not substituted for the chosen one.")
        assertTrue(capability.problems(moved).any { it.contains("account to send from") })
    }

    private fun readyDraft() = NativeMailComposeDraft(
        mode = NativeMailComposeMode.New,
        senderId = "7",
        to = "grace@example.test",
        subject = "Hello",
        body = "Text",
    )

    private fun recoverableCapability() = capability().let { base ->
        base.copy(
            action = base.action.copy(
                binding = base.action.binding.copy(method = HttpMethod.PUT, path = "/apps/synthetic/accounts/{accountId}/send"),
                resultRecoveryActionId = "send-status",
            ),
        )
    }

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
    )
}
