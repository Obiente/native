package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.BoardOperation

/** Initial board reads: the board list, and one board's lists with their cards. */
internal data class LoadedDeckBoards(
    val boards: List<DeckBoard>,
    val archivedBoards: List<DeckBoard> = emptyList(),
)

internal data class LoadedDeckStacks(
    val board: DeckBoard,
    val stacks: List<DeckStack>,
)

internal suspend fun loadDeckBoards(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    routes: SemanticBoardRoutes,
): LoadedDeckBoards {
    val response = execute(routes.boards())
    val collection = parseDeckResponseOffUi { parseDeckBoardCollection(response) }
    return LoadedDeckBoards(boards = collection.active, archivedBoards = collection.archived)
}

internal suspend fun loadDeckStacks(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    routes: SemanticBoardRoutes,
    board: DeckBoard,
): LoadedDeckStacks {
    val authoritativeBoard = fetchAuthoritativeBoard(execute, routes, board.id)
    return LoadedDeckStacks(
        board = authoritativeBoard,
        stacks = fetchBoardStacks(execute, routes, board.id),
    )
}

/**
 * Reads one board. A contract without a single-board read is answered from a fresh board list,
 * which is equally authoritative for the board record.
 */
internal suspend fun fetchAuthoritativeBoard(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    routes: SemanticBoardRoutes,
    boardId: Long,
): DeckBoard = if (routes.supports(BoardOperation.ReadBoard)) {
    val response = execute(routes.board(boardId, authoritative = true))
    parseDeckResponseOffUi { parseDeckBoard(response) }
} else {
    val response = execute(routes.boards().copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork))
    parseDeckResponseOffUi { parseDeckBoardFromList(boardId, response) }
}

/** Reads one card, or re-reads its list when the contract describes no single-card read. */
internal suspend fun fetchAuthoritativeCard(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    routes: SemanticBoardRoutes,
    card: DeckCard,
): DeckCard = if (routes.supports(BoardOperation.ReadCard)) {
    val response = execute(routes.card(card, authoritative = true))
    parseDeckResponseOffUi { parseDeckCard(card.boardId, card.stackId, response) }
} else {
    fetchBoardStacks(execute, routes, card.boardId, authoritative = true)
        .firstNotNullOfOrNull { stack -> stack.cards.firstOrNull { it.id == card.id } }
        ?: error("The card is no longer on this board.")
}

/**
 * Reads a board's lists. Lists that do not embed their cards are completed through the contract's
 * per-list card read, bounded so a very wide board cannot issue an unbounded request burst. A list
 * past that bound is marked as not loaded, so it is never presented as an empty list.
 */
internal suspend fun fetchBoardStacks(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    routes: SemanticBoardRoutes,
    boardId: Long,
    authoritative: Boolean = false,
    archived: Boolean = false,
): List<DeckStack> {
    val request = if (archived) routes.archivedStacks(boardId) else routes.stacks(boardId)
    val response = execute(if (authoritative) request.copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork) else request)
    val stacks = parseDeckResponseOffUi { parseDeckStacks(boardId, response, includeArchivedCards = archived) }
    if (!routes.supports(BoardOperation.ListCards)) return stacks
    return stacks.mapIndexed { index, stack ->
        if (stack.cardsEmbedded) {
            stack
        } else if (index >= MAX_SEPARATE_CARD_LIST_READS) {
            stack.copy(cardsLoaded = false)
        } else {
            val cardsResponse = execute(routes.cards(boardId, stack.id))
            stack.copy(
                cards = parseDeckResponseOffUi {
                    parseDeckStackCards(boardId, stack.id, cardsResponse, includeArchivedCards = archived)
                },
            )
        }
    }
}

internal const val MAX_SEPARATE_CARD_LIST_READS = 40
internal const val DECK_UNLOADED_LIST_MESSAGE =
    "This board has more lists than can be loaded at once, so this list's cards were not loaded. " +
        "Archive lists you no longer need to see its cards here."
