package dev.obiente.nextcloudnative.nativeui.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The one verified write path, with synthetic records only. */
class NativeVerifiedWriteTest {
    @Test
    fun `a read-back showing the submitted values confirms the write`() = runBlocking<Unit> {
        val outcome = write(readBack = listOf(note(title = "New title")))
        assertIs<NativeVerifiedWriteOutcome.Confirmed<List<NativeRecord>>>(outcome)
        assertFalse(outcome.contradictsPostcondition)
    }

    @Test
    fun `a server-normalized value is an adjustment, not a contradiction`() = runBlocking<Unit> {
        val outcome = assertIs<NativeVerifiedWriteOutcome.Adjusted<List<NativeRecord>>>(write(readBack = listOf(note(title = "new title"))))
        assertEquals(setOf("title"), outcome.adjusted)
        assertTrue(outcome.drifted.isEmpty())
        assertFalse(outcome.contradictsPostcondition)
    }

    @Test
    fun `read-back drift on an untouched field contradicts the postcondition`() = runBlocking<Unit> {
        val outcome = assertIs<NativeVerifiedWriteOutcome.Adjusted<List<NativeRecord>>>(
            write(readBack = listOf(note(title = "New title", body = "Edited elsewhere"))),
        )
        assertEquals(setOf("body"), outcome.drifted)
        assertTrue(outcome.contradictsPostcondition)
    }

    @Test
    fun `server bookkeeping fields are not drift`() = runBlocking<Unit> {
        val expectation = NativeWriteExpectation(
            "7",
            mapOf("title" to JsonPrimitive("New title")),
            mapOf("body" to JsonPrimitive("Synthetic body"), "lastModified" to JsonPrimitive(100)),
            mapOf("title" to JsonPrimitive("Old title")),
        )
        val record = note(title = "New title").let { it.copy(wireValues = it.wireValues + ("lastModified" to JsonPrimitive(200))) }
        assertEquals(NativeWriteVerification.Confirmed, expectation.verify(listOf(record)))
    }

    @Test
    fun `2xx then a read-back of the previous value is not applied and contradicts the success`() = runBlocking<Unit> {
        val outcome = assertIs<NativeVerifiedWriteOutcome.NotApplied<List<NativeRecord>>>(write(readBack = listOf(note(title = "Old title"))))
        assertEquals(NativeNotAppliedReason.NotAppliedAfterSuccess, outcome.reason)
        assertTrue(outcome.contradictsPostcondition)
    }

    @Test
    fun `an ambiguous send whose read-back shows the previous value is simply not applied`() = runBlocking<Unit> {
        val outcome = assertIs<NativeVerifiedWriteOutcome.NotApplied<List<NativeRecord>>>(
            write(send = NativeWriteSendResult.Ambiguous("Timed out."), readBack = listOf(note(title = "Old title"))),
        )
        assertEquals(NativeNotAppliedReason.NotAppliedAfterAmbiguous, outcome.reason)
        assertEquals("Timed out.", outcome.message)
        assertFalse(outcome.contradictsPostcondition, "An unconfirmed send that did not apply is no contradiction.")
    }

    @Test
    fun `a definite rejection is reported without a read-back`() = runBlocking<Unit> {
        var reads = 0
        val outcome = write(send = NativeWriteSendResult.Rejected("Not allowed."), onRead = { reads++ })
        assertEquals(NativeNotAppliedReason.Rejected, assertIs<NativeVerifiedWriteOutcome.NotApplied<List<NativeRecord>>>(outcome).reason)
        assertEquals(0, reads)
    }

    @Test
    fun `a failed read-back or an unidentified target is unknown`() = runBlocking<Unit> {
        assertIs<NativeVerifiedWriteOutcome.Unknown>(write(readFails = true))
        assertIs<NativeVerifiedWriteOutcome.Unknown>(write(readBack = emptyList()), "The target is missing.")
        assertIs<NativeVerifiedWriteOutcome.Unknown>(write(readBack = listOf(note("New title"), note("New title"))), "Two records claim the id.")
        assertIs<NativeVerifiedWriteOutcome.Unknown>(write(readBack = listOf(note("New title").copy(actionSafeIdentity = false))))
    }

    @Test
    fun `a thrown send is ambiguous and is read back`() = runBlocking<Unit> {
        var reads = 0
        val outcome = runNativeVerifiedWrite(
            send = { error("connection reset") },
            readBack = { reads++; listOf(note(title = "New title")) },
            verify = expectation()::verify,
            actionLabel = "Edit note",
        )
        assertIs<NativeVerifiedWriteOutcome.Confirmed<List<NativeRecord>>>(outcome)
        assertEquals(1, reads)
    }

    @Test
    fun `a pre-read difference or failure stops before sending`() = runBlocking<Unit> {
        listOf(NativeWritePreRead.Changed to NativeNotAppliedReason.PreconditionChanged, NativeWritePreRead.Unverified to NativeNotAppliedReason.PreconditionUnverified)
            .forEach { (preRead, reason) ->
                var sent = 0
                val outcome = runNativeVerifiedWrite(
                    send = { sent++; NativeWriteSendResult.Accepted },
                    readBack = { listOf(note("New title")) },
                    verify = expectation()::verify,
                    actionLabel = "Edit note",
                    preRead = { preRead },
                )
                assertEquals(reason, assertIs<NativeVerifiedWriteOutcome.NotApplied<List<NativeRecord>>>(outcome).reason)
                assertEquals(0, sent)
            }
        val thrown = runNativeVerifiedWrite(
            send = { NativeWriteSendResult.Accepted },
            readBack = { listOf(note("New title")) },
            verify = expectation()::verify,
            actionLabel = "Edit note",
            preRead = { error("offline") },
        )
        assertEquals(NativeNotAppliedReason.PreconditionUnverified, assertIs<NativeVerifiedWriteOutcome.NotApplied<List<NativeRecord>>>(thrown).reason)
    }

    @Test
    fun `cancellation between send and read-back stays cancellation and never reads`() {
        var reads = 0
        assertFailsWith<CancellationException> {
            runBlocking {
                runNativeVerifiedWrite(
                    send = { throw CancellationException("screen left") },
                    readBack = { reads++; listOf(note("New title")) },
                    verify = expectation()::verify,
                    actionLabel = "Edit note",
                )
            }
        }
        assertEquals(0, reads, "The caller treats the write as unknown and reconciles on its next authoritative read.")
        assertFailsWith<CancellationException> {
            runBlocking {
                runNativeVerifiedWrite(
                    send = { NativeWriteSendResult.Accepted },
                    readBack = { throw CancellationException("screen left") },
                    verify = expectation()::verify,
                    actionLabel = "Edit note",
                )
            }
        }
    }

    @Test
    fun `wire values compare as JSON`() {
        assertTrue(sameWireValue(JsonPrimitive(5), JsonPrimitive(5.0)))
        assertFalse(
            sameWireValue(JsonPrimitive(9007199254740992), JsonPrimitive(9007199254740993)),
            "Whole numbers past 2^53 stay distinct.",
        )
        assertTrue(sameWireValue(JsonPrimitive(9007199254740993), JsonPrimitive(9007199254740993)))
        assertFalse(sameWireValue(JsonPrimitive(5), JsonPrimitive(5.5)))
        assertFalse(sameWireValue(JsonPrimitive("5"), JsonPrimitive(5)), "A string is not a number.")
        assertFalse(sameWireValue(JsonPrimitive(true), JsonPrimitive("true")))
        assertTrue(sameWireValue(JsonNull, JsonNull))
        assertFalse(sameWireValue(null, JsonNull), "An absent field is not a stored null.")
    }

    private suspend fun write(
        send: NativeWriteSendResult = NativeWriteSendResult.Accepted,
        readBack: List<NativeRecord> = listOf(note("New title")),
        readFails: Boolean = false,
        onRead: () -> Unit = {},
    ): NativeVerifiedWriteOutcome<List<NativeRecord>> = runNativeVerifiedWrite(
        send = { send },
        readBack = {
            onRead()
            if (readFails) error("read failed")
            readBack
        },
        verify = expectation()::verify,
        actionLabel = "Edit note",
    )

    private fun expectation() = NativeWriteExpectation(
        targetId = "7",
        submitted = mapOf("title" to JsonPrimitive("New title")),
        untouchedBaseline = mapOf("body" to JsonPrimitive("Synthetic body")),
        previous = mapOf("title" to JsonPrimitive("Old title")),
    )

    private fun note(title: String, body: String = "Synthetic body"): NativeRecord {
        val wire: Map<String, JsonElement> = mapOf("id" to JsonPrimitive(7), "title" to JsonPrimitive(title), "body" to JsonPrimitive(body))
        return NativeRecord("7", mapOf("id" to "7", "title" to title, "body" to body), wireValues = wire)
    }
}
