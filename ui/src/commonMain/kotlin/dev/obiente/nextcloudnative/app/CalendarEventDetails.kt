package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

@Composable
internal fun EventDetailDialog(
    event: GroupwareCalendarEvent,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    error: String?,
    embedded: Boolean = false,
    inPlace: Boolean = false,
) {
    CalendarEventSurface(
        embedded = embedded,
        inPlace = inPlace,
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CalendarEventDetailsBody(event)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (canEdit) TextButton(onClick = onEdit) {
                Text(if (event.recurrenceRule != null) "Edit series" else "Edit")
            }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (canEdit) TextButton(onClick = onDelete) {
                Text(
                    if (event.recurrenceRule != null) "Delete series" else "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/** The inspector and full-page event view share the same facts and series scope. */
@Composable
internal fun CalendarEventDetailsBody(event: GroupwareCalendarEvent, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        CalendarEventFact(NextcloudIcons.Calendar, "Date", event.displayDateRange())
        CalendarEventFact(NextcloudIcons.Clock, "Time",
            event.displayTimeRange() + if (!event.allDay && event.start.endsWith('Z')) " UTC" else "")
        event.location?.takeIf(String::isNotBlank)?.let {
            CalendarEventFact(Icons.Outlined.LocationOn, "Location", it)
        }
        event.recurrenceRule?.let {
            CalendarEventFact(NextcloudIcons.Refresh, "Repeats", calendarRecurrenceDescription(it))
        }
        if (event.recurrenceRule != null || event.isGeneratedOccurrence) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(NextcloudIcons.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (event.isGeneratedOccurrence) "This occurrence is read-only to protect the complete recurring series."
                        else "Editing or deleting applies to the complete series.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        event.description?.takeIf(String::isNotBlank)?.let {
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Description", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CalendarEventFact(icon: ImageVector, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun GroupwareCalendarEvent.displayDateRange(): String {
    val startDate = start.take(8)
    val parsedEnd = end?.take(8)?.parseCompactCalendarDate()
    val endDate = if (parsedEnd != null && parsedEnd.compactValue > startDate) {
        if (allDay) parsedEnd.plusDays(-1).compactValue else parsedEnd.compactValue
    } else startDate
    return if (endDate > startDate) "${startDate.displayCalendarDate()} - ${endDate.displayCalendarDate()}"
    else startDate.displayCalendarDate()
}
