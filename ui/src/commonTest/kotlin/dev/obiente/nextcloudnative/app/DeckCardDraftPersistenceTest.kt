package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DeckCardDraftPersistenceTest {
    @Test
    fun draftIdentitySeparatesBoardsStacksExistingCardsAndNewCards() {
        val existing = DeckCardDraftKey(boardId = 11L, stackId = 22L, cardId = 33L)

        assertNotEquals(existing, existing.copy(boardId = 12L))
        assertNotEquals(existing, existing.copy(stackId = 23L))
        assertNotEquals(existing, existing.copy(cardId = 34L))
        assertNotEquals(existing, existing.copy(cardId = null))
    }

    @Test
    fun retentionKeepsNewestEntriesDeterministically() {
        val entries = (1L..30L).map { index ->
            DeckCardDraftRetention.Entry(
                storageKey = "draft-$index",
                updatedAtEpochMillis = index,
            )
        }

        assertEquals(
            (1L..6L).mapTo(linkedSetOf()) { "draft-$it" },
            DeckCardDraftRetention.keysToPrune(entries),
        )
        assertEquals(
            setOf("draft-a"),
            DeckCardDraftRetention.keysToPrune(
                entries = listOf(
                    DeckCardDraftRetention.Entry("draft-a", 10L),
                    DeckCardDraftRetention.Entry("draft-b", 10L),
                ),
                maximumEntries = 1,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            DeckCardDraftRetention.keysToPrune(entries, maximumEntries = 0)
        }
    }
}
