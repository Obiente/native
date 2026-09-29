package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.DeckBoard
import dev.obiente.nextcloudnative.app.DeckCard
import dev.obiente.nextcloudnative.app.DeckPermissions
import dev.obiente.nextcloudnative.app.DeckStack
import dev.obiente.nextcloudnative.app.DeckUser
import dev.obiente.nextcloudnative.app.DeckWorkspaceState
import dev.obiente.nextcloudnative.app.NativeDeckBoardSurface
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities

/**
 * Disposable synthetic Deck workspace for pointer and responsive-layout QA.
 *
 * It has no account, network, persistence, or server write path.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Deck interaction QA",
        state = rememberWindowState(width = 1_180.dp, height = 760.dp),
    ) {
        NextcloudNativeTheme(darkTheme = true) {
            CompositionLocalProvider(
                LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                    isDesktop = true,
                    usesDenseControls = true,
                    supportsAuxiliaryPane = true,
                ),
            ) {
                var stacks by remember { mutableStateOf(deckQaStacks()) }
                NextcloudAppBackground {
                    NativeDeckBoardSurface(
                        state = DeckWorkspaceState.Board(
                            board = deckQaBoard(),
                            stacks = stacks,
                        ),
                        onExit = {},
                        onSelectBoard = {},
                        onBackToBoards = {},
                        onOpenCard = {},
                        onSelectCard = {},
                        onDismissCard = {},
                        onRetry = {},
                        onMoveCard = { card, destination, insertionIndex ->
                            stacks = stacks.moveQaCard(card, destination, insertionIndex)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun List<DeckStack>.moveQaCard(
    card: DeckCard,
    destination: DeckStack,
    insertionIndex: Int,
): List<DeckStack> {
    val withoutCard = map { stack ->
        stack.copy(cards = stack.cards.filterNot { it.id == card.id })
    }
    return withoutCard.map { stack ->
        if (stack.id != destination.id) {
            stack
        } else {
            val cards = stack.cards.toMutableList()
            cards.add(
                insertionIndex.coerceIn(0, cards.size),
                card.copy(stackId = destination.id),
            )
            stack.copy(cards = cards)
        }
    }
}

private fun deckQaBoard() = DeckBoard(
    id = 1,
    title = "Release planning",
    color = "8b5cf6",
    archived = false,
    owner = DeckUser("qa-user", "QA user"),
    labels = emptyList(),
    permissions = DeckPermissions(
        canRead = true,
        canEdit = true,
        canManage = true,
        canShare = false,
    ),
    shared = false,
    lastModified = null,
    etag = null,
)

private fun deckQaStacks(): List<DeckStack> = listOf(
    deckQaStack(
        id = 10,
        title = "Planned",
        cards = listOf(
            deckQaCard(100, 10, "Confirm release notes", 100),
            deckQaCard(101, 10, "Check Android package", 200),
            deckQaCard(102, 10, "Verify desktop update", 300),
        ),
    ),
    deckQaStack(
        id = 20,
        title = "In progress",
        cards = listOf(
            deckQaCard(200, 20, "Exercise native card drag", 100),
            deckQaCard(201, 20, "Review compact layout", 200),
        ),
    ),
    deckQaStack(
        id = 30,
        title = "Done",
        cards = listOf(
            deckQaCard(300, 30, "Prepare synthetic fixture", 100),
        ),
    ),
)

private fun deckQaStack(
    id: Long,
    title: String,
    cards: List<DeckCard>,
) = DeckStack(
    id = id,
    boardId = 1,
    title = title,
    order = id,
    doneColumn = title == "Done",
    cards = cards,
    lastModified = null,
    etag = null,
)

private fun deckQaCard(
    id: Long,
    stackId: Long,
    title: String,
    order: Long,
) = DeckCard(
    id = id,
    boardId = 1,
    stackId = stackId,
    title = title,
    descriptionMarkdown = null,
    ownerId = "qa-user",
    color = null,
    order = order,
    dueAt = null,
    startAt = null,
    completedAt = null,
    archived = false,
    overdue = false,
    labels = emptyList(),
    assignees = emptyList(),
    attachmentCount = 0,
    unreadCommentCount = 0,
    etag = null,
)
