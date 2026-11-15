package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** Reminders the editor can change, plus the ones it keeps as stored. */
@Composable
internal fun CalendarReminderEditor(
    minutes: List<Int>,
    keptReminders: List<CalendarEventReminder>,
    enabled: Boolean,
    onChange: (List<Int>) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    CalendarEditorSection("Reminders") {
        if (minutes.isEmpty() && keptReminders.isEmpty()) {
            Text("No reminders", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            minutes.sorted().forEach { offset ->
                InputChip(
                    selected = false,
                    enabled = enabled,
                    onClick = { onChange(minutes - offset) },
                    label = { Text(calendarReminderLabel(offset)) },
                    trailingIcon = { Icon(NextcloudIcons.Close, "Remove reminder ${calendarReminderLabel(offset)}", Modifier.size(16.dp)) },
                )
            }
        }
        keptReminders.forEach { reminder ->
            Text("${reminder.description}, kept as set elsewhere", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                enabled = enabled && minutes.size + keptReminders.size < MAX_CALENDAR_REMINDERS,
            ) {
                Icon(NextcloudIcons.Add, null, Modifier.size(18.dp))
                Text("Add reminder")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                CalendarReminderPresets.filterNot { it in minutes }.forEach { offset ->
                    DropdownMenuItem(
                        text = { Text(calendarReminderLabel(offset)) },
                        onClick = { menuOpen = false; onChange(minutes + offset) },
                    )
                }
            }
        }
    }
}

/**
 * The guest list. Adding or removing guests sends invitations or cancellations from the server
 * when the event is saved, so the editor only allows it to the organizer.
 */
@Composable
internal fun CalendarGuestEditor(
    addresses: List<String>,
    known: Map<String, CalendarEventParticipant>,
    editable: Boolean,
    readOnlyReason: String?,
    enabled: Boolean,
    onChange: (List<String>) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    val candidate = entry.trim().lowercase()
    val candidateValid = candidate.isCalendarEmailAddress() && candidate !in addresses
    fun add() {
        if (candidateValid && addresses.size < MAX_CALENDAR_ATTENDEES) {
            onChange(addresses + candidate)
            entry = ""
        }
    }
    CalendarEditorSection("Guests") {
        if (addresses.isEmpty()) {
            Text("No guests", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        addresses.forEach { address ->
            val participant = known[address]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(participant?.displayName ?: address, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        participant?.participation?.label ?: "Invitation sent when you save",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (editable) {
                    IconButton(onClick = { onChange(addresses - address) }, enabled = enabled) {
                        Icon(NextcloudIcons.Close, "Remove ${participant?.displayName ?: address}")
                    }
                }
            }
        }
        if (editable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it.take(254) },
                    label = { Text("Invite by email") },
                    singleLine = true,
                    isError = entry.isNotBlank() && !candidate.isCalendarEmailAddress(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                )
                TextButton(onClick = ::add, enabled = enabled && candidateValid) { Text("Add") }
            }
        } else if (readOnlyReason != null) {
            Text(readOnlyReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Why the guest list cannot be changed here, or null when it can. */
internal fun calendarGuestReadOnlyReason(invitation: CalendarEventInvitation, identity: CalendarUserIdentity?): String? = when {
    invitation.canManageAttendees(identity) -> null
    identity == null -> "Your calendar address could not be loaded, so guests can't be changed right now."
    identity.primaryAddress == null -> "Add an email address to your Nextcloud profile to invite people."
    else -> "Only the organizer can change the guest list."
}
