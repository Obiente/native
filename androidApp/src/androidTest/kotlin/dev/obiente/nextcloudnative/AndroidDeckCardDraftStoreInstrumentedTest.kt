package dev.obiente.nextcloudnative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.DeckCardDraftKey
import dev.obiente.nextcloudnative.app.DeckCardDraftRetention
import dev.obiente.nextcloudnative.app.DeckUiCardDraft
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.PersistedDeckCardDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDeckCardDraftStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)
    }

    @Before
    fun clearFixtureStore() {
        check(preferences.edit().clear().commit())
    }

    @After
    fun removeFixtureStore() {
        check(preferences.edit().clear().commit())
    }

    @Test
    fun encryptedDraftSurvivesStoreRecreationAndRemainsResourceScoped() {
        val key = DeckCardDraftKey(boardId = 7L, stackId = 11L, cardId = 42L)
        val persisted = PersistedDeckCardDraft(
            key = key,
            draft = DeckUiCardDraft(
                title = "Prepare launch",
                descriptionMarkdown = "Private draft text",
                dueDate = "2026-08-01",
                dueTime = "10:30",
                dueAtBeforeEditing = "2026-08-01T08:30:00Z",
            ),
        )
        val store = AndroidDeckCardDraftStore(
            context = context,
            preferencesName = TEST_PREFERENCES,
            nowEpochMillis = { 100L },
        )
        store.save(sessionA, persisted)

        val rawStorage = preferences.all.entries.single()
        val encrypted = rawStorage.value as String
        assertTrue(rawStorage.key.startsWith(AndroidDeckCardDraftStore.KEY_PREFIX))
        assertFalse("Prepare launch" in encrypted)
        assertFalse("Private draft text" in encrypted)
        assertFalse(sessionA.loginName in encrypted)
        assertFalse(sessionA.appPassword in encrypted)
        assertNotEquals(store.storageKey(sessionA, key), store.storageKey(sessionB, key))
        assertNotEquals(
            store.storageKey(sessionA, key),
            store.storageKey(sessionA, key.copy(boardId = key.boardId + 1L)),
        )
        assertNotEquals(
            store.storageKey(sessionA, key),
            store.storageKey(sessionA, key.copy(stackId = key.stackId + 1L)),
        )
        assertNotEquals(
            store.storageKey(sessionA, key),
            store.storageKey(sessionA, key.copy(cardId = null)),
        )

        val recreated = AndroidDeckCardDraftStore(context, TEST_PREFERENCES)
        assertEquals(persisted, recreated.load(sessionA, key))
        assertNull(recreated.load(sessionB, key))
        assertNull(
            recreated.load(
                sessionA,
                key.copy(stackId = key.stackId + 1L),
            ),
        )
    }

    @Test
    fun savingPrunesOldestDraftsToTheBoundedRetentionLimit() {
        var now = 0L
        val store = AndroidDeckCardDraftStore(
            context = context,
            preferencesName = TEST_PREFERENCES,
            nowEpochMillis = { ++now },
        )
        val saved = (1L..(DeckCardDraftRetention.MAX_ENTRIES + 3L)).map { boardId ->
            PersistedDeckCardDraft(
                key = DeckCardDraftKey(
                    boardId = boardId,
                    stackId = 1L,
                    cardId = null,
                ),
                draft = DeckUiCardDraft(
                    title = "Draft $boardId",
                    descriptionMarkdown = "",
                    dueDate = "",
                    dueTime = "",
                ),
            ).also { store.save(sessionA, it) }
        }

        assertEquals(DeckCardDraftRetention.MAX_ENTRIES, preferences.all.size)
        saved.take(3).forEach { oldest ->
            assertNull(store.load(sessionA, oldest.key))
        }
        saved.drop(3).forEach { retained ->
            assertEquals(retained, store.load(sessionA, retained.key))
        }
    }

    private companion object {
        const val TEST_PREFERENCES = "test_deck_card_drafts"
        val sessionA = NextcloudSession(
            serverUrl = "https://cloud.example.test",
            loginName = "account-a",
            appPassword = "test-password-a",
        )
        val sessionB = NextcloudSession(
            serverUrl = "https://cloud.example.test",
            loginName = "account-b",
            appPassword = "test-password-b",
        )
    }
}
