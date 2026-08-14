package dev.obiente.nextcloudnative.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun DesktopGroupwareCalendarWorkspace(
    month: CalendarMonth,
    selectedDate: String,
    view: CalendarWorkspaceView,
    calendars: List<GroupwareCalendar>,
    events: List<GroupwareCalendarEvent>,
    hiddenCalendarHrefs: Set<String>,
    query: String,
    selectedEvent: GroupwareCalendarEvent?,
    loading: Boolean = false,
    mutationsEnabled: Boolean = true,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onViewChanged: (CalendarWorkspaceView) -> Unit,
    onQueryChanged: (String) -> Unit,
    onCalendarVisibilityChanged: (String, Boolean) -> Unit,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent?) -> Unit,
    onCreateEvent: () -> Unit,
    onRefresh: () -> Unit,
    onEditEvent: (GroupwareCalendarEvent) -> Unit,
    onDeleteEvent: (GroupwareCalendarEvent) -> Unit,
) {
    val presentation = remember(events, calendars, hiddenCalendarHrefs, query, selectedDate) {
        buildCalendarWorkspacePresentation(events, calendars, hiddenCalendarHrefs, query, selectedDate)
    }
    val calendarByHref = remember(calendars) { calendars.associateBy(GroupwareCalendar::href) }
    val writableCalendarAvailable = calendars.any(GroupwareCalendar::writable)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Calendar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (loading) "Loading calendars and events..."
                    else "${month.title} · ${presentation.visibleEvents.size} visible events",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onToday) { Text("Today") }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(NextcloudIcons.Refresh, contentDescription = "Refresh calendars")
            }
            Button(onClick = onCreateEvent, enabled = mutationsEnabled && writableCalendarAvailable) {
                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("New event", modifier = Modifier.padding(start = 7.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onPrevious) {
                Icon(NextcloudIcons.Back, contentDescription = "Previous ${view.navigationPeriod()}")
            }
            Text(
                if (view == CalendarWorkspaceView.Week) presentation.weekDates.weekTitle() else month.title,
                modifier = Modifier.width(190.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onNext) {
                Icon(NextcloudIcons.ChevronRight, contentDescription = "Next ${view.navigationPeriod()}")
            }
            CalendarViewControls(view = view, onViewChanged = onViewChanged)
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.width(280.dp),
                singleLine = true,
                leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                placeholder = { Text("Search events") },
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CalendarSourcesPane(
                calendars = calendars,
                hiddenCalendarHrefs = hiddenCalendarHrefs,
                eventCountByCalendar = presentation.eventCountByCalendar,
                selectedDate = selectedDate,
                selectedDateEvents = presentation.selectedDateEvents,
                loading = loading,
                onCalendarVisibilityChanged = onCalendarVisibilityChanged,
                onSelectEvent = onSelectEvent,
                modifier = Modifier.width(218.dp).fillMaxHeight(),
            )
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(NextcloudRadii.Medium),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                AnimatedContent(
                    targetState = CalendarCanvasState(month, view, presentation),
                    transitionSpec = {
                        fadeIn(tween(durationMillis = 180)) togetherWith
                            fadeOut(tween(durationMillis = 120))
                    },
                    contentKey = { canvas -> canvas.month to canvas.view },
                    label = "Calendar canvas",
                ) { canvas ->
                    when (canvas.view) {
                    CalendarWorkspaceView.Month -> DesktopCalendarMonthGrid(
                        month = canvas.month,
                        selectedDate = selectedDate,
                        eventsByDate = canvas.presentation.eventsByDate,
                        calendarByHref = calendarByHref,
                        onSelectDate = onSelectDate,
                        onSelectEvent = { event -> onSelectDate(event.start.take(8)); onSelectEvent(event) },
                    )
                    CalendarWorkspaceView.Week -> DesktopCalendarWeek(
                        dates = canvas.presentation.weekDates,
                        selectedDate = selectedDate,
                        eventsByDate = canvas.presentation.eventsByDate,
                        calendarByHref = calendarByHref,
                        onSelectDate = onSelectDate,
                        onSelectEvent = onSelectEvent,
                    )
                    CalendarWorkspaceView.Agenda -> DesktopCalendarAgenda(
                        events = canvas.presentation.visibleEvents,
                        calendarByHref = calendarByHref,
                        selectedEvent = selectedEvent,
                        onSelectEvent = onSelectEvent,
                    )
                    }
                }
            }
            CalendarInspectorPane(
                selectedDate = selectedDate,
                event = selectedEvent,
                calendar = selectedEvent?.let { calendarByHref[it.calendarHref] },
                selectedDateEvents = presentation.selectedDateEvents,
                createEnabled = mutationsEnabled && writableCalendarAvailable,
                mutationsEnabled = mutationsEnabled,
                onSelectEvent = onSelectEvent,
                onCreateEvent = onCreateEvent,
                onEditEvent = onEditEvent,
                onDeleteEvent = onDeleteEvent,
                modifier = Modifier.width(292.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CalendarViewControls(
    view: CalendarWorkspaceView,
    onViewChanged: (CalendarWorkspaceView) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CalendarWorkspaceView.entries.forEach { candidate ->
            FilterChip(
                selected = candidate == view,
                onClick = { onViewChanged(candidate) },
                label = { Text(candidate.name) },
            )
        }
    }
}

@Composable
private fun CalendarSourcesPane(
    calendars: List<GroupwareCalendar>,
    hiddenCalendarHrefs: Set<String>,
    eventCountByCalendar: Map<String, Int>,
    selectedDate: String,
    selectedDateEvents: List<GroupwareCalendarEvent>,
    loading: Boolean,
    onCalendarVisibilityChanged: (String, Boolean) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("My calendars", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose what appears in this workspace",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(calendars, key = GroupwareCalendar::href) { calendar ->
                val visible = calendar.href !in hiddenCalendarHrefs
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onCalendarVisibilityChanged(calendar.href, !visible)
                    }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(
                        Modifier.size(11.dp).background(
                            if (visible) calendarWorkspaceColor(calendar) else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(calendar.displayName, style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (calendar.writable) "Can edit" else "Read only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        eventCountByCalendar[calendar.href].orEmptyCount(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (loading && calendars.isEmpty()) {
                item {
                    Text(
                        "Loading your calendars...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    selectedDate.displayCalendarDate(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (selectedDateEvents.isEmpty()) {
                item {
                    Text(
                        "No events on this day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(selectedDateEvents.take(5), key = GroupwareCalendarEvent::instanceId) { event ->
                    TextButton(onClick = { onSelectEvent(event) }, modifier = Modifier.fillMaxWidth()) {
                        Text(event.title, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopCalendarMonthGrid(
    month: CalendarMonth,
    selectedDate: String,
    eventsByDate: Map<String, List<GroupwareCalendarEvent>>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
) {
    val leading = dayOfWeekMondayFirst(month.year, month.month, 1)
    val cells = (List(leading) { null } + (1..month.days()).map { day -> day }).let { values ->
        values + List((7 - values.size % 7) % 7) { null }
    }.chunked(7)
    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            CALENDAR_WEEK_DAYS.forEach { day ->
                Text(
                    day,
                    modifier = Modifier.weight(1f).padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        cells.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        val date = "${month.isoPrefix}${day.toString().padStart(2, '0')}"
                        val dayEvents = eventsByDate[date].orEmpty()
                        Surface(
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(NextcloudRadii.Small),
                            color = if (date == selectedDate) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (date == selectedDate) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    day.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (date == selectedDate) FontWeight.Bold else FontWeight.Medium,
                                )
                                dayEvents.take(3).forEach { event ->
                                    CalendarGridEvent(event, calendarByHref[event.calendarHref]) {
                                        onSelectEvent(event)
                                    }
                                }
                                if (dayEvents.size > 3) {
                                    Text(
                                        "+${dayEvents.size - 3} more",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopCalendarWeek(
    dates: List<String>,
    selectedDate: String,
    eventsByDate: Map<String, List<GroupwareCalendarEvent>>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        dates.forEachIndexed { index, date ->
            val events = eventsByDate[date].orEmpty()
            Surface(
                onClick = { onSelectDate(date) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                color = if (date == selectedDate) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(CALENDAR_WEEK_DAYS[index], style = MaterialTheme.typography.labelMedium)
                    Text(
                        date.takeLast(2).trimStart('0'),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HorizontalDivider()
                    events.forEach { event ->
                        CalendarGridEvent(event, calendarByHref[event.calendarHref]) { onSelectEvent(event) }
                    }
                    if (events.isEmpty()) {
                        Text(
                            "Available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopCalendarAgenda(
    events: List<GroupwareCalendarEvent>,
    calendarByHref: Map<String, GroupwareCalendar>,
    selectedEvent: GroupwareCalendarEvent?,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
) {
    val grouped = events.groupBy { event -> event.start.take(8) }.toSortedMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        grouped.forEach { (date, dateEvents) ->
            item(key = "heading:$date") {
                Text(
                    date.displayCalendarDate(),
                    modifier = Modifier.padding(top = 8.dp, bottom = 3.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(dateEvents, key = GroupwareCalendarEvent::instanceId) { event ->
                val selected = selectedEvent?.instanceId == event.instanceId
                Surface(
                    onClick = { onSelectEvent(event) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp, 38.dp).background(calendarWorkspaceColor(calendarByHref[event.calendarHref]), CircleShape))
                        Text(event.displayTimeRange(), modifier = Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(event.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(event.location, calendarByHref[event.calendarHref]?.displayName)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarInspectorPane(
    selectedDate: String,
    event: GroupwareCalendarEvent?,
    calendar: GroupwareCalendar?,
    selectedDateEvents: List<GroupwareCalendarEvent>,
    createEnabled: Boolean,
    mutationsEnabled: Boolean,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    onCreateEvent: () -> Unit,
    onEditEvent: (GroupwareCalendarEvent) -> Unit,
    onDeleteEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (event == null) {
                Text(selectedDate.displayCalendarDate(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (selectedDateEvents.isEmpty()) "Your schedule is clear."
                    else "${selectedDateEvents.size} event${if (selectedDateEvents.size == 1) "" else "s"} scheduled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onCreateEvent,
                    enabled = createEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add event", modifier = Modifier.padding(start = 7.dp))
                }
                selectedDateEvents.forEach { candidate ->
                    Surface(
                        onClick = { onSelectEvent(candidate) },
                        shape = RoundedCornerShape(NextcloudRadii.Small),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(candidate.title, fontWeight = FontWeight.SemiBold)
                            Text(candidate.displayTimeRange(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(11.dp).background(calendarWorkspaceColor(calendar), CircleShape))
                    Text(calendar?.displayName ?: "Calendar", style = MaterialTheme.typography.labelLarge)
                }
                Text(event.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                CalendarInspectorFact(NextcloudIcons.Calendar, event.start.take(8).displayCalendarDate())
                CalendarInspectorFact(NextcloudIcons.Schedule, event.displayTimeRange())
                event.location?.let { location -> CalendarInspectorFact(NextcloudIcons.app("location"), location) }
                event.recurrenceRule?.let { recurrence -> CalendarInspectorFact(NextcloudIcons.Refresh, recurrence) }
                event.description?.let { description ->
                    HorizontalDivider()
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))
                val editable = mutationsEnabled && calendar?.writable == true &&
                    event.etag != null && !event.isGeneratedOccurrence
                if (event.isGeneratedOccurrence) {
                    Text(
                        "This generated occurrence is read-only. Open the series to change it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { onEditEvent(event) },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (event.recurrenceRule != null) "Edit series" else "Edit event") }
                TextButton(
                    onClick = { onDeleteEvent(event) },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (event.recurrenceRule != null) "Delete series" else "Delete event",
                        color = if (editable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class CalendarCanvasState(
    val month: CalendarMonth,
    val view: CalendarWorkspaceView,
    val presentation: CalendarWorkspacePresentation,
)

@Composable
private fun CalendarInspectorFact(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CalendarGridEvent(
    event: GroupwareCalendarEvent,
    calendar: GroupwareCalendar?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(
            calendarWorkspaceColor(calendar).copy(alpha = 0.18f),
            RoundedCornerShape(4.dp),
        ).clickable(onClick = onClick).padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(4.dp, 16.dp).background(calendarWorkspaceColor(calendar), CircleShape))
        Column(modifier = Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!event.allDay) {
                Text(event.start.compactTime(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun calendarWorkspaceColor(calendar: GroupwareCalendar?): Color {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
    )
    return palette[(calendar?.href?.hashCode() ?: 0).absoluteValueSafe() % palette.size]
}

private fun Int.absoluteValueSafe(): Int = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)

private fun Int?.orEmptyCount(): String = (this ?: 0).toString()

private fun CalendarWorkspaceView.navigationPeriod(): String = when (this) {
    CalendarWorkspaceView.Week -> "week"
    CalendarWorkspaceView.Month,
    CalendarWorkspaceView.Agenda,
    -> "month"
}

private fun List<String>.weekTitle(): String {
    val first = firstOrNull()?.parseCompactCalendarDate() ?: return "Week"
    val last = lastOrNull()?.parseCompactCalendarDate() ?: return "Week"
    return if (first.month == last.month) {
        "${first.day}-${last.day} ${CALENDAR_MONTH_NAMES[first.month - 1]} ${first.year}"
    } else {
        "${first.day} ${CALENDAR_MONTH_NAMES[first.month - 1]} - ${last.day} ${CALENDAR_MONTH_NAMES[last.month - 1]}"
    }
}

private val CALENDAR_WEEK_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val CALENDAR_MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
