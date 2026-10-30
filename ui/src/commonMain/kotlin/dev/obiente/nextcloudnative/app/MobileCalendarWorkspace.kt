package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudAnimatedIcon
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileGroupwareCalendarWorkspace(
    month: CalendarMonth,
    selectedDate: String,
    view: CalendarWorkspaceView,
    calendars: List<GroupwareCalendar>,
    events: List<GroupwareCalendarEvent>,
    hiddenCalendarHrefs: Set<String>,
    query: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onViewChanged: (CalendarWorkspaceView) -> Unit,
    onQueryChanged: (String) -> Unit,
    onCalendarVisibilityChanged: (String, Boolean) -> Unit,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    onCreateAt: ((String) -> Unit)? = null,
    todayDate: String = currentCalendarDate(),
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    // The time line follows the clock; a minute is fine-grained enough for a schedule.
    val now by produceState(calendarLocalNow()) {
        while (true) {
            delay(30_000)
            value = calendarLocalNow()
        }
    }
    val nowMinute = now?.takeIf { it.first == todayDate }?.second
    var sourcesVisible by rememberSaveable { mutableStateOf(false) }
    val presentation = remember(events, calendars, hiddenCalendarHrefs, query, selectedDate, month) {
        buildCalendarWorkspacePresentation(events, calendars, hiddenCalendarHrefs, query, selectedDate, month)
    }
    val calendarByHref = remember(calendars) { calendars.associateBy(GroupwareCalendar::href) }
    val period = when (view) {
        CalendarWorkspaceView.Week -> "week"
        CalendarWorkspaceView.Day -> "day"
        CalendarWorkspaceView.Month, CalendarWorkspaceView.Agenda -> "month"
    }
    fun openDay(date: String) {
        onSelectDate(date)
        onViewChanged(CalendarWorkspaceView.Day)
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (view) {
                CalendarWorkspaceView.Week -> calendarWeekTitle(presentation.weekDates)
                CalendarWorkspaceView.Day -> selectedDate.displayCalendarDate()
                CalendarWorkspaceView.Month, CalendarWorkspaceView.Agenda -> month.title
            }, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onToday) { Text("Today") }
            IconButton(onClick = onPrevious) { Icon(NextcloudIcons.Back, "Previous $period") }
            IconButton(onClick = onNext) { Icon(NextcloudIcons.ChevronRight, "Next $period") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CalendarViewSelector(view, onViewChanged)
            }
            IconButton(onClick = {
                if (searchVisible || query.isNotEmpty()) { onQueryChanged(""); searchVisible = false }
                else searchVisible = true
            }) { NextcloudAnimatedIcon(if (searchVisible || query.isNotEmpty()) NextcloudIcons.Close else NextcloudIcons.Search,
                if (searchVisible || query.isNotEmpty()) "Close search" else "Search events") }
            IconButton(onClick = { sourcesVisible = true }) {
                Icon(NextcloudIcons.Filter, "Choose visible calendars")
            }
        }
        if (searchVisible || query.isNotEmpty()) {
            OutlinedTextField(value = query, onValueChange = onQueryChanged, singleLine = true,
                label = { Text("Search events") },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChanged("") }) {
                    Icon(NextcloudIcons.Close, "Clear search")
                } },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
        if (hiddenCalendarHrefs.isNotEmpty()) TextButton(
            onClick = { sourcesVisible = true }, modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("${calendars.count { it.href !in hiddenCalendarHrefs }} of ${calendars.size} calendars shown") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (query.isNotBlank()) {
            val matches = if (view == CalendarWorkspaceView.Week || view == CalendarWorkspaceView.Day) presentation.visibleEvents.filter {
                it.overlapsCalendarDateRange(presentation.weekDates.firstOrNull(), presentation.weekDates.lastOrNull())
            } else presentation.visibleEvents
            Text("Search results", Modifier.padding(start = 16.dp, top = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall)
            CalendarAgenda(matches, onSelectEvent, Modifier.weight(1f), calendarByHref = calendarByHref,
                earliestDisplayDate = if (view == CalendarWorkspaceView.Week || view == CalendarWorkspaceView.Day) presentation.weekDates.firstOrNull() else "${month.isoPrefix}01",
                searching = true)
        } else NextcloudContentEntrance(view, NextcloudEntranceAxis.Horizontal,
            Modifier.weight(1f).calendarPeriodSwipe(onPrevious, onNext), order = view.ordinal) { when (view) {
            CalendarWorkspaceView.Month -> CalendarPhoneMonthGrid(month, selectedDate, todayDate, presentation.visibleEvents,
                calendarByHref, ::openDay, onSelectEvent, Modifier.fillMaxSize())
            CalendarWorkspaceView.Week -> CalendarTimeGrid(
                presentation.weekDates, selectedDate, todayDate, presentation.eventsByDate, calendarByHref,
                onSelectDate = ::openDay, onSelectEvent = { _, event -> onSelectEvent(event) },
                onCreateAt = onCreateAt, modifier = Modifier.fillMaxSize(), nowMinute = nowMinute,
            )
            CalendarWorkspaceView.Day -> CalendarTimeGrid(
                listOf(selectedDate), selectedDate, todayDate, presentation.eventsByDate, calendarByHref,
                onSelectDate = onSelectDate, onSelectEvent = { _, event -> onSelectEvent(event) },
                onCreateAt = onCreateAt, modifier = Modifier.fillMaxSize(), nowMinute = nowMinute,
            )
            CalendarWorkspaceView.Agenda -> CalendarAgenda(presentation.visibleEvents, onSelectEvent,
                Modifier.fillMaxSize(), calendarByHref = calendarByHref,
                earliestDisplayDate = "${month.isoPrefix}01", searching = query.isNotBlank())
        } }
    }
    if (sourcesVisible) ModalBottomSheet(
        onDismissRequest = { sourcesVisible = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 560.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("My calendars", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { sourcesVisible = false }) { Text("Done") }
                }
                Text("Choose which events to show", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(calendars, key = GroupwareCalendar::href) { calendar ->
                CalendarVisibilityRow(calendar, calendar.href !in hiddenCalendarHrefs,
                    presentation.eventCountByCalendar[calendar.href] ?: 0,
                    { visible -> onCalendarVisibilityChanged(calendar.href, visible) },
                    Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}
