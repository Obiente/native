package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class DesktopAccountCleanupRetryTest {
    @Test
    fun retryStopsAsSoonAsTheCommittedCleanupIsFinished() = runBlocking {
        var attempts = 0
        var waits = 0

        retryDesktopAccountSyncPairCleanupsBounded(
            maximumAttempts = 3,
            waitBeforeNextAttempt = { waits += 1 },
        ) {
            attempts += 1
            attempts < 2
        }

        assertEquals(2, attempts)
        assertEquals(1, waits)
    }

    @Test
    fun retryStopsAtTheBoundAndLeavesDurableRecoveryToRestart() = runBlocking {
        var attempts = 0
        var waits = 0

        retryDesktopAccountSyncPairCleanupsBounded(
            maximumAttempts = 3,
            waitBeforeNextAttempt = { waits += 1 },
        ) {
            attempts += 1
            true
        }

        assertEquals(3, attempts)
        assertEquals(2, waits)
    }
}
