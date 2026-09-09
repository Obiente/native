package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii

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
    todayDate: String = currentCalendarDate(),
) {
    val presentation = remember(events, calendars, hiddenCalendarHrefs, query, selectedDate, month) {
        buildCalendarWorkspacePresentation(events, calendars, hiddenCalendarHrefs, query, selectedDate, month)
    }
    val calendarByHref = remember(calendars) { calendars.associateBy(GroupwareCalendar::href) }
    val createEnabled = mutationsEnabled && calendars.any(GroupwareCalendar::writable)
    var sourcesRequested by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var detailsRequested by rememberSaveable { mutableStateOf<Boolean?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val workspaceWidth = maxWidth
        val panes = calendarPaneLayout(workspaceWidth.value.toInt(), sourcesRequested, detailsRequested, selectedEvent != null)
        val selectDay: (String) -> Unit = { date -> onSelectDate(date); onSelectEvent(null) }
        val showDay: (String) -> Unit = { date -> selectDay(date); detailsRequested = true }
        val selectEvent: (GroupwareCalendarEvent) -> Unit = { event ->
            detailsRequested = true
            onSelectEvent(event)
        }
        Column(Modifier.fillMaxSize()) {
            DesktopCalendarToolbar(
                title = if (view == CalendarWorkspaceView.Week) calendarWeekTitle(presentation.weekDates) else month.title,
                view = view,
                query = query,
                loading = loading,
                createEnabled = createEnabled,
                sourcesVisible = panes.sourcesVisible,
                detailsVisible = panes.detailsVisible,
                onPrevious = onPrevious,
                onNext = onNext,
                onToday = onToday,
                onViewChanged = onViewChanged,
                onQueryChanged = onQueryChanged,
                onRefresh = onRefresh,
                onCreateEvent = onCreateEvent,
                onToggleSources = {
                    sourcesRequested = !panes.sourcesVisible
                    if (!panes.sourcesVisible && workspaceWidth < 1050.dp) detailsRequested = false
                },
                onToggleDetails = { detailsRequested = !panes.detailsVisible },
            )
            Row(
                Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (panes.sourcesInline) DesktopCalendarSourcesPane(
                    calendars = calendars,
                    hiddenCalendarHrefs = hiddenCalendarHrefs,
                    eventCountByCalendar = presentation.eventCountByCalendar,
                    loading = loading,
                    onCalendarVisibilityChanged = onCalendarVisibilityChanged,
                    onClose = { sourcesRequested = false },
                    modifier = Modifier.width(216.dp).fillMaxHeight(),
                )
                Surface(
                    Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    when (view) {
                        CalendarWorkspaceView.Month -> DesktopCalendarMonthGrid(
                            month, selectedDate, todayDate, presentation.eventsByDate, calendarByHref,
                            selectDay, showDay,
                            { date, event -> onSelectDate(date); selectEvent(event) },
                        )
                        CalendarWorkspaceView.Week -> DesktopCalendarWeek(
                            presentation.weekDates, selectedDate, todayDate, presentation.eventsByDate,
                            calendarByHref, selectDay,
                            { date, event -> onSelectDate(date); selectEvent(event) },
                        )
                        CalendarWorkspaceView.Agenda -> DesktopCalendarAgenda(
                            presentation.visibleEvents, calendarByHref, selectedEvent,
                            { event -> onSelectDate(event.start.take(8)); selectEvent(event) },
                        )
                    }
                }
                if (panes.detailsInline) DesktopCalendarInspectorPane(
                    selectedDate, selectedEvent, calendarByHref, presentation.selectedDateEvents,
                    createEnabled, mutationsEnabled, selectEvent, onCreateEvent, onEditEvent, onDeleteEvent,
                    onClose = { detailsRequested = false },
                    onShowDay = { onSelectEvent(null) },
                    modifier = Modifier.width(292.dp).fillMaxHeight(),
                )
            }
        }
        if (panes.sourcesVisible && !panes.sourcesInline) {
            CalendarDialogSurface(
                onDismissRequest = { sourcesRequested = false },
                title = { Text("Calendars") },
                text = {
                    DesktopCalendarSourcesPane(
                        calendars, hiddenCalendarHrefs, presentation.eventCountByCalendar, loading,
                        onCalendarVisibilityChanged, onClose = null,
                        modifier = Modifier.fillMaxWidth().height(480.dp),
                    )
                },
                confirmButton = { TextButton(onClick = { sourcesRequested = false }) { Text("Done") } },
                dismissButton = {},
            )
        }
        if (panes.detailsVisible && !panes.detailsInline) {
            CalendarDialogSurface(
                onDismissRequest = { detailsRequested = false },
                title = { Text(if (selectedEvent == null) "Day schedule" else "Event details") },
                text = {
                    DesktopCalendarInspectorPane(
                        selectedDate, selectedEvent, calendarByHref, presentation.selectedDateEvents,
                        createEnabled, mutationsEnabled, selectEvent,
                        { detailsRequested = false; onCreateEvent() },
                        { event -> detailsRequested = false; onEditEvent(event) },
                        { event -> detailsRequested = false; onDeleteEvent(event) },
                        onClose = null, onShowDay = { onSelectEvent(null) },
                        modifier = Modifier.fillMaxWidth().height(480.dp),
                    )
                },
                confirmButton = { TextButton(onClick = { detailsRequested = false }) { Text("Close") } },
                dismissButton = {},
            )
        }
    }
}

@Composable
private fun DesktopCalendarToolbar(
    title: String,
    view: CalendarWorkspaceView,
    query: String,
    loading: Boolean,
    createEnabled: Boolean,
    sourcesVisible: Boolean,
    detailsVisible: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onViewChanged: (CalendarWorkspaceView) -> Unit,
    onQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateEvent: () -> Unit,
    onToggleSources: () -> Unit,
    onToggleDetails: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compactToolbar = maxWidth < (700 * fontScale).dp
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (loading) "Calendar - refreshing..." else "Calendar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compactToolbar) DesktopCalendarPeriodNavigation(view, loading, onPrevious, onNext, onToday, onRefresh)
                Button(onClick = onCreateEvent, enabled = createEnabled) {
                    Icon(NextcloudIcons.Add, null, Modifier.size(18.dp))
                    Text("New event", Modifier.padding(start = 7.dp))
                }
            }
            if (compactToolbar) {
                DesktopCalendarPeriodNavigation(
                    view, loading, onPrevious, onNext, onToday, onRefresh, Modifier.padding(top = 8.dp),
                )
            }
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CalendarViewSelector(view, onViewChanged)
                FilterChip(sourcesVisible, onToggleSources, label = { Text("Calendars") })
                FilterChip(detailsVisible, onToggleDetails, label = { Text("Details") })
                OutlinedTextField(
                    value = query, onValueChange = onQueryChanged,
                    modifier = Modifier.width(248.dp), singleLine = true,
                    leadingIcon = { Icon(NextcloudIcons.Search, null) },
                    placeholder = { Text("Search events") },
                )
            }
        }
    }
}

@Composable
private fun DesktopCalendarPeriodNavigation(
    view: CalendarWorkspaceView,
    loading: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onPrevious) {
            Icon(NextcloudIcons.Back, "Previous ${view.navigationPeriod()}")
        }
        IconButton(onClick = onNext) {
            Icon(NextcloudIcons.ChevronRight, "Next ${view.navigationPeriod()}")
        }
        OutlinedButton(onClick = onToday) { Text("Today") }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(NextcloudIcons.Refresh, "Refresh calendars")
        }
    }
}

private fun CalendarWorkspaceView.navigationPeriod(): String = when (this) {
    CalendarWorkspaceView.Week -> "week"
    CalendarWorkspaceView.Month, CalendarWorkspaceView.Agenda -> "month"
}
