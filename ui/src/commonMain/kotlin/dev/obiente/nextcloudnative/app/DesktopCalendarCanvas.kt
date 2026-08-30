package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import kotlin.math.floor
import kotlinx.coroutines.launch

@Composable
internal fun DesktopCalendarMonthGrid(
    month: CalendarMonth,
    selectedDate: String,
    todayDate: String,
    eventsByDate: Map<String, List<GroupwareCalendarEvent>>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onSelectDate: (String) -> Unit,
    onShowDate: (String) -> Unit,
    onSelectEvent: (String, GroupwareCalendarEvent) -> Unit,
) {
    val leading = dayOfWeekMondayFirst(month.year, month.month, 1)
    val cells = (List(leading) { null } + (1..month.days()).toList()).let { values ->
        values + List((7 - values.size % 7) % 7) { null }
    }.chunked(7)
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvasWidth = maxWidth.coerceAtLeast(630.dp)
        val rowHeight = ((maxHeight - 38.dp) / cells.size).coerceAtLeast((96 * fontScale).dp)
        Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(canvasWidth).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                    DESKTOP_CALENDAR_WEEK_DAYS.forEach { day ->
                        Text(
                            day, Modifier.weight(1f).padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                cells.forEach { week ->
                    Row(Modifier.fillMaxWidth().height(rowHeight)) {
                        week.forEachIndexed { index, day ->
                            val date = day?.let { "${month.isoPrefix}${it.toString().padStart(2, '0')}" }
                            val events = eventsByDate[date].orEmpty()
                            val chosen = date == selectedDate
                            val background = when {
                                chosen -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                                day == null || index >= 5 -> MaterialTheme.colorScheme.surfaceContainerLow
                                else -> MaterialTheme.colorScheme.surface
                            }
                            val cellModifier = Modifier.weight(1f).fillMaxHeight().background(background)
                                .drawBehind {
                                    drawLine(lineColor, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                                    if (index > 0) drawLine(lineColor, Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
                                }
                            if (date == null) {
                                Box(cellModifier)
                            } else {
                                val eventHeight = (31 * fontScale).dp
                                val availableHeight = rowHeight - (38 * fontScale).dp
                                val fullCapacity = floor(availableHeight.value / eventHeight.value).toInt().coerceAtLeast(1)
                                val capacity = if (events.size > fullCapacity) {
                                    floor((availableHeight.value - 36 * fontScale) / eventHeight.value).toInt().coerceAtLeast(0)
                                } else fullCapacity
                                Column(
                                    cellModifier.clickable(role = Role.Button) { onSelectDate(date) }
                                        .semantics {
                                            selected = chosen
                                            contentDescription = calendarDayDescription(date, events.size, date == todayDate)
                                        }.padding(horizontal = 5.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    DesktopCalendarDayNumber(date, chosen, date == todayDate)
                                    events.take(capacity).forEach { event ->
                                        DesktopCalendarGridEvent(event, calendarByHref[event.calendarHref], compact = true) {
                                            onSelectEvent(date, event)
                                        }
                                    }
                                    if (events.size > capacity) {
                                        TextButton(
                                            onClick = { onShowDate(date) },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics {
                                                contentDescription = "Show all ${events.size} events on ${date.displayCalendarDate()}"
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) {
                                            Text("+${events.size - capacity} more", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
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
internal fun DesktopCalendarWeek(
    dates: List<String>,
    selectedDate: String,
    todayDate: String,
    eventsByDate: Map<String, List<GroupwareCalendarEvent>>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (String, GroupwareCalendarEvent) -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val horizontalScroll = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val minimumColumnWidth = (108 * density.fontScale.coerceAtLeast(1f)).dp
        val columnWidth = (availableWidth / 7).coerceAtLeast(minimumColumnWidth)
        val hasHorizontalOverflow = availableWidth < minimumColumnWidth * 7
        val scrollStep = with(density) { (availableWidth * 0.75f).roundToPx() }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontalScroll)) {
                dates.forEachIndexed { index, date ->
                    val events = eventsByDate[date].orEmpty()
                    val chosen = date == selectedDate
                    Column(
                        Modifier.width(columnWidth).fillMaxHeight().drawBehind {
                            if (index > 0) drawLine(lineColor, Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
                        },
                    ) {
                        Column(
                            Modifier.fillMaxWidth().background(
                                if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            ).clickable(role = Role.Button) { onSelectDate(date) }.semantics {
                                selected = chosen
                                contentDescription = calendarDayDescription(date, events.size, date == todayDate)
                            }.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(DESKTOP_CALENDAR_WEEK_DAYS[index], style = MaterialTheme.typography.labelMedium)
                            DesktopCalendarDayNumber(date, chosen, date == todayDate)
                        }
                        HorizontalDivider(color = lineColor)
                        LazyColumn(
                            Modifier.fillMaxSize().semantics { contentDescription = "Events on ${date.displayCalendarDate()}" },
                            contentPadding = PaddingValues(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            items(events, key = GroupwareCalendarEvent::instanceId) { event ->
                                DesktopCalendarGridEvent(event, calendarByHref[event.calendarHref], compact = false) {
                                    onSelectEvent(date, event)
                                }
                            }
                            if (events.isEmpty()) item {
                                Text("No events", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (hasHorizontalOverflow) {
                HorizontalDivider(color = lineColor)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { coroutineScope.launch { horizontalScroll.scrollTo((horizontalScroll.value - scrollStep).coerceAtLeast(0)) } },
                        enabled = horizontalScroll.value > 0,
                    ) {
                        Icon(NextcloudIcons.Back, "Show earlier days")
                    }
                    Text(
                        "Scroll week", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(
                        onClick = { coroutineScope.launch { horizontalScroll.scrollTo((horizontalScroll.value + scrollStep).coerceAtMost(horizontalScroll.maxValue)) } },
                        enabled = horizontalScroll.value < horizontalScroll.maxValue,
                    ) {
                        Icon(NextcloudIcons.ChevronRight, "Show later days")
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopCalendarAgenda(
    events: List<GroupwareCalendarEvent>,
    calendarByHref: Map<String, GroupwareCalendar>,
    selectedEvent: GroupwareCalendarEvent?,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (events.isEmpty()) item {
            Text("No matching events", style = MaterialTheme.typography.titleMedium)
            Text("Try another date or check your calendar filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        events.groupBy { it.start.take(8) }.toSortedMap().forEach { (date, dateEvents) ->
            item(key = "heading:$date") {
                CalendarDayHeading(date, dateEvents.size, Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            items(dateEvents, key = GroupwareCalendarEvent::instanceId) { event ->
                CalendarEventListItem(event, calendarByHref[event.calendarHref], selectedEvent?.instanceId == event.instanceId) {
                    onSelectEvent(event)
                }
            }
        }
    }
}

@Composable
private fun DesktopCalendarDayNumber(date: String, selected: Boolean, today: Boolean) {
    Box(
        Modifier.size(28.dp).background(
            when {
                today -> MaterialTheme.colorScheme.primary
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Transparent
            }, CircleShape,
        ), contentAlignment = Alignment.Center,
    ) {
        Text(
            date.takeLast(2).toInt().toString(), style = MaterialTheme.typography.labelLarge,
            fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Medium,
            color = if (today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DesktopCalendarGridEvent(
    event: GroupwareCalendarEvent,
    calendar: GroupwareCalendar?,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val color = calendarWorkspaceColor(calendar)
    val eventModifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
        .clickable(role = Role.Button, onClick = onClick).semantics {
            contentDescription = listOfNotNull(event.title, event.displayTimeRange(), calendar?.displayName).joinToString(", ")
        }
    if (compact) {
        Row(
            eventModifier.padding(horizontal = 5.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(3.dp, 18.dp).background(color, CircleShape))
            if (!event.allDay) Text(event.start.compactTime(), style = MaterialTheme.typography.labelSmall)
            Text(
                event.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column(
            eventModifier.drawBehind {
                drawLine(color, Offset(1.5.dp.toPx(), 4.dp.toPx()), Offset(1.5.dp.toPx(), size.height - 4.dp.toPx()), 3.dp.toPx())
            }.padding(start = 7.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(event.displayTimeRange(), style = MaterialTheme.typography.labelSmall)
            Text(
                event.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun calendarDayDescription(date: String, count: Int, today: Boolean): String =
    "${date.displayCalendarDate()}, $count ${if (count == 1) "event" else "events"}${if (today) ", today" else ""}"

private val DESKTOP_CALENDAR_WEEK_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
