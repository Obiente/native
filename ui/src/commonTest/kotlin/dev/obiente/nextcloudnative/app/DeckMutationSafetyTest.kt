package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    fun batchReconciliationBlocksAmbiguousAndPartiallyAppliedMutations() {
        assertTrue(
            requiresDeckBatchMutationReconciliation(
                confirmedWrites = 0,
                failedResponseStatus = null,
            ),
        )
        assertTrue(
            requiresDeckBatchMutationReconciliation(
                confirmedWrites = 0,
                failedResponseStatus = 503,
            ),
        )
        assertTrue(
            requiresDeckBatchMutationReconciliation(
                confirmedWrites = 1,
                failedResponseStatus = 409,
            ),
        )
        assertFalse(
            requiresDeckBatchMutationReconciliation(
                confirmedWrites = 0,
                failedResponseStatus = 409,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            requiresDeckBatchMutationReconciliation(
                confirmedWrites = -1,
                failedResponseStatus = 409,
            )
        }
    }

    @Test
    fun unknownBoardDeletePreservesUndoOnlyWhenTheBoardDisappeared() {
        val board = board(etag = "\"board-1\"")
        val recovery = DeckUiBoardRecovery(
            board = board,
            verification = DeckBoardRecoveryVerification.DeleteOutcome,
        )

        assertNull(reconcileDeckBoardRecovery(recovery, listOf(board)))
        val confirmedDelete = requireNotNull(reconcileDeckBoardRecovery(recovery, emptyList()))
        assertFalse(confirmedDelete.verifying)
        assertTrue(confirmedDelete.errorMessage?.contains("restore") == true)
    }

    @Test
    fun unknownBoardRestoreClearsRecoveryOnlyWhenTheBoardReturned() {
        val board = board(etag = "\"board-1\"")
        val recovery = DeckUiBoardRecovery(
            board = board,
            verification = DeckBoardRecoveryVerification.RestoreOutcome,
        )

        assertNull(reconcileDeckBoardRecovery(recovery, listOf(board)))
        val unconfirmedRestore = requireNotNull(reconcileDeckBoardRecovery(recovery, emptyList()))
        assertFalse(unconfirmedRestore.verifying)
        assertTrue(unconfirmedRestore.errorMessage?.contains("try again") == true)
    }

    @Test
    fun etagsDetectChangesAndMissingEtagsCompareTheCompleteEditableCard() {
        val original = card("\"revision-1\"")
        assertTrue(original.hasSameAuthoritativeRevision(card("\"revision-1\"")))
        assertFalse(original.hasSameAuthoritativeRevision(card("\"revision-2\"")))
        assertTrue(original.copy(etag = null).hasSameAuthoritativeRevision(card(null)))
        assertFalse(
            original.copy(etag = null).hasSameAuthoritativeRevision(
                card(null).copy(descriptionMarkdown = "Changed elsewhere"),
            ),
        )
        assertFalse(
            original.copy(etag = null).hasSameAuthoritativeRevision(
                card(null).copy(stackId = 99),
            ),
        )
    }

    @Test
    fun boardUpdatesRejectChangedEtagsAndUseModeledFieldsWhenEtagsAreMissing() {
        val original = board(etag = "\"board-1\"")

        assertTrue(original.hasSameAuthoritativeRevision(board(etag = "\"board-1\"")))
        assertFalse(original.hasSameAuthoritativeRevision(board(etag = "\"board-2\"")))
        assertTrue(
            original.copy(etag = null).hasSameAuthoritativeRevision(
                board(etag = null),
            ),
        )
        assertFalse(
            original.copy(etag = null).hasSameAuthoritativeRevision(
                board(etag = null).copy(archived = true),
            ),
        )
    }

    private fun board(etag: String?) = DeckBoard(
        id = 1,
        title = "Synthetic board",
        color = "a970ff",
        archived = false,
        owner = null,
        labels = emptyList(),
        users = emptyList(),
        permissions = DeckPermissions(
            canRead = true,
            canEdit = true,
            canManage = true,
            canShare = false,
        ),
        shared = false,
        lastModified = 123L,
        etag = etag,
    )

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
