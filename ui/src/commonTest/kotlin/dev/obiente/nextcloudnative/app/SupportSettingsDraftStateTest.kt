package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SupportSettingsDraftStateTest {
    @Test
    fun `reply drafts remain bound to their report`() {
        val drafts = SupportSettingsDraftState()

        drafts.updateReplyDraft("report-a", "Private details for A")
        drafts.updateReplyDraft("report-b", "Different details for B")

        assertEquals("Private details for A", drafts.replyDraft("report-a"))
        assertEquals("Different details for B", drafts.replyDraft("report-b"))
    }

    @Test
    fun `removed reports discard their reply drafts`() {
        val drafts = SupportSettingsDraftState()
        drafts.updateReplyDraft("retained", "Keep")
        drafts.updateReplyDraft("deleted", "Remove")

        drafts.retainReplyDrafts(setOf("retained"))

        assertEquals("Keep", drafts.replyDraft("retained"))
        assertEquals("", drafts.replyDraft("deleted"))
    }

    @Test
    fun `new report draft is separate from conversation drafts`() {
        val drafts = SupportSettingsDraftState()

        drafts.updateReportDraft("New report details")
        drafts.updateReplyDraft("report-a", "Existing request reply")

        assertEquals("New report details", drafts.reportDraft)
        assertEquals("Existing request reply", drafts.replyDraft("report-a"))
    }

    @Test
    fun `reply limit counts UTF-8 bytes`() {
        assertEquals(4, supportReplyMessageByteCount("\uD83D\uDE42"))
        assertEquals(false, supportReplyMessageIsWithinLimit("\uD83D\uDE42".repeat(2_049)))
    }

}
