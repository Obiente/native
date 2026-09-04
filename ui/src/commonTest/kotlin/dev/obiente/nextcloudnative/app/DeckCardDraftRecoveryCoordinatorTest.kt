package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckCardDraftRecoveryCoordinatorTest {
    @Test
    fun discardPersistsTheLatestReplacementAndClearsTheRecoveryError() = runBlocking {
        val services = FakeDraftServices(loadFailure = true)
        val recovery = DeckCardDraftRecoveryCoordinator(services, session)
        assertEquals(
            DeckCardDraftRecoveryCoordinator.UNREADABLE_DRAFT_FAILURE,
            recovery.load(key, original),
        )
        services.operations.clear()
        val replacement = original.copy(title = "Replacement edit")

        val error = recovery.discardAndPersistReplacement(key, replacement, original)

        assertNull(error)
        assertEquals(listOf("clear-unreadable", "save"), services.operations)
        assertEquals(replacement, services.saved?.draft)
        assertNull(recovery.unreadableKey)
    }

    @Test
    fun resetRestoresCapacityAndPersistsTheCurrentDraft() = runBlocking {
        val services = FakeDraftServices(capacityFailure = true)
        val recovery = DeckCardDraftRecoveryCoordinator(services, session)
        val replacement = original.copy(title = "Draft after reset")
        assertEquals(
            DeckCardDraftRecoveryCoordinator.DRAFT_SAVE_FAILURE,
            recovery.persist(key, replacement, original),
        )
        assertTrue(recovery.resetRequired)
        services.capacityFailure = false

        val error = recovery.resetAndPersistReplacement(key, replacement, original)

        assertNull(error)
        assertFalse(recovery.resetRequired)
        assertEquals(listOf("save", "reset", "save"), services.operations)
        assertEquals(replacement, services.saved?.draft)
    }

    @Test
    fun failedSubmissionQuarantineBlocksTheDraftFromBeingLoadedAgain() = runBlocking {
        val services = FakeDraftServices(quarantineFailure = true)
        val recovery = DeckCardDraftRecoveryCoordinator(services, session)

        assertEquals(
            DeckCardDraftRecoveryCoordinator.SUBMITTED_QUARANTINE_FAILURE,
            recovery.quarantineSubmitted(key),
        )
        services.loadFailure = false
        services.loaded = PersistedDeckCardDraft(key, original.copy(title = "Already submitted"))

        assertEquals(
            DeckCardDraftRecoveryCoordinator.SUBMITTED_QUARANTINE_FAILURE,
            recovery.load(key, original),
        )
        assertNull(recovery.restoredDraft)
        assertEquals(listOf("quarantine"), services.operations)
    }

    @Test
    fun successfulLoadForAnotherEditorClearsThePreviousRecoveryFailure() = runBlocking {
        val services = FakeDraftServices(loadFailure = true)
        val recovery = DeckCardDraftRecoveryCoordinator(services, session)
        assertEquals(
            DeckCardDraftRecoveryCoordinator.UNREADABLE_DRAFT_FAILURE,
            recovery.load(key, original),
        )
        services.loadFailure = false
        val nextKey = key.copy(cardId = 43L)

        val error = recovery.load(nextKey, original)

        assertNull(error)
        assertEquals(nextKey, recovery.loadedKey)
        assertNull(recovery.unreadableKey)
    }

    private class FakeDraftServices(
        var loadFailure: Boolean = false,
        var capacityFailure: Boolean = false,
        var quarantineFailure: Boolean = false,
    ) : DeckCardDraftPlatformServices {
        val operations = mutableListOf<String>()
        var loaded: PersistedDeckCardDraft? = null
        var saved: PersistedDeckCardDraft? = null

        override suspend fun loadDeckCardDraft(
            session: NextcloudSession,
            key: DeckCardDraftKey,
        ): PersistedDeckCardDraft? {
            operations += "load"
            if (loadFailure) error("Synthetic unreadable draft")
            return loaded
        }

        override suspend fun saveDeckCardDraft(
            session: NextcloudSession,
            draft: PersistedDeckCardDraft,
        ) {
            operations += "save"
            if (capacityFailure) throw DeckCardDraftCapacityException()
            saved = draft
        }

        override suspend fun clearDeckCardDraft(
            session: NextcloudSession,
            key: DeckCardDraftKey,
            discardUnreadable: Boolean,
        ) {
            operations += if (discardUnreadable) "clear-unreadable" else "clear"
        }

        override suspend fun quarantineSubmittedDeckCardDraft(
            session: NextcloudSession,
            key: DeckCardDraftKey,
        ) {
            operations += "quarantine"
            if (quarantineFailure) error("Synthetic quarantine failure")
        }

        override suspend fun discardAllDeckCardDrafts() {
            operations += "reset"
        }
    }

    private companion object {
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test",
            loginName = "alice",
            appPassword = "test-password",
        )
        val key = DeckCardDraftKey(boardId = 7L, stackId = 11L, cardId = 42L)
        val original = DeckUiCardDraft(
            title = "Original title",
            descriptionMarkdown = "Original details",
            dueDate = "",
            dueTime = "",
        )
    }
}
