package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFileSyncRetryPolicyTest {
    @Test
    fun `automatic retries wait with exponential delays`() {
        val lastAttempt = 1_000_000L

        assertFalse(desktopAutomaticFileSyncRetryEligible(3, lastAttempt, lastAttempt + 7L * 60L * 1_000L))
        assertTrue(desktopAutomaticFileSyncRetryEligible(3, lastAttempt, lastAttempt + 8L * 60L * 1_000L))
    }

    @Test
    fun `fifth automatic failure remains terminal until requested recovery`() {
        assertFalse(
            desktopAutomaticFileSyncRetryEligible(
                attemptCount = 5,
                lastAttemptEpochMillis = 1_000_000L,
                nowEpochMillis = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `missing timestamp and backwards clock fail closed`() {
        assertFalse(desktopAutomaticFileSyncRetryEligible(1, null, 1_000_000L))
        assertFalse(desktopAutomaticFileSyncRetryEligible(1, 1_000_001L, 1_000_000L))
    }
}
