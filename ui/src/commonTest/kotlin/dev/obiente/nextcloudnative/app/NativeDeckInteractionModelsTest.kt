package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeDeckInteractionModelsTest {
    @Test
    fun boardDraftNormalizesWithoutAcceptingInvalidColors() {
        assertEquals(
            DeckUiBoardDraft("Planning", "8b5cf6"),
            DeckUiBoardDraft("  Planning  ", "#8B5CF6").normalized(),
        )
        assertNull(DeckUiBoardDraft("Planning", "8b5cf6").validationError())
        assertNotNull(DeckUiBoardDraft("Planning", "purple").validationError())
        assertNotNull(DeckUiBoardDraft("", "8b5cf6").validationError())
    }

    @Test
    fun cardDraftRequiresARealDateBeforeTime() {
        assertNull(
            DeckUiCardDraft(
                title = "Prepare release notes",
                descriptionMarkdown = "",
                dueDate = "2028-02-29",
                dueTime = "09:30",
            ).validationError(),
        )
        assertNotNull(
            DeckUiCardDraft(
                title = "Prepare release notes",
                descriptionMarkdown = "",
                dueDate = "2027-02-29",
                dueTime = "09:30",
            ).validationError(),
        )
        assertNotNull(
            DeckUiCardDraft(
                title = "Prepare release notes",
                descriptionMarkdown = "",
                dueDate = "",
                dueTime = "09:30",
            ).validationError(),
        )
    }

    @Test
    fun datePickerConversionRoundTripsRepresentativeDates() {
        listOf(
            "1970-01-01",
            "2000-02-29",
            "2026-07-26",
            "2099-12-31",
        ).forEach { date ->
            assertEquals(
                date,
                deckUiDateFromEpochMillis(requireNotNull(deckUiDateToEpochMillis(date))),
            )
        }
    }

    @Test
    fun commentValidationMatchesDeckServerLimit() {
        assertNull(validateDeckUiComment("Looks good"))
        assertNull(validateDeckUiComment("a".repeat(1_000)))
        assertNotNull(validateDeckUiComment("a".repeat(1_001)))
        assertNotNull(validateDeckUiComment("   "))
    }

    @Test
    fun dueDateOptionsRejectInvalidDatesAtTheirBoundary() {
        assertEquals(
            "Tomorrow",
            DeckUiDueDateOption("Tomorrow", "2026-07-27").label,
        )
        assertFailsWith<IllegalArgumentException> {
            DeckUiDueDateOption("Tomorrow", "2026-02-30")
        }
    }
}
