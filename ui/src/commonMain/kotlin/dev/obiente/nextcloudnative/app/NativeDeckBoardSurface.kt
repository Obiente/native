package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

@Composable
fun NativeDeckBoardSurface(
    state: DeckWorkspaceState,
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
    modifier: Modifier = Modifier,
) {
    when (state) {
        DeckWorkspaceState.Loading -> NativeDeckCenteredState(modifier) { CircularProgressIndicator() }
        is DeckWorkspaceState.Empty -> NativeDeckCenteredState(modifier) {
            Icon(NextcloudIcons.app("deck"), contentDescription = null, modifier = Modifier.size(44.dp))
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                state.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.canCreateBoards && onCreateBoard != null) {
                Button(onClick = onCreateBoard) { Text("Create board") }
            }
        }
        is DeckWorkspaceState.Error -> NativeDeckCenteredState(modifier) {
            Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(44.dp))
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                state.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.canRetry) OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
        is DeckWorkspaceState.BoardPicker -> DeckBoardPicker(
            state = state,
            onSelectBoard = onSelectBoard,
            onCreateBoard = onCreateBoard,
            boardActions = boardActions,
            modifier = modifier,
        )
        is DeckWorkspaceState.Board -> DeckBoardWorkspace(
            state = state,
            onBackToBoards = onBackToBoards,
            onOpenCard = onOpenCard,
            onSelectCard = onSelectCard,
            onDismissCard = onDismissCard,
            onCreateStack = onCreateStack,
            onCreateCard = onCreateCard,
            boardActions = boardActions,
            stackActions = stackActions,
            cardActions = cardActions,
            modifier = modifier,
        )
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
    onBackToBoards: () -> Unit,
    onOpenCard: (DeckCard) -> Unit,
    onSelectCard: (DeckCard) -> Unit,
    onDismissCard: () -> Unit,
    onCreateStack: ((DeckBoard) -> Unit)?,
    onCreateCard: ((DeckStack) -> Unit)?,
    boardActions: (DeckBoard) -> List<NextcloudCardAction>,
    stackActions: (DeckStack) -> List<NextcloudCardAction>,
    cardActions: (DeckCard) -> List<NextcloudCardAction>,
    modifier: Modifier,
) {
    val activeBoardActions = boardActions(state.board)
    var boardMenuExpanded by remember(state.board.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Large,
                vertical = NextcloudSpacing.Medium,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            IconButton(onClick = onBackToBoards) {
                Icon(NextcloudIcons.Back, contentDescription = "Back to boards")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.board.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.stacks.size} lists - ${state.stacks.sumOf { it.cards.size }} cards",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onCreateStack != null && state.board.permissions.canManage) {
                IconButton(onClick = { onCreateStack(state.board) }) {
                    Icon(NextcloudIcons.Add, contentDescription = "Add list")
                }
            }
            NextcloudCardOverflow(
                itemLabel = state.board.title,
                actions = activeBoardActions,
                expanded = boardMenuExpanded,
                onExpandedChange = { boardMenuExpanded = it },
            )
        }
        HorizontalDivider()
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
    LazyRow(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        items(stacks, key = DeckStack::id) { stack ->
            val actions = stackActions(stack)
            var menuExpanded by remember(stack.id) { mutableStateOf(false) }
            Card(
                modifier = Modifier.width(316.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NextcloudSpacing.Small,
                        end = NextcloudSpacing.Small,
                        bottom = NextcloudSpacing.Medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(stack.cards, key = DeckCard::id) { card ->
                        DeckCardItem(
                            card = card,
                            selected = card.id == selectedCardId,
                            onOpen = { onOpenCard(card) },
                            onSelect = { onSelectCard(card) },
                            actions = cardActions(card),
                        )
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
}

@Composable
private fun DeckCardItem(
    card: DeckCard,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    actions: List<NextcloudCardAction>,
) {
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        NextcloudTheme.colors.appTile
    }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NextcloudRadii.Card))
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
                card.dueAt?.let { "Due ${it.take(10)}" },
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
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        card.dueAt?.let { due ->
            item { DeckInspectorField("Due", due) }
        }
        card.startAt?.let { start ->
            item { DeckInspectorField("Starts", start) }
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
