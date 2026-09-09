package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow

@Composable
internal fun HomeWorkspaceActions(
    onCustomize: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    NextcloudCardOverflow(
        itemLabel = "Home",
        actions = buildList {
            onCustomize?.let { add(NextcloudCardAction("Customize Home", onClick = it)) }
            onSettings?.let { add(NextcloudCardAction("Settings", onClick = it)) }
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
    )
}
