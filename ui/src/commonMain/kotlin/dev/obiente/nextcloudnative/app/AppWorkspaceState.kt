package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder

/** Keeps registration at one composition location when the navigation shell changes. */
@Composable
internal fun AppWorkspaceState(
    holder: SaveableStateHolder,
    appId: String?,
    content: @Composable () -> Unit,
) {
    if (appId == null) content() else holder.SaveableStateProvider("app:$appId", content)
}
