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
        val removedPhotoState = PhotoTimelineUiStateRepository.stateFor(removed)
        val retainedPhotoState = PhotoTimelineUiStateRepository.stateFor(retained)
        val removedProducer = sharedAccountPrivateMemoryGate.producer(removedKey)
        val retainedProducer = sharedAccountPrivateMemoryGate.producer(retainedKey)
        val removedDynamicProducer = sharedDynamicNativeMemoryCache.producer(removed)
        val retainedDynamicProducer = sharedDynamicNativeMemoryCache.producer(retained)
        try {
            PreviewMemoryCache.put(removedPreview, byteArrayOf(1), removedProducer)
            PreviewMemoryCache.put(retainedPreview, byteArrayOf(2), retainedProducer)
            sharedNextcloudNotesCache.storeDetail(removed, note(1L, "Removed"), removedProducer)
            sharedNextcloudNotesCache.storeDetail(retained, note(2L, "Retained"), retainedProducer)
            sharedDynamicNativeMemoryCache.storeScreen(
                dynamicKey(removed), dynamicSnapshot(1), removedDynamicProducer,
            )
            sharedDynamicNativeMemoryCache.storeScreen(
                dynamicKey(retained), dynamicSnapshot(2), retainedDynamicProducer,
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
        PreviewMemoryCache.put(
            PreviewCacheKey(accountKey, "core", 1L, "etag", 64, 64), byteArrayOf(1), staleProducer,
        )

        assertNull(sharedDashboardStatusMemoryCache.get(account, 1L))
        assertNull(ContactsWorkspaceMemoryCache.get(account, "user"))
        assertNull(UserStatusWorkspaceMemoryCache.get(account))
        assertNull(sharedDocumentEditingCapabilitiesCache.get(account))
        assertNull(DeckWorkspaceMemoryCache.get(account))
        assertNull(PreviewMemoryCache.get(PreviewCacheKey(accountKey, "core", 1L, "etag", 64, 64)))

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
