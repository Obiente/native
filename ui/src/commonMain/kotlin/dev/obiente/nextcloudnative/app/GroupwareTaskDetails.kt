package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudDetailSheet
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** What the person may do with the task shown in details, resolved by the Tasks screen. */
internal data class TaskDetailState(
    val writable: Boolean,
    val deleteSafe: Boolean,
    val interactionBlocked: Boolean,
    val error: String?,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val relations: TaskRelations = TaskRelations(null, emptyList()),
    val onOpenTask: (GroupwareTask) -> Unit = {},
    /** Null when a subtask cannot be added here. */
    val onAddSubtask: (() -> Unit)? = null,
)

/**
 * Task details where the list is too narrow for the side inspector. Phones get a bottom sheet
 * over the list; narrow desktop windows keep the centered dialog that suits a pointer. The
 * host's saveable selection decides whether either is shown, so both reopen after recreation.
 */
@Composable
internal fun TaskDetailOverlay(task: GroupwareTask, state: TaskDetailState, onDismiss: () -> Unit) {
    if (LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
        TaskDetailsDialog(
            task, state.writable, state.deleteSafe, state.interactionBlocked, state.error,
            onDismiss, state.onEdit, state.onDelete,
        )
        return
    }
    NextcloudDetailSheet(
        title = task.title,
        onDismiss = { if (!state.interactionBlocked) onDismiss() },
        closeLabel = "Close task",
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            TaskFacts(task, state.writable, state.deleteSafe, state.error)
            TaskRelationsSection(state)
        }
        TaskActionRow(state, Modifier.padding(NextcloudSpacing.Large))
    }
}

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
                TaskFacts(task, writable, deleteSafe, error)
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

/** Wide-list inspector: the task's title and actions stay put while its details scroll. */
@Composable
internal fun TaskInspector(task: GroupwareTask, state: TaskDetailState) {
    Column(Modifier.fillMaxSize()) {
        Text(
            task.title,
            modifier = Modifier.padding(NextcloudSpacing.Large).semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        TaskActionRow(state, Modifier.padding(horizontal = NextcloudSpacing.Large))
        HorizontalDivider(Modifier.padding(top = NextcloudSpacing.Medium), color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            TaskFacts(task, state.writable, state.deleteSafe, state.error)
            TaskRelationsSection(state)
        }
    }
}

@Composable
private fun TaskActionRow(state: TaskDetailState, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        OutlinedButton(enabled = !state.interactionBlocked && state.writable, onClick = state.onEdit) { Text("Edit") }
        TextButton(enabled = !state.interactionBlocked && state.writable && state.deleteSafe, onClick = state.onDelete) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ColumnScope.TaskFacts(task: GroupwareTask, writable: Boolean, deleteSafe: Boolean, error: String?) {
    Text(if (task.completed) "Completed" else if ((task.percentComplete ?: 0) > 0) "${task.percentComplete}% done" else "Open")
    task.start?.let { Text("Starts ${it.displayTaskDueDate()}") }
    task.due?.let { Text("Due ${it.displayTaskDueDate()}") }
    TaskPriority.of(task.priority).takeIf { it != TaskPriority.None }?.let { Text("${it.label} priority") }
    task.categories.takeIf(List<String>::isNotEmpty)?.let { Text("Tags: ${it.joinToString(", ")}") }
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
