package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mikepenz.markdown.m3.Markdown
import dev.obiente.nextcloudnative.app.design.BoardDragVerticalScrollTarget
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragHandle
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragAutoScroll
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.app.design.resolveBoardDragVerticalLane
import kotlin.math.roundToInt

internal data class DeckWorkspacePresentation(
    val visibleState: DeckWorkspaceState,
    val visibleBoard: DeckBoard?,
    val cachedError: DeckWorkspaceState.Error?,
    val mutationsEnabled: Boolean,
)

internal fun resolveDeckWorkspacePresentation(
    state: DeckWorkspaceState,
    boardContext: DeckBoard?,
): DeckWorkspacePresentation {
    val error = state as? DeckWorkspaceState.Error
    val cachedState = error?.cachedState?.takeUnless { it is DeckWorkspaceState.Error }
    val visibleState = cachedState ?: state
    return DeckWorkspacePresentation(
        visibleState = visibleState,
        visibleBoard = (visibleState as? DeckWorkspaceState.Board)?.board ?: boardContext,
        cachedError = error?.takeIf { cachedState != null },
        mutationsEnabled = state is DeckWorkspaceState.Board ||
            state is DeckWorkspaceState.BoardPicker ||
            state is DeckWorkspaceState.Empty,
    )
}

@Composable
fun NativeDeckBoardSurface(
    state: DeckWorkspaceState,
    boardContext: DeckBoard? = null,
    onExit: () -> Unit,
    onSelectBoard: (DeckBoard) -> Unit,
    onBackToBoards: () -> Unit,
    onOpenCard: (DeckCard) -> Unit,
    onSelectCard: (DeckCard) -> Unit,
    onDismissCard: () -> Unit,
    onRetry: () -> Unit,
    onCreateBoard: (() -> Unit)? = null,
    onCreateStack: ((DeckBoard) -> Unit)? = null,
    onCreateCard: ((DeckStack) -> Unit)? = null,
    boardActions: (DeckBoard) -> List<NextcloudCardAction> = { emptyList() },
    stackActions: (DeckStack) -> List<NextcloudCardAction> = { emptyList() },
    cardActions: (DeckCard) -> List<NextcloudCardAction> = { emptyList() },
    onMoveCard: ((DeckCard, DeckStack, Int) -> Unit)? = null,
    boardRecovery: DeckUiBoardRecovery? = null,
    onRestoreBoard: ((DeckBoard) -> Unit)? = null,
    onDismissBoardRecovery: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val presentation = resolveDeckWorkspacePresentation(state, boardContext)
    val visibleState = presentation.visibleState
    val activeBoard = presentation.visibleBoard
    val authoritativeBoard = (state as? DeckWorkspaceState.Board)?.board
    val activeBoardActions = authoritativeBoard?.let(boardActions).orEmpty()
    var boardMenuExpanded by remember(activeBoard?.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = activeBoard?.title ?: "Deck",
            subtitle = (visibleState as? DeckWorkspaceState.Board)?.let { boardState ->
                "${boardState.stacks.size} lists - " +
                    "${boardState.stacks.sumOf { it.cards.size }} cards"
            } ?: "Boards, stacks, and cards",
            onBack = if (activeBoard == null) onExit else onBackToBoards,
            trailingContent = {
                if (
                    authoritativeBoard != null &&
                    onCreateStack != null &&
                    authoritativeBoard.permissions.canManage
                ) {
                    IconButton(onClick = { onCreateStack(authoritativeBoard) }) {
                        Icon(NextcloudIcons.Add, contentDescription = "Add list")
                    }
                }
                if (authoritativeBoard != null) {
                    NextcloudCardOverflow(
                        itemLabel = authoritativeBoard.title,
                        actions = activeBoardActions,
                        expanded = boardMenuExpanded,
                        onExpandedChange = { boardMenuExpanded = it },
                    )
                }
            },
        )
        HorizontalDivider()
        boardRecovery?.let { recovery ->
            DeckBoardRecoveryBanner(
                recovery = recovery,
                onRestore = onRestoreBoard,
                onDismiss = onDismissBoardRecovery,
            )
        }
        presentation.cachedError?.let { error ->
            DeckCachedErrorBanner(error, onRetry)
        }
        when (visibleState) {
            DeckWorkspaceState.Loading -> NativeDeckCenteredState(Modifier.weight(1f)) {
                CircularProgressIndicator()
            }
            is DeckWorkspaceState.Empty -> NativeDeckCenteredState(Modifier.weight(1f)) {
                Icon(NextcloudIcons.app("deck"), contentDescription = null, modifier = Modifier.size(44.dp))
                Text(visibleState.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    visibleState.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (
                    visibleState.canCreateBoards &&
                    onCreateBoard != null &&
                    presentation.mutationsEnabled
                ) {
                    Button(onClick = onCreateBoard) { Text("Create board") }
                }
            }
            is DeckWorkspaceState.Error -> NativeDeckCenteredState(Modifier.weight(1f)) {
                Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(44.dp))
                Text(visibleState.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    visibleState.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (visibleState.canRetry) OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
            is DeckWorkspaceState.BoardPicker -> DeckBoardPicker(
                state = visibleState,
                onSelectBoard = onSelectBoard,
                onCreateBoard = onCreateBoard.takeIf { presentation.mutationsEnabled },
                boardActions = if (presentation.mutationsEnabled) {
                    boardActions
                } else {
                    { _: DeckBoard -> emptyList() }
                },
                modifier = Modifier.weight(1f),
            )
            is DeckWorkspaceState.Board -> DeckBoardWorkspace(
                state = visibleState,
                onOpenCard = onOpenCard,
                onSelectCard = onSelectCard,
                onDismissCard = onDismissCard,
                onCreateCard = onCreateCard.takeIf { presentation.mutationsEnabled },
                stackActions = if (presentation.mutationsEnabled) {
                    stackActions
                } else {
                    { _: DeckStack -> emptyList() }
                },
                cardActions = if (presentation.mutationsEnabled) {
                    cardActions
                } else {
                    { _: DeckCard -> emptyList() }
                },
                onMoveCard = onMoveCard.takeIf { presentation.mutationsEnabled },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DeckBoardRecoveryBanner(
    recovery: DeckUiBoardRecovery,
    onRestore: ((DeckBoard) -> Unit)?,
    onDismiss: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Medium,
                vertical = NextcloudSpacing.Small,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (recovery.verification) {
                        DeckBoardRecoveryVerification.DeleteOutcome -> "Checking board deletion"
                        DeckBoardRecoveryVerification.RestoreOutcome -> "Checking board restore"
                        null -> "${recovery.board.title} was deleted"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    recovery.errorMessage ?: "You can restore this board.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = { onRestore?.invoke(recovery.board) },
                enabled = onRestore != null && !recovery.restoring && !recovery.verifying,
            ) {
                Text(
                    when {
                        recovery.verifying -> "Checking..."
                        recovery.restoring -> "Restoring..."
                        else -> "Undo"
                    },
                )
            }
            if (onDismiss != null && !recovery.restoring && !recovery.verifying) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun DeckCachedErrorBanner(
    error: DeckWorkspaceState.Error,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Medium,
                vertical = NextcloudSpacing.Small,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(error.title, style = MaterialTheme.typography.labelLarge)
                Text(
                    error.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (error.canRetry) {
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun NativeDeckCenteredState(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            NextcloudSpacing.Medium,
            Alignment.CenterVertically,
        ),
        content = content,
    )
}

@Composable
private fun DeckBoardPicker(
    state: DeckWorkspaceState.BoardPicker,
    onSelectBoard: (DeckBoard) -> Unit,
    onCreateBoard: (() -> Unit)?,
    boardActions: (DeckBoard) -> List<NextcloudCardAction>,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${state.boards.size} boards",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.canCreateBoards && onCreateBoard != null) {
                Button(onClick = onCreateBoard) {
                    Icon(NextcloudIcons.Add, contentDescription = null)
                    Spacer(Modifier.width(NextcloudSpacing.Small))
                    Text("New board")
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            items(state.boards, key = DeckBoard::id) { board ->
                val actions = boardActions(board)
                var menuExpanded by remember(board.id) { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
                        onOpen = { onSelectBoard(board) },
                        onShowActions = actions.takeIf { it.isNotEmpty() }?.let {
                            { menuExpanded = true }
                        },
                        openLabel = "Open ${board.title}",
                        actionsLabel = "Actions for ${board.title}",
                    ),
                    colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(NextcloudRadii.Small),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(NextcloudIcons.app("deck"), contentDescription = null)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                board.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    board.owner?.displayName,
                                    "Shared".takeIf { board.shared },
                                    "Read only".takeIf { !board.permissions.canEdit },
                                ).joinToString(" - ").ifBlank { "Private board" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (actions.isEmpty()) {
                            Icon(NextcloudIcons.ChevronRight, contentDescription = null)
                        } else {
                            NextcloudCardOverflow(
                                itemLabel = board.title,
                                actions = actions,
                                expanded = menuExpanded,
                                onExpandedChange = { menuExpanded = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckBoardWorkspace(
    state: DeckWorkspaceState.Board,
    onOpenCard: (DeckCard) -> Unit,
    onSelectCard: (DeckCard) -> Unit,
    onDismissCard: () -> Unit,
    onCreateCard: ((DeckStack) -> Unit)?,
    stackActions: (DeckStack) -> List<NextcloudCardAction>,
    cardActions: (DeckCard) -> List<NextcloudCardAction>,
    onMoveCard: ((DeckCard, DeckStack, Int) -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val showInspector = maxWidth >= 1100.dp && state.selectedCard != null
            Row(modifier = Modifier.fillMaxSize()) {
                DeckLanes(
                    stacks = state.stacks,
                    selectedCardId = state.selectedCardId,
                    onOpenCard = onOpenCard,
                    onSelectCard = onSelectCard,
                    onCreateCard = onCreateCard,
                    stackActions = stackActions,
                    cardActions = cardActions,
                    onMoveCard = onMoveCard,
                    modifier = Modifier.weight(1f),
                )
                if (showInspector) {
                    Box(
                        modifier = Modifier.fillMaxHeight().width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    state.selectedCard?.let { card ->
                        DeckCardInspector(
                            card = card,
                            actions = cardActions(card),
                            modifier = Modifier.width(340.dp).fillMaxHeight(),
                        )
                    }
                }
            }
            if (!showInspector) {
                state.selectedCard?.let { card ->
                    val actions = cardActions(card)
                    var menuExpanded by remember(card.id) { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = onDismissCard,
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(card.title, modifier = Modifier.weight(1f))
                                NextcloudCardOverflow(
                                    itemLabel = card.title,
                                    actions = actions,
                                    expanded = menuExpanded,
                                    onExpandedChange = { menuExpanded = it },
                                )
                            }
                        },
                        text = {
                            DeckCardInspector(
                                card = card,
                                actions = actions,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                                showTitle = false,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = onDismissCard) {
                                Text("Close")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckLanes(
    stacks: List<DeckStack>,
    selectedCardId: Long?,
    onOpenCard: (DeckCard) -> Unit,
    onSelectCard: (DeckCard) -> Unit,
    onCreateCard: ((DeckStack) -> Unit)?,
    stackActions: (DeckStack) -> List<NextcloudCardAction>,
    cardActions: (DeckCard) -> List<NextcloudCardAction>,
    onMoveCard: ((DeckCard, DeckStack, Int) -> Unit)?,
    modifier: Modifier,
) {
    if (stacks.isEmpty()) {
        NativeDeckCenteredState(modifier) {
            Text("No lists", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Add a list in Deck to start organizing cards.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val stackBounds = remember { DeckUiBoundsRegistry<Long>() }
    val cardBounds = remember { DeckUiBoundsRegistry<Long>() }
    val laneScrollBounds = remember { mutableMapOf<Long, Rect>() }
    val laneScrollStates = remember { mutableMapOf<Long, LazyListState>() }
    val boardScrollState = rememberLazyListState()
    var draggedCard by remember { mutableStateOf<DeckCard?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var dragGrabOffset by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<DeckUiCardDropTarget?>(null) }
    var lanesBounds by remember { mutableStateOf<Rect?>(null) }
    var terminalDropRequested by remember { mutableStateOf(false) }

    fun resolveDropTarget(card: DeckCard, position: Offset): DeckUiCardDropTarget? {
        val destination = stacks.firstOrNull { stack ->
            stackBounds.bounds(stack.id)?.contains(position.x, position.y) == true
        } ?: return null
        val destinationBounds = stackBounds.bounds(destination.id) ?: return null
        var insertionIndex = 0
        val visibleCardZones = buildList {
            destination.cards.forEach { candidate ->
                if (candidate.id == card.id) return@forEach
                cardBounds.bounds(candidate.id)?.let { bounds ->
                    add(
                        DeckUiCardDropZone(
                            card = candidate,
                            bounds = bounds,
                            insertionIndex = insertionIndex,
                        ),
                    )
                }
                insertionIndex += 1
            }
        }
        return resolveDeckUiCardDropTarget(
            pointerX = position.x,
            pointerY = position.y,
            zones = listOf(
                DeckUiStackDropZone(
                    stack = destination,
                    bounds = destinationBounds,
                    cards = visibleCardZones,
                ),
            ),
            draggedCard = card,
        )
    }

    fun clearDrag() {
        draggedCard = null
        dragPosition = null
        dragGrabOffset = Offset.Zero
        dropTarget = null
        terminalDropRequested = false
    }

    fun refreshDropTarget() {
        val position = dragPosition
        val activeCard = draggedCard
        if (position != null && activeCard != null) {
            dropTarget = resolveDropTarget(activeCard, position)
        }
    }

    fun commitDrop() {
        val card = draggedCard
        val target = dropTarget
        try {
            if (card != null && target != null && !target.isNoOpFor(card, stacks)) {
                onMoveCard?.invoke(
                    card,
                    target.stack,
                    target.insertionIndex,
                )
            }
        } finally {
            clearDrag()
        }
    }

    NextcloudBoardDragAutoScroll(
        activeDragKey = draggedCard?.id,
        position = dragPosition,
        boardViewport = lanesBounds,
        horizontalScrollState = boardScrollState,
        verticalScrollTargetAt = { position, boardViewport, activationHalo ->
            val laneId = resolveBoardDragVerticalLane(
                position = position,
                boardViewport = boardViewport,
                laneViewports = laneScrollBounds,
                verticalActivationHalo = activationHalo,
            )
            val viewport = laneId?.let(laneScrollBounds::get)
            val state = laneId?.let(laneScrollStates::get)
            if (viewport != null && state != null) {
                BoardDragVerticalScrollTarget(state, viewport)
            } else {
                null
            }
        },
        terminalDropRequested = terminalDropRequested,
        onTargetRefresh = ::refreshDropTarget,
        onTerminalDropReady = ::commitDrop,
    )

    Box(
        modifier = modifier.fillMaxHeight().onGloballyPositioned { coordinates ->
            lanesBounds = coordinates.boundsInWindow()
        },
    ) {
        LazyRow(
            state = boardScrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            items(stacks, key = DeckStack::id) { stack ->
                val stackBoundsOwner = remember(stack.id) { Any() }
                val laneScrollState = rememberLazyListState()
                DisposableEffect(stack.id, stackBoundsOwner) {
                    onDispose {
                        stackBounds.remove(stack.id, stackBoundsOwner)
                    }
                }
                DisposableEffect(stack.id, laneScrollState) {
                    laneScrollStates[stack.id] = laneScrollState
                    onDispose {
                        if (laneScrollStates[stack.id] === laneScrollState) {
                            laneScrollBounds.remove(stack.id)
                            laneScrollStates.remove(stack.id)
                        }
                    }
                }
                val actions = stackActions(stack)
                var menuExpanded by remember(stack.id) { mutableStateOf(false) }
                val laneShape = RoundedCornerShape(NextcloudRadii.Card)
                val isDropTarget = dropTarget?.stack?.id == stack.id
                Card(
                    modifier = Modifier.width(316.dp).fillMaxHeight()
                        .onGloballyPositioned { coordinates ->
                            stackBounds.update(
                                stack.id,
                                stackBoundsOwner,
                                coordinates.boundsInWindow().toDeckUiRect(),
                            )
                        }
                        .then(
                            if (isDropTarget) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = laneShape,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    shape = laneShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .nextcloudCardInteractions(
                            onOpen = null,
                            onShowActions = actions.takeIf { it.isNotEmpty() }?.let {
                                { menuExpanded = true }
                            },
                            actionsLabel = "Actions for ${stack.title}",
                        )
                        .padding(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stack.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${stack.cards.size} cards",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onCreateCard != null) {
                        IconButton(onClick = { onCreateCard(stack) }) {
                            Icon(NextcloudIcons.Add, contentDescription = "Add card to ${stack.title}")
                        }
                    }
                    NextcloudCardOverflow(
                        itemLabel = stack.title,
                        actions = actions,
                        expanded = menuExpanded,
                        onExpandedChange = { menuExpanded = it },
                    )
                }
                LazyColumn(
                    state = laneScrollState,
                    modifier = Modifier.fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            laneScrollBounds[stack.id] = coordinates.boundsInWindow()
                        },
                    contentPadding = PaddingValues(
                        start = NextcloudSpacing.Small,
                        end = NextcloudSpacing.Small,
                        bottom = NextcloudSpacing.Medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    val activeCard = draggedCard
                    val activeTarget = dropTarget?.takeIf { it.stack.id == stack.id }
                    stack.cards.forEachIndexed { index, card ->
                        val insertionBefore = stack.cards
                            .take(index)
                            .count { candidate -> candidate.id != activeCard?.id }
                        if (
                            card.id != activeCard?.id &&
                            activeTarget?.insertionIndex == insertionBefore
                        ) {
                            item(key = "drop-${stack.id}-$insertionBefore") {
                                DeckDropIndicator()
                            }
                        }
                        item(key = card.id) {
                            val cardBoundsOwner = remember(card.id) { Any() }
                            DisposableEffect(card.id, cardBoundsOwner) {
                                onDispose {
                                    cardBounds.remove(card.id, cardBoundsOwner)
                                }
                            }
                            DeckCardItem(
                                card = card,
                                selected = card.id == selectedCardId,
                                dragging = card.id == activeCard?.id,
                                dragEnabled = onMoveCard != null,
                                onBoundsChanged = { bounds ->
                                    cardBounds.update(card.id, cardBoundsOwner, bounds)
                                },
                                onDragStart = dragStart@{ localPosition ->
                                    val bounds = cardBounds.bounds(card.id) ?: return@dragStart
                                    val position = Offset(
                                        x = bounds.left + localPosition.x,
                                        y = bounds.top + localPosition.y,
                                    )
                                    draggedCard = card
                                    dragGrabOffset = localPosition
                                    dragPosition = position
                                    dropTarget = resolveDropTarget(card, position)
                                    terminalDropRequested = false
                                },
                                onDrag = drag@{ amount ->
                                    val position = (dragPosition ?: return@drag) + amount
                                    dragPosition = position
                                    dropTarget = resolveDropTarget(card, position)
                                },
                                onDragEnd = { terminalDropRequested = true },
                                onDragCancel = ::clearDrag,
                                onOpen = { onOpenCard(card) },
                                onSelect = { onSelectCard(card) },
                                actions = cardActions(card),
                            )
                        }
                    }
                    val remainingCardCount = stack.cards.count { card ->
                        card.id != activeCard?.id
                    }
                    if (activeTarget?.insertionIndex == remainingCardCount) {
                        item(key = "drop-${stack.id}-$remainingCardCount") {
                            DeckDropIndicator()
                        }
                    }
                    if (stack.cards.isEmpty()) {
                        item {
                            Text(
                                "No cards",
                                modifier = Modifier.padding(NextcloudSpacing.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                }
            }
        }
        val previewCard = draggedCard
        val previewPosition = dragPosition
        val viewport = lanesBounds
        if (previewCard != null && previewPosition != null && viewport != null) {
            DeckDraggedCardPreview(
                card = previewCard,
                target = dropTarget,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (
                                previewPosition.x -
                                    viewport.left -
                                    dragGrabOffset.x
                                ).roundToInt(),
                            y = (
                                previewPosition.y -
                                    viewport.top -
                                    dragGrabOffset.y
                                ).roundToInt(),
                        )
                    }
                    .zIndex(2f),
            )
        }
    }
}

@Composable
private fun DeckCardItem(
    card: DeckCard,
    selected: Boolean,
    dragging: Boolean,
    dragEnabled: Boolean,
    onBoundsChanged: (DeckUiRect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    actions: List<NextcloudCardAction>,
) {
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    var itemBounds by remember(card.id) { mutableStateOf<DeckUiRect?>(null) }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        NextcloudTheme.colors.appTile
    }
    Card(
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                coordinates.boundsInWindow().toDeckUiRect().let { bounds ->
                    itemBounds = bounds
                    onBoundsChanged(bounds)
                }
            }
            .graphicsLayer {
                alpha = if (dragging) 0.18f else 1f
            }
            .clip(RoundedCornerShape(NextcloudRadii.Card))
            .nextcloudCardInteractions(
                onOpen = {
                    onSelect()
                    onOpen()
                },
                onShowActions = actions.takeIf { it.isNotEmpty() }?.let {
                    { menuExpanded = true }
                },
                openLabel = "Open ${card.title}",
                actionsLabel = "Actions for ${card.title}",
            ),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                if (dragEnabled) {
                    NextcloudBoardDragHandle(
                        itemLabel = card.title,
                        dragActive = dragging,
                        onDragStart = { windowPosition ->
                            val cardBounds = itemBounds ?: return@NextcloudBoardDragHandle
                            onDragStart(
                                Offset(
                                    x = windowPosition.x - cardBounds.left,
                                    y = windowPosition.y - cardBounds.top,
                                ),
                            )
                        },
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
                Text(
                    card.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                NextcloudCardOverflow(
                    itemLabel = card.title,
                    actions = actions,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
            if (card.labels.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    card.labels.take(3).forEach { label ->
                        Surface(
                            shape = RoundedCornerShape(NextcloudRadii.Pill),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                label.title,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            val metadata = listOfNotNull(
                card.dueAt?.let { "Due ${deckInstantDisplayLabel(it)}" },
                "${card.attachmentCount} files".takeIf { card.attachmentCount > 0 },
                "${card.unreadCommentCount} unread".takeIf { card.unreadCommentCount > 0 },
                "${card.assignees.size} assigned".takeIf { card.assignees.isNotEmpty() },
            )
            if (metadata.isNotEmpty()) {
                Text(
                    metadata.joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.overdue) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun DeckDraggedCardPreview(
    card: DeckCard,
    target: DeckUiCardDropTarget?,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.width(300.dp),
        shape = RoundedCornerShape(NextcloudRadii.Card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                target?.let { "Move to ${it.stack.title}" } ?: "Move over a list",
                style = MaterialTheme.typography.labelMedium,
                color = target?.let { MaterialTheme.colorScheme.primary }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeckDropIndicator() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Small),
        shape = RoundedCornerShape(NextcloudRadii.Pill),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Spacer(modifier = Modifier.fillMaxWidth().heightIn(min = 4.dp))
    }
}

private fun androidx.compose.ui.geometry.Rect.toDeckUiRect() = DeckUiRect(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)

@Composable
private fun DeckCardInspector(
    card: DeckCard,
    actions: List<NextcloudCardAction>,
    modifier: Modifier,
    showTitle: Boolean = true,
) {
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        if (showTitle) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        card.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    NextcloudCardOverflow(
                        itemLabel = card.title,
                        actions = actions,
                        expanded = menuExpanded,
                        onExpandedChange = { menuExpanded = it },
                    )
                }
            }
        }
        card.descriptionMarkdown?.takeIf(String::isNotBlank)?.let { description ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    Text("Description", style = MaterialTheme.typography.labelMedium)
                    Markdown(content = description)
                }
            }
        }
        card.dueAt?.let { due ->
            item { DeckInspectorField("Due", deckInstantDisplayLabel(due)) }
        }
        card.startAt?.let { start ->
            item { DeckInspectorField("Starts", deckInstantDisplayLabel(start)) }
        }
        if (card.labels.isNotEmpty()) {
            item { DeckInspectorField("Labels", card.labels.joinToString(", ") { it.title }) }
        }
        if (card.assignees.isNotEmpty()) {
            item { DeckInspectorField("Assigned", card.assignees.joinToString(", ") { it.displayName }) }
        }
        if (card.attachmentCount > 0) {
            item { DeckInspectorField("Attachments", card.attachmentCount.toString()) }
        }
    }
}

@Composable
private fun DeckInspectorField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
