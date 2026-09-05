package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDurableUploadTerminalCapabilityTest {
    @Test
    fun `terminal worker retries while capability cleanup remains uncommitted`() {
        var releaseAttempts = 0

        val result = resultAfterDurableUploadCapabilityRelease(
            releaseCapability = {
                releaseAttempts += 1
                false
            },
            releasedResult = "finished",
            retainedResult = "retry",
        )

        assertEquals("retry", result)
        assertEquals(1, releaseAttempts)
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
    fun `removed account retries after terminal transition when release is retained`() {
        val events = mutableListOf<String>()

        val result = failQueuedDurableUploadForUnavailableAccount(
            transitionToFailed = { events += "fail" },
            releaseSelection = {
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
