package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun NextcloudContextMenuArea(
    items: () -> List<NextcloudContextMenuItem>,
    content: @Composable () -> Unit,
) {
    ContextMenuArea(
        items = {
            items().map { item ->
                ContextMenuItem(
                    label = item.label,
                    enabled = item.enabled,
                    onClick = item.onClick,
                )
            }
        },
        content = content,
    )
}
