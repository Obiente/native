package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/**
 * Task list actions in the Tasks header: a new list, changing or sharing the list on screen, and
 * the trash bin that holds deleted tasks and lists.
 */
@Composable
internal fun TaskListMenu(
    currentList: GroupwareCalendar?,
    enabled: Boolean,
    lists: CalendarCollectionManager,
    trash: CalendarTrashBinManager,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(NextcloudIcons.Settings, contentDescription = "Task list actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("New task list") },
                leadingIcon = { Icon(NextcloudIcons.Add, null) },
                onClick = { open = false; lists.actions.onCreate(false) },
            )
            currentList?.takeIf { it.kind != GroupwareCalendarKind.Subscription }?.let { list ->
                DropdownMenuItem(
                    text = { Text(if (list.kind == GroupwareCalendarKind.Shared) "Remove ${list.displayName}" else "Edit ${list.displayName}") },
                    leadingIcon = { Icon(NextcloudIcons.Edit, null) },
                    onClick = { open = false; lists.actions.onManage(list) },
                )
            }
            DropdownMenuItem(
                text = { Text("Trash bin") },
                leadingIcon = { Icon(NextcloudIcons.Empty, null) },
                onClick = { open = false; trash.show() },
            )
        }
    }
}
