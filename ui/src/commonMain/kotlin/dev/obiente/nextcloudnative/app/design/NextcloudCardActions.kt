package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Product-wide card interaction guardrail.
 *
 * A card's surface performs only its primary open/select action. Secondary and destructive
 * operations are exposed through this menu, which is opened by the overflow affordance or by
 * long-pressing the card. Destructive operations still require their screen-level confirmation.
 */
data class NextcloudCardAction(
    val label: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

fun Modifier.nextcloudCardInteractions(
    onOpen: (() -> Unit)?,
    onShowActions: (() -> Unit)?,
    openLabel: String? = null,
    actionsLabel: String? = null,
): Modifier = when {
    onOpen != null && onShowActions != null -> combinedClickable(
        onClickLabel = openLabel,
        onLongClickLabel = actionsLabel,
        onClick = onOpen,
        onLongClick = onShowActions,
    )
    onOpen != null -> combinedClickable(onClickLabel = openLabel, onClick = onOpen)
    onShowActions != null -> combinedClickable(
        onLongClickLabel = actionsLabel,
        onClick = {},
        onLongClick = onShowActions,
    )
    else -> this
}

@Composable
fun NextcloudCardOverflow(
    itemLabel: String,
    actions: List<NextcloudCardAction>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    Box(modifier) {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(NextcloudIcons.More, contentDescription = "Actions for $itemLabel")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    enabled = action.enabled,
                    onClick = {
                        onExpandedChange(false)
                        action.onClick()
                    },
                )
            }
        }
    }
}
