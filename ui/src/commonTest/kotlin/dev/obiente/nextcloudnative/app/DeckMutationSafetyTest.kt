package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckMutationSafetyTest {
    @Test
    fun missingResponseAndMalformedSuccessHaveAmbiguousOutcomes() {
        assertTrue(isAmbiguousDeckMutationFailure(null))
        assertTrue(isAmbiguousDeckMutationFailure(200))
        assertTrue(isAmbiguousDeckMutationFailure(503))
        assertFalse(isAmbiguousDeckMutationFailure(409))
    }

    @Test
    fun etagsDetectAChangedAuthoritativeCardWhenBothAreAvailable() {
        val original = card("\"revision-1\"")
        assertTrue(original.hasSameAuthoritativeRevision(card("\"revision-1\"")))
        assertFalse(original.hasSameAuthoritativeRevision(card("\"revision-2\"")))
        assertTrue(original.copy(etag = null).hasSameAuthoritativeRevision(card("\"revision-2\"")))
    }

    private fun card(etag: String?) = DeckCard(
        id = 3,
        boardId = 1,
        stackId = 2,
        title = "Synthetic card",
        descriptionMarkdown = null,
        ownerId = null,
        color = null,
        order = 0,
        dueAt = null,
        startAt = null,
        completedAt = null,
        archived = false,
        overdue = false,
        labels = emptyList(),
        assignees = emptyList(),
        attachmentCount = 0,
        unreadCommentCount = 0,
        etag = etag,
    )
}
