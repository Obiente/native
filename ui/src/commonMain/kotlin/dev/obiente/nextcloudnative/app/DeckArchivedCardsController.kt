package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.nativeui.runtime.BoardOperation
import kotlinx.coroutines.CancellationException

data class DeckUiArchivedCardsState(
    val board: DeckBoard,
    val groups: List<DeckArchivedCardGroup> = emptyList(),
    val loading: Boolean = true,
    val busyCardId: Long? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
    val outcomeUnknown: Boolean = false,
    val pendingDeletion: DeckCard? = null,
    val canRestore: Boolean = true,
    val canDelete: Boolean = true,
) {
    val canChangeCards: Boolean
        get() = board.permissions.canEdit && !board.archived && busyCardId == null && !outcomeUnknown && !loading
}

/**
 * State holder for the archived cards view. Restoring and deleting are each verified by reading
 * the archived lists again; the board lanes reload after any confirmed change.
 */
internal class DeckArchivedCardsController(private val context: DeckBoardToolContext) {
    var state: DeckUiArchivedCardsState? by mutableStateOf(null)
        private set

    /** Advances whenever the dialog opens or closes, so a load started for an earlier dialog is dropped. */
    private var dialogGeneration = 0

    fun open(board: DeckBoard) {
        dialogGeneration++
        state = DeckUiArchivedCardsState(
            board = board,
            canRestore = context.routes.supports(BoardOperation.UnarchiveCard),
            canDelete = context.routes.supports(BoardOperation.DeleteCard),
        )
    }

    fun close() {
        if (state?.busyCardId != null) return
        dialogGeneration++
        state = null
    }

    suspend fun load() {
        val current = state ?: return
        val generation = dialogGeneration
        state = current.copy(loading = true, errorMessage = null)
        try {
            val groups = fetchGroups(current.board.id)
            if (generation != dialogGeneration) return
            state = state?.copy(groups = groups, loading = false, outcomeUnknown = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation != dialogGeneration) return
            state = state?.copy(
                loading = false,
                errorMessage = failure.message ?: "Archived cards could not be loaded.",
            )
        }
    }

    suspend fun unarchive(card: DeckCard) = write(
        card = card,
        actionLabel = "restore card",
        request = { access -> context.routes.unarchiveCard(access, card) },
        successNotice = "${card.title} is back on the board",
    )

    fun requestDeletion(card: DeckCard) {
        val current = state ?: return
        if (current.canChangeCards) state = current.copy(pendingDeletion = card, errorMessage = null)
    }

    fun cancelDeletion() {
        val current = state ?: return
        if (current.busyCardId == null) state = current.copy(pendingDeletion = null)
    }

    suspend fun confirmDeletion() {
        val card = state?.pendingDeletion ?: return
        write(
            card = card,
            actionLabel = "delete card",
            request = { access -> context.routes.deleteCard(access, card) },
            successNotice = "${card.title} was deleted",
        )
    }

    private suspend fun write(
        card: DeckCard,
        actionLabel: String,
        request: (DeckBoardAccess) -> NextcloudApiRequest,
        successNotice: String,
    ) {
        val current = state ?: return
        if (!current.canChangeCards) return
        val planned = try {
            require(card.boardId == current.board.id) { "The card belongs to another board." }
            require(current.groups.any { group -> group.cards.any { it.id == card.id } }) {
                "The card is no longer archived."
            }
            request(DeckBoardAccess.from(current.board))
        } catch (invalid: IllegalArgumentException) {
            state = current.copy(errorMessage = invalid.message, pendingDeletion = null)
            return
        } catch (invalid: IllegalStateException) {
            state = current.copy(errorMessage = invalid.message, pendingDeletion = null)
            return
        }
        state = current.copy(busyCardId = card.id, errorMessage = null, notice = null)
        val outcome = runVerifiedDeckWrite(
            execute = context.execute,
            request = planned,
            reread = { fetchGroups(current.board.id) },
            verify = { groups ->
                if (groups.none { group -> group.cards.any { it.id == card.id } }) {
                    DeckWriteVerification.Confirmed
                } else {
                    DeckWriteVerification.NotApplied("The app did not change ${card.title}.")
                }
            },
            actionLabel = actionLabel,
        )
        val latest = state ?: return
        state = when (outcome) {
            is DeckVerifiedWriteOutcome.Confirmed -> {
                context.onBoardContentChanged()
                latest.copy(
                    groups = outcome.snapshot,
                    busyCardId = null,
                    pendingDeletion = null,
                    notice = successNotice,
                )
            }
            is DeckVerifiedWriteOutcome.Rejected -> latest.copy(
                groups = outcome.snapshot ?: latest.groups,
                busyCardId = null,
                pendingDeletion = null,
                errorMessage = outcome.message,
            )
            is DeckVerifiedWriteOutcome.Unknown -> latest.copy(
                busyCardId = null,
                pendingDeletion = null,
                outcomeUnknown = true,
                errorMessage = outcome.message,
            )
        }
    }

    private suspend fun fetchGroups(boardId: Long): List<DeckArchivedCardGroup> {
        val stacks = fetchBoardStacks(context.execute, context.routes, boardId, authoritative = true, archived = true)
        return deckArchivedCardGroups(stacks)
    }
}
