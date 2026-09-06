package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class AccountPrivateMemoryCleanupTest {
    @Test
    fun `removal purges one account from shared workspace memory`() {
        val removed = session("removed")
        val retained = session("retained")
        val removedKey = removed.accountId.storageKey
        val retainedKey = retained.accountId.storageKey
        val removedPreview = PreviewCacheKey(removedKey, "core", 1L, "etag", 64, 64)
        val retainedPreview = PreviewCacheKey(retainedKey, "core", 2L, "etag", 64, 64)
        val removedPhotoState = PhotoTimelineUiStateRepository.stateFor(removed)
        val retainedPhotoState = PhotoTimelineUiStateRepository.stateFor(retained)
        try {
            PreviewMemoryCache.put(removedPreview, byteArrayOf(1))
            PreviewMemoryCache.put(retainedPreview, byteArrayOf(2))
            sharedNextcloudNotesCache.storeDetail(removed, note(1L, "Removed"))
            sharedNextcloudNotesCache.storeDetail(retained, note(2L, "Retained"))
            sharedDynamicNativeMemoryCache.storeScreen(dynamicKey(removed), dynamicSnapshot(1))
            sharedDynamicNativeMemoryCache.storeScreen(dynamicKey(retained), dynamicSnapshot(2))
            sharedDashboardStatusMemoryCache.store(removed, NativeDashboardSnapshot(emptyList(), emptyMap()), null, 1L)
            sharedDashboardStatusMemoryCache.store(retained, NativeDashboardSnapshot(emptyList(), emptyMap()), null, 1L)
            ContactsWorkspaceMemoryCache.store(removed, "removed", ContactsLoadState.Ready(emptyList(), emptyList()))
            ContactsWorkspaceMemoryCache.store(retained, "retained", ContactsLoadState.Ready(emptyList(), emptyList()))
            DeckWorkspaceMemoryCache.store(removed, deckSnapshot())
            DeckWorkspaceMemoryCache.store(retained, deckSnapshot())
            sharedDocumentEditingCapabilitiesCache.store(
                removed, NextcloudDocumentEditingCapabilities.Unavailable, null,
            )
            sharedDocumentEditingCapabilitiesCache.store(
                retained, NextcloudDocumentEditingCapabilities.Unavailable, null,
            )
            ActivityWorkspaceMemoryCache.store(removed, "all", ActivityTimelineState(initialized = true))
            ActivityWorkspaceMemoryCache.store(retained, "all", ActivityTimelineState(initialized = true))
            TalkWorkspaceMemoryCache.storeRooms(removed, listOf(TalkRoom("removed", "Removed", null, 0)))
            TalkWorkspaceMemoryCache.storeRooms(retained, listOf(TalkRoom("retained", "Retained", null, 0)))

            AccountPrivateMemoryCleanup.removeAccount(removedKey)

            assertNull(PreviewMemoryCache.get(removedPreview))
            assertContentEquals(byteArrayOf(2), PreviewMemoryCache.get(retainedPreview))
            assertNull(sharedNextcloudNotesCache.detail(removed, 1L))
            assertEquals("Retained", sharedNextcloudNotesCache.detail(retained, 2L)?.title)
            assertNull(sharedDynamicNativeMemoryCache.screen(dynamicKey(removed)))
            assertNotNull(sharedDynamicNativeMemoryCache.screen(dynamicKey(retained)))
            assertNull(sharedDashboardStatusMemoryCache.get(removed, 1L))
            assertNotNull(sharedDashboardStatusMemoryCache.get(retained, 1L))
            assertNull(ContactsWorkspaceMemoryCache.get(removed, "removed"))
            assertNotNull(ContactsWorkspaceMemoryCache.get(retained, "retained"))
            assertNull(DeckWorkspaceMemoryCache.get(removed))
            assertNotNull(DeckWorkspaceMemoryCache.get(retained))
            assertNull(sharedDocumentEditingCapabilitiesCache.get(removed))
            assertNotNull(sharedDocumentEditingCapabilitiesCache.get(retained))
            assertNull(ActivityWorkspaceMemoryCache.get(removed, "all"))
            assertNotNull(ActivityWorkspaceMemoryCache.get(retained, "all"))
            assertNull(TalkWorkspaceMemoryCache.rooms(removed))
            assertEquals("retained", TalkWorkspaceMemoryCache.rooms(retained)?.single()?.token)
            assertNotSame(removedPhotoState, PhotoTimelineUiStateRepository.stateFor(removed))
            assertSame(retainedPhotoState, PhotoTimelineUiStateRepository.stateFor(retained))
        } finally {
            AccountPrivateMemoryCleanup.removeAccount(removedKey)
            AccountPrivateMemoryCleanup.removeAccount(retainedKey)
        }
    }

    private fun session(name: String) = NextcloudSession(
        serverUrl = "https://$name.private-memory.example.test",
        loginName = name,
        appPassword = "password",
    )

    private fun note(id: Long, title: String) = NextcloudNote(
        id = id,
        title = title,
        modified = 1L,
        category = "Personal",
        favorite = false,
        readOnly = false,
        content = "private",
        etag = "etag-$id",
    )

    private fun dynamicKey(session: NextcloudSession) =
        dynamicScreenCacheKey(session, "dashboard", "widgets", null, emptyMap())

    private fun dynamicSnapshot(page: Int) = DynamicScreenSnapshot(
        records = emptyList(),
        relatedRecords = emptyMap(),
        pagination = DynamicPaginationCheckpoint(page, "page-$page"),
    )

    private fun deckSnapshot() = DeckWorkspaceMemorySnapshot(
        state = DeckWorkspaceState.Loading,
        loadedBoards = emptyList(),
        capabilities = null,
        activeRoute = null,
        requestedBoard = null,
        requestedBoardId = null,
        requestedCardId = null,
    )
}
