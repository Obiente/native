package dev.obiente.nextcloudnative

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AndroidFileSyncCapabilityStartupRecoveryTest {
    @Test fun pendingCleanupReceivesDurableScheduleAfterStartupReconciliation() = runBlocking {
        var schedules = 0
        var reconciled = false
        runAndroidFileSyncCapabilityStartupRecovery({}, { reconciled = true; true },
            { check(reconciled); schedules++ }, { throw AssertionError(it) })
        assertEquals(1, schedules)
        runAndroidFileSyncCapabilityStartupRecovery({}, { false },
            { schedules++ }, { throw AssertionError(it) })
        assertEquals(1, schedules)
    }

    @Test fun failedReconciliationStillSchedulesRecoveryAndRetainsFailure() = runBlocking {
        val failure = IOException("Synthetic local recovery failure")
        var schedules = 0
        var reported: Exception? = null
        runAndroidFileSyncCapabilityStartupRecovery({}, { throw failure },
            { schedules++ }, { reported = it })
        assertEquals(1, schedules)
        assertSame(failure, reported)
    }

    @Test fun cancellationDoesNotBecomeFailureOrScheduleNewWork(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            runAndroidFileSyncCapabilityStartupRecovery({}, { throw CancellationException() },
                { error("Cancellation cannot schedule recovery") }, { error("Cancellation cannot report failure") })
        }
    }
}
