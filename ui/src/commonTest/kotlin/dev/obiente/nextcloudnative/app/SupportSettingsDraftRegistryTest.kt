package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SupportSettingsDraftRegistryTest {
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
}
