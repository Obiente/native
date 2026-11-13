package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Board-level tools that open from a board's overflow menu. */
internal class DeckBoardTools(context: DeckBoardToolContext) {
    val sharing = DeckBoardSharingController(context)
    val settings = DeckBoardSettingsController(context)
    val archive = DeckArchivedCardsController(context)

    /**
     * Sharing, settings, and archived cards for [board]. Archiving the board itself stays with the
     * workspace because it changes which board list the board belongs to.
     */
    fun actions(
        board: DeckBoard,
        boardOpen: Boolean,
        onToggleArchived: ((DeckBoard) -> Unit)?,
    ): List<NextcloudCardAction> = buildList {
        if (board.canOpenSharing()) {
            add(NextcloudCardAction(if (board.canAddShares()) "Share board" else "Board access") { sharing.open(board) })
        }
        if (board.permissions.canRead) {
            add(NextcloudCardAction("Board settings") { settings.open(board) })
        }
        if (boardOpen && board.permissions.canRead) {
            add(NextcloudCardAction("Archived cards") { archive.open(board) })
        }
        if (onToggleArchived != null && board.canChangeArchiveState() && board.color != null) {
            add(
                NextcloudCardAction(if (board.archived) "Unarchive board" else "Archive board") {
                    onToggleArchived(board)
                },
            )
        }
    }
}

@Composable
internal fun DeckBoardToolsHost(tools: DeckBoardTools, currentUserId: String) {
    val scope = rememberCoroutineScope()
    tools.sharing.state?.let { state ->
        val boardId = state.board.id
        val query = state.query
        // The board list may omit the share list, so the sheet reads the board itself first.
        LaunchedEffect(tools.sharing, boardId) { tools.sharing.refresh() }
        LaunchedEffect(tools.sharing, boardId, query) {
            if (query.isNotBlank()) delay(DECK_SHARE_SEARCH_DEBOUNCE_MILLIS)
            tools.sharing.search(query)
        }
        DeckBoardSharingDialog(
            state = state,
            currentUserId = currentUserId,
            onQueryChange = tools.sharing::updateQuery,
            onAdd = { sharee -> scope.launch { tools.sharing.add(sharee) } },
            onSetPermissions = { acl, permissions ->
                scope.launch { tools.sharing.setPermissions(acl, permissions) }
            },
            onRequestRemoval = tools.sharing::requestRemoval,
            onRefresh = { scope.launch { tools.sharing.refresh() } },
            onDismiss = tools.sharing::close,
        )
        state.pendingRemoval?.let { acl ->
            DeckUiDestructiveConfirmationDialog(
                title = "Remove access for ${acl.participant.displayName}?",
                message = "They will no longer see ${state.board.title}. " +
                    "Deck also removes their card assignments on this board.",
                busy = state.busy,
                errorMessage = null,
                onDismiss = tools.sharing::cancelRemoval,
                onConfirm = { scope.launch { tools.sharing.confirmRemoval() } },
            )
        }
    }
    tools.settings.state?.let { state ->
        LaunchedEffect(tools.settings, state.board.id) { tools.settings.load() }
        DeckBoardSettingsDialog(
            state = state,
            onChange = { change -> scope.launch { tools.settings.change(change) } },
            onRetry = { scope.launch { tools.settings.load() } },
            onDismiss = tools.settings::close,
        )
    }
    tools.archive.state?.let { state ->
        LaunchedEffect(tools.archive, state.board.id) { tools.archive.load() }
        DeckArchivedCardsDialog(
            state = state,
            onRestore = { card -> scope.launch { tools.archive.unarchive(card) } },
            onRequestDelete = tools.archive::requestDeletion,
            onRefresh = { scope.launch { tools.archive.load() } },
            onDismiss = tools.archive::close,
        )
        state.pendingDeletion?.let { card ->
            DeckUiDestructiveConfirmationDialog(
                title = "Delete ${card.title}?",
                message = "This removes the archived card from the board.",
                busy = state.busyCardId != null,
                errorMessage = null,
                onDismiss = tools.archive::cancelDeletion,
                onConfirm = { scope.launch { tools.archive.confirmDeletion() } },
            )
        }
    }
}

/**
 * Applies a planned list move. Each update sets an absolute order, so a partial failure is
 * repaired by refreshing the board and moving again; no update is retried automatically.
 * Returns a message for the user, or null when every update was accepted.
 */
internal suspend fun runDeckStackMove(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    plan: DeckWriteRoutePlan,
    board: DeckBoard,
    updates: List<DeckStackOrderUpdate>,
): String? {
    if (updates.isEmpty()) return null
    val access = DeckBoardAccess.from(board)
    var applied = 0
    for (update in updates) {
        try {
            val response = execute(
                plan.updateStack(
                    access,
                    DeckStackContext(DeckBoardId(update.stack.boardId), update.stack.id),
                    DeckStackDraft(update.stack.title, update.order),
                ),
            )
            parseDeckMutationReceipt(response, expectedId = update.stack.id)
            applied += 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf(String::isNotBlank) ?: "Deck could not move the list."
            return if (applied == 0) reason else "Some lists moved before an error. $reason The board was refreshed."
        }
    }
    return null
}

private const val DECK_SHARE_SEARCH_DEBOUNCE_MILLIS = 300L
