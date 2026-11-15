package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopWorkspaceKind
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation

@Composable
internal fun RootShell(
    presentation: NextcloudPresentation,
    selected: NextcloudDestination,
    desktopWorkspaceKind: NextcloudDesktopWorkspaceKind = NextcloudDesktopWorkspaceKind.Root,
    navigationEnabled: Boolean = true,
    onSelected: (NextcloudDestination) -> Unit,
    identity: NextcloudDesktopIdentity?,
    onOpenApp: (String) -> Unit = {},
    activeAppId: String? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (presentation == NextcloudPresentation.Desktop) {
            NextcloudDesktopShell(
                selected = selected,
                onSelected = onSelected,
                identity = identity,
                onOpenApp = onOpenApp,
                activeAppId = activeAppId,
                workspaceKind = desktopWorkspaceKind,
                navigationEnabled = navigationEnabled,
                content = content,
            )
        } else {
            dev.obiente.nextcloudnative.app.design.NextcloudAdaptiveShell(
                selected = selected,
                onSelected = onSelected,
                identity = identity,
                activeAppId = activeAppId,
                onOpenApp = onOpenApp,
                navigationEnabled = navigationEnabled,
                content = content,
            )
        }
    }
}
