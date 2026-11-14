package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * A phone month view that reads like a wall calendar: the weeks fill the screen, and each day
 * shows its events as colored chips with a "+N" for the rest. Tapping a day opens its schedule;
 * tapping a chip opens the event. Short screens and large text scroll instead of squeezing.
 */
@Composable
internal fun CalendarPhoneMonthGrid(
    month: CalendarMonth,
    selectedDate: String,
    todayDate: String,
    events: List<GroupwareCalendarEvent>,
    calendarByHref: Map<String, GroupwareCalendar>,
    onOpenDay: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byDay = remember(events, month) {
        calendarEventsByDate(events, "${month.isoPrefix}01", "${month.isoPrefix}${month.days()}")
    }
    val weeks = remember(month) {
        val leading = dayOfWeekMondayFirst(month.year, month.month, 1)
        (List<Int?>(leading) { null } + (1..month.days()).map { it })
            .let { days -> days + List((7 - days.size % 7) % 7) { null } }.chunked(7)
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            CalendarWeekdayNames.forEachIndexed { index, day ->
                Text(day, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    color = if (index >= 5) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val minimumRow = (84 * fontScale).dp
            val rowHeight = (maxHeight / weeks.size).coerceAtLeast(minimumRow)
            val chipHeight = (19 * fontScale).dp
            val capacity = floor(((rowHeight - (30 * fontScale).dp) / chipHeight)).toInt().coerceAtLeast(1)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                weeks.forEach { week ->
                    Row(Modifier.fillMaxWidth().height(rowHeight - 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { day ->
                            val date = day?.let { "${month.isoPrefix}${it.toString().padStart(2, '0')}" }
                            if (date == null) {
                                Box(Modifier.weight(1f).fillMaxHeight())
                            } else {
                                CalendarPhoneMonthCell(
                                    date, byDay[date].orEmpty(), calendarByHref, date == selectedDate, date == todayDate,
                                    capacity, { onOpenDay(date) }, onSelectEvent, Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarPhoneMonthCell(
    date: String,
    events: List<GroupwareCalendarEvent>,
    calendarByHref: Map<String, GroupwareCalendar>,
    selected: Boolean,
    today: Boolean,
    capacity: Int,
    onOpenDay: () -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier,
) {
    // A day with more events than fit keeps one line for the count.
    val shown = if (events.size > capacity) (capacity - 1).coerceAtLeast(0) else events.size
    Column(
        modifier.background(
            animatedColor(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow),
            RoundedCornerShape(10.dp),
        ).clickable(role = Role.Button, onClick = onOpenDay).semantics {
            this.selected = selected
            contentDescription = "${date.displayCalendarDate()}, ${events.size} ${if (events.size == 1) "event" else "events"}${if (today) ", today" else ""}"
        }.padding(horizontal = 2.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(24.dp).background(if (today) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(date.takeLast(2).toInt().toString(), style = MaterialTheme.typography.labelLarge,
                fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Medium,
                color = if (today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        events.take(shown).forEach { event ->
            CalendarEventChip(event, calendarByHref[event.calendarHref], dense = true) { onSelectEvent(event) }
        }
        if (events.size > shown) {
            Text("+${events.size - shown}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}
