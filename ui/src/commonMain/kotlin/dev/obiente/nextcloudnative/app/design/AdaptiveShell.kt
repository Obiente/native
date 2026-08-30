package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Phone and tablet chrome share app switching, selection, and host-owned navigation callbacks. */
@Composable
internal fun NextcloudAdaptiveShell(
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    identity: NextcloudDesktopIdentity?,
    activeAppId: String? = null,
    onOpenApp: (String) -> Unit = {},
    navigationEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var switcherOpen by remember(identity?.accountScopeKey, activeAppId, selected) { mutableStateOf(false) }
    val activeApp = nextcloudShellAppItems(identity, activeAppId).active
    val latestContent by rememberUpdatedState(content)
    val workspace = remember(identity?.accountScopeKey) { movableContentOf { latestContent() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = resolveNextcloudRootShellLayout(
            NextcloudPresentation.Adaptive, maxWidth.value.toInt(),
            if (activeAppId != null) NextcloudDestination.Apps else selected,
        )
        if (layout.navigationStyle == NextcloudNavigationStyle.BottomBar) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { workspace() }
                NextcloudBottomNavigation(
                    selected, onSelected, enabled = navigationEnabled,
                    activeApp = activeApp, onSwitchApp = { switcherOpen = true },
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NextcloudNavigationRail(
                    selected, onSelected, enabled = navigationEnabled,
                    activeApp = activeApp, onSwitchApp = { switcherOpen = true },
                )
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.fillMaxHeight().widthIn(max = requireNotNull(layout.contentMaximumWidthDp).dp).fillMaxWidth()) {
                        workspace()
                    }
                }
            }
        }
        if (switcherOpen) {
            NextcloudAppSwitcher(
                identity = identity,
                activeAppId = activeAppId,
                selectedDestination = selected,
                enabled = navigationEnabled,
                onOpenApp = onOpenApp,
                onSelected = onSelected,
                onDismiss = { switcherOpen = false },
            )
        }
    }
}
