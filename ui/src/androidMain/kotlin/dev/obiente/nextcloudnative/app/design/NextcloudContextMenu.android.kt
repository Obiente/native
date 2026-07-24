package dev.obiente.nextcloudnative.app.design

import androidx.compose.runtime.Composable

@Composable
actual fun NextcloudContextMenuArea(
    items: () -> List<NextcloudContextMenuItem>,
    content: @Composable () -> Unit,
) {
    content()
}
