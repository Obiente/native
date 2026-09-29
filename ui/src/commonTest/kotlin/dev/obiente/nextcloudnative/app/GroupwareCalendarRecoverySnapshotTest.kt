package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupwareCalendarRecoverySnapshotTest {
    private val accountScope = durableMutationAccountScope(
        NextcloudSession("https://fixture.invalid", "synthetic-user", "synthetic-password"),
    )

    @Test
    fun delayedEffectCannotMixEarlierDecodeWithNewValidRecoveryRecord() = runBlocking {
        val href = "/remote.php/dav/calendars/synthetic-user/personal/event.ics"
        val draft = EventDraft("Synthetic Event", "2026-09-28", "10:00", "11:00", false, "", "", "FREQ=WEEKLY")
        val create = CalendarMutationPostcondition.Upsert(
            href, href.substringBeforeLast('/') + "/", "synthetic-event", null, draft,
        )
        listOf(create, create.copy(previousEtag = "old-etag"), CalendarMutationPostcondition.Delete(href))
            .forEach { postcondition ->
                var liveRecord: String? = null
                val captured = calendarRecoverySnapshot(liveRecord, accountScope)
                val releaseEffect = CompletableDeferred<Unit>()
                var recoveryDialogShown = false
                val effect = launch(start = CoroutineStart.UNDISPATCHED) {
                    releaseEffect.await()
                    // Old code paired this live non-null record with captured.postcondition == null.
                    assertTrue(liveRecord != null)
                    recoveryDialogShown = captured.unreadable
                }
                liveRecord = CalendarMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
                releaseEffect.complete(Unit)
                effect.join()
                assertFalse(recoveryDialogShown)
                val current = calendarRecoverySnapshot(liveRecord, accountScope)
                assertFalse(current.unreadable)
                assertFalse(current.readyToVerify(loaded = true, requestRunning = true))
                assertTrue(current.readyToVerify(loaded = true, requestRunning = false))
                assertFalse(current.readyToVerify(loaded = false, requestRunning = false))
                assertTrue(current != captured)
                assertEquals(postcondition, current.postcondition)
                assertFalse(calendarRecoverySnapshot(null, accountScope).hasRecord)
            }
    }

    @Test
    fun malformedAndOtherAccountRecoveryRemainProtected() {
        assertTrue(calendarRecoverySnapshot("invalid", accountScope).unreadable)
        val encoded = CalendarMutationRecoveryState(accountScope,
            CalendarMutationPostcondition.Delete("/calendar/one.ics")).encodeForSavedState()
        val anotherScope = durableMutationAccountScope(
            NextcloudSession("https://fixture.invalid", "another-user", "synthetic-password"),
        )
        assertTrue(calendarRecoverySnapshot(encoded, anotherScope).unreadable)
    }
}
