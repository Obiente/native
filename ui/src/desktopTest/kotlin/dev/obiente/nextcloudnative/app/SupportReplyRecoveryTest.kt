package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SupportReplyRecoveryTest {
    @Test
    fun supportsRecoveryThroughoutTheAcceptedConversationLimit() {
        val reporterIds = (1 until MAX_SUPPORT_CONVERSATION_MESSAGES).map { "reporter-$it" }
        val recovery = assertNotNull(SupportReplyRecoveryMarker.awaiting(reporterIds))
        val persisted = recovery.persisted()

        assertNull(persisted.reporterMessageIdsBeforeAttempt)
        assertEquals(reporterIds.size, persisted.reporterMessageCountBeforeAttempt)
        assertEquals(reporterIds.last(), persisted.lastReporterMessageIdBeforeAttempt)
        assertNull(SupportReplyRecoveryMarker.restored(persisted).afterAuthoritativeGet(reporterIds))
        assertEquals(
            SupportDiagnosticsReplyRecoveryState.DeliveredAwaitingAcknowledgement,
            recovery.afterAuthoritativeGet(reporterIds + "reporter-1000")?.presentationState,
        )
        assertNotNull(SupportReplyRecoveryMarker.awaiting(reporterIds + "reporter-1000"))
        assertNull(SupportReplyRecoveryMarker.awaiting(reporterIds + listOf("reporter-1000", "reporter-1001")))
    }
}
