package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmSupportReplyRecoveryTest {
    @Test
    fun bindsDeliveryEvidenceToTheAttemptedReplyBody() {
        val recovery = assertNotNull(createSupportReplyRecoveryMarker(
            reporterMessageIds = listOf("reporter-before"),
            attemptedReply = "The local private reply",
            salt = "c".repeat(32),
        ))

        assertFalse(recovery.observeReporterMessage("reporter-other", "An unrelated reply").attemptedReplyMatch)
        assertTrue(recovery.observeReporterMessage("reporter-local", "The local private reply").attemptedReplyMatch)
    }
}
