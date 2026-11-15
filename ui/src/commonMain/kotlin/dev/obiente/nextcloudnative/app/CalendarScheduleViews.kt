package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun CalendarAgenda(
    events: List<GroupwareCalendarEvent>,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    emptyPeriod: String = "month",
    earliestDisplayDate: String? = null,
    calendarByHref: Map<String, GroupwareCalendar> = emptyMap(),
    searching: Boolean = false,
) {
    val groups = events.groupBy { event ->
        earliestDisplayDate?.let { maxOf(event.start.take(8), it) } ?: event.start.take(8)
    }.toSortedMap()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        groups.forEach { (date, dateEvents) ->
            item(key = "heading-$date") { CalendarDayHeading(date, dateEvents.size) }
            items(dateEvents, key = GroupwareCalendarEvent::instanceId) { event ->
                CalendarEventListItem(event, calendarByHref[event.calendarHref],
                    modifier = Modifier.padding(bottom = 6.dp)) { onSelectEvent(event) }
            }
        }
        if (events.isEmpty()) item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (searching) "No matching events" else "No events this $emptyPeriod",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (searching) "Try another search or choose which calendars to show." else "Events from your visible calendars appear here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
