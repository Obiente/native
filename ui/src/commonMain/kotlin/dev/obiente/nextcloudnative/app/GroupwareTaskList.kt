package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal enum class TaskFilter { Open, All, Completed }

internal const val ALL_TASK_LISTS_DESTINATION_ID = "all-task-lists"

/** Task lists as filters. A single list needs no filter, so it gets no destinations. */
internal fun taskListDestinations(ready: TasksLoadState.Ready?): List<NextcloudCollectionDestination> {
    val calendars = ready?.calendars.orEmpty()
    if (calendars.size < 2) return emptyList()
    val openTasks = ready?.tasks.orEmpty().filterNot(GroupwareTask::completed)
    return listOf(
        NextcloudCollectionDestination(ALL_TASK_LISTS_DESTINATION_ID, "All lists", count = openTasks.size),
    ) + calendars.map { calendar ->
        NextcloudCollectionDestination(
            id = calendar.href,
            label = calendar.displayName.ifBlank { "Task list" },
            count = openTasks.count { task -> task.calendarHref == calendar.href },
            accessibilityId = "task-list",
            supportingText = "Read only".takeUnless { ready?.canWrite(calendar.href) == true },
        )
    }
}

/** The task list a saved filter names, or null for all lists when it no longer exists. */
internal fun taskFilterCalendar(ready: TasksLoadState.Ready?, filterHref: String?): GroupwareCalendar? =
    filterHref?.let { href -> ready?.calendars?.firstOrNull { it.href == href } }

internal fun filterGroupwareTasks(
    tasks: List<GroupwareTask>,
    filter: TaskFilter,
    calendar: GroupwareCalendar?,
    query: String,
): List<GroupwareTask> {
    val needle = query.trim().lowercase()
    return tasks.filter { task ->
        (calendar == null || task.calendarHref == calendar.href) &&
            (filter == TaskFilter.All || filter == TaskFilter.Completed && task.completed ||
                filter == TaskFilter.Open && !task.completed) &&
            (needle.isBlank() || task.title.lowercase().contains(needle) ||
                task.description?.lowercase()?.contains(needle) == true)
    }
}

/** Per-task availability of the row menu actions, resolved by the screen that owns mutations. */
internal data class TaskRowActions(
    val canEdit: (GroupwareTask) -> Boolean,
    val canDelete: (GroupwareTask) -> Boolean,
    val canToggle: (GroupwareTask) -> Boolean,
    val onToggleDone: (GroupwareTask, Boolean) -> Unit,
    val onOpen: (GroupwareTask) -> Unit,
    val onEdit: (GroupwareTask) -> Unit,
    val onDelete: (GroupwareTask) -> Unit,
)

@Composable
internal fun TaskList(
    tasks: List<GroupwareTask>,
    emptyMessage: String,
    selectedInstanceId: String?,
    listState: LazyListState,
    actions: TaskRowActions,
    modifier: Modifier = Modifier,
    /** How deeply each task is nested under its parent, by instance id. */
    depths: Map<String, Int> = emptyMap(),
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(tasks, key = GroupwareTask::instanceId) { task ->
            TaskRow(task, selected = task.instanceId == selectedInstanceId, actions, depths[task.instanceId] ?: 0)
        }
        if (tasks.isEmpty()) item {
            Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskRow(task: GroupwareTask, selected: Boolean, actions: TaskRowActions, depth: Int = 0) {
    var menuExpanded by remember(task.instanceId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.padding(start = (depth.coerceAtMost(4) * 24).dp).fillMaxWidth()
            .semantics { this.selected = selected }.combinedClickable(
            onClickLabel = "Open ${task.title}",
            onLongClickLabel = "Show actions for ${task.title}",
            onClick = { actions.onOpen(task) },
            onLongClick = { menuExpanded = true },
        ),
        colors = workspaceListCardColors(selected),
        border = workspaceListCardBorder(selected),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = task.completed,
                onCheckedChange = if (actions.canToggle(task)) { done -> actions.onToggleDone(task, done) } else null,
                modifier = Modifier.semantics {
                    contentDescription = if (task.completed) "Mark ${task.title} as not done" else "Mark ${task.title} as done"
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                taskRowSummary(task)?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(NextcloudIcons.More, contentDescription = "Actions for ${task.title}")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("View details") },
                        onClick = {
                            menuExpanded = false
                            actions.onOpen(task)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        enabled = actions.canEdit(task),
                        onClick = {
                            menuExpanded = false
                            actions.onEdit(task)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        enabled = actions.canDelete(task),
                        onClick = {
                            menuExpanded = false
                            actions.onDelete(task)
                        },
                    )
                }
            }
        }
    }
}
