package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.nativeui.runtime.BoardOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Board-level tools that open from a board's overflow menu, each offered only when described. */
internal class DeckBoardTools(
    private val context: DeckBoardToolContext,
    private val appSettings: SemanticSettings?,
) {
    val sharing = DeckBoardSharingController(context)
    val archive = DeckArchivedCardsController(context)

    /** The board whose settings sheet is open, or null. */
    var settingsBoard: DeckBoard? by mutableStateOf(null)
        private set
    val settingsController: SemanticSettingsController? = appSettings?.let { settings ->
        SemanticSettingsController(context.execute, settings)
    }

    fun closeSettings() {
        settingsBoard = null
    }

    private val routes get() = context.routes

    /**
     * Sharing, settings, and archived cards for [board]. Archiving the board itself stays with the
     * workspace because it changes which board list the board belongs to.
     */
    fun actions(
        board: DeckBoard,
        boardOpen: Boolean,
        onToggleArchived: ((DeckBoard) -> Unit)?,
    ): List<NextcloudCardAction> = buildList {
        val shareWrites = routes.supports(BoardOperation.AddShare) || routes.supports(BoardOperation.UpdateShare) ||
            routes.supports(BoardOperation.DeleteShare)
        if (board.canOpenSharing() && (shareWrites || board.acl.isNotEmpty())) {
            val canAdd = board.canAddShares() && routes.supports(BoardOperation.AddShare)
            add(NextcloudCardAction(if (canAdd) "Share board" else "Board access") { sharing.open(board) })
        }
        if (board.permissions.canRead && (appSettings != null || board.settings.isNotEmpty())) {
            add(NextcloudCardAction("Board settings") { settingsBoard = board })
        }
        if (boardOpen && board.permissions.canRead && routes.supports(BoardOperation.ListArchivedLanes)) {
            add(NextcloudCardAction("Archived cards") { archive.open(board) })
        }
        if (
            onToggleArchived != null && board.canChangeArchiveState() && board.color != null &&
            routes.declares(BoardOperation.UpdateBoard, dev.obiente.nextcloudnative.nativeui.runtime.BoardField.Archived)
        ) {
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
                    "The app may also remove their card assignments on this board.",
                busy = state.busy,
                errorMessage = null,
                onDismiss = tools.sharing::cancelRemoval,
                onConfirm = { scope.launch { tools.sharing.confirmRemoval() } },
            )
        }
    }
    tools.settingsBoard?.let { board ->
        SemanticSettingsDialog(
            title = "Board settings",
            controller = tools.settingsController,
            onDismiss = tools::closeSettings,
            readOnlyTitle = board.title,
            readOnlyEntries = board.settings.entries.map { (key, value) -> key.boardSettingLabel() to value },
        )
    }
    tools.archive.state?.let { state ->
        LaunchedEffect(tools.archive, state.board.id) { tools.archive.load() }
        DeckArchivedCardsDialog(
            state = state,
            onRestore = { card -> scope.launch { tools.archive.unarchive(card) } },
            onRequestDelete = tools.archive::requestDeletion,
            onRefresh = { scope.launch { tools.archive.load() } },
            onLoadMoreLists = { scope.launch { tools.archive.loadMoreLists() } },
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
    plan: SemanticBoardRoutes,
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
                    record = update.stack.rawFields,
                ),
            )
            parseDeckMutationReceipt(response, expectedId = update.stack.id)
            applied += 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf(String::isNotBlank) ?: "The list could not be moved."
            return if (applied == 0) reason else "Some lists moved before an error. $reason The board was refreshed."
        }
    }
    return null
}

private fun String.boardSettingLabel(): String = replace(Regex("[_:.-]+"), " ")
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .trim()
    .lowercase()
    .replaceFirstChar(Char::uppercase)

private const val DECK_SHARE_SEARCH_DEBOUNCE_MILLIS = 300L
