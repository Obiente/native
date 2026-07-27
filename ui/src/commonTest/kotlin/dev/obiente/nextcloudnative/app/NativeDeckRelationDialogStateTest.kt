package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeDeckRelationDialogStateTest {
    @Test
    fun `comment composer keeps reply draft and target until success is confirmed`() {
        val replyTarget = comment(key = "18", message = "Original")
        val pending = DeckCommentComposerState()
            .beginReply(replyTarget)
            .copy(draft = "A useful reply")

        val failed = pending.afterMutationResult(confirmedSuccess = false)
        assertSame(pending, failed)
        assertEquals("A useful reply", failed.draft)
        assertEquals(replyTarget, failed.replyingTo)
        assertNull(failed.editing)

        val confirmed = pending.afterMutationResult(confirmedSuccess = true)
        assertEquals("", confirmed.draft)
        assertNull(confirmed.replyingTo)
        assertNull(confirmed.editing)
    }

    @Test
    fun `comment composer keeps edited text until success is confirmed`() {
        val editTarget = comment(key = "19", message = "Before")
        val pending = DeckCommentComposerState()
            .beginEdit(editTarget)
            .copy(draft = "After")

        val failed = pending.afterMutationResult(confirmedSuccess = false)
        assertEquals("After", failed.draft)
        assertEquals(editTarget, failed.editing)
        assertNull(failed.replyingTo)

        val confirmed = pending.afterMutationResult(confirmedSuccess = true)
        assertEquals(DeckCommentComposerState(), confirmed)
    }

    @Test
    fun `label editor keeps normalized input open until success is confirmed`() {
        val label = DeckLabel(id = 4, title = "Old", color = "ff0000")
        val pending = DeckLabelEditorState()
            .beginEdit(label)
            .copy(draft = DeckUiLabelDraft("Updated label", "a970ff"))

        val failed = pending.afterMutationResult(confirmedSuccess = false)
        assertSame(pending, failed)
        assertTrue(failed.visible)
        assertEquals(label, failed.editingLabel)
        assertEquals("Updated label", failed.draft.title)
        assertEquals("a970ff", failed.draft.color)

        val confirmed = pending.afterMutationResult(confirmedSuccess = true)
        assertFalse(confirmed.visible)
        assertNull(confirmed.editingLabel)
        assertEquals(DeckUiLabelDraft("", "8b5cf6"), confirmed.draft)
    }

    private fun comment(key: String, message: String) = DeckUiComment(
        key = key,
        author = DeckUser(id = "reviewer", displayName = "Reviewer"),
        messageMarkdown = message,
        createdLabel = "Today",
        edited = false,
        canEdit = true,
        canDelete = true,
    )
}
