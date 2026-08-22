package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.runtime.saveable.SaverScope

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
    fun `unknown reply delivery remains blocked until refresh`() {
        val drafts = SupportSettingsDraftState()
        drafts.updateReplyDraft("report-a", "Do not resend blindly")
        drafts.updateReplyRefreshRequirement("report-a", true)

        assertEquals(true, drafts.replyRequiresRefresh("report-a"))

        drafts.clearReplyRefreshRequirements()

        assertEquals(false, drafts.replyRequiresRefresh("report-a"))
        assertEquals("Do not resend blindly", drafts.replyDraft("report-a"))
    }

    @Test
    fun `restoration keeps recovery guard but not private drafts`() {
        val drafts = SupportSettingsDraftState()
        drafts.updateReportDraft("Private report text")
        drafts.updateReplyDraft("report-a", "Private reply text")
        drafts.updateReplyRefreshRequirement("report-a", true)

        val saved = with(SupportSettingsDraftState.Saver) {
            with(SaverScope { true }) { save(drafts) }
        }
        val restored = requireNotNull(saved?.let(SupportSettingsDraftState.Saver::restore))

        assertEquals(true, restored.replyRequiresRefresh("report-a"))
        assertEquals("", restored.reportDraft)
        assertEquals("", restored.replyDraft("report-a"))
    }
}
