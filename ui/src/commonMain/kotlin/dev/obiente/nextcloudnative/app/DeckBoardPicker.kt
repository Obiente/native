package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

/** Active boards first, then a collapsed section with boards the user archived. */
@Composable
internal fun DeckBoardPicker(
    state: DeckWorkspaceState.BoardPicker,
    onSelectBoard: (DeckBoard) -> Unit,
    onCreateBoard: (() -> Unit)?,
    boardActions: (DeckBoard) -> List<NextcloudCardAction>,
    modifier: Modifier,
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
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
                DeckBoardPickerCard(board, onSelectBoard, boardActions(board))
            }
            if (state.archivedBoards.isNotEmpty()) {
                item(key = "archived-toggle") {
                    TextButton(
                        onClick = { showArchived = !showArchived },
                        modifier = Modifier.semantics { heading() },
                    ) {
                        Text(
                            if (showArchived) {
                                "Hide archived boards"
                            } else {
                                "Show archived boards (${state.archivedBoards.size})"
                            },
                        )
                    }
                }
                if (showArchived) {
                    items(state.archivedBoards, key = { "archived:${it.id}" }) { board ->
                        DeckBoardPickerCard(board, onSelectBoard, boardActions(board))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckBoardPickerCard(
    board: DeckBoard,
    onSelectBoard: (DeckBoard) -> Unit,
    actions: List<NextcloudCardAction>,
) {
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
                        "Archived".takeIf { board.archived },
                        board.owner?.displayName,
                        "Shared".takeIf { board.shared || board.acl.isNotEmpty() },
                        "Read only".takeIf { !board.permissions.canEdit || board.archived },
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
