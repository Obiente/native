package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun TaskDetailsDialog(
    task: GroupwareTask,
    writable: Boolean,
    deleteSafe: Boolean,
    interactionBlocked: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!interactionBlocked) onDismiss() },
        title = { Text("Task details") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text(task.title, style = MaterialTheme.typography.titleLarge)
                Text(if (task.completed) "Completed" else "Open")
                task.due?.let { Text("Due ${it.displayTaskDueDate()}") }
                task.description?.let { Text(it) }
                if (!writable) {
                    Text("This task list is read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!deleteSafe) {
                    Text(
                        "This is one component of a recurring task. Edit the selected component; deleting the shared calendar object is withheld.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !interactionBlocked && writable, onClick = onEdit) { Text("Edit") }
        },
        dismissButton = {
            TextButton(enabled = !interactionBlocked && writable && deleteSafe, onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
