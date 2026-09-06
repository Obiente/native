package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDurableUploadTerminalCapabilityTest {
    @Test
    fun `terminal worker retries while capability cleanup remains uncommitted`() {
        var releaseAttempts = 0
        var recoveryRequests = 0

        val result = resultAfterDurableUploadCapabilityReleaseOrQuarantine(
            releaseCapability = {
                releaseAttempts += 1
                false
            },
            onCleanupRetained = { recoveryRequests += 1 },
            releasedResult = "finished",
            retainedResult = "retry",
        )

        assertEquals("retry", result)
        assertEquals(1, releaseAttempts)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `terminal worker finishes after capability cleanup commits`() {
        var releaseAttempts = 0

        val result = resultAfterDurableUploadCapabilityRelease(
            releaseCapability = {
                releaseAttempts += 1
                true
            },
            releasedResult = "finished",
            retainedResult = "retry",
        )

        assertEquals("finished", result)
        assertEquals(1, releaseAttempts)
    }

    @Test
    fun `terminal worker finishes and commits cleanup after capability quarantine`() {
        val events = mutableListOf<String>()

        val result = resultAfterDurableUploadCapabilityReleaseOrQuarantine(
            releaseCapability = { onQuarantined ->
                events += "quarantine"
                onQuarantined()
                false
            },
            completeCapabilityCleanup = { events += "complete" },
            onCleanupRetained = { events += "retry" },
            releasedResult = "finished",
            retainedResult = "retry",
        )

        assertEquals("finished", result)
        assertEquals(listOf("quarantine", "complete"), events)
    }

    @Test
    fun `terminal status dismissal accepts quarantine but not transient cleanup failure`() {
        val events = mutableListOf<String>()
        assertTrue(
            dismissTerminalDurableUploadStatus(
                release = { onQuarantined ->
                    onQuarantined()
                    false
                },
                removeStatus = { events += "remove" },
            ),
        )
        assertFalse(
            dismissTerminalDurableUploadStatus(
                release = { false },
                removeStatus = { events += "unexpected" },
            ),
        )
        assertEquals(listOf("remove"), events)
    }

    @Test
    fun `removed account retries after terminal transition when release is retained`() {
        val events = mutableListOf<String>()

        val result = failQueuedDurableUploadForUnavailableAccount(
            transitionToFailed = { events += "fail" },
            releaseSelection = { _ ->
                events += "release"
                false
            },
            recordFailure = { events += "diagnose" },
            failureResult = "failed",
            retryResult = "retry",
        )

        assertEquals("retry", result)
        assertEquals(listOf("fail", "release", "diagnose"), events)
    }
}
