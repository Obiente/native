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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

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
    todayDate: String = currentCalendarDate(),
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var sourcesVisible by rememberSaveable { mutableStateOf(false) }
    val presentation = remember(events, calendars, hiddenCalendarHrefs, query, selectedDate, month) {
        buildCalendarWorkspacePresentation(events, calendars, hiddenCalendarHrefs, query, selectedDate, month)
    }
    val calendarByHref = remember(calendars) { calendars.associateBy(GroupwareCalendar::href) }
    val period = if (view == CalendarWorkspaceView.Week) "week" else "month"
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (view == CalendarWorkspaceView.Week) calendarWeekTitle(presentation.weekDates) else month.title,
                Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
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
            }) { Icon(if (searchVisible || query.isNotEmpty()) NextcloudIcons.Close else NextcloudIcons.Search,
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
            val matches = if (view == CalendarWorkspaceView.Week) presentation.visibleEvents.filter {
                it.overlapsCalendarDateRange(presentation.weekDates.firstOrNull(), presentation.weekDates.lastOrNull())
            } else presentation.visibleEvents
            Text("Search results", Modifier.padding(start = 16.dp, top = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall)
            CalendarAgenda(matches, onSelectEvent, Modifier.weight(1f), calendarByHref = calendarByHref,
                earliestDisplayDate = if (view == CalendarWorkspaceView.Week) presentation.weekDates.firstOrNull() else "${month.isoPrefix}01",
                searching = true)
        } else when (view) {
            CalendarWorkspaceView.Month -> MonthCalendar(month, selectedDate, presentation.visibleEvents,
                onSelectDate, onSelectEvent, Modifier.weight(1f), calendarByHref, todayDate)
            CalendarWorkspaceView.Week -> CalendarWeekSchedule(
                presentation.weekDates, selectedDate, presentation.eventsByDate, calendarByHref,
                todayDate, onSelectDate, onSelectEvent, Modifier.weight(1f),
            )
            CalendarWorkspaceView.Agenda -> CalendarAgenda(presentation.visibleEvents, onSelectEvent,
                Modifier.weight(1f), calendarByHref = calendarByHref,
                earliestDisplayDate = "${month.isoPrefix}01", searching = query.isNotBlank())
        }
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
