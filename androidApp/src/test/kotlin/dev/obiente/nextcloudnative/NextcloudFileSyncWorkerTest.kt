package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncRejectionScope
import dev.obiente.nextcloudnative.app.SupportDiagnosticValuePrivacy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextcloudFileSyncWorkerTest {
    @Test
    fun `conflict notification preserves totals beyond the review page`() {
        assertEquals("1 sync conflict needs review.", syncConflictNotificationDetail(1))
        assertEquals("8 sync conflicts need review.", syncConflictNotificationDetail(8))
        assertFailsWith<IllegalArgumentException> { syncConflictNotificationDetail(0) }
    }

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

    @Test
    fun `preflight rejections retain their safe reason and distinct scope`() {
        val reason = "This detected media-folder pair is not upload-only. Remove it and add it again."
        val fields = backgroundSyncCompletionDiagnosticFields(
            pairId = "pair-1",
            failedCount = 3,
            conflictCount = 0,
            result = FileSyncCenterActionResult.Rejected(reason, FileSyncRejectionScope.Preflight),
        )

        assertEquals("preflight", fields.single { it.name == "failure_scope" }.value)
        assertEquals(reason, fields.single { it.name == "rejection_reason" }.value)
        assertEquals("3", fields.single { it.name == "failed_count" }.value)
        assertEquals(
            SupportDiagnosticValuePrivacy.Identifier,
            fields.single { it.name == "pair" }.privacy,
        )
    }

    @Test
    fun `item failures are not mislabeled with a preflight reason`() {
        val fields = backgroundSyncCompletionDiagnosticFields(
            pairId = "pair-1",
            failedCount = 2,
            conflictCount = 1,
            result = FileSyncCenterActionResult.Rejected("2 operations failed."),
        )

        assertEquals("items", fields.single { it.name == "failure_scope" }.value)
        assertEquals(null, fields.singleOrNull { it.name == "rejection_reason" })
    }
}
