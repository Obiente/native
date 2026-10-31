package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Archived cards grouped by list, with restore and delete in each card's overflow menu. */
@Composable
internal fun DeckArchivedCardsDialog(
    state: DeckUiArchivedCardsState,
    onRestore: (DeckCard) -> Unit,
    onRequestDelete: (DeckCard) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    DeckUiAdaptiveDialog(
        title = "Archived cards",
        supportingText = "Cards archived on ${state.board.title}. Restore a card to put it back on its list.",
        onDismiss = onDismiss,
        dismissLabel = "Close",
        busy = state.busyCardId != null,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            state.errorMessage?.let { item(key = "error") { DeckUiInlineError(it) } }
            state.notice?.let { item(key = "notice") { DeckToolNotice(it) } }
            if (state.outcomeUnknown || (state.errorMessage != null && !state.loading)) {
                item(key = "refresh") {
                    OutlinedButton(onClick = onRefresh, enabled = state.busyCardId == null) {
                        Text("Refresh archived cards")
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") { CircularProgressIndicator() }
                state.groups.isEmpty() -> item(key = "empty") {
                    DeckToolHint("No archived cards on this board. Archive a card from its menu to hide it from the lists.")
                }
                else -> state.groups.forEach { group ->
                    item(key = "stack:${group.stack.id}") {
                        Text(
                            group.stack.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = NextcloudSpacing.Medium).semantics { heading() },
                        )
                    }
                    items(group.cards, key = { "card:${it.id}" }) { card ->
                        DeckArchivedCardRow(
                            card = card,
                            busy = state.busyCardId == card.id,
                            actions = if (state.canChangeCards) {
                                listOfNotNull(
                                    NextcloudCardAction("Restore to board") { onRestore(card) }
                                        .takeIf { state.canRestore },
                                    NextcloudCardAction("Delete card", destructive = true) { onRequestDelete(card) }
                                        .takeIf { state.canDelete },
                                )
                            } else {
                                emptyList()
                            },
                        )
                    }
                }
            }
            if (!state.board.permissions.canEdit || state.board.archived) {
                item(key = "read-only") {
                    DeckToolHint(
                        if (state.board.archived) {
                            "This board is archived. Unarchive it to restore cards."
                        } else {
                            "Restoring cards needs edit permission on this board."
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckArchivedCardRow(
    card: DeckCard,
    busy: Boolean,
    actions: List<NextcloudCardAction>,
) {
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(card.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                card.labels.takeIf(List<DeckLabel>::isNotEmpty)?.joinToString(", ") { it.title },
                card.dueAt?.let { "Due ${it.take(10)}" },
                "Done".takeIf { card.completedAt != null },
            ).joinToString(" - ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            NextcloudCardOverflow(
                itemLabel = card.title,
                actions = actions,
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            )
        }
    }
}
