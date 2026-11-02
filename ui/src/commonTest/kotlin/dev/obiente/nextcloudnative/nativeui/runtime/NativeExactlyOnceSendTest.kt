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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Technique D with a synthetic notification send. */
class NativeExactlyOnceSendTest {
    @Test
    fun `the attempt is durable before the request and cleared after a confirmed send`() = runBlocking<Unit> {
        val store = MemoryAttempts()
        var storedWhenSent: NativeSendAttempt? = null
        val outcome = sender(store).send(request(), { request ->
            storedWhenSent = store.value
            assertNotNull((request as NativeActionRequest.Submit).sendAttempt, "The executor sees the exactly-once token.")
            NativeActionExecutionResult.Success()
        }, "1 recipient", confirmedResend = false, declaresIdempotencyKey = false)
        assertIs<NativeExactlyOnceSendOutcome.Sent>(outcome)
        assertEquals(NativeSendAttemptState.Prepared, storedWhenSent?.state)
        assertEquals(nativeSendFingerprint(request()), storedWhenSent?.payloadFingerprint)
        assertNull(store.value)
    }

    @Test
    fun `a rejection clears the attempt and keeps nothing to confirm`() = runBlocking<Unit> {
        val store = MemoryAttempts()
        val outcome = sender(store).send(request(), { NativeActionExecutionResult.Failure("Invalid address.", NativeActionFailureOutcome.Rejected) },
            "1 recipient", confirmedResend = false, declaresIdempotencyKey = false)
        assertEquals("Invalid address.", assertIs<NativeExactlyOnceSendOutcome.Rejected>(outcome).message)
        assertNull(store.value)
    }

    @Test
    fun `an unknown result is may-have-been-sent and blocks an unconfirmed resend`() = runBlocking<Unit> {
        val store = MemoryAttempts()
        var calls = 0
        val executor = NativeActionExecutor { calls++; NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown) }
        val first = assertIs<NativeExactlyOnceSendOutcome.MaybeSent>(sender(store).send(request(), executor, "1 recipient", false, false))
        assertEquals(NativeSendAttemptState.MaybeSent, store.value?.state)
        assertEquals(NativeSendUnknownReason.Unconfirmed, first.attempt.unknownReason)
        val blocked = sender(store).send(request(), executor, "1 recipient", confirmedResend = false, declaresIdempotencyKey = false)
        assertEquals(NativeExactlyOnceSendOutcome.NotSent(NativeSendRefusal.ResendNeedsConfirmation), blocked)
        assertEquals(1, calls, "Nothing is ever sent again without confirmation.")
    }

    @Test
    fun `a declared idempotency key is reused on a confirmed resend of the same payload only`() = runBlocking<Unit> {
        val store = MemoryAttempts()
        val keys = mutableListOf<String?>()
        val unknown = NativeActionExecutor { request ->
            keys += (request as NativeActionRequest.Submit).sendAttempt?.idempotencyKey
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        val sender = sender(store)
        sender.send(request(), unknown, "1 recipient", confirmedResend = false, declaresIdempotencyKey = true)
        sender.send(request(), unknown, "1 recipient", confirmedResend = true, declaresIdempotencyKey = true)
        sender.send(request(body = "Edited"), unknown, "1 recipient", confirmedResend = true, declaresIdempotencyKey = true)
        assertNotNull(keys[0])
        assertEquals(keys[0], keys[1], "The same payload reuses the key so the server can drop the duplicate.")
        assertNotEquals(keys[1], keys[2], "A changed payload is a new message.")
    }

    @Test
    fun `an undeclared idempotency key is never invented`() = runBlocking<Unit> {
        val store = MemoryAttempts()
        var token: NativeSendAttemptToken? = null
        sender(store).send(request(), { request ->
            token = (request as NativeActionRequest.Submit).sendAttempt
            NativeActionExecutionResult.Success()
        }, "1 recipient", confirmedResend = false, declaresIdempotencyKey = false)
        assertNotNull(token)
        assertNull(token?.idempotencyKey)
    }

    @Test
    fun `without a durable attempt record nothing is sent`() = runBlocking<Unit> {
        var calls = 0
        val outcome = sender(MemoryAttempts(failSave = true)).send(request(), { calls++; NativeActionExecutionResult.Success() },
            "1 recipient", confirmedResend = false, declaresIdempotencyKey = false)
        assertEquals(NativeExactlyOnceSendOutcome.NotSent(NativeSendRefusal.AttemptNotRecorded), outcome)
        assertEquals(0, calls)
    }

    @Test
    fun `an unknown result followed by cancellation keeps the prepared record, which reads as interrupted after restart`() {
        val store = MemoryAttempts()
        assertFailsWith<CancellationException> {
            runBlocking {
                sender(store).send(request(), { throw CancellationException("closed") }, "1 recipient", false, false)
            }
        }
        val stored = assertNotNull(store.value)
        assertEquals(NativeSendAttemptState.Prepared, stored.state)
        val restored = stored.afterRestart()
        assertEquals(NativeSendAttemptState.MaybeSent, restored.state)
        assertEquals(NativeSendUnknownReason.Interrupted, restored.unknownReason)
    }

    @Test
    fun `the fingerprint ignores value order and covers every value`() {
        assertEquals(
            nativeSendFingerprint(request().copy(values = mapOf("to" to "a@example.test", "body" to "Hello"))),
            nativeSendFingerprint(request().copy(values = mapOf("body" to "Hello", "to" to "a@example.test"))),
        )
        assertNotEquals(nativeSendFingerprint(request()), nativeSendFingerprint(request(body = "Hello!")))
    }

    private fun sender(store: NativeSendAttemptStore): NativeExactlyOnceSend {
        var counter = 0
        return NativeExactlyOnceSend(store, clock = { 1_000L }, newToken = { (++counter).toString().padStart(32, '0') })
    }

    private fun request(body: String = "Hello") = NativeActionRequest.Submit(
        action = ActionSpec(
            "notify-send", "Send notification", "notifications",
            ApiBinding(HttpMethod.POST, "/apps/notifier/api/notifications", "notify-send"),
            ActionIntent.execute, ActionRisk.mutating, false, Confidence.high,
        ),
        values = mapOf("to" to "a@example.test", "body" to body),
        confirmed = true,
    )

    private class MemoryAttempts(private val failSave: Boolean = false) : NativeSendAttemptStore {
        var value: NativeSendAttempt? = null

        override suspend fun load(): NativeSendAttempt? = value

        override suspend fun save(attempt: NativeSendAttempt) {
            if (failSave) error("disk full")
            value = attempt
        }

        override suspend fun clear() {
            value = null
        }
    }
}
