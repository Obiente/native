package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class AccountPrivateMemoryLifecycleTest {
    @Test
    fun `aggregate retirement purges private stores and rejects their old producers`() {
        val session = NextcloudSession(
            serverUrl = "https://private-memory-lifecycle.example.test",
            loginName = "private-memory-lifecycle-test",
            appPassword = "password",
        )
        val account = session.accountId.storageKey
        val dynamicKey = dynamicScreenCacheKey(session, "dashboard", "widgets", null, emptyMap())
        val previewKey = PreviewCacheKey(account, "core-preview", 701L, "preview-v1", 320, 320)
        val dynamicProducer = requireNotNull(sharedDynamicNativeMemoryCache.producer(dynamicKey))
        val previewProducer = requireNotNull(sharedPreviewMemoryCache.producer(previewKey))
        val notesProducer = requireNotNull(sharedNextcloudNotesCache.producer(session))
        val drafts = SupportSettingsDraftRegistry.stateFor(session)
        try {
            sharedDynamicNativeMemoryCache.storeScreen(
                dynamicKey,
                dynamicSnapshot(1),
                dynamicProducer,
            )
            sharedPreviewMemoryCache.put(previewKey, byteArrayOf(1), previewProducer)
            sharedNextcloudNotesCache.storeDetail(session, note(1, "Before"), notesProducer)
            drafts.updateReportDraft("Private report")

            AccountPrivateMemoryLifecycle.retireAccount(account)

            assertNull(sharedDynamicNativeMemoryCache.screen(dynamicKey))
            assertNull(sharedPreviewMemoryCache.get(previewKey))
            assertNull(sharedNextcloudNotesCache.detail(session, 1L))
            assertFalse(drafts.hasDraftContent())

            AccountPrivateMemoryLifecycle.activateAccount(account)
            val currentDynamicProducer = requireNotNull(sharedDynamicNativeMemoryCache.producer(dynamicKey))
            val currentPreviewProducer = requireNotNull(sharedPreviewMemoryCache.producer(previewKey))
            val currentNotesProducer = requireNotNull(sharedNextcloudNotesCache.producer(session))
            val currentDrafts = SupportSettingsDraftRegistry.stateFor(session)
            assertNotSame(drafts, currentDrafts)

            sharedDynamicNativeMemoryCache.storeScreen(
                dynamicKey,
                dynamicSnapshot(2),
                currentDynamicProducer,
            )
            sharedPreviewMemoryCache.put(previewKey, byteArrayOf(2), currentPreviewProducer)
            sharedNextcloudNotesCache.storeDetail(session, note(1, "Current"), currentNotesProducer)
            currentDrafts.updateReportDraft("Current report")

            sharedDynamicNativeMemoryCache.storeScreen(dynamicKey, dynamicSnapshot(3), dynamicProducer)
            sharedPreviewMemoryCache.put(previewKey, byteArrayOf(3), previewProducer)
            sharedNextcloudNotesCache.storeDetail(session, note(1, "Late"), notesProducer)
            drafts.updateReportDraft("Late report")

            assertEquals(2, sharedDynamicNativeMemoryCache.screen(dynamicKey)?.pagination?.nextPageNumber)
            assertContentEquals(byteArrayOf(2), sharedPreviewMemoryCache.get(previewKey))
            assertEquals("Current", sharedNextcloudNotesCache.detail(session, 1L)?.title)
            assertEquals("Current report", currentDrafts.reportDraft)
        } finally {
            AccountPrivateMemoryLifecycle.retireAccount(account)
            AccountPrivateMemoryLifecycle.activateAccount(account)
        }
    }

    private fun dynamicSnapshot(page: Int) = DynamicScreenSnapshot(
        records = emptyList(),
        relatedRecords = emptyMap(),
        pagination = DynamicPaginationCheckpoint(
            nextPageNumber = page,
            nextRequestValue = "page-$page",
        ),
    )

    private fun note(id: Long, title: String) = NextcloudNote(
        id = id,
        title = title,
        modified = 1L,
        category = "Personal",
        favorite = false,
        readOnly = false,
        content = "private body",
        etag = "etag-$id",
    )
}
