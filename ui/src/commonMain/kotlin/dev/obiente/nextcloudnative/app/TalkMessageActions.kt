package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudContextMenuArea
import dev.obiente.nextcloudnative.app.design.NextcloudContextMenuItem
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

/**
 * Secondary actions for one chat message.
 *
 * Touch opens them with a long press, desktop with a right click or the overflow button, and
 * assistive technology through custom accessibility actions. The message itself has no primary
 * action, so a tap never changes anything.
 */
@Composable
internal fun TalkMessageActionHost(
    message: TalkMessage,
    mine: Boolean,
    actions: List<NextcloudCardAction>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    val itemLabel = "message from ${message.actorDisplayName}"
    NextcloudContextMenuArea(
        items = { actions.map { NextcloudContextMenuItem(it.label, it.enabled, it.onClick) } },
    ) {
        Box(
            modifier = modifier
                .semantics {
                    customActions = actions.filter(NextcloudCardAction::enabled).map { action ->
                        CustomAccessibilityAction(action.label) { action.onClick(); true }
                    }
                }
                .nextcloudCardInteractions(
                    onOpen = null,
                    onShowActions = { menuOpen = true }.takeUnless { desktop },
                    actionsLabel = "Actions for $itemLabel",
                ),
        ) {
            content()
            if (desktop) {
                NextcloudCardOverflow(
                    itemLabel = itemLabel,
                    actions = actions,
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    modifier = Modifier.align(if (mine) Alignment.TopStart else Alignment.TopEnd),
                )
            } else {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    action.label,
                                    color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            enabled = action.enabled,
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Reaction counts. When reacting is allowed each chip toggles this account's reaction. */
@Composable
internal fun TalkReactionChips(reactions: List<TalkReaction>, interactions: TalkMessageInteractions) {
    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        reactions.take(MAX_VISIBLE_REACTIONS).forEach { reaction ->
            val toggle = interactions.onToggleReaction
            val description = when {
                toggle == null -> "${reaction.emoji} reactions: ${reaction.count}"
                reaction.reactedByMe -> "Remove your ${reaction.emoji} reaction, ${reaction.count} in total"
                else -> "React with ${reaction.emoji}, ${reaction.count} in total"
            }
            val color = if (reaction.reactedByMe) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.66f)
            }
            val chipModifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = description
                selected = reaction.reactedByMe
                if (toggle != null) role = Role.Button
            }
            if (toggle != null) {
                Surface(
                    onClick = { toggle(reaction.emoji) },
                    enabled = !interactions.pending,
                    modifier = chipModifier,
                    color = color,
                    shape = CircleShape,
                ) { TalkReactionLabel(reaction) }
            } else {
                Surface(modifier = chipModifier, color = color, shape = CircleShape) { TalkReactionLabel(reaction) }
            }
        }
    }
}

@Composable
private fun TalkReactionLabel(reaction: TalkReaction) {
    Text(
        "${reaction.emoji} ${reaction.count}",
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}

/** A short set of common reactions, matching the quick choices other Talk clients offer. */
@Composable
internal fun TalkQuickReactionBar(onReact: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TALK_QUICK_REACTIONS.forEach { emoji ->
                TextButton(
                    onClick = { onReact(emoji) },
                    modifier = Modifier.semantics { contentDescription = "React with $emoji" },
                ) { Text(emoji, style = MaterialTheme.typography.titleMedium) }
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

internal val TALK_QUICK_REACTIONS = listOf(
    "👍",
    "❤️",
    "😂",
    "😮",
    "😢",
    "🎉",
)

private const val MAX_VISIBLE_REACTIONS = 5
