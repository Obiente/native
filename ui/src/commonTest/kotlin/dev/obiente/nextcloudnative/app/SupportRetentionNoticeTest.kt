package dev.obiente.nextcloudnative.app

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportRetentionNoticeTest {
    @BeforeTest
    @AfterTest
    fun clearPendingRequest() {
        SupportNewReportRequests.consume()
    }

    @Test
    fun `fresh install without earlier support storage never shows the notice`() {
        val store = InMemorySupportRetentionNoticeStore()

        store.recordSupportRetentionInstallHistory(supportStorageExistedAtLaunch = false)
        // The first launch created the support directory, so later launches see it.
        store.recordSupportRetentionInstallHistory(supportStorageExistedAtLaunch = true)

        assertEquals(SupportRetentionNoticeState.NotApplicable.storedValue, store.value)
        assertFalse(SupportRetentionNoticeController(store).visible)
    }

    @Test
    fun `install upgraded from a build with direct support shows the notice once`() {
        val store = InMemorySupportRetentionNoticeStore()

        store.recordSupportRetentionInstallHistory(supportStorageExistedAtLaunch = true)

        assertEquals(SupportRetentionNoticeState.Pending.storedValue, store.value)
        assertTrue(SupportRetentionNoticeController(store).visible)
    }

    @Test
    fun `dismiss hides the notice permanently across a restart`() {
        val store = InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Pending.storedValue)
        val controller = SupportRetentionNoticeController(store)

        controller.dismiss()

        assertFalse(controller.visible)
        assertEquals(SupportRetentionNoticeState.Dismissed.storedValue, store.value)
        store.recordSupportRetentionInstallHistory(supportStorageExistedAtLaunch = true)
        assertFalse(SupportRetentionNoticeController(store).visible)
        assertFalse(SupportNewReportRequests.consume())
    }

    @Test
    fun `send a new report hides the notice permanently and requests the report flow once`() {
        val store = InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Pending.storedValue)
        val controller = SupportRetentionNoticeController(store)

        controller.sendNewReport()

        assertFalse(controller.visible)
        assertEquals(SupportRetentionNoticeState.Dismissed.storedValue, store.value)
        assertFalse(SupportRetentionNoticeController(store).visible)
        assertTrue(SupportNewReportRequests.requested.value)
        assertTrue(SupportNewReportRequests.consume())
        assertFalse(SupportNewReportRequests.consume())
    }

    @Test
    fun `opening the report flow from the notice does not prefill any earlier report data`() {
        val drafts = SupportSettingsDraftState()
        val controller = SupportRetentionNoticeController(
            InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Pending.storedValue),
        )

        controller.sendNewReport()

        assertTrue(SupportNewReportRequests.consume())
        assertEquals("", drafts.reportDraft)
    }

    @Test
    fun `undecided or unreadable values never show the notice`() {
        assertFalse(SupportRetentionNoticeController(InMemorySupportRetentionNoticeStore()).visible)
        assertFalse(SupportRetentionNoticeController(InMemorySupportRetentionNoticeStore("unexpected")).visible)
    }

    @Test
    fun `failed dismissal hides the notice for this session and keeps the stored state`() {
        val store = InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Pending.storedValue, writable = false)
        val controller = SupportRetentionNoticeController(store)

        controller.dismiss()

        assertFalse(controller.visible)
        assertEquals(SupportRetentionNoticeState.Pending.storedValue, store.value)
    }

    @Test
    fun `notice copy matches the approved announcement`() {
        assertEquals("Support reports are now kept longer", SUPPORT_RETENTION_NOTICE_TITLE)
        assertEquals(
            "We used to delete support reports after 30 days to protect your privacy, but that did not leave us " +
                "enough time to act on them. Reports are now kept until you or Obiente Support delete them. " +
                "If you sent a report and have not heard back, please send it again.",
            SUPPORT_RETENTION_NOTICE_BODY,
        )
        assertEquals("Send a new report", SUPPORT_RETENTION_NOTICE_SEND)
        assertEquals("Dismiss", SUPPORT_RETENTION_NOTICE_DISMISS)
        assertNull(SUPPORT_RETENTION_NOTICE_BODY.firstOrNull { it.code > 0x7e })
    }
}

internal class InMemorySupportRetentionNoticeStore(
    var value: String? = null,
    private val writable: Boolean = true,
) : SupportRetentionNoticeStore {
    override fun read(): String? = value

    override fun write(value: String): Boolean {
        if (writable) this.value = value
        return writable
    }
}
