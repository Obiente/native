package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** Who the user is to scheduling, and how they answer an invitation, for the event details. */
internal class CalendarInvitationActions(
    val identity: CalendarUserIdentity?,
    val canRespond: (GroupwareCalendarEvent) -> Boolean,
    val onRespond: (GroupwareCalendarEvent, CalendarParticipation) -> Unit,
)

/** Organizer, guests with their answers, the user's own answer, and reminders. */
@Composable
internal fun CalendarEventInvitationSection(event: GroupwareCalendarEvent, actions: CalendarInvitationActions?) {
    val invitation = remember(event.rawCalendar, event.uid, event.recurrenceId) { event.invitation() }
    val reminders = remember(event.rawCalendar, event.uid, event.recurrenceId) { event.reminders() }
    val own = invitation.invitationFor(actions?.identity)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (own != null && actions != null) {
            CalendarRsvpRow(event, own, actions)
        }
        if (invitation.isMeeting) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(NextcloudIcons.People, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Guests (${invitation.attendees.size})",
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                invitation.organizer?.let { organizer ->
                    CalendarParticipantRow(organizer.displayName, "Organizer")
                }
                invitation.attendees.forEach { attendee ->
                    CalendarParticipantRow(
                        attendee.displayName,
                        attendee.participation.label + if (attendee.optional) ", optional" else "",
                    )
                }
            }
        }
        if (reminders.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(NextcloudIcons.Activity, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reminders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    reminders.forEach { Text(it.description, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun CalendarRsvpRow(
    event: GroupwareCalendarEvent,
    own: CalendarEventParticipant,
    actions: CalendarInvitationActions,
) {
    val enabled = actions.canRespond(event)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your answer", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(CalendarParticipation.Accepted, CalendarParticipation.Tentative, CalendarParticipation.Declined).forEach { answer ->
                val selected = own.participation == answer
                NextcloudFilterChip(
                    selected = selected,
                    enabled = enabled && !selected,
                    onClick = { actions.onRespond(event, answer) },
                    label = { Text(answer.label) },
                    modifier = Modifier.semantics {
                        contentDescription = if (selected) "${answer.label}, your current answer" else "Answer ${answer.label}"
                    },
                )
            }
        }
        if (event.isPartOfSeries && event.sourceRecurrenceId() == null) {
            Text(
                "Your answer applies to every occurrence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarParticipantRow(name: String, status: String) {
    Row(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The RECURRENCE-ID of the component this instance was read from: null for the series master. */
internal fun GroupwareCalendarEvent.sourceRecurrenceId(): String? {
    val id = recurrenceId ?: return null
    val lines = rawCalendar.unfoldCalendarLines()
    return id.takeIf { lines.calendarEventComponent(uid, it, href.substringAfterLast('/').substringBeforeLast('.')) != null }
}
