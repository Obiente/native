package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

data class DeckUiSharingState(
    val board: DeckBoard,
    val query: String = "",
    val results: List<DeckSharee> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val errorMessage: String? = null,
    /** A write could not be confirmed. Further writes wait for a successful refresh. */
    val outcomeUnknown: Boolean = false,
    val pendingRemoval: DeckAcl? = null,
) {
    val writesEnabled: Boolean
        get() = !busy && !outcomeUnknown

    /** Search results the user can still add, excluding the owner and existing shares. */
    val addableResults: List<DeckSharee>
        get() = results.filterNot { sharee ->
            (sharee.type == DeckAclType.User && sharee.id == board.owner?.id) ||
                board.acl.any { it.type == sharee.type && it.participant.id == sharee.id }
        }
}

/**
 * State holder for the board sharing sheet. Every write is verified with a fresh board read, and
 * an unconfirmed write blocks further sharing changes until a refresh reconciles the state.
 */
internal class DeckBoardSharingController(private val context: DeckBoardToolContext) {
    var state: DeckUiSharingState? by mutableStateOf(null)
        private set
    private var searchGeneration = 0L

    fun open(board: DeckBoard) {
        require(board.canOpenSharing()) { "You cannot manage sharing for this board." }
        searchGeneration += 1L
        state = DeckUiSharingState(board = board, searching = board.canAddShares())
    }

    fun close() {
        if (state?.busy == true) return
        searchGeneration += 1L
        state = null
    }

    fun updateQuery(query: String) {
        val current = state ?: return
        state = current.copy(query = query, searchError = null)
    }

    /** Runs one search. Callers debounce typing; stale responses are discarded. */
    suspend fun search(query: String) {
        val current = state ?: return
        if (!current.board.canAddShares()) return
        val generation = ++searchGeneration
        state = current.copy(searching = true, searchError = null)
        try {
            val response = context.execute(deckShareeSearchRequest(current.board.id, query))
            val results = parseDeckResponseOffUi { parseDeckSharees(response) }
            if (generation == searchGeneration) {
                state = state?.copy(results = results, searching = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == searchGeneration) {
                state = state?.copy(
                    searching = false,
                    searchError = failure.message ?: "People could not be searched.",
                )
            }
        }
    }

    suspend fun refresh() {
        val current = state ?: return
        if (current.busy) return
        state = current.copy(busy = true, errorMessage = null, notice = null)
        try {
            val board = context.fetchBoard(current.board.id)
            context.onBoardRefreshed(board)
            state = state?.copy(board = board, busy = false, outcomeUnknown = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state = state?.copy(
                busy = false,
                errorMessage = failure.message ?: "The board could not be refreshed.",
            )
        }
    }

    suspend fun add(sharee: DeckSharee) = write(
        actionLabel = "share board",
        request = { board -> context.writePlan().addBoardAcl(board, sharee) },
        verify = { board -> verifyDeckAclAdded(board, sharee) },
        successNotice = "Shared with ${sharee.displayName}",
    )

    suspend fun setPermissions(acl: DeckAcl, requested: DeckAclPermissions) = write(
        actionLabel = "change permissions",
        request = { board ->
            context.writePlan().updateBoardAcl(board, acl, context.currentUserId, requested)
        },
        verify = { board -> verifyDeckAclUpdated(board, acl.id, requested) },
        successNotice = "Permissions updated for ${acl.participant.displayName}",
    )

    fun requestRemoval(acl: DeckAcl) {
        val current = state ?: return
        if (!current.writesEnabled) return
        if (!current.board.aclEditability(acl, context.currentUserId).canRemove) return
        state = current.copy(pendingRemoval = acl, errorMessage = null)
    }

    fun cancelRemoval() {
        val current = state ?: return
        if (!current.busy) state = current.copy(pendingRemoval = null)
    }

    suspend fun confirmRemoval() {
        val acl = state?.pendingRemoval ?: return
        write(
            actionLabel = "remove access",
            request = { board ->
                context.writePlan().deleteBoardAcl(board, acl, context.currentUserId)
            },
            verify = { board -> verifyDeckAclRemoved(board, acl.id) },
            successNotice = "Removed access for ${acl.participant.displayName}",
        )
    }

    private suspend fun write(
        actionLabel: String,
        request: (DeckBoard) -> NextcloudApiRequest,
        verify: (DeckBoard) -> DeckWriteVerification,
        successNotice: String,
    ) {
        val current = state ?: return
        if (!current.writesEnabled) return
        val planned = try {
            request(current.board)
        } catch (invalid: IllegalArgumentException) {
            state = current.copy(errorMessage = invalid.message, pendingRemoval = null)
            return
        } catch (invalid: IllegalStateException) {
            state = current.copy(errorMessage = invalid.message, pendingRemoval = null)
            return
        }
        state = current.copy(busy = true, errorMessage = null, notice = null)
        val outcome = runVerifiedDeckWrite(
            execute = context.execute,
            request = planned,
            acceptResponse = ::requireDeckSharingResponse,
            reread = { context.fetchBoard(current.board.id) },
            verify = verify,
            actionLabel = actionLabel,
        )
        val latest = state ?: return
        state = when (outcome) {
            is DeckVerifiedWriteOutcome.Confirmed -> {
                context.onBoardRefreshed(outcome.snapshot)
                latest.copy(
                    board = outcome.snapshot,
                    busy = false,
                    pendingRemoval = null,
                    notice = outcome.message ?: successNotice,
                )
            }
            is DeckVerifiedWriteOutcome.Rejected -> {
                outcome.snapshot?.let(context.onBoardRefreshed)
                latest.copy(
                    board = outcome.snapshot ?: latest.board,
                    busy = false,
                    pendingRemoval = null,
                    errorMessage = outcome.message,
                )
            }
            is DeckVerifiedWriteOutcome.Unknown -> latest.copy(
                busy = false,
                pendingRemoval = null,
                outcomeUnknown = true,
                errorMessage = outcome.message,
            )
        }
    }
}

private fun requireDeckSharingResponse(response: NextcloudApiResponse) {
    require(response.status in 200..299) {
        when (response.status) {
            403 -> "You do not have permission to change sharing for this board."
            404 -> "This share or board no longer exists."
            else -> "Deck refused the sharing change (HTTP ${response.status})."
        }
    }
}
