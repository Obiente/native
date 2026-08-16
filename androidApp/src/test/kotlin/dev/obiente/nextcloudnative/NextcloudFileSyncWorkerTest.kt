package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextcloudFileSyncWorkerTest {
    @Test
    fun `run failures receive only bounded immediate retries`() {
        assertEquals(BackgroundSyncWorkerDisposition.Retry, backgroundSyncFailureDisposition(0))
        assertEquals(BackgroundSyncWorkerDisposition.Retry, backgroundSyncFailureDisposition(1))
        assertEquals(BackgroundSyncWorkerDisposition.WaitForNextPeriod, backgroundSyncFailureDisposition(2))
        assertEquals(BackgroundSyncWorkerDisposition.WaitForNextPeriod, backgroundSyncFailureDisposition(20))
    }

    @Test
    fun `negative WorkManager attempt counts fail closed`() {
        assertFailsWith<IllegalArgumentException> { backgroundSyncFailureDisposition(-1) }
    }

    @Test
    fun `persisted item failures wait for the next periodic run`() {
        assertEquals(
            BackgroundSyncWorkerDisposition.WaitForNextPeriod,
            backgroundSyncCompletionDisposition(failedCount = 1, resultRejected = false),
        )
        assertEquals(
            BackgroundSyncWorkerDisposition.WaitForNextPeriod,
            backgroundSyncCompletionDisposition(failedCount = 0, resultRejected = true),
        )
        assertEquals(
            BackgroundSyncWorkerDisposition.Complete,
            backgroundSyncCompletionDisposition(failedCount = 0, resultRejected = false),
        )
        assertFailsWith<IllegalArgumentException> {
            backgroundSyncCompletionDisposition(failedCount = -1, resultRejected = false)
        }
    }
}
