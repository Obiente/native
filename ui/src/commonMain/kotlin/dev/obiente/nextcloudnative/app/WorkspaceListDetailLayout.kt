package dev.obiente.nextcloudnative.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationHost
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudMotion
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceBreakpoints
import dev.obiente.nextcloudnative.app.design.nextcloudAnimatedColor
import dev.obiente.nextcloudnative.app.design.nextcloudSelectionColors
import dev.obiente.nextcloudnative.app.design.resolveNextcloudCollectionNavigationMode

/**
 * Whether a list workspace this wide shows the selected item beside the list. Narrower layouts
 * show it over the list: a bottom sheet on phones and a dialog in narrow desktop windows.
 */
internal fun workspaceShowsDetailBesideList(workspaceWidth: Dp): Boolean =
    workspaceWidth.value >= NextcloudWorkspaceBreakpoints.TwoPaneDp

/** Collection navigation (tabs, rail, or sidebar) for a list workspace of this width. */
@Composable
internal fun workspaceCollectionNavigationMode(
    workspaceWidth: Dp,
    destinationCount: Int,
): NextcloudCollectionNavigationMode = resolveNextcloudCollectionNavigationMode(
    host = if (LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
        NextcloudCollectionNavigationHost.Desktop
    } else {
        NextcloudCollectionNavigationHost.AdaptiveAndroid
    },
    availableWidthDp = workspaceWidth.value.toInt(),
    destinationCount = destinationCount,
)

/**
 * Inspector beside a list. It slides in when an item is selected and out when the selection
 * clears; choosing another item slides its details in without rebuilding the pane. The last
 * item stays rendered while the pane leaves, so the exit never shows an empty panel.
 */
@Composable
internal fun <T : Any> WorkspaceSideInspector(
    item: T?,
    itemKey: (T) -> Any,
    width: Dp = 360.dp,
    content: @Composable (T) -> Unit,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    var lastItem by remember { mutableStateOf(item) }
    SideEffect { if (item != null) lastItem = item }
    val shown = item ?: lastItem
    AnimatedVisibility(
        visible = item != null,
        enter = NextcloudMotion.sidePaneEnter(reducedMotion),
        exit = NextcloudMotion.sidePaneExit(reducedMotion),
    ) {
        Row(Modifier.fillMaxHeight()) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            shown?.let { current ->
                NextcloudContentEntrance(itemKey(current), NextcloudEntranceAxis.Horizontal) {
                    Box(Modifier.width(width).fillMaxHeight()) { content(current) }
                }
            }
        }
    }
}

/**
 * Card colors for a list row that may be the one shown in the inspector. Selection also sets
 * the row's selected semantics and outline at the call site, so it never depends on color alone.
 */
@Composable
internal fun workspaceListCardColors(selected: Boolean, unselected: Color = NextcloudTheme.colors.appTile): CardColors =
    CardDefaults.cardColors(
        containerColor = nextcloudAnimatedColor(if (selected) nextcloudSelectionColors().fill else unselected),
    )

@Composable
internal fun workspaceListCardBorder(selected: Boolean): BorderStroke? =
    if (selected) BorderStroke(1.dp, nextcloudSelectionColors().outline) else null
