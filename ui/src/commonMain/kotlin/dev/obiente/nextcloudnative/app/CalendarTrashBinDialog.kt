package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** Deleted calendars, events and tasks, with restore and permanent removal. */
@Composable
internal fun CalendarTrashBinDialog(manager: CalendarTrashBinManager) {
    if (!manager.open) return
    val contents = manager.contents
    AlertDialog(
        onDismissRequest = manager::dismiss,
        title = { Text("Trash bin") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    if (manager.loading || manager.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        contents?.retentionDays?.let { "Deleted items are removed for good after $it days." }
                            ?: "Deleted calendars, events and tasks stay here for a while before they are removed for good.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (contents != null && contents.items.isEmpty()) item {
                    Text("The trash bin is empty.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                items(contents?.items.orEmpty(), key = CalendarTrashItem::href) { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (item.kind) {
                                CalendarTrashKind.Calendar -> NextcloudIcons.Calendar
                                CalendarTrashKind.Event -> NextcloudIcons.Clock
                                CalendarTrashKind.Task -> NextcloudIcons.Task
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    item.kind.label(),
                                    item.deletedAt?.take(10)?.let { "deleted $it" },
                                ).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { manager.restore(item) }, enabled = !manager.busy) { Text("Restore") }
                        TextButton(onClick = { manager.requestRemoval(item) }, enabled = !manager.busy) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                manager.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onClick = manager::dismiss, enabled = !manager.busy) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = manager::reload, enabled = !manager.busy && !manager.loading) { Text("Refresh") }
                TextButton(onClick = manager::requestEmpty, enabled = !manager.busy && contents?.items?.isNotEmpty() == true) {
                    Text("Empty trash bin", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
    manager.confirmingRemoval?.let { item ->
        GroupwareConfirmDialog(
            title = "Delete ${item.name} for good?",
            message = "This cannot be undone." + if (item.kind == CalendarTrashKind.Calendar) " Every event and task in it is deleted too." else "",
            action = "Delete for good",
            onConfirm = manager::confirmRemoval,
            onDismiss = manager::cancelConfirmation,
        )
    }
    if (manager.confirmingEmpty) {
        GroupwareConfirmDialog(
            title = "Empty the trash bin?",
            message = "All ${contents?.items?.size ?: 0} deleted items are removed for good. This cannot be undone.",
            action = "Empty trash bin",
            onConfirm = manager::confirmRemoval,
            onDismiss = manager::cancelConfirmation,
        )
    }
}

private fun CalendarTrashKind.label(): String = when (this) {
    CalendarTrashKind.Calendar -> "Calendar"
    CalendarTrashKind.Event -> "Event"
    CalendarTrashKind.Task -> "Task"
}
