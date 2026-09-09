package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopCalendarSourcesPane(
    calendars: List<GroupwareCalendar>,
    hiddenCalendarHrefs: Set<String>,
    eventCountByCalendar: Map<String, Int>,
    loading: Boolean,
    onCalendarVisibilityChanged: (String, Boolean) -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(NextcloudRadii.Medium), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                DesktopCalendarPaneHeading("My calendars", "Hide calendars panel", onClose)
                Text("Show events from", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(calendars, key = GroupwareCalendar::href) { calendar ->
                CalendarVisibilityRow(
                    calendar, calendar.href !in hiddenCalendarHrefs, eventCountByCalendar[calendar.href] ?: 0,
                    { visible -> onCalendarVisibilityChanged(calendar.href, visible) },
                )
            }
            if (calendars.isEmpty()) item {
                Text(
                    if (loading) "Loading your calendars..." else "No calendars available",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DesktopCalendarInspectorPane(
    selectedDate: String,
    event: GroupwareCalendarEvent?,
    calendarByHref: Map<String, GroupwareCalendar>,
    selectedDateEvents: List<GroupwareCalendarEvent>,
    createEnabled: Boolean,
    mutationsEnabled: Boolean,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    onCreateEvent: () -> Unit,
    onEditEvent: (GroupwareCalendarEvent) -> Unit,
    onDeleteEvent: (GroupwareCalendarEvent) -> Unit,
    onClose: (() -> Unit)?,
    onShowDay: () -> Unit,
    modifier: Modifier,
) {
    val calendar = event?.let { calendarByHref[it.calendarHref] }
    Surface(
        modifier, shape = RoundedCornerShape(NextcloudRadii.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                DesktopCalendarPaneHeading(if (event == null) "Day schedule" else "Event details", "Hide details panel", onClose)
            }
            if (event == null) {
                item {
                    CalendarDayHeading(selectedDate, selectedDateEvents.size)
                }
                if (selectedDateEvents.isEmpty()) item {
                    Text("No events on this day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(selectedDateEvents, key = GroupwareCalendarEvent::instanceId) { candidate ->
                    CalendarEventListItem(candidate, calendarByHref[candidate.calendarHref]) { onSelectEvent(candidate) }
                }
                item {
                    Button(onClick = onCreateEvent, enabled = createEnabled, modifier = Modifier.fillMaxWidth()) {
                        Icon(NextcloudIcons.Add, null, Modifier.size(18.dp))
                        Text("Add event", Modifier.padding(start = 7.dp))
                    }
                }
            } else {
                item {
                    TextButton(onClick = onShowDay, contentPadding = PaddingValues(0.dp)) {
                        Icon(NextcloudIcons.Back, null, Modifier.size(16.dp))
                        Text("Day schedule", Modifier.padding(start = 6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(9.dp).background(calendarWorkspaceColor(calendar), CircleShape))
                        Text(calendar?.displayName ?: "Calendar", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        event.title, Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    )
                }
                item { CalendarEventDetailsBody(event) }
                item {
                    val editable = mutationsEnabled && calendar?.writable == true && event.etag != null && !event.isGeneratedOccurrence
                    OutlinedButton(onClick = { onEditEvent(event) }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                        Text(if (event.recurrenceRule != null) "Edit series" else "Edit event")
                    }
                    TextButton(onClick = { onDeleteEvent(event) }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (event.recurrenceRule != null) "Delete series" else "Delete event",
                            color = if (editable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopCalendarPaneHeading(title: String, closeLabel: String, onClose: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, closeLabel, Modifier.size(18.dp)) }
    }
}
