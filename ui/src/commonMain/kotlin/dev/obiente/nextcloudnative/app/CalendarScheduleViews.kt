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
internal fun MonthCalendar(
    month: CalendarMonth,
    selectedDate: String,
    events: List<GroupwareCalendarEvent>,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    calendarByHref: Map<String, GroupwareCalendar> = emptyMap(),
    todayDate: String = currentCalendarDate(),
) {
    val byDay = remember(events, month) {
        calendarEventsByDate(events, "${month.isoPrefix}01", "${month.isoPrefix}${month.days()}")
    }
    val cells = remember(month) {
        val leading = dayOfWeekMondayFirst(month.year, month.month, 1)
        (List<Int?>(leading) { null } + (1..month.days()).map { it })
            .let { days -> days + List((7 - days.size % 7) % 7) { null } }.chunked(7)
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item(key = "month-grid") {
            CalendarWeekdayLabels()
            cells.forEachIndexed { weekIndex, week ->
                key(calendarMonthWeekKey(month, weekIndex)) {
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            if (day == null) Spacer(Modifier.weight(1f).height(48.dp))
                            else {
                                val date = "${month.isoPrefix}${day.toString().padStart(2, '0')}"
                                CalendarDateButton(date, date == selectedDate, date == todayDate,
                                    byDay[date].orEmpty(), calendarByHref, { onSelectDate(date) }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        calendarSelectedDay(selectedDate, byDay[selectedDate].orEmpty(), calendarByHref, onSelectEvent)
    }
}

@Composable
internal fun CalendarWeekSchedule(
    dates: List<String>,
    selectedDate: String,
    eventsByDate: Map<String, List<GroupwareCalendarEvent>>,
    calendarByHref: Map<String, GroupwareCalendar>,
    todayDate: String,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item(key = "week-strip") {
            CalendarWeekdayLabels()
            Row(Modifier.fillMaxWidth()) {
                dates.forEach { date ->
                    CalendarDateButton(date, date == selectedDate, date == todayDate,
                        eventsByDate[date].orEmpty(), calendarByHref, { onSelectDate(date) }, Modifier.weight(1f))
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        calendarSelectedDay(selectedDate, eventsByDate[selectedDate].orEmpty(), calendarByHref, onSelectEvent)
    }
}

private fun LazyListScope.calendarSelectedDay(
    date: String,
    events: List<GroupwareCalendarEvent>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
) {
    item(key = "selected-day:$date") { CalendarDayHeading(date, events.size) }
    if (events.isEmpty()) item(key = "empty-day:$date") {
        Text("No events scheduled", Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(events, key = GroupwareCalendarEvent::instanceId) { event ->
        CalendarEventListItem(event, calendarByHref[event.calendarHref],
            modifier = Modifier.padding(bottom = 6.dp)) { onSelectEvent(event) }
    }
}

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

@Composable
private fun CalendarWeekdayLabels() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CalendarDateButton(
    date: String,
    isSelected: Boolean,
    isToday: Boolean,
    events: List<GroupwareCalendarEvent>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.heightIn(min = 48.dp)
        .semantics {
            selected = isSelected
            contentDescription = "${date.displayCalendarDate()}, ${events.size} ${if (events.size == 1) "event" else "events"}${if (isToday) ", Today" else ""}"
        }.clickable(role = Role.Button, onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.sizeIn(minWidth = 30.dp, minHeight = 30.dp)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .padding(horizontal = 5.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
            Text(date.takeLast(2).toInt().toString(), style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            events.distinctBy(GroupwareCalendarEvent::calendarHref).take(3).forEach {
                Box(Modifier.size(4.dp).background(calendarWorkspaceColor(calendarByHref[it.calendarHref], it.calendarHref), CircleShape))
            }
        }
    }
}
