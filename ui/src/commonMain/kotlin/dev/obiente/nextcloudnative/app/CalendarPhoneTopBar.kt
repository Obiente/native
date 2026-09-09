package dev.obiente.nextcloudnative.app

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarPhoneTopBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    navigationEnabled: Boolean = true,
    createEnabled: Boolean,
) {
    TopAppBar(
        title = { Text("Calendar") },
        navigationIcon = {
            IconButton(enabled = navigationEnabled, onClick = onBack) {
                Icon(NextcloudIcons.Back, contentDescription = "Leave calendar")
            }
        },
        actions = {
            IconButton(onClick = onRefresh) { Icon(NextcloudIcons.Refresh, contentDescription = "Refresh calendars") }
            FilledTonalIconButton(enabled = createEnabled, onClick = onCreate) {
                Icon(NextcloudIcons.Add, contentDescription = "Create event")
            }
        },
    )
}
