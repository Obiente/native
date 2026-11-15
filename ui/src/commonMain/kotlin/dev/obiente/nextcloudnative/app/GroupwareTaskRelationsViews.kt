package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** A task's parent and direct subtasks, resolved within its own list. */
internal data class TaskRelations(
    val parent: GroupwareTask?,
    val subtasks: List<GroupwareTask>,
)

internal fun taskRelations(task: GroupwareTask, tasks: List<GroupwareTask>): TaskRelations = TaskRelations(
    parent = task.parentUid?.let { uid -> tasks.firstOrNull { it.calendarHref == task.calendarHref && it.uid == uid && it.recurrenceId == null } },
    subtasks = if (task.recurrenceId != null) emptyList()
    else tasks.filter { it.calendarHref == task.calendarHref && it.parentUid == task.uid && it.uid != task.uid },
)

/** One short line under a task in the list: due date, priority, progress and tags. */
internal fun taskRowSummary(task: GroupwareTask): String? = listOfNotNull(
    task.due?.let { "Due ${it.displayTaskDueDate()}" },
    TaskPriority.of(task.priority).takeIf { it != TaskPriority.None }?.let { "${it.label} priority" },
    task.percentComplete?.takeIf { !task.completed && it in 1..99 }?.let { "$it% done" },
    task.categories.takeIf(List<String>::isNotEmpty)?.joinToString(", "),
).joinToString(" - ").takeIf(String::isNotEmpty)

@Composable
internal fun TaskRelationsSection(state: TaskDetailState) {
    val relations = state.relations
    if (relations.parent == null && relations.subtasks.isEmpty() && state.onAddSubtask == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        relations.parent?.let { parent ->
            Text("Subtask of", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TaskRelationRow(parent, state.onOpenTask)
        }
        if (relations.subtasks.isNotEmpty() || state.onAddSubtask != null) {
            Text(
                "Subtasks (${relations.subtasks.count(GroupwareTask::completed)} of ${relations.subtasks.size} done)",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            relations.subtasks.forEach { subtask -> TaskRelationRow(subtask, state.onOpenTask) }
            state.onAddSubtask?.let { add ->
                TextButton(onClick = add, enabled = !state.interactionBlocked) {
                    Icon(NextcloudIcons.Add, null, Modifier.size(18.dp))
                    Text("Add subtask")
                }
            }
        }
    }
}

@Composable
private fun TaskRelationRow(task: GroupwareTask, onOpen: (GroupwareTask) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${task.title}") { onOpen(task) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (task.completed) NextcloudIcons.CheckCircle else NextcloudIcons.Task,
            contentDescription = if (task.completed) "Done" else "Open",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            task.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (task.completed) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
