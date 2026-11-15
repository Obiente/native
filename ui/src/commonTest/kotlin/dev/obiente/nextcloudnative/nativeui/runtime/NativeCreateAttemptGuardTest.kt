package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Creates of a synthetic "ledger" app: a new entry is never added twice by accident. */
class NativeCreateAttemptGuardTest {
    @Test
    fun `an unknown create is recorded before it leaves and holds the next one back after a restart`() = runBlocking<Unit> {
        val store = MemoryAttempts()
        var calls = 0
        var recordedWhenSent: NativeSendAttempt? = null
        val lost = NativeActionExecutor {
            calls++
            recordedWhenSent = store.value
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        val first = NativeCreateAttemptGuard(store, clock = { 1L }).apply { restore() }
        assertEquals(NativeCreateOutcome.Ran(confirmed = false), first.submit(request(), NativeEditorWriteSession(), lost) {})
        assertEquals(NativeSendAttemptState.Prepared, recordedWhenSent?.state, "The attempt is on record before the request leaves.")
        assertEquals(NativeSendAttemptState.MaybeSent, store.value?.state)

        // The screen was left and opened again, or the app restarted: a new guard, the same record.
        val later = NativeCreateAttemptGuard(store, clock = { 2L })
        val session = NativeEditorWriteSession()
        assertEquals(NativeCreateOutcome.NotSent(null), later.submit(request(), session, lost) {}, "Nothing runs before the record is read.")
        later.noteRecords(1)
        later.restore()
        val unread = assertIs<NativeCreateOutcome.NotSent>(later.submit(request(), session, lost) {})
        assertTrue(unread.message.orEmpty().contains("Refresh"), "Nothing is added before the list was read again.")
        assertTrue(assertIs<NativeCreateOutcome.NotSent>(later.submit(request(), session, lost) {}).message.orEmpty().contains("Refresh"))
        assertEquals(1, calls, "Pressing again does not stand in for that read.")
        later.noteRecords(2)
        val held = assertIs<NativeCreateOutcome.NotSent>(later.submit(request(), session, lost) {})
        assertTrue(held.message.orEmpty().contains("may already have been added"))
        assertEquals(1, calls, "The first press only explains.")

        val refused = NativeActionExecutor { calls++; NativeActionExecutionResult.Failure("Closed.", NativeActionFailureOutcome.Rejected) }
        assertEquals(NativeCreateOutcome.Ran(confirmed = false), later.submit(request(), session, refused) {})
        assertEquals(2, calls, "Pressing again is the decision to add another.")
        assertNotNull(later.unconfirmed, "A refused second attempt says nothing about the first.")
        assertNotNull(store.value)

        assertIs<NativeCreateOutcome.NotSent>(later.submit(request(), NativeEditorWriteSession(), refused) {})
        val accepted = NativeActionExecutor { calls++; NativeActionExecutionResult.Success() }
        assertEquals(NativeCreateOutcome.Ran(confirmed = true), later.submit(request(), NativeEditorWriteSession(), accepted) {})
        assertNull(later.unconfirmed)
        assertNull(store.value, "A confirmed create leaves no record.")
    }

    @Test
    fun `nothing is created without a record and an unreadable record is treated as an earlier attempt`() = runBlocking<Unit> {
        var calls = 0
        val executor = NativeActionExecutor { calls++; NativeActionExecutionResult.Success() }
        val unwritable = NativeCreateAttemptGuard(MemoryAttempts(failSave = true)).apply { restore() }
        val notRecorded = assertIs<NativeCreateOutcome.NotSent>(unwritable.submit(request(), NativeEditorWriteSession(), executor) {})
        assertTrue(notRecorded.message.orEmpty().contains("could not keep a record"))

        val unreadable = NativeCreateAttemptGuard(MemoryAttempts(failLoad = true)).apply { noteRecords(1); restore() }
        assertIs<NativeCreateOutcome.NotSent>(unreadable.submit(request(), NativeEditorWriteSession(), executor) {})
        unreadable.noteRecords(2)
        assertIs<NativeCreateOutcome.NotSent>(unreadable.submit(request(), NativeEditorWriteSession(), executor) {})
        assertEquals(0, calls)
        assertEquals(NativeCreateOutcome.Ran(confirmed = true), unreadable.submit(request(), NativeEditorWriteSession(), executor) {})
    }

    private fun request() = NativeActionRequest.Submit(
        action = ActionSpec(
            "entries.create", "Add entry", "entries",
            ApiBinding(HttpMethod.POST, "/apps/ledger/api/books/{bookId}/entries", "entries.create"),
            ActionIntent.create, ActionRisk.mutating, false, Confidence.high,
        ),
        values = mapOf("bookId" to "7", "amount" to "12"),
        confirmed = true,
    )

    private class MemoryAttempts(private val failSave: Boolean = false, private val failLoad: Boolean = false) : NativeSendAttemptStore {
        var value: NativeSendAttempt? = null

        override suspend fun load(): NativeSendAttempt? {
            check(!failLoad) { "Synthetic storage is unreadable." }
            return value
        }

        override suspend fun save(attempt: NativeSendAttempt) {
            check(!failSave) { "Synthetic storage is full." }
            value = attempt
        }

        override suspend fun clear() {
            value = null
        }
    }
}
