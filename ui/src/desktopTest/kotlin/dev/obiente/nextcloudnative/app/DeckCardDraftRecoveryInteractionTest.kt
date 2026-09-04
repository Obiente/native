package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckCardDraftRecoveryInteractionTest {
    @Test
    fun unreadableDraftRequiresConfirmationAndForwardsTheLatestEdit() {
        var discarded: DeckUiCardDraft? = null
        nativeSceneTest(720, 800, content = {
            DeckUiCardEditorDialog(
                stack = stack(),
                card = card(),
                draftRecoveryFailed = true,
                busy = false,
                errorMessage = "The saved card draft could not be restored safely.",
                quickDueDates = emptyList(),
                onDismiss = {},
                onDiscardRecoveredDraft = { discarded = it },
                onSubmit = {},
            )
        }) {
            replaceText("Original title", "Replacement edit")
            click("Discard")

            assertTrue(has("Discard saved draft?"))
            assertNull(discarded)
            click("Cancel")
            assertNull(discarded)

            click("Discard")
            click("Discard")
            assertEquals("Replacement edit", discarded?.title)
        }
    }

    @Test
    fun fullRecoveryStoreOffersConfirmedResetWithTheLatestEdit() {
        var replacement: DeckUiCardDraft? = null
        nativeSceneTest(720, 800, content = {
            DeckUiCardEditorDialog(
                stack = stack(),
                card = card(),
                draftRecoveryResetRequired = true,
                busy = false,
                errorMessage = "The unsaved card draft could not be stored safely.",
                quickDueDates = emptyList(),
                onDismiss = {},
                onResetDraftRecovery = { replacement = it },
                onSubmit = {},
            )
        }) {
            replaceText("Original title", "Draft after reset")
            click("Reset")

            assertTrue(has("Reset all saved card drafts?"))
            assertNull(replacement)
            click("Reset drafts")
            assertEquals("Draft after reset", replacement?.title)
        }
    }

    private fun stack() = DeckStack(
        id = 11L,
        boardId = 7L,
        title = "Planning",
        order = 0L,
        doneColumn = false,
        cards = listOf(card()),
        lastModified = null,
        etag = null,
    )

    private fun card() = DeckCard(
        id = 42L,
        boardId = 7L,
        stackId = 11L,
        title = "Original title",
        descriptionMarkdown = "Original details",
        ownerId = null,
        color = null,
        order = 0L,
        dueAt = null,
        startAt = null,
        completedAt = null,
        archived = false,
        overdue = false,
        labels = emptyList(),
        assignees = emptyList(),
        attachmentCount = 0,
        unreadCommentCount = 0,
        etag = null,
    )
}
