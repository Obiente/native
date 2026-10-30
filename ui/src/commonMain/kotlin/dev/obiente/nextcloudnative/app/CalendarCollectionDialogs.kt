package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** The editor and removal confirmation the calendar list opens. */
@Composable
internal fun CalendarCollectionDialogs(manager: CalendarCollectionManager) {
    manager.edit?.let { edit ->
        CalendarCollectionEditorDialog(
            edit = edit,
            busy = manager.busy,
            error = manager.error,
            onSave = manager::save,
            onDelete = (edit as? CalendarCollectionEdit.Existing)?.calendar?.let { calendar -> { manager.requestDelete(calendar) } },
            onDismiss = manager::dismiss,
        )
    }
    manager.deleting?.let { calendar ->
        CalendarCollectionDeleteDialog(calendar, manager.busy, manager.error, manager::confirmDelete, manager::dismiss)
    }
}

/** "New calendar" with its two kinds: a calendar of the user's own, or a subscription to a link. */
@Composable
internal fun CalendarCollectionCreateButton(actions: CalendarCollectionActions, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New calendar", Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("New calendar") },
                leadingIcon = { Icon(NextcloudIcons.Calendar, contentDescription = null) },
                onClick = { menuOpen = false; actions.onCreate(false) },
            )
            DropdownMenuItem(
                text = { Text("Subscribe to a calendar link") },
                leadingIcon = { Icon(NextcloudIcons.Cloud, contentDescription = null) },
                onClick = { menuOpen = false; actions.onCreate(true) },
            )
        }
    }
}

/** No event calendar yet: say so, and offer to make one instead of a dead end. */
@Composable
internal fun CalendarCollectionEmptyState(actions: CalendarCollectionActions, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(NextcloudIcons.Calendar, contentDescription = null, modifier = Modifier.size(40.dp))
        Text("No calendars yet", style = MaterialTheme.typography.titleMedium)
        Text("Create a calendar for your events, or subscribe to one by its link.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CalendarCollectionCreateButton(actions, Modifier.widthIn(max = 320.dp))
        TextButton(onClick = onRetry) { Text("Refresh") }
    }
}

@Composable
private fun CalendarCollectionEditorDialog(
    edit: CalendarCollectionEdit,
    busy: Boolean,
    error: String?,
    onSave: (CalendarCollectionDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val existing = (edit as? CalendarCollectionEdit.Existing)?.calendar
    val subscription = existing?.kind == GroupwareCalendarKind.Subscription || (edit as? CalendarCollectionEdit.New)?.subscription == true
    var name by remember(edit) { mutableStateOf(existing?.displayName.orEmpty()) }
    var color by remember(edit) {
        mutableStateOf(existing?.color?.take(7)?.uppercase()?.takeIf { it.length == 7 } ?: CalendarCollectionColors.first())
    }
    var withTasks by remember(edit) { mutableStateOf(false) }
    var link by remember(edit) { mutableStateOf("") }
    val draft = CalendarCollectionDraft(name, color, withTasks, sourceUrl = link.takeIf { existing == null && subscription })
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(NextcloudIcons.Calendar, contentDescription = null) },
        title = {
            Text(
                when {
                    existing == null && subscription -> "New subscription"
                    existing == null -> "New calendar"
                    subscription -> "Edit subscription"
                    else -> "Edit calendar"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                if (existing == null && subscription) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text("Calendar link") },
                        supportingText = { Text("An iCalendar address such as a holiday or team feed. Its events are read-only.") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                existing?.sourceUrl?.let { source ->
                    Text("Feed: $source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Color", style = MaterialTheme.typography.labelLarge)
                CalendarColorPicker(color, enabled = !busy) { color = it }
                if (existing == null && !subscription) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(withTasks, enabled = !busy, role = Role.Switch) { withTasks = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Also use for tasks")
                            Text("Tasks saved here appear in the Tasks app.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = withTasks, onCheckedChange = null)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Text(if (subscription) "Unsubscribe" else "Delete calendar", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = !busy && draft.validationMessage() == null) {
                Text(if (busy) "Saving..." else if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarColorPicker(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    // A color set elsewhere stays available, so saving a rename never changes the color.
    val colors = (listOf(selected) + CalendarCollectionColors).distinctBy(String::uppercase)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { hex ->
            val isSelected = hex.equals(selected, ignoreCase = true)
            val swatch = hex.parseCalendarColor() ?: Color.Gray
            Box(
                Modifier.size(40.dp)
                    .selectable(isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(hex) }
                    .semantics { contentDescription = calendarColorName(hex) }
                    .border(2.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                    .padding(5.dp)
                    .background(swatch, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Icon(NextcloudIcons.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CalendarCollectionDeleteDialog(
    calendar: GroupwareCalendar,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = calendar.displayName
    val (title, message, action) = when (calendar.kind) {
        GroupwareCalendarKind.Owned -> Triple(
            "Delete calendar?",
            "\"$name\" and all its events will be deleted. If your server keeps a calendar trash bin, " +
                "you can restore it from the Nextcloud calendar on the web for a limited time.",
            "Delete",
        )
        GroupwareCalendarKind.Subscription -> Triple(
            "Unsubscribe?",
            "Events from \"$name\" will no longer appear in your calendar. The feed itself is not changed.",
            "Unsubscribe",
        )
        GroupwareCalendarKind.Shared -> Triple(
            "Remove shared calendar?",
            "\"$name\" will be removed from your calendars. ${calendar.ownerName ?: "The owner"} keeps the calendar and its events.",
            "Remove",
        )
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(message)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Working..." else action, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private fun calendarColorName(hex: String): String = when (hex.uppercase()) {
    "#0082C9" -> "Blue"
    "#1E78C1" -> "Dark blue"
    "#2E9E5B" -> "Green"
    "#E9A23B" -> "Amber"
    "#E9322D" -> "Red"
    "#8E44AD" -> "Purple"
    "#C71585" -> "Magenta"
    "#795548" -> "Brown"
    else -> "Current color $hex"
}
