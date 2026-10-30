package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Day and week schedules as a time grid: a column per date with hour lines, timed events placed
 * by their start and length, overlapping events side by side, and all-day events in a row above.
 * Today's column shows the current time. Tapping empty time offers a new event starting there,
 * rounded to the half hour; tapping a date header opens that day. The grid starts scrolled to
 * the working morning, or to just before now on today.
 */
@Composable
internal fun CalendarTimeGrid(
    dates: List<String>,
    selectedDate: String,
    todayDate: String,
    eventsByDate: Map<String, List<GroupwareCalendarEvent>>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (String, GroupwareCalendarEvent) -> Unit,
    onCreateAt: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    nowMinute: Int? = null,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val hourHeight = CalendarHourHeight * fontScale
    val gutter = CalendarTimeGutter * fontScale
    val layouts = remember(dates, eventsByDate) {
        dates.associateWith { date -> calendarDayLayout(date, eventsByDate[date].orEmpty()) }
    }
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val firstHour = if (todayDate in dates && nowMinute != null) (nowMinute / 60 - 1).coerceAtLeast(0) else DefaultFirstHour
    LaunchedEffect(dates.firstOrNull(), dates.size) {
        scroll.scrollTo(with(density) { (hourHeight * firstHour).roundToPx() })
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Box(Modifier.width(gutter))
            dates.forEach { date ->
                CalendarGridDateHeader(date, date == selectedDate, date == todayDate, compact = dates.size > 1,
                    eventCount = eventsByDate[date].orEmpty().size, onClick = { onSelectDate(date) }, modifier = Modifier.weight(1f))
            }
        }
        if (layouts.values.any { it.allDay.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("All day", Modifier.width(gutter).padding(end = 6.dp, top = 3.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 2)
                dates.forEach { date ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val allDay = layouts[date]?.allDay.orEmpty()
                        allDay.take(MaxAllDayChips).forEach { event ->
                            CalendarEventChip(event, calendarByHref[event.calendarHref], dense = dates.size > 1) { onSelectEvent(date, event) }
                        }
                        if (allDay.size > MaxAllDayChips) Text("+${allDay.size - MaxAllDayChips}",
                            Modifier.clickable(role = Role.Button) { onSelectDate(date) }.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider(color = lineColor)
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            Column(Modifier.width(gutter)) {
                (0 until 24).forEach { hour ->
                    Box(Modifier.height(hourHeight).fillMaxWidth()) {
                        if (hour > 0) Text("%02d:00".format(hour), Modifier.align(Alignment.TopEnd).offset(y = (-7).dp).padding(end = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            dates.forEachIndexed { index, date ->
                CalendarGridDayColumn(
                    date = date, layout = layouts[date] ?: CalendarDayLayout(emptyList(), emptyList()),
                    calendarByHref = calendarByHref, hourHeight = hourHeight, lineColor = lineColor,
                    leadingLine = index > 0, dense = dates.size > 1,
                    nowMinute = nowMinute.takeIf { date == todayDate },
                    onSelectEvent = { event -> onSelectEvent(date, event) },
                    onCreateAt = onCreateAt,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CalendarGridDayColumn(
    date: String,
    layout: CalendarDayLayout,
    calendarByHref: Map<String, GroupwareCalendar>,
    hourHeight: Dp,
    lineColor: Color,
    leadingLine: Boolean,
    dense: Boolean,
    nowMinute: Int?,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    onCreateAt: ((String) -> Unit)?,
    modifier: Modifier,
) {
    val nowColor = MaterialTheme.colorScheme.error
    val currentOnCreateAt by rememberUpdatedState(onCreateAt)
    BoxWithConstraints(
        modifier.height(hourHeight * 24)
            .drawBehind {
                val hour = hourHeight.toPx()
                for (line in 1 until 24) drawLine(lineColor, Offset(0f, hour * line), Offset(size.width, hour * line), 1f)
                if (leadingLine) drawLine(lineColor, Offset.Zero, Offset(0f, size.height), 1f)
            }
            .then(if (onCreateAt == null) Modifier else Modifier.pointerInput(date, hourHeight) {
                detectTapGestures { position ->
                    currentOnCreateAt?.invoke(calendarSlotStart(date, (position.y / hourHeight.toPx() * 60).toInt()))
                }
            })
            .semantics(mergeDescendants = false) {
                contentDescription = "Schedule for ${date.displayCalendarDate()}"
                if (onCreateAt != null) onClick("New event on ${date.displayCalendarDate()}") {
                    currentOnCreateAt?.invoke(calendarSlotStart(date, DefaultFirstHour * 60 + 60)); true
                }
            },
    ) {
        val columnWidth = maxWidth
        layout.timed.forEach { placement ->
            val top = hourHeight * (placement.startMinute / 60f)
            val height = (hourHeight * ((placement.endMinute - placement.startMinute) / 60f))
                .coerceAtLeast(hourHeight * (MinimumEventMinutes / 60f))
            val laneWidth = columnWidth / placement.lanes
            CalendarTimedEventBlock(
                placement.event, calendarByHref[placement.event.calendarHref], dense,
                Modifier.offset(x = laneWidth * placement.lane, y = top).width(laneWidth).height(height).padding(1.dp),
            ) { onSelectEvent(placement.event) }
        }
        nowMinute?.let { minute ->
            val y = hourHeight * (minute / 60f)
            Box(Modifier.fillMaxWidth().offset(y = y - 1.dp).height(2.dp).background(nowColor))
            Box(Modifier.offset(x = (-4).dp, y = y - 5.dp).size(10.dp).background(nowColor, CircleShape))
        }
    }
}

@Composable
private fun CalendarGridDateHeader(
    date: String,
    selected: Boolean,
    today: Boolean,
    compact: Boolean,
    eventCount: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val parsed = date.parseCompactCalendarDate()
    val weekday = parsed?.let { CalendarWeekdayNames[dayOfWeekMondayFirst(it.year, it.month, it.day)] }.orEmpty()
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick).semantics {
            this.selected = selected
            contentDescription = "${date.displayCalendarDate()}, $eventCount ${if (eventCount == 1) "event" else "events"}${if (today) ", today" else ""}"
        }.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(weekday, style = MaterialTheme.typography.labelMedium,
            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.size(if (compact) 32.dp else 40.dp)
                .background(animatedColor(if (today) MaterialTheme.colorScheme.primary else Color.Transparent), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(parsed?.day?.toString().orEmpty(),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = if (today || selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun CalendarTimedEventBlock(
    event: GroupwareCalendarEvent,
    calendar: GroupwareCalendar?,
    dense: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val color = calendarWorkspaceColor(calendar, event.calendarHref)
    val content = calendarEventContentColor(color)
    Column(
        modifier.background(color, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(event.title, event.displayTimeRange(), event.location, calendar?.displayName).joinToString(", ")
            }
            .padding(horizontal = if (dense) 3.dp else 8.dp, vertical = 3.dp),
    ) {
        Text(event.title, Modifier.sharedElement(SharedElementKeys.calendarEventTitle(event.instanceId)),
            style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = content, maxLines = if (dense) 3 else 2, overflow = TextOverflow.Ellipsis)
        if (!dense) Text(listOfNotNull(event.displayTimeRange(), event.location?.takeIf(String::isNotBlank)).joinToString(" | "),
            style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A filled, one-line event label in the calendar's color, for month cells and all-day rows. */
@Composable
internal fun CalendarEventChip(
    event: GroupwareCalendarEvent,
    calendar: GroupwareCalendar?,
    dense: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = calendarWorkspaceColor(calendar, event.calendarHref)
    Text(
        event.title,
        modifier.fillMaxWidth().heightIn(min = if (dense) 16.dp else 20.dp)
            .background(color, RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(event.title, event.displayTimeRange(), calendar?.displayName).joinToString(", ") }
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = calendarEventContentColor(color),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/** Text that stays readable on a calendar color, whatever the server chose. */
internal fun calendarEventContentColor(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF111418) else Color.White

internal val CalendarWeekdayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val CalendarHourHeight = 52.dp
private val CalendarTimeGutter = 48.dp
private const val DefaultFirstHour = 7
private const val MaxAllDayChips = 2

/**
 * Swiping sideways across a calendar view moves to the previous or next period, as wall and
 * phone calendars do. Vertical scrolling inside the view is unaffected; short drags do nothing.
 */
@Composable
internal fun Modifier.calendarPeriodSwipe(onPrevious: () -> Unit, onNext: () -> Unit): Modifier {
    val currentPrevious by rememberUpdatedState(onPrevious)
    val currentNext by rememberUpdatedState(onNext)
    val threshold = with(LocalDensity.current) { CalendarSwipeDistance.toPx() }
    return pointerInput(Unit) {
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = {
                when {
                    travelled > threshold -> currentPrevious()
                    travelled < -threshold -> currentNext()
                }
            },
        ) { change, delta ->
            travelled += delta
            change.consume()
        }
    }
}

private val CalendarSwipeDistance = 72.dp
