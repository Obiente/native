package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeDeckInteractionModelsTest {
    @Test
    fun persistedCardDraftIsResourceScopedAndDetectsOnlyEditorChanges() {
        val original = DeckUiCardDraft(
            title = "Prepare release notes",
            descriptionMarkdown = "Keep this context.",
            dueDate = "2026-08-01",
            dueTime = "10:30",
            dueAtBeforeEditing = "2026-08-01T08:30:00Z",
        )
        val key = DeckCardDraftKey(boardId = 11, stackId = 22, cardId = 33)
        val persisted = PersistedDeckCardDraft(key, original.copy(descriptionMarkdown = "Updated"))

        assertEquals(key, persisted.key)
        assertFalse(original.hasMeaningfulChangesFrom(original))
        assertTrue(persisted.draft.hasMeaningfulChangesFrom(original))
        assertEquals(
            original.copy(
                dueDate = "2026-08-02",
                dueTime = "14:00",
                dueAtBeforeEditing = "2026-08-02T12:00:00Z",
            ),
            original.reconcileUntouchedDueDate(
                original.copy(
                    dueDate = "2026-08-02",
                    dueTime = "14:00",
                    dueAtBeforeEditing = "2026-08-02T12:00:00Z",
                ),
            ),
        )
        assertEquals(
            original.copy(dueFieldsEdited = true),
            original.copy(dueFieldsEdited = true).reconcileUntouchedDueDate(
                original.copy(dueDate = "2026-08-02"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            DeckCardDraftKey(boardId = 0, stackId = 22, cardId = 33)
        }
    }

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
    fun labelDraftNormalizesBoardManagedValues() {
        assertEquals(
            DeckUiLabelDraft("Needs review", "a970ff"),
            DeckUiLabelDraft("  Needs review  ", "#A970FF").normalized(),
        )
        assertNull(DeckUiLabelDraft("Needs review", "a970ff").validationError())
        assertNotNull(DeckUiLabelDraft("", "a970ff").validationError())
        assertNotNull(DeckUiLabelDraft("Needs review", "purple").validationError())
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
    fun cardDraftPreservesMarkdownWhitespaceAndUntouchedServerDueDate() {
        val draft = DeckUiCardDraft(
            title = "  Prepare release notes  ",
            descriptionMarkdown = "    indented code  \nline break  ",
            dueDate = "",
            dueTime = "",
            dueAtBeforeEditing = "not-a-parseable-server-date",
        )

        assertEquals(
            "    indented code  \nline break  ",
            draft.normalized().descriptionMarkdown,
        )
        assertEquals(
            "not-a-parseable-server-date",
            draft.resolvedDueAt(editedDueAt = null),
        )
        assertNull(
            draft.copy(dueFieldsEdited = true).resolvedDueAt(editedDueAt = null),
        )
    }

    @Test
    fun cardEditorDraftRetainsAnUnparseableServerDueDate() {
        val draft = DeckCard(
            id = 3L,
            boardId = 1L,
            stackId = 2L,
            title = "Synthetic card",
            descriptionMarkdown = "Details",
            ownerId = "owner",
            color = null,
            order = 0L,
            dueAt = "not-a-parseable-server-date",
            startAt = null,
            completedAt = null,
            archived = false,
            overdue = false,
            labels = emptyList(),
            assignees = emptyList(),
            attachmentCount = 0,
            unreadCommentCount = 0,
            etag = "\"v1\"",
        ).toDeckUiDraft()

        assertEquals("", draft.dueDate)
        assertEquals("", draft.dueTime)
        assertEquals("not-a-parseable-server-date", draft.dueAtBeforeEditing)
        assertEquals(
            "not-a-parseable-server-date",
            draft.resolvedDueAt(editedDueAt = null),
        )
    }

    @Test
    fun deckTimestampPresentationUsesLocalTimeAndPreservesUnknownValues() {
        val local = deckInstantToLocalDateTime("2026-07-26T22:30:00Z")

        assertNotNull(local)
        assertEquals("${local.date} ${local.time}", deckInstantDisplayLabel("2026-07-26T22:30:00Z"))
        assertEquals("unknown-server-value", deckInstantDisplayLabel("unknown-server-value"))
    }

    @Test
    fun staleWorkspaceStatesDoNotExposeMutationDialogs() {
        val createBoard = DeckUiInteraction.BoardEditor(null)
        val error = DeckWorkspaceState.Error(
            title = "Offline",
            message = "Showing cached data.",
            cachedState = DeckWorkspaceState.Empty(
                title = "No boards",
                message = "No cached boards.",
                canCreateBoards = true,
            ),
        )

        assertFalse(createBoard.isAvailableFor(error))
        assertTrue(createBoard.isAvailableFor(requireNotNull(error.cachedState)))
        assertFalse(createBoard.isAvailableFor(DeckWorkspaceState.Loading))
    }

    @Test
    fun labelManagerStaysBoundToItsAuthoritativeBoard() {
        val board = syntheticBoard(id = 7L)
        val otherBoard = syntheticBoard(id = 8L)
        val interaction = DeckUiInteraction.ManageLabels(board)

        assertTrue(
            interaction.isAvailableFor(
                DeckWorkspaceState.Board(board, stacks = emptyList()),
            ),
        )
        assertFalse(
            interaction.isAvailableFor(
                DeckWorkspaceState.Board(otherBoard, stacks = emptyList()),
            ),
        )
        assertFalse(
            interaction.isAvailableFor(
                DeckWorkspaceState.Board(board.copy(archived = true), stacks = emptyList()),
            ),
        )
        assertFalse(interaction.isAvailableFor(DeckWorkspaceState.Loading))
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

private fun syntheticBoard(id: Long): DeckBoard = DeckBoard(
    id = id,
    title = "Synthetic board",
    color = "8b5cf6",
    archived = false,
    owner = null,
    labels = emptyList(),
    permissions = DeckPermissions(
        canRead = true,
        canEdit = true,
        canManage = true,
        canShare = false,
    ),
    shared = false,
    lastModified = null,
    etag = "\"v1\"",
)
