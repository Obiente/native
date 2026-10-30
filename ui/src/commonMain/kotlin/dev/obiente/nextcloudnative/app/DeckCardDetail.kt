package dev.obiente.nextcloudnative.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudDetailSheet
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudMotion
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/**
 * Wide-board inspector for the selected card. The pane slides in from the side when a card is
 * selected and out when the selection clears; choosing another card slides its details in
 * without rebuilding the pane. The last card stays rendered while the pane leaves, so the
 * exit never shows an empty panel.
 */
@Composable
internal fun DeckCardSideInspector(
    visible: Boolean,
    card: DeckCard?,
    cardActions: (DeckCard) -> List<NextcloudCardAction>,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    var lastCard by remember { mutableStateOf(card) }
    SideEffect { if (card != null) lastCard = card }
    val shownCard = card ?: lastCard
    AnimatedVisibility(
        visible = visible && shownCard != null,
        enter = NextcloudMotion.sidePaneEnter(reducedMotion),
        exit = NextcloudMotion.sidePaneExit(reducedMotion),
    ) {
        Row(Modifier.fillMaxHeight()) {
            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            shownCard?.let { shown ->
                NextcloudContentEntrance(shown.id, NextcloudEntranceAxis.Horizontal) {
                    DeckCardInspector(
                        card = shown,
                        actions = cardActions(shown),
                        modifier = Modifier.width(340.dp).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * Card details where the board is too narrow for the side inspector. Phones get a bottom sheet
 * over the board; narrow desktop windows keep the centered dialog that suits a pointer. The
 * host's saveable card selection decides whether either is shown, so both reopen after
 * recreation, and dismissal always goes through [onDismissCard].
 */
@Composable
internal fun DeckCardDetailOverlay(
    card: DeckCard?,
    cardActions: (DeckCard) -> List<NextcloudCardAction>,
    onDismissCard: () -> Unit,
) {
    card ?: return
    val actions = cardActions(card)
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    val overflow: @Composable () -> Unit = {
        NextcloudCardOverflow(
            itemLabel = card.title,
            actions = actions,
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
        )
    }
    if (!LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
        NextcloudDetailSheet(
            title = card.title,
            onDismiss = onDismissCard,
            closeLabel = "Close card",
            headerActions = { overflow() },
        ) {
            DeckCardInspector(
                card = card,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
                showTitle = false,
            )
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismissCard,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(card.title, modifier = Modifier.weight(1f))
                overflow()
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
                        modifier = Modifier.weight(1f).sharedElement(SharedElementKeys.deckCardTitle(card.id)),
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
                    Markdown(typography = nativeMarkdownTypography(), content = description)
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
