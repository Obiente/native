package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SupportReplyRecoveryTest {
    @Test
    fun supportsRecoveryThroughoutTheAcceptedConversationLimit() {
        val reporterIds = (1 until MAX_SUPPORT_CONVERSATION_MESSAGES).map { "reporter-$it" }
        val identity = SupportReplyRecoveryIdentity("a".repeat(32), "b".repeat(64))
        val recovery = assertNotNull(SupportReplyRecoveryMarker.awaiting(reporterIds, identity))
        val persisted = recovery.persisted()
        val baseline = reporterIds.map { SupportReplyRecoveryObservation(it, false) }

        assertNull(persisted.reporterMessageIdsBeforeAttempt)
        assertEquals(reporterIds.size, persisted.reporterMessageCountBeforeAttempt)
        assertEquals(reporterIds.last(), persisted.lastReporterMessageIdBeforeAttempt)
        assertNull(SupportReplyRecoveryMarker.restored(persisted).afterAuthoritativeGet(baseline))
        assertEquals(
            SupportDiagnosticsReplyRecoveryState.DeliveredAwaitingAcknowledgement,
            recovery.afterAuthoritativeGet(baseline + SupportReplyRecoveryObservation("reporter-1000", true))
                ?.presentationState,
        )
        assertNull(recovery.afterAuthoritativeGet(
            baseline + SupportReplyRecoveryObservation("reporter-1000", false),
        ))
        assertNotNull(SupportReplyRecoveryMarker.awaiting(reporterIds + "reporter-1000", identity))
        assertNull(SupportReplyRecoveryMarker.awaiting(
            reporterIds + listOf("reporter-1000", "reporter-1001"), identity,
        ))
    }

    @Test
    fun legacyUnknownRecoveryCanBeAcknowledgedAfterAnAuthoritativeRefresh() {
        val reconciled = assertNotNull(SupportReplyRecoveryMarker.legacyUnknown().afterAuthoritativeGet(emptyList()))

        assertEquals(
            SupportDiagnosticsReplyRecoveryState.DeliveryUnknownAwaitingAcknowledgement,
            reconciled.presentationState,
        )
        assertEquals(
            SupportDiagnosticsReplyRecoveryState.DeliveryUnknownAwaitingAcknowledgement,
            SupportReplyRecoveryMarker.restored(reconciled.persisted()).presentationState,
        )
    }
}
