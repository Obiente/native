package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A small set of mutually exclusive choices. IDs must be stable and unique within the control. */
data class NextcloudSegmentedOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

/**
 * Shared view switcher or single-choice filter. Selection is owned by the caller.
 * Arrow keys move focus; Enter or Space activates. Unknown selections never choose a fallback.
 * Bounded layouts scroll without truncating labels and offer a menu when choices overflow.
 */
@Composable
fun NextcloudSegmentedControl(
    options: List<NextcloudSegmentedOption>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    role: Role = Role.Tab,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val direction = LocalLayoutDirection.current
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var naturalWidth by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, options) { menuOpen = false }
    val activate: (String) -> Unit = { id ->
        if (enabled && id != selectedId && options.any { it.id == id && it.enabled }) onSelected(id)
        menuOpen = false
    }
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(NextcloudRadii.Card))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(3.dp).selectableGroup().semantics {
                accessibilityLabel?.let { contentDescription = it }
            },
    ) {
        val bounded = constraints.hasBoundedWidth
        val viewportWidth = constraints.maxWidth
        val overflow = bounded && naturalWidth > viewportWidth
        val orderedIds = options.map { it.id }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(if (bounded) Modifier.weight(1f, fill = false).horizontalScroll(scrollState) else Modifier) {
                Row(Modifier.onSizeChanged { naturalWidth = it.width }, verticalAlignment = Alignment.CenterVertically) {
                    options.forEach { option ->
                        key(option.id) {
                            val focusRequester = remember { FocusRequester() }
                            val reveal = remember { BringIntoViewRequester() }
                            var focused by remember { mutableStateOf(false) }
                            DisposableEffect(option.id) {
                                requesters[option.id] = focusRequester
                                onDispose { requesters.remove(option.id) }
                            }
                            LaunchedEffect(selectedId, focusedId, orderedIds, viewportWidth, naturalWidth, overflow) {
                                if ((focusedId ?: selectedId) == option.id) reveal.bringIntoView()
                            }
                            val selected = selectedId == option.id
                            val active = enabled && option.enabled
                            val shape = RoundedCornerShape(NextcloudRadii.Small)
                            val foreground = when {
                                !active -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                selected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(
                                Modifier.bringIntoViewRequester(reveal).focusRequester(focusRequester)
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        if (it.isFocused) focusedId = option.id
                                        else if (focusedId == option.id) focusedId = null
                                        if (it.isFocused) scope.launch { reveal.bringIntoView() }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (!active || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        val move = when (event.key) {
                                            Key.DirectionLeft -> if (direction == LayoutDirection.Rtl) NextcloudSegmentedFocusMove.Next
                                                else NextcloudSegmentedFocusMove.Previous
                                            Key.DirectionRight -> if (direction == LayoutDirection.Rtl) NextcloudSegmentedFocusMove.Previous
                                                else NextcloudSegmentedFocusMove.Next
                                            Key.MoveHome -> NextcloudSegmentedFocusMove.First
                                            Key.MoveEnd -> NextcloudSegmentedFocusMove.Last
                                            else -> return@onPreviewKeyEvent false
                                        }
                                        nextcloudSegmentedFocusTarget(options, option.id, move)?.let { id ->
                                            requesters[id]?.requestFocus() ?: false
                                        } ?: false
                                    }
                                    .clip(shape)
                                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                                    .border(2.dp, if (focused && active) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                                    .selectable(selected, active, role = role, onClick = { activate(option.id) })
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                option.icon?.let { Icon(it, null, Modifier.size(18.dp), tint = foreground) }
                                Text(option.label, style = MaterialTheme.typography.labelLarge,
                                    color = foreground, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            if (overflow) Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(NextcloudIcons.ExpandMore, "Show ${accessibilityLabel ?: "options"}")
                }
                DropdownMenu(expanded = menuOpen && enabled, onDismissRequest = { menuOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp)) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { activate(option.id) },
                            enabled = option.enabled,
                            modifier = Modifier.semantics { selected = option.id == selectedId },
                            trailingIcon = if (option.id == selectedId) ({ Icon(NextcloudIcons.CheckCircle, null) }) else null,
                        )
                    }
                }
            }
        }
    }
}

internal enum class NextcloudSegmentedFocusMove { Previous, Next, First, Last }

internal fun nextcloudSegmentedFocusTarget(
    options: List<NextcloudSegmentedOption>,
    currentId: String?,
    move: NextcloudSegmentedFocusMove,
): String? {
    val ids = options.filter { it.enabled }.map { it.id }
    if (ids.isEmpty()) return null
    val index = ids.indexOf(currentId)
    return when (move) {
        NextcloudSegmentedFocusMove.First -> ids.first()
        NextcloudSegmentedFocusMove.Last -> ids.last()
        NextcloudSegmentedFocusMove.Next -> ids[(index + 1) % ids.size]
        NextcloudSegmentedFocusMove.Previous -> ids[if (index <= 0) ids.lastIndex else index - 1]
    }
}
