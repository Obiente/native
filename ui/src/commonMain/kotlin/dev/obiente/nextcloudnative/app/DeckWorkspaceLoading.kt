package dev.obiente.nextcloudnative.app

/** Initial Deck reads: capability negotiation, the board list, and one board's lists. */
internal data class LoadedDeckBoards(
    val route: DeckReadRoutePlan,
    val boards: List<DeckBoard>,
    val capabilities: DeckCapabilities?,
    val archivedBoards: List<DeckBoard> = emptyList(),
)

internal data class LoadedDeckStacks(
    val route: DeckReadRoutePlan,
    val board: DeckBoard,
    val stacks: List<DeckStack>,
)

internal suspend fun loadDeckBoards(
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
    val capabilities = capabilityResponse.takeIf { it.status in 200..299 }
        ?.let { response ->
            parseDeckResponseOffUi {
                parseDeckCapabilities(response)
            }
        }
    val negotiation = negotiateDeckReadRoutes(capabilities)
    var route = negotiation.candidates.first()
    while (true) {
        val response = services.executeNextcloudApi(session, route.boards(details = true))
        if (response.status in 200..299) {
            val collection = parseDeckResponseOffUi { parseDeckBoardCollection(response) }
            return LoadedDeckBoards(
                route = route,
                boards = collection.active,
                capabilities = capabilities,
                archivedBoards = collection.archived,
            )
        }
        route = negotiation.nextAfter(route, response.status)
            ?: return LoadedDeckBoards(
                route = route,
                boards = parseDeckResponseOffUi {
                    parseDeckBoards(response)
                },
                capabilities = capabilities,
            )
    }
}

internal suspend fun loadDeckStacks(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    board: DeckBoard,
    preferredRoute: DeckReadRoutePlan?,
): LoadedDeckStacks {
    val negotiation = negotiateDeckReadRoutes(null)
    var route = preferredRoute ?: negotiation.candidates.first()
    while (true) {
        val boardResponse = services.executeNextcloudApi(
            session,
            route.workspaceBoardMetadata(board.id),
        )
        if (boardResponse.status !in 200..299) {
            route = negotiation.nextAfter(route, boardResponse.status)
                ?: return LoadedDeckStacks(
                    route = route,
                    board = parseDeckResponseOffUi {
                        parseDeckBoard(boardResponse)
                    },
                    stacks = emptyList(),
                )
            continue
        }
        val authoritativeBoard = parseDeckResponseOffUi {
            parseDeckBoard(boardResponse)
        }
        val stacksResponse = services.executeNextcloudApi(session, route.stacks(board.id))
        if (stacksResponse.status in 200..299) {
            return LoadedDeckStacks(
                route = route,
                board = authoritativeBoard,
                stacks = parseDeckResponseOffUi {
                    parseDeckStacks(board.id, stacksResponse)
                },
            )
        }
        route = negotiation.nextAfter(route, stacksResponse.status)
            ?: return LoadedDeckStacks(
                route = route,
                board = authoritativeBoard,
                stacks = parseDeckResponseOffUi {
                    parseDeckStacks(board.id, stacksResponse)
                },
            )
    }
}

private const val DECK_CAPABILITY_RESPONSE_BYTES = 2L * 1024L * 1024L
