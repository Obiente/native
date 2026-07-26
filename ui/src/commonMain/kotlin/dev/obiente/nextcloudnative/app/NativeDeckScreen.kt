package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException

/**
 * Read-only production host for the native Deck workspace.
 *
 * The host negotiates only the typed, supported Deck API versions and keeps board, stack, and
 * card identities separate. Mutation callbacks deliberately remain unavailable until the caller
 * can provide the required permission, conflict, and postcondition guarantees.
 */
@Composable
fun NativeDeckScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    modifier: Modifier = Modifier,
) {
    var state by remember(session) { mutableStateOf<DeckWorkspaceState>(DeckWorkspaceState.Loading) }
    var loadedBoards by remember(session) { mutableStateOf<List<DeckBoard>>(emptyList()) }
    var canCreateBoards by remember(session) { mutableStateOf(false) }
    var activeRoute by remember(session) { mutableStateOf<DeckReadRoutePlan?>(null) }
    var requestedBoard by remember(session) { mutableStateOf<DeckBoard?>(null) }
    var loadAttempt by remember(session) { mutableStateOf(0) }

    LaunchedEffect(session, requestedBoard?.id, loadAttempt) {
        val board = requestedBoard
        if (board == null) {
            if (loadedBoards.isEmpty()) state = DeckWorkspaceState.Loading
            runCatching {
                loadDeckBoards(services, session)
            }.onSuccess { loaded ->
                loadedBoards = loaded.boards
                canCreateBoards = loaded.canCreateBoards
                activeRoute = loaded.route
                state = if (loaded.boards.isEmpty()) {
                    DeckWorkspaceState.Empty(
                        title = "No boards",
                        message = "Create a board in Deck to start organizing cards.",
                        canCreateBoards = loaded.canCreateBoards,
                    )
                } else {
                    DeckWorkspaceState.BoardPicker(
                        boards = loaded.boards,
                        canCreateBoards = loaded.canCreateBoards,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = DeckWorkspaceState.Error(
                    title = "Deck could not load",
                    message = error.message ?: "The server did not return the Deck workspace.",
                    cachedState = loadedBoards.takeIf { it.isNotEmpty() }?.let {
                        DeckWorkspaceState.BoardPicker(it, canCreateBoards)
                    },
                )
            }
        } else {
            state = DeckWorkspaceState.Loading
            runCatching {
                loadDeckStacks(
                    services = services,
                    session = session,
                    board = board,
                    preferredRoute = activeRoute,
                )
            }.onSuccess { loaded ->
                activeRoute = loaded.route
                state = DeckWorkspaceState.Board(board = board, stacks = loaded.stacks)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = DeckWorkspaceState.Error(
                    title = "Board could not load",
                    message = error.message ?: "The server did not return this board.",
                    cachedState = DeckWorkspaceState.BoardPicker(loadedBoards, canCreateBoards),
                )
            }
        }
    }

    NativeDeckBoardSurface(
        state = state,
        onSelectBoard = { board ->
            requestedBoard = board
        },
        onBackToBoards = {
            requestedBoard = null
            state = if (loadedBoards.isEmpty()) {
                DeckWorkspaceState.Empty(
                    title = "No boards",
                    message = "Create a board in Deck to start organizing cards.",
                    canCreateBoards = canCreateBoards,
                )
            } else {
                DeckWorkspaceState.BoardPicker(loadedBoards, canCreateBoards)
            }
        },
        onOpenCard = { card ->
            state = (state as? DeckWorkspaceState.Board)?.copy(selectedCardId = card.id) ?: state
        },
        onSelectCard = { card ->
            state = (state as? DeckWorkspaceState.Board)?.copy(selectedCardId = card.id) ?: state
        },
        onDismissCard = {
            state = (state as? DeckWorkspaceState.Board)?.copy(selectedCardId = null) ?: state
        },
        onRetry = { loadAttempt += 1 },
        onCreateBoard = null,
        onCreateCard = null,
        modifier = modifier,
    )
}

private data class LoadedDeckBoards(
    val route: DeckReadRoutePlan,
    val boards: List<DeckBoard>,
    val canCreateBoards: Boolean,
)

private data class LoadedDeckStacks(
    val route: DeckReadRoutePlan,
    val stacks: List<DeckStack>,
)

private suspend fun loadDeckBoards(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): LoadedDeckBoards {
    val capabilityResponse = services.executeNextcloudApi(
        session,
        NextcloudApiRequest(
            method = NextcloudApiMethod.GET,
            relativePath = "/ocs/v2.php/cloud/capabilities",
            queryParameters = mapOf("format" to "json"),
            ocsApiRequest = true,
            maximumResponseBytes = DECK_CAPABILITY_RESPONSE_BYTES,
        ),
    )
    val capabilities = when (capabilityResponse.status) {
        in 200..299 -> parseDeckCapabilities(capabilityResponse)
        401, 403 -> parseDeckCapabilities(capabilityResponse)
        else -> null
    }
    val negotiation = negotiateDeckReadRoutes(capabilities)
    var route = negotiation.candidates.first()
    while (true) {
        val response = services.executeNextcloudApi(session, route.boards())
        if (response.status in 200..299) {
            return LoadedDeckBoards(
                route = route,
                boards = parseDeckBoards(response),
                canCreateBoards = capabilities?.canCreateBoards == true,
            )
        }
        route = negotiation.nextAfter(route, response.status)
            ?: return LoadedDeckBoards(
                route = route,
                boards = parseDeckBoards(response),
                canCreateBoards = capabilities?.canCreateBoards == true,
            )
    }
}

private suspend fun loadDeckStacks(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    board: DeckBoard,
    preferredRoute: DeckReadRoutePlan?,
): LoadedDeckStacks {
    val negotiation = negotiateDeckReadRoutes(null)
    var route = preferredRoute ?: negotiation.candidates.first()
    while (true) {
        val response = services.executeNextcloudApi(session, route.stacks(board.id))
        if (response.status in 200..299) {
            return LoadedDeckStacks(route, parseDeckStacks(board.id, response))
        }
        route = negotiation.nextAfter(route, response.status)
            ?: return LoadedDeckStacks(route, parseDeckStacks(board.id, response))
    }
}

private const val DECK_CAPABILITY_RESPONSE_BYTES = 2L * 1024L * 1024L
