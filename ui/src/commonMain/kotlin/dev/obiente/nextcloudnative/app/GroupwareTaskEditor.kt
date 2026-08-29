package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

private const val MAX_TASK_EDITOR_SAVED_STATE_CHARACTERS = 32 * 1_024

internal data class GroupwareTaskEditorState(
    val title: String,
    val dueDate: String,
    val description: String,
    val completed: Boolean,
    val calendarHref: String?,
    val editStartEtag: String?,
)

internal fun encodeGroupwareTaskEditorStateForSavedState(
    state: GroupwareTaskEditorState,
): List<String>? {
    val values = listOf(
        state.title,
        state.dueDate,
        state.description,
        state.completed.toString(),
        state.calendarHref.orEmpty(),
        state.editStartEtag.orEmpty(),
    )
    return values.takeIf { encoded ->
        encoded.sumOf { value -> value.length } <= MAX_TASK_EDITOR_SAVED_STATE_CHARACTERS
    }
}

internal fun decodeGroupwareTaskEditorStateFromSavedState(
    values: List<String>,
): GroupwareTaskEditorState? = values.takeIf { it.size == 6 }?.let { encoded ->
    GroupwareTaskEditorState(
        title = encoded[0],
        dueDate = encoded[1],
        description = encoded[2],
        completed = encoded[3].toBooleanStrictOrNull() ?: return null,
        calendarHref = encoded[4].ifEmpty { null },
        editStartEtag = encoded[5].ifEmpty { null },
    )
}

private val GroupwareTaskEditorStateSaver = Saver<GroupwareTaskEditorState, List<String>>(
    save = { state -> encodeGroupwareTaskEditorStateForSavedState(state) },
    restore = { values -> decodeGroupwareTaskEditorStateFromSavedState(values) },
)

@Composable
internal fun TaskEditorDialog(
    task: GroupwareTask?,
    calendars: List<GroupwareCalendar>,
    mutationInProgress: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (TaskDraft, GroupwareCalendar, String?) -> Unit,
) {
    val editorKey = task?.instanceId ?: "new-task"
    var editorState by rememberSaveable(editorKey, stateSaver = GroupwareTaskEditorStateSaver) {
        mutableStateOf(
            GroupwareTaskEditorState(
                title = task?.title.orEmpty(),
                dueDate = task?.due?.take(8)?.let { compact ->
                    if (compact.length == 8) {
                        "${compact.take(4)}-${compact.substring(4, 6)}-${compact.takeLast(2)}"
                    } else {
                        ""
                    }
                }.orEmpty(),
                description = task?.description.orEmpty(),
                completed = task?.completed == true,
                calendarHref = task?.calendarHref ?: calendars.firstOrNull()?.href,
                editStartEtag = task?.etag,
            ),
        )
    }
    val calendar = selectedGroupwareTaskCalendar(calendars, editorState.calendarHref)
    val dateValid = editorState.dueDate.isBlank() ||
        editorState.dueDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
        isValidGroupwareTaskDueDate(editorState.dueDate.replace("-", ""))
    AlertDialog(
        onDismissRequest = { if (!mutationInProgress) onDismiss() },
        title = { Text(if (task == null) "New task" else "Edit task") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                item {
                    OutlinedTextField(
                        editorState.title,
                        { editorState = editorState.copy(title = it) },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        editorState.dueDate,
                        { editorState = editorState.copy(dueDate = it) },
                        label = { Text("Due date") },
                        placeholder = { Text("YYYY-MM-DD") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = !dateValid,
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        editorState.description,
                        { editorState = editorState.copy(description = it) },
                        label = { Text("Description") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = editorState.completed,
                            onCheckedChange = { editorState = editorState.copy(completed = it) },
                            enabled = !mutationInProgress,
                        )
                        Text("Completed")
                    }
                }
                if (calendars.size > 1 || calendar == null) item {
                    Text("Task list", style = MaterialTheme.typography.labelLarge)
                    if (editorState.calendarHref != null && calendar == null) {
                        Text(
                            "The previously selected task list is unavailable. Choose another list to continue.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        calendars.forEach { candidate ->
                            FilterChip(
                                selected = candidate.href == calendar?.href,
                                onClick = { editorState = editorState.copy(calendarHref = candidate.href) },
                                label = { Text(candidate.displayName) },
                                enabled = !mutationInProgress,
                            )
                        }
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        dismissButton = {
            TextButton(enabled = !mutationInProgress, onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = !mutationInProgress && editorState.title.isNotBlank() && dateValid && calendar != null,
                onClick = {
                    onSave(
                        TaskDraft(
                            editorState.title,
                            editorState.dueDate,
                            editorState.description,
                            editorState.completed,
                        ),
                        requireNotNull(calendar),
                        editorState.editStartEtag,
                    )
                },
            ) { Text("Save") }
        },
    )
}

internal fun selectedGroupwareTaskCalendar(
    calendars: List<GroupwareCalendar>,
    selectedHref: String?,
): GroupwareCalendar? = calendars.firstOrNull { calendar -> calendar.href == selectedHref }
