package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        AccountPrivateMemoryLifecycle.activateAccount(removedKey)
        AccountPrivateMemoryLifecycle.activateAccount(retainedKey)
        val removedPreview = PreviewCacheKey(removedKey, "core", 1L, "etag", 64, 64)
        val retainedPreview = PreviewCacheKey(retainedKey, "core", 2L, "etag", 64, 64)
        val removedDynamicKey = dynamicKey(removed)
        val retainedDynamicKey = dynamicKey(retained)
        val removedProducer = requireNotNull(sharedAccountPrivateMemoryGate.producer(removedKey))
        val retainedProducer = requireNotNull(sharedAccountPrivateMemoryGate.producer(retainedKey))
        val removedDynamicProducer = requireNotNull(sharedDynamicNativeMemoryCache.producer(removedDynamicKey))
        val retainedDynamicProducer = requireNotNull(sharedDynamicNativeMemoryCache.producer(retainedDynamicKey))
        val removedCarryoverScope = "removed|photos:timeline"
        val retainedCarryoverScope = "retained|photos:timeline"
        val removedCarryoverGeneration = requireNotNull(
            sharedMediaTimelineDavCarryoverStore.beginAccountGeneration(
                removed.accountId,
                removedCarryoverScope,
                removedProducer,
            ),
        )
        val retainedCarryoverGeneration = requireNotNull(
            sharedMediaTimelineDavCarryoverStore.beginAccountGeneration(
                retained.accountId,
                retainedCarryoverScope,
                retainedProducer,
            ),
        )
        val carryoverCursor = PhotoTimelineCursor("private-memory-carryover")
        val carryover = mediaCarryover()
        val removedPhotoState = PhotoTimelineUiStateRepository.stateFor(removed)
        val retainedPhotoState = PhotoTimelineUiStateRepository.stateFor(retained)
        removedPhotoState.initialLoadCompleted.value = true
        try {
            sharedPreviewMemoryCache.put(removedPreview, byteArrayOf(1), removedProducer)
            sharedPreviewMemoryCache.put(retainedPreview, byteArrayOf(2), retainedProducer)
            sharedNextcloudNotesCache.storeDetail(removed, note(1L, "Removed"), removedProducer)
            sharedNextcloudNotesCache.storeDetail(retained, note(2L, "Retained"), retainedProducer)
            sharedDynamicNativeMemoryCache.storeScreen(
                removedDynamicKey, dynamicSnapshot(1), removedDynamicProducer,
            )
            sharedDynamicNativeMemoryCache.storeScreen(
                retainedDynamicKey, dynamicSnapshot(2), retainedDynamicProducer,
            )
            sharedDashboardStatusMemoryCache.store(
                removed, NativeDashboardSnapshot(emptyList(), emptyMap()), null, 1L, removedProducer,
            )
            sharedDashboardStatusMemoryCache.store(
                retained, NativeDashboardSnapshot(emptyList(), emptyMap()), null, 1L, retainedProducer,
            )
            ContactsWorkspaceMemoryCache.store(
                removed, "removed", ContactsLoadState.Ready(emptyList(), emptyList()), removedProducer,
            )
            ContactsWorkspaceMemoryCache.store(
                retained, "retained", ContactsLoadState.Ready(emptyList(), emptyList()), retainedProducer,
            )
            CalendarWorkspaceMemoryCache.store(removed, "removed", calendarSnapshot(), removedProducer)
            CalendarWorkspaceMemoryCache.store(retained, "retained", calendarSnapshot(), retainedProducer)
            UserStatusWorkspaceMemoryCache.store(removed, statusSnapshot("removed"), removedProducer)
            UserStatusWorkspaceMemoryCache.store(retained, statusSnapshot("retained"), retainedProducer)
            DeckWorkspaceMemoryCache.store(removed, deckSnapshot(), removedProducer)
            DeckWorkspaceMemoryCache.store(retained, deckSnapshot(), retainedProducer)
            sharedDocumentEditingCapabilitiesCache.store(
                removed, NextcloudDocumentEditingCapabilities.Unavailable, null, removedProducer,
            )
            sharedDocumentEditingCapabilitiesCache.store(
                retained, NextcloudDocumentEditingCapabilities.Unavailable, null, retainedProducer,
            )
            ActivityWorkspaceMemoryCache.store(
                removed, "all", ActivityTimelineState(initialized = true), removedProducer,
            )
            ActivityWorkspaceMemoryCache.store(
                retained, "all", ActivityTimelineState(initialized = true), retainedProducer,
            )
            TalkWorkspaceMemoryCache.storeRooms(
                removed, listOf(TalkRoom("removed", "Removed", null, 0)), removedProducer,
            )
            TalkWorkspaceMemoryCache.storeRooms(
                retained, listOf(TalkRoom("retained", "Retained", null, 0)), retainedProducer,
            )
            TalkWorkspaceMemoryCache.storeMessages(removed, "removed", emptyList(), removedProducer)
            TalkWorkspaceMemoryCache.storeMessages(retained, "retained", emptyList(), retainedProducer)
            sharedMediaTimelineDavCarryoverStore.put(
                removed.accountId,
                removedCarryoverScope,
                removedCarryoverGeneration,
                carryoverCursor,
                carryover,
                removedProducer,
            )
            sharedMediaTimelineDavCarryoverStore.put(
                retained.accountId,
                retainedCarryoverScope,
                retainedCarryoverGeneration,
                carryoverCursor,
                carryover,
                retainedProducer,
            )

            AccountPrivateMemoryCleanup.removeAccount(removedKey)

            assertNull(sharedPreviewMemoryCache.get(removedPreview))
            assertContentEquals(byteArrayOf(2), sharedPreviewMemoryCache.get(retainedPreview))
            assertNull(sharedNextcloudNotesCache.detail(removed, 1L))
            assertEquals("Retained", sharedNextcloudNotesCache.detail(retained, 2L)?.title)
            assertNull(sharedDynamicNativeMemoryCache.screen(removedDynamicKey))
            assertNotNull(sharedDynamicNativeMemoryCache.screen(retainedDynamicKey))
            assertNull(sharedDashboardStatusMemoryCache.get(removed, 1L))
            assertNotNull(sharedDashboardStatusMemoryCache.get(retained, 1L))
            assertNull(ContactsWorkspaceMemoryCache.get(removed, "removed"))
            assertNotNull(ContactsWorkspaceMemoryCache.get(retained, "retained"))
            assertNull(CalendarWorkspaceMemoryCache.get(removed, "removed", testMonth, testWindow))
            assertNotNull(CalendarWorkspaceMemoryCache.get(retained, "retained", testMonth, testWindow))
            assertNull(UserStatusWorkspaceMemoryCache.get(removed))
            assertEquals("retained", UserStatusWorkspaceMemoryCache.get(retained)?.status?.userId)
            assertNull(DeckWorkspaceMemoryCache.get(removed))
            assertNotNull(DeckWorkspaceMemoryCache.get(retained))
            assertNull(sharedDocumentEditingCapabilitiesCache.get(removed))
            assertNotNull(sharedDocumentEditingCapabilitiesCache.get(retained))
            assertNull(ActivityWorkspaceMemoryCache.get(removed, "all"))
            assertNotNull(ActivityWorkspaceMemoryCache.get(retained, "all"))
            assertNull(TalkWorkspaceMemoryCache.rooms(removed))
            assertEquals("retained", TalkWorkspaceMemoryCache.rooms(retained)?.single()?.token)
            assertNull(TalkWorkspaceMemoryCache.messages(removed, "removed"))
            assertNotNull(TalkWorkspaceMemoryCache.messages(retained, "retained"))
            assertFalse(removedPhotoState.initialLoadCompleted.value)
            assertNotSame(removedPhotoState, PhotoTimelineUiStateRepository.stateFor(removed))
            assertSame(retainedPhotoState, PhotoTimelineUiStateRepository.stateFor(retained))

            AccountPrivateMemoryLifecycle.activateAccount(removedKey)
            val currentProducer = requireNotNull(sharedAccountPrivateMemoryGate.producer(removedKey))
            assertNull(
                sharedMediaTimelineDavCarryoverStore.take(
                    removed.accountId,
                    removedCarryoverScope,
                    removedCarryoverGeneration,
                    carryoverCursor,
                    currentProducer,
                ),
            )
            assertEquals(
                carryover,
                sharedMediaTimelineDavCarryoverStore.take(
                    retained.accountId,
                    retainedCarryoverScope,
                    retainedCarryoverGeneration,
                    carryoverCursor,
                    retainedProducer,
                ),
            )
            sharedDashboardStatusMemoryCache.store(
                removed, NativeDashboardSnapshot(emptyList(), emptyMap()), null, 2L, removedProducer,
            )
            ContactsWorkspaceMemoryCache.store(
                removed, "removed", ContactsLoadState.Ready(emptyList(), emptyList()), removedProducer,
            )
            CalendarWorkspaceMemoryCache.store(removed, "removed", calendarSnapshot(), removedProducer)
            UserStatusWorkspaceMemoryCache.store(removed, statusSnapshot("removed"), removedProducer)
            DeckWorkspaceMemoryCache.store(removed, deckSnapshot(), removedProducer)
            sharedDocumentEditingCapabilitiesCache.store(
                removed, NextcloudDocumentEditingCapabilities.Unavailable, null, removedProducer,
            )
            ActivityWorkspaceMemoryCache.store(
                removed, "all", ActivityTimelineState(initialized = true), removedProducer,
            )
            TalkWorkspaceMemoryCache.storeRooms(
                removed, listOf(TalkRoom("removed", "Removed", null, 0)), removedProducer,
            )

            assertNull(sharedDashboardStatusMemoryCache.get(removed, 2L))
            assertNull(ContactsWorkspaceMemoryCache.get(removed, "removed"))
            assertNull(CalendarWorkspaceMemoryCache.get(removed, "removed", testMonth, testWindow))
            assertNull(UserStatusWorkspaceMemoryCache.get(removed))
            assertNull(DeckWorkspaceMemoryCache.get(removed))
            assertNull(sharedDocumentEditingCapabilitiesCache.get(removed))
            assertNull(ActivityWorkspaceMemoryCache.get(removed, "all"))
            assertNull(TalkWorkspaceMemoryCache.rooms(removed))

            ContactsWorkspaceMemoryCache.store(
                removed, "removed", ContactsLoadState.Ready(emptyList(), emptyList()), currentProducer,
            )
            assertNotNull(ContactsWorkspaceMemoryCache.get(removed, "removed"))
        } finally {
            AccountPrivateMemoryCleanup.removeAccount(removedKey)
            AccountPrivateMemoryCleanup.removeAccount(retainedKey)
            AccountPrivateMemoryLifecycle.activateAccount(removedKey)
            AccountPrivateMemoryLifecycle.activateAccount(retainedKey)
        }
    }

    @Test
    fun `stale workspace completion cannot repopulate a reactivated account`() {
        val account = session("crossing")
        val accountKey = account.accountId.storageKey
        AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        val staleProducer = requireNotNull(sharedAccountPrivateMemoryGate.producer(accountKey))

        AccountPrivateMemoryLifecycle.retireAccount(accountKey)
        AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        TalkWorkspaceMemoryCache.storeRooms(
            account, listOf(TalkRoom("stale", "Stale", null, 0)), staleProducer,
        )

        assertNull(TalkWorkspaceMemoryCache.rooms(account))
        val currentProducer = requireNotNull(sharedAccountPrivateMemoryGate.producer(accountKey))
        TalkWorkspaceMemoryCache.storeRooms(
            account, listOf(TalkRoom("current", "Current", null, 0)), currentProducer,
        )
        assertEquals("current", TalkWorkspaceMemoryCache.rooms(account)?.single()?.token)
        assertFalse(staleProducer == currentProducer)
        AccountPrivateMemoryCleanup.removeAccount(accountKey)
    }

    @Test
    fun `stale private reads cannot repopulate removed caches after reactivation`() {
        val account = session("private-cache-race")
        val accountKey = account.accountId.storageKey
        AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        val staleProducer = requireNotNull(sharedAccountPrivateMemoryGate.producer(accountKey))
        val dashboard = NativeDashboardSnapshot(emptyList(), emptyMap())
        val contacts = ContactsLoadState.Ready(emptyList(), emptyList())
        val status = userStatusState()

        AccountPrivateMemoryLifecycle.retireAccount(accountKey)
        AccountPrivateMemoryLifecycle.activateAccount(accountKey)
        sharedDashboardStatusMemoryCache.store(account, dashboard, null, 1L, staleProducer)
        ContactsWorkspaceMemoryCache.store(account, "user", contacts, staleProducer)
        UserStatusWorkspaceMemoryCache.store(account, status, staleProducer)
        sharedDocumentEditingCapabilitiesCache.store(
            account, NextcloudDocumentEditingCapabilities.Unavailable, null, staleProducer,
        )
        DeckWorkspaceMemoryCache.store(account, deckSnapshot(), staleProducer)
        sharedPreviewMemoryCache.put(
            PreviewCacheKey(accountKey, "core", 1L, "etag", 64, 64), byteArrayOf(1), staleProducer,
        )

        assertNull(sharedDashboardStatusMemoryCache.get(account, 1L))
        assertNull(ContactsWorkspaceMemoryCache.get(account, "user"))
        assertNull(UserStatusWorkspaceMemoryCache.get(account))
        assertNull(sharedDocumentEditingCapabilitiesCache.get(account))
        assertNull(DeckWorkspaceMemoryCache.get(account))
        assertNull(sharedPreviewMemoryCache.get(PreviewCacheKey(accountKey, "core", 1L, "etag", 64, 64)))

        val currentProducer = requireNotNull(sharedAccountPrivateMemoryGate.producer(accountKey))
        sharedDashboardStatusMemoryCache.store(account, dashboard, null, 2L, currentProducer)
        ContactsWorkspaceMemoryCache.store(account, "user", contacts, currentProducer)
        UserStatusWorkspaceMemoryCache.store(account, status, currentProducer)
        sharedDocumentEditingCapabilitiesCache.store(
            account, NextcloudDocumentEditingCapabilities.Unavailable, null, currentProducer,
        )
        DeckWorkspaceMemoryCache.store(account, deckSnapshot(), currentProducer)

        assertNotNull(sharedDashboardStatusMemoryCache.get(account, 2L))
        assertNotNull(ContactsWorkspaceMemoryCache.get(account, "user"))
        assertNotNull(UserStatusWorkspaceMemoryCache.get(account))
        assertNotNull(sharedDocumentEditingCapabilitiesCache.get(account))
        assertNotNull(DeckWorkspaceMemoryCache.get(account))
        AccountPrivateMemoryCleanup.removeAccount(accountKey)
        AccountPrivateMemoryLifecycle.activateAccount(accountKey)
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

    private fun userStatusState() = UserStatusSurfaceState.Available(
        capabilities = NativeUserStatusCapabilities(true, true, true, true),
        status = NativeUserStatus(
            userId = "user",
            presence = NativeUserPresence.Online,
            message = "Private status",
            icon = null,
            messageId = null,
            clearAtEpochSeconds = null,
            messageIsPredefined = false,
            statusIsUserDefined = true,
        ),
        predefined = emptyList(),
    )

    private fun calendarSnapshot() = CalendarLoadState.Ready(testMonth, testWindow, emptyList(), emptyList())

    private fun statusSnapshot(userId: String) = UserStatusSurfaceState.Available(
        capabilities = NativeUserStatusCapabilities(false, false, false, false),
        status = NativeUserStatus(
            userId, NativeUserPresence.Offline, null, null, null, null,
            messageIsPredefined = false, statusIsUserDefined = false,
        ),
        predefined = emptyList(),
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

    private fun mediaCarryover() = MediaTimelineDavCarryover(
        mapOf(
            MediaTimelinePartitionKey.Mime(MediaSearchDavPartition.ImageMime) to
                MediaTimelinePartitionCarryover(
                    files = listOf(
                        NextcloudFile(
                            path = "Photos/private.jpg",
                            name = "private.jpg",
                            isDirectory = false,
                            mimeType = "image/jpeg",
                            size = 1L,
                            lastModified = "private",
                            fileId = 1L,
                            hasPreview = true,
                        ),
                    ),
                    remoteCursorAfterFetched = null,
                ),
        ),
    )

    private companion object {
        val testMonth = CalendarMonth(2026, 9)
        val testWindow = GroupwareDavTimeWindow("20260901T000000Z", "20261001T000000Z")
    }
}
