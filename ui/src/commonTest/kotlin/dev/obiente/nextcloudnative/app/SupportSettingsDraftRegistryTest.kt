package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SupportSettingsDraftRegistryTest {
    @Test
    fun `login draft survives state reacquisition without saved instance data`() {
        val first = SupportSettingsDraftRegistry.loginState()
        try {
            first.updateReportDraft("Private login report draft")

            val restored = SupportSettingsDraftRegistry.loginState()

            assertSame(first, restored)
            assertEquals("Private login report draft", restored.reportDraft)
        } finally {
            first.clearDrafts()
        }
    }

    @Test
    fun `login draft is cleared after identity handoff`() {
        val draft = SupportSettingsDraftRegistry.loginState()
        draft.updateReportDraft("First person's private report")
        draft.updateReplyDraft("retained-report", "First person's private reply")

        draft.clearDrafts()

        assertFalse(SupportSettingsDraftRegistry.loginState().hasDraftContent())
    }

    @Test
    fun `account draft survives state reacquisition without saved instance data`() {
        val account = "a".repeat(64)
        val first = SupportSettingsDraftRegistry.stateFor(account)
        first.updateReportDraft("Private report draft")
        first.updateReplyDraft("report-a", "Private reply draft")

        val restored = SupportSettingsDraftRegistry.stateFor(account)

        assertSame(first, restored)
        assertEquals("Private report draft", restored.reportDraft)
        assertEquals("Private reply draft", restored.replyDraft("report-a"))
    }

    @Test
    fun `draft holders remain separated by account digest`() {
        val first = SupportSettingsDraftRegistry.stateFor("b".repeat(64))
        val second = SupportSettingsDraftRegistry.stateFor("c".repeat(64))

        assertNotSame(first, second)
    }

    @Test
    fun `opening more accounts never evicts a non-empty draft`() {
        val retainedAccount = "d".repeat(64)
        val retained = SupportSettingsDraftRegistry.stateFor(retainedAccount)
        retained.updateReportDraft("Unsaved private report")
        listOf('e', 'f', '0', '1', '2').forEach { marker ->
            SupportSettingsDraftRegistry.stateFor(marker.toString().repeat(64))
        }

        assertSame(retained, SupportSettingsDraftRegistry.stateFor(retainedAccount))
        assertEquals("Unsaved private report", retained.reportDraft)
    }
}
