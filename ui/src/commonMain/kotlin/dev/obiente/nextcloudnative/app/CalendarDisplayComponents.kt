package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption

/** Calendar supplies view IDs; the shared control owns layout and input behavior. */
@Composable
internal fun CalendarViewSelector(
    view: CalendarWorkspaceView,
    onViewChanged: (CalendarWorkspaceView) -> Unit,
    modifier: Modifier = Modifier,
) {
    NextcloudSegmentedControl(
        options = CalendarWorkspaceView.entries.map { NextcloudSegmentedOption(it.name, it.name) },
        selectedId = view.name,
        onSelected = { id -> CalendarWorkspaceView.entries.firstOrNull { it.name == id }?.let(onViewChanged) },
        modifier = modifier,
        accessibilityLabel = "calendar views",
    )
}

/** Consistent time-first event rows, with the same calendar color in every layout. */
@Composable
internal fun CalendarEventListItem(
    event: GroupwareCalendarEvent,
    calendar: GroupwareCalendar? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val timeColumnWidth = 58.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(timeColumnWidth), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (event.allDay) "All day" else event.start.compactTime(),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (!event.allDay) event.end?.compactTime()?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.padding(top = 3.dp).size(3.dp, 38.dp)
                .background(calendarWorkspaceColor(calendar, event.calendarHref), CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                val metadata = listOfNotNull(event.location?.takeIf { it.isNotBlank() }, calendar?.displayName?.takeIf { it.isNotBlank() })
                if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun CalendarDayHeading(date: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(date.displayCalendarDate(), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("$count ${if (count == 1) "event" else "events"}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun calendarWorkspaceColor(calendar: GroupwareCalendar?, fallbackHref: String? = null): Color {
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error)
    val hash = (calendar?.href ?: fallbackHref)?.hashCode() ?: 0
    // Preserve the existing desktop color assignment when sharing it with phone views.
    val index = if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)
    return palette[index % palette.size]
}

@Composable
internal fun CalendarVisibilityRow(
    calendar: GroupwareCalendar,
    visible: Boolean,
    eventCount: Int,
    onVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp))
        .toggleable(visible, role = Role.Checkbox, onValueChange = onVisibilityChanged)
        .semantics { contentDescription = "Show ${calendar.displayName}" }
        .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(checked = visible, onCheckedChange = null)
        Box(Modifier.size(8.dp).background(calendarWorkspaceColor(calendar), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(calendar.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!calendar.writable) Text("Read only", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(eventCount.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
