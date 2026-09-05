package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

internal data class FilesWorkspacePanes(
    val desktop: Boolean,
    val showNavigation: Boolean,
    val showInspector: Boolean,
)

@Composable
internal fun FilesPaneControls(
    panes: FilesWorkspacePanes,
    hasSelection: Boolean,
    onToggleNavigation: () -> Unit,
    onToggleInspector: () -> Unit,
) {
    if (!panes.desktop) return
    Row {
        IconToggleButton(checked = panes.showNavigation, onCheckedChange = { onToggleNavigation() }) {
            Icon(NextcloudIcons.Menu, if (panes.showNavigation) "Hide library" else "Show library")
        }
        IconToggleButton(checked = panes.showInspector, onCheckedChange = { onToggleInspector() }, enabled = hasSelection) {
            Icon(NextcloudIcons.Info, if (panes.showInspector) "Hide file details" else "Show file details")
        }
    }
}

/** Keep a usable file list between optional panes, including in a resized desktop window. */
internal fun resolveFilesWorkspacePanes(
    widthDp: Int,
    desktopPresentation: Boolean,
    navigationCollapsed: Boolean,
    inspectorClosed: Boolean,
    hasSelection: Boolean,
): FilesWorkspacePanes {
    val desktop = desktopPresentation && widthDp >= 800
    val inspector = desktop && hasSelection && !inspectorClosed
    return FilesWorkspacePanes(
        desktop = desktop,
        showNavigation = desktop && !navigationCollapsed && (!inspector || widthDp >= 1200),
        showInspector = inspector,
    )
}
