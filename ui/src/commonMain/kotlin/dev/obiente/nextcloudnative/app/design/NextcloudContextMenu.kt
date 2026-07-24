package dev.obiente.nextcloudnative.app.design

import androidx.compose.runtime.Composable

data class NextcloudContextMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Native right-click menu on desktop and a no-op content wrapper on touch platforms. */
@Composable
expect fun NextcloudContextMenuArea(
    items: () -> List<NextcloudContextMenuItem>,
    content: @Composable () -> Unit,
)
