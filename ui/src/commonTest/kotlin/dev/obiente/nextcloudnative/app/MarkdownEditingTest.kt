package dev.obiente.nextcloudnative.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownEditingTest {
    @Test
    fun wrapsSelectionAndKeepsContentSelected() {
        val edited = applyMarkdownEdit(
            TextFieldValue("make this bold", TextRange(10, 14)),
            MarkdownEditAction.Bold,
        )

        assertEquals("make this **bold**", edited.text)
        assertEquals(TextRange(12, 16), edited.selection)
    }

    @Test
    fun insertsSelectablePlaceholderAtCursor() {
        val edited = applyMarkdownEdit(
            TextFieldValue("A note: ", TextRange(8)),
            MarkdownEditAction.Link,
        )

        assertEquals("A note: [link text](https://)", edited.text)
        assertEquals("link text", edited.text.substring(edited.selection.min, edited.selection.max))
    }

    @Test
    fun togglesExistingBoldWrapperOff() {
        val edited = applyMarkdownEdit(
            TextFieldValue("This is **important**.", TextRange(10, 19)),
            MarkdownEditAction.Bold,
        )

        assertEquals("This is important.", edited.text)
        assertEquals(TextRange(8, 17), edited.selection)
    }

    @Test
    fun appliesChecklistToEverySelectedLineAndTogglesItOff() {
        val original = TextFieldValue("first\nsecond\nthird", TextRange(0, 12))
        val checked = applyMarkdownEdit(original, MarkdownEditAction.Checklist)
        val unchecked = applyMarkdownEdit(checked, MarkdownEditAction.Checklist)

        assertEquals("- [ ] first\n- [ ] second\nthird", checked.text)
        assertEquals("first\nsecond\nthird", unchecked.text)
        assertEquals(TextRange(0, 12), unchecked.selection)
    }

    @Test
    fun headingEditTargetsTheCurrentLine() {
        val edited = applyMarkdownEdit(
            TextFieldValue("one\ntwo\nthree", TextRange(6)),
            MarkdownEditAction.Heading,
        )

        assertEquals("one\n# two\nthree", edited.text)
        assertEquals(TextRange(8), edited.selection)
    }

    @Test
    fun insertsHeadingPrefixOnAnEmptyCurrentLine() {
        val edited = applyMarkdownEdit(
            TextFieldValue("above\n", TextRange(6)),
            MarkdownEditAction.Heading,
        )

        assertEquals("above\n# ", edited.text)
        assertEquals(TextRange(8), edited.selection)
    }

    @Test
    fun quoteTargetsSelectedLinesAndInlineCodeTogglesCleanly() {
        val quoted = applyMarkdownEdit(
            TextFieldValue("first\nsecond", TextRange(0, 12)),
            MarkdownEditAction.Quote,
        )
        val coded = applyMarkdownEdit(
            TextFieldValue("Run command", TextRange(4, 11)),
            MarkdownEditAction.InlineCode,
        )
        val uncoded = applyMarkdownEdit(coded, MarkdownEditAction.InlineCode)

        assertEquals("> first\n> second", quoted.text)
        assertEquals("Run `command`", coded.text)
        assertEquals("Run command", uncoded.text)
    }

    @Test
    fun utf8SizeAvoidsAllocatingAFullEncodedDraftAndCountsSurrogatePairs() {
        assertEquals(5L, "plain".utf8Size())
        assertEquals(2L, "é".utf8Size())
        assertEquals(4L, "🙂".utf8Size())
        assertEquals("mixed é 🙂".encodeToByteArray().size.toLong(), "mixed é 🙂".utf8Size())
    }

    @Test
    fun uninitializedDraftIsNeverDirtyButRestoredEditsArePreserved() {
        assertFalse(
            noteDraftIsDirty(
                initialized = false,
                content = "local",
                originalContent = "",
                category = "Work",
                originalCategory = "",
                favorite = true,
                originalFavorite = false,
            ),
        )
        assertTrue(
            noteDraftIsDirty(
                initialized = true,
                content = "local",
                originalContent = "server",
                category = "Work",
                originalCategory = "Work",
                favorite = false,
                originalFavorite = false,
            ),
        )
    }
}
