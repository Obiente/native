package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** Priority, progress, start date, tags and parent task in the task editor. */
@Composable
internal fun TaskExtrasEditor(
    extras: TaskExtras,
    completed: Boolean,
    parentCandidates: List<GroupwareTask>,
    knownTags: List<String>,
    enabled: Boolean,
    onChange: (TaskExtras) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Priority", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskPriority.entries.forEach { priority ->
                NextcloudFilterChip(
                    selected = TaskPriority.of(extras.priority) == priority,
                    onClick = { onChange(extras.copy(priority = priority.value)) },
                    label = { Text(priority.label) },
                    enabled = enabled,
                )
            }
        }
        val percent = if (completed) 100 else extras.percentComplete.coerceAtMost(99)
        Text("Progress: $percent%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(extras.copy(percentComplete = ((it / 10f).toInt() * 10).coerceIn(0, 90))) },
            valueRange = 0f..100f,
            steps = 9,
            enabled = enabled && !completed,
            modifier = Modifier.semantics { stateDescription = "$percent percent done" },
        )
        CalendarDateField(
            extras.startDate,
            { onChange(extras.copy(startDate = it)) },
            enabled = enabled,
            label = "Start date",
            chooseLabel = "Choose start date",
            optional = true,
        )
        ContactGroupsField(
            groups = extras.categories,
            suggestions = knownTags,
            enabled = enabled,
            title = "Tags",
            fieldLabel = "Add tag",
            onChange = { onChange(extras.copy(categories = it)) },
        )
        TaskParentPicker(extras.parentUid, parentCandidates, enabled) { onChange(extras.copy(parentUid = it)) }
    }
}

@Composable
private fun TaskParentPicker(
    parentUid: String,
    candidates: List<GroupwareTask>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val parent = candidates.firstOrNull { it.uid == parentUid }
    Text("Subtask of", style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled && (candidates.isNotEmpty() || parentUid.isNotEmpty())) {
            Text(
                when {
                    parentUid.isEmpty() -> "No parent task"
                    parent != null -> parent.title
                    else -> "A task in another list"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(NextcloudIcons.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("No parent task") }, onClick = { open = false; onSelect("") })
            candidates.take(MAX_PARENT_CHOICES).forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { open = false; onSelect(candidate.uid) },
                )
            }
        }
    }
}

private const val MAX_PARENT_CHOICES = 200
