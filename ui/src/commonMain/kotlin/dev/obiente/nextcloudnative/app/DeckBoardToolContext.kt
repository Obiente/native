package dev.obiente.nextcloudnative.app

/**
 * Narrow dependencies for the board-level Deck tools (sharing, settings, archive).
 *
 * The tools receive the verified route plans and an authenticated request function instead of
 * the whole platform service, and report fresh authoritative boards back to the workspace.
 */
internal class DeckBoardToolContext(
    val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    val readPlan: () -> DeckReadRoutePlan,
    val writePlan: () -> DeckWriteRoutePlan,
    val currentUserId: String,
    /** Receives a board that was just read from the server. */
    val onBoardRefreshed: (DeckBoard) -> Unit,
    /** Asks the workspace to reload lists and cards after archive changes. */
    val onBoardContentChanged: () -> Unit,
) {
    suspend fun fetchBoard(boardId: Long): DeckBoard {
        val response = execute(readPlan().authoritativeBoard(boardId))
        return parseDeckResponseOffUi { parseDeckBoard(response) }
    }
}
