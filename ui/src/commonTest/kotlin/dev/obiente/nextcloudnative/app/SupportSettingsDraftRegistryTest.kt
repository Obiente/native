package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
    fun `account draft can be cleared after logout`() {
        val account = "3".repeat(64)
        SupportSettingsDraftRegistry.stateFor(account).updateReportDraft("Previous user's private report")

        SupportSettingsDraftRegistry.stateFor(account).clearDrafts()

        assertFalse(SupportSettingsDraftRegistry.stateFor(account).hasDraftContent())
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

    @Test
    fun `retirement clears the old state and preserves other accounts and login`() {
        val targetAccount = "6".repeat(64)
        val otherAccount = "7".repeat(64)
        val target = SupportSettingsDraftRegistry.stateFor(targetAccount)
        val other = SupportSettingsDraftRegistry.stateFor(otherAccount)
        val login = SupportSettingsDraftRegistry.loginState()
        try {
            target.updateReportDraft("Removed account report")
            target.updateReplyDraft("removed", "Removed account reply")
            other.updateReportDraft("Other account report")
            login.updateReportDraft("Login report")

            SupportSettingsDraftRegistry.retireAccount(targetAccount)

            assertFalse(target.hasDraftContent())
            assertEquals("Other account report", other.reportDraft)
            assertEquals("Login report", login.reportDraft)
            val whileClosed = SupportSettingsDraftRegistry.stateFor(targetAccount)
            whileClosed.updateReportDraft("Must not survive")
            assertFalse(whileClosed.hasDraftContent())

            SupportSettingsDraftRegistry.activateAccount(targetAccount)
            val current = SupportSettingsDraftRegistry.stateFor(targetAccount)
            assertNotSame(target, current)
            assertFalse(current.hasDraftContent())
            target.updateReportDraft("Late old report")
            whileClosed.updateReplyDraft("late", "Late closed reply")
            assertFalse(current.hasDraftContent())
        } finally {
            SupportSettingsDraftRegistry.retireAccount(targetAccount)
            SupportSettingsDraftRegistry.activateAccount(targetAccount)
            SupportSettingsDraftRegistry.retireAccount(otherAccount)
            SupportSettingsDraftRegistry.activateAccount(otherAccount)
            login.clearDrafts()
        }
    }

    @Test
    fun `concurrent draft writes cannot survive account retirement`() = runBlocking {
        val account = "8".repeat(64)
        val state = SupportSettingsDraftRegistry.stateFor(account)
        try {
            val writers = List(8) { worker ->
                async(Dispatchers.Default) {
                    repeat(100) { iteration ->
                        state.updateReportDraft("report-$worker-$iteration")
                        state.updateReplyDraft("reply-$worker", "reply-$iteration")
                    }
                }
            }
            val retirement = async(Dispatchers.Default) {
                SupportSettingsDraftRegistry.retireAccount(account)
            }
            (writers + retirement).awaitAll()

            assertFalse(state.hasDraftContent())
            assertFalse(SupportSettingsDraftRegistry.stateFor(account).hasDraftContent())
        } finally {
            SupportSettingsDraftRegistry.retireAccount(account)
            SupportSettingsDraftRegistry.activateAccount(account)
        }
    }
}
