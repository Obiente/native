package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch
import kotlin.time.Clock

private enum class CalendarDisplayMode { Month, Agenda }

private data class CalendarMonth(val year: Int, val month: Int) {
    init {
        require(month in 1..12)
    }

    val title: String get() = "${MONTH_NAMES[month - 1]} $year"
    val compactStart: String get() = "%04d%02d01T000000Z".format(year, month)
    val isoPrefix: String get() = "%04d%02d".format(year, month)

    fun next(): CalendarMonth = if (month == 12) CalendarMonth(year + 1, 1) else CalendarMonth(year, month + 1)
    fun previous(): CalendarMonth = if (month == 1) CalendarMonth(year - 1, 12) else CalendarMonth(year, month - 1)
    fun days(): Int = groupwareCalendarDaysInMonth(year, month)
}

private sealed interface CalendarLoadState {
    data object Loading : CalendarLoadState
    data class Ready(
        val calendars: List<GroupwareCalendar>,
        val events: List<GroupwareCalendarEvent>,
    ) : CalendarLoadState
    data class Error(val message: String) : CalendarLoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeGroupwareCalendarScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onBack: () -> Unit,
) {
    var month by remember { mutableStateOf(currentCalendarMonth()) }
    var selectedDate by remember { mutableStateOf(currentCalendarDate()) }
    var mode by remember { mutableStateOf(CalendarDisplayMode.Month) }
    var state by remember { mutableStateOf<CalendarLoadState>(CalendarLoadState.Loading) }
    var loadAttempt by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<GroupwareCalendarEvent?>(null) }
    var creating by remember { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        state = CalendarLoadState.Loading
        state = runCatching {
            val home = groupwareCalendarHomeHref(userId)
            val calendarResponse = services.executeGroupwareDav(
                session,
                groupwareDavCollectionDiscoveryRequest(home),
            )
            val calendars = parseGroupwareCalendars(calendarResponse)
            val window = GroupwareDavTimeWindow(month.compactStart, month.next().compactStart)
            val events = calendars.flatMap { calendar ->
                val response = services.executeGroupwareDav(
                    session,
                    groupwareDavCollectionQueryRequest(
                        collectionHref = calendar.href,
                        kind = GroupwareDavKind.Event,
                        timeWindow = window,
                    ),
                )
                expandGroupwareCalendarEvents(
                    parseGroupwareCalendarEvents(calendar.href, response),
                    window,
                )
            }.sortedWith(compareBy(GroupwareCalendarEvent::start, GroupwareCalendarEvent::title))
            CalendarLoadState.Ready(calendars, events)
        }.getOrElse { CalendarLoadState.Error(it.message ?: "Could not load calendars.") }
    }

    LaunchedEffect(session, userId, month, loadAttempt) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Calendar", fontWeight = FontWeight.SemiBold)
                        Text(
                            month.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NextcloudIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadAttempt += 1 }) {
                        Icon(NextcloudIcons.Refresh, contentDescription = "Refresh calendars")
                    }
                    IconButton(
                        onClick = { creating = true },
                        enabled = (state as? CalendarLoadState.Ready)?.calendars?.any { it.writable } == true,
                    ) {
                        Icon(NextcloudIcons.Add, contentDescription = "Create event")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    month = month.previous()
                    selectedDate = "${month.isoPrefix}01"
                }) {
                    Icon(NextcloudIcons.Back, contentDescription = "Previous month")
                }
                FilterChip(
                    selected = mode == CalendarDisplayMode.Month,
                    onClick = { mode = CalendarDisplayMode.Month },
                    label = { Text("Month") },
                )
                FilterChip(
                    selected = mode == CalendarDisplayMode.Agenda,
                    onClick = { mode = CalendarDisplayMode.Agenda },
                    label = { Text("Agenda") },
                )
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    month = currentCalendarMonth()
                    selectedDate = currentCalendarDate()
                }) { Text("Today") }
                IconButton(onClick = {
                    month = month.next()
                    selectedDate = "${month.isoPrefix}01"
                }) {
                    Icon(NextcloudIcons.ChevronRight, contentDescription = "Next month")
                }
            }

            when (val value = state) {
                CalendarLoadState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is CalendarLoadState.Error -> CalendarError(value.message) { loadAttempt += 1 }
                is CalendarLoadState.Ready -> {
                    if (value.calendars.isEmpty()) {
                        CalendarError("No event calendars were found.") { loadAttempt += 1 }
                    } else if (mode == CalendarDisplayMode.Month) {
                        MonthCalendar(
                            month = month,
                            selectedDate = selectedDate,
                            events = value.events,
                            onSelectDate = { selectedDate = it },
                            onSelectEvent = { editing = it },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        CalendarAgenda(value.events, onSelectEvent = { editing = it }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    val ready = state as? CalendarLoadState.Ready
    if (creating && ready != null) {
        EventEditorDialog(
            event = null,
            initialDate = selectedDate,
            calendars = ready.calendars.filter(GroupwareCalendar::writable),
            onDismiss = { creating = false },
            error = mutationError,
            onSave = { draft, calendar ->
                mutationError = null
                scope.launch {
                    runCatching {
                        val uid = "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
                        val request = GroupwareDavMutationSpec(
                            kind = GroupwareDavKind.Event,
                            mutation = GroupwareDavMutation.Create,
                            objectHref = "${calendar.href}$uid.ics",
                            content = createGroupwareCalendarEventContent(
                                uid = uid,
                                title = draft.title,
                                start = draft.startValue(),
                                end = draft.endValue(),
                                allDay = draft.allDay,
                                location = draft.location,
                                description = draft.description,
                            ),
                        ).toGroupwareDavRequest()
                        val response = services.executeGroupwareDav(session, request)
                        check(response.status in 200..299) { "Creating the event failed (HTTP ${response.status})." }
                    }.onSuccess {
                        creating = false
                        loadAttempt += 1
                    }.onFailure { mutationError = it.message ?: "Could not create the event." }
                }
            },
        )
    }

    editing?.let { event ->
        val calendar = ready?.calendars?.firstOrNull { it.href == event.calendarHref }
        if (event.status == EDITING_MARKER && calendar != null) {
            EventEditorDialog(
                event = event.copy(status = null),
                initialDate = event.start.take(8),
                calendars = listOf(calendar),
                onDismiss = { editing = null },
                error = mutationError,
                onSave = { draft, _ ->
                    mutationError = null
                    scope.launch {
                        runCatching {
                            val updated = updateGroupwareCalendarEventContent(
                                event = event,
                                title = draft.title,
                                start = draft.startValue(),
                                end = draft.endValue(),
                                allDay = draft.allDay,
                                location = draft.location,
                                description = draft.description,
                            )
                            val request = GroupwareDavMutationSpec(
                                kind = GroupwareDavKind.Event,
                                mutation = GroupwareDavMutation.Update,
                                objectHref = event.href,
                                etag = event.etag,
                                content = updated,
                            ).toGroupwareDavRequest()
                            val response = services.executeGroupwareDav(session, request)
                            check(response.status in 200..299) {
                                "Saving the event failed (HTTP ${response.status})."
                            }
                        }.onSuccess {
                            editing = null
                            loadAttempt += 1
                        }.onFailure { mutationError = it.message ?: "Could not save the event." }
                    }
                },
            )
        } else {
            EventDetailDialog(
                event = event,
                canEdit = calendar?.writable == true && event.etag != null && !event.isGeneratedOccurrence,
                onDismiss = { editing = null },
                onEdit = { creating = false; editing = event.copy(status = EDITING_MARKER) },
                onDelete = {
                    mutationError = null
                    scope.launch {
                        runCatching {
                            val request = GroupwareDavMutationSpec(
                                kind = GroupwareDavKind.Event,
                                mutation = GroupwareDavMutation.Delete,
                                objectHref = event.href,
                                etag = event.etag,
                            ).toGroupwareDavRequest()
                            val response = services.executeGroupwareDav(session, request)
                            check(response.status in 200..299) {
                                "Deleting the event failed (HTTP ${response.status})."
                            }
                        }.onSuccess {
                            editing = null
                            loadAttempt += 1
                        }.onFailure { mutationError = it.message ?: "Could not delete the event." }
                    }
                },
                error = mutationError,
            )
        }
    }
}

@Composable
private fun CalendarError(message: String, retry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(38.dp))
        Text(message, modifier = Modifier.padding(NextcloudSpacing.Medium))
        Button(onClick = retry) { Text("Try again") }
    }
}

@Composable
private fun MonthCalendar(
    month: CalendarMonth,
    selectedDate: String,
    events: List<GroupwareCalendarEvent>,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byDay = remember(events) { events.groupBy { it.start.take(8) } }
    val leading = dayOfWeekMondayFirst(month.year, month.month, 1)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                WEEK_DAYS.forEach { day ->
                    Text(
                        day,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val cells = List(leading) { null } + (1..month.days()).map { it }
        items(cells.chunked(7)) { week ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                (week + List(7 - week.size) { null }).forEach { day ->
                    if (day == null) {
                        Box(modifier = Modifier.weight(1f))
                    } else {
                        val date = "${month.isoPrefix}${day.toString().padStart(2, '0')}"
                        val dayEvents = byDay[date].orEmpty()
                        val selected = date == selectedDate
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                    RoundedCornerShape(NextcloudRadii.Small),
                                )
                                .clickable { onSelectDate(date) }
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                day.toString(),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            dayEvents.take(2).forEach { event ->
                                Text(
                                    event.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                        .clickable { onSelectEvent(event) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (dayEvents.size > 2) {
                                Text("+${dayEvents.size - 2}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            val selectedEvents = byDay[selectedDate].orEmpty()
            HorizontalDivider(modifier = Modifier.padding(vertical = NextcloudSpacing.Small))
            Text(
                selectedDate.displayCalendarDate(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (selectedEvents.isEmpty()) {
                Text(
                    "No events",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = NextcloudSpacing.Medium),
                )
            } else {
                selectedEvents.forEach { event -> CalendarEventCard(event) { onSelectEvent(event) } }
            }
        }
    }
}

@Composable
private fun CalendarAgenda(
    events: List<GroupwareCalendarEvent>,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = events.groupBy { it.start.take(8) }.toSortedMap()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        groups.forEach { (date, dateEvents) ->
            item(key = "heading-$date") {
                Text(
                    date.displayCalendarDate(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                )
            }
            items(dateEvents, key = GroupwareCalendarEvent::instanceId) { event ->
                CalendarEventCard(event) { onSelectEvent(event) }
            }
        }
        if (events.isEmpty()) {
            item {
                Text("No events this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CalendarEventCard(event: GroupwareCalendarEvent, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Box(
                modifier = Modifier.size(5.dp, 48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, fontWeight = FontWeight.SemiBold)
                Text(
                    event.displayTimeRange(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.location?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EventDetailDialog(
    event: GroupwareCalendarEvent,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    error: String?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NextcloudIcons.Calendar, contentDescription = null) },
        title = { Text(event.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(event.start.take(8).displayCalendarDate())
                Text(event.displayTimeRange())
                event.location?.let { Text("Location: $it") }
                event.recurrenceRule?.let { Text("Repeats: $it") }
                if (event.isGeneratedOccurrence) {
                    Text(
                        "This occurrence is read-only to protect the complete recurring series.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                event.description?.let { Text(it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (canEdit) TextButton(onClick = onEdit) {
                Text(if (event.recurrenceRule != null) "Edit series" else "Edit")
            }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (canEdit) TextButton(onClick = onDelete) {
                Text(
                    if (event.recurrenceRule != null) "Delete series" else "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

private data class EventDraft(
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val allDay: Boolean,
    val location: String,
    val description: String,
) {
    fun startValue(): String = date.isoDateToCompact() + if (allDay) "" else "T${startTime.timeToCompact()}00Z"
    fun endValue(): String? = if (allDay) nextIsoDate(date)?.isoDateToCompact()
    else date.isoDateToCompact() + "T${endTime.timeToCompact()}00Z"
}

@Composable
private fun EventEditorDialog(
    event: GroupwareCalendarEvent?,
    initialDate: String,
    calendars: List<GroupwareCalendar>,
    onDismiss: () -> Unit,
    error: String?,
    onSave: (EventDraft, GroupwareCalendar) -> Unit,
) {
    val initialIsoDate = (event?.start?.take(8) ?: initialDate).compactDateToIso()
    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var date by remember(event, initialDate) { mutableStateOf(initialIsoDate) }
    var startTime by remember(event) { mutableStateOf(event?.start?.compactTime() ?: "09:00") }
    var endTime by remember(event) { mutableStateOf(event?.end?.compactTime() ?: "10:00") }
    var allDay by remember(event) { mutableStateOf(event?.allDay ?: false) }
    var location by remember(event) { mutableStateOf(event?.location.orEmpty()) }
    var description by remember(event) { mutableStateOf(event?.description.orEmpty()) }
    var calendar by remember(calendars) { mutableStateOf(calendars.firstOrNull()) }
    val valid = title.isNotBlank() && date.isIsoCalendarDate() &&
        (allDay || startTime.isCalendarTime() && endTime.isCalendarTime())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "New event" else "Edit event") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Date") },
                        supportingText = { Text("YYYY-MM-DD") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("All day", modifier = Modifier.weight(1f))
                        Switch(checked = allDay, onCheckedChange = { allDay = it })
                    }
                }
                if (!allDay) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            OutlinedTextField(
                                value = startTime,
                                onValueChange = { startTime = it },
                                label = { Text("Starts") },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = endTime,
                                onValueChange = { endTime = it },
                                label = { Text("Ends") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Location") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
                if (calendars.size > 1) {
                    item {
                        Text("Calendar", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            calendars.forEach { candidate ->
                                FilterChip(
                                    selected = calendar == candidate,
                                    onClick = { calendar = candidate },
                                    label = { Text(candidate.displayName) },
                                )
                            }
                        }
                    }
                }
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid && calendar != null,
                onClick = {
                    onSave(
                        EventDraft(title, date, startTime, endTime, allDay, location, description),
                        requireNotNull(calendar),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun GroupwareCalendarEvent.displayTimeRange(): String {
    if (allDay) return "All day"
    val startTime = start.compactTime()
    val endTime = end?.compactTime()
    return if (endTime == null) startTime else "$startTime - $endTime"
}

private fun String.compactTime(): String =
    if (length >= 13 && getOrNull(8) == 'T') "${substring(9, 11)}:${substring(11, 13)}" else "09:00"

private fun String.displayCalendarDate(): String {
    if (length != 8) return this
    val year = take(4).toIntOrNull() ?: return this
    val month = substring(4, 6).toIntOrNull()?.takeIf { it in 1..12 } ?: return this
    val day = takeLast(2).toIntOrNull() ?: return this
    return "$day ${MONTH_NAMES[month - 1]} $year"
}

private fun String.compactDateToIso(): String =
    if (length == 8) "${take(4)}-${substring(4, 6)}-${takeLast(2)}" else this

private fun String.isoDateToCompact(): String = replace("-", "")
private fun String.timeToCompact(): String = replace(":", "")

private fun String.isIsoCalendarDate(): Boolean {
    if (length != 10 || getOrNull(4) != '-' || getOrNull(7) != '-') return false
    val year = take(4).toIntOrNull() ?: return false
    val month = substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return false
    val day = takeLast(2).toIntOrNull() ?: return false
    return year in 1..9999 && day in 1..groupwareCalendarDaysInMonth(year, month)
}

private fun String.isCalendarTime(): Boolean {
    if (length != 5 || getOrNull(2) != ':') return false
    val hour = take(2).toIntOrNull() ?: return false
    val minute = takeLast(2).toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

private fun nextIsoDate(date: String): String? {
    if (!date.isIsoCalendarDate()) return null
    var year = date.take(4).toInt()
    var month = date.substring(5, 7).toInt()
    var day = date.takeLast(2).toInt() + 1
    if (day > groupwareCalendarDaysInMonth(year, month)) {
        day = 1
        month += 1
        if (month > 12) {
            month = 1
            year += 1
        }
    }
    return "%04d-%02d-%02d".format(year, month, day)
}

private fun currentCalendarMonth(): CalendarMonth {
    val date = epochDayToCivil(Clock.System.now().epochSeconds.floorDiv(86_400))
    return CalendarMonth(date.first, date.second)
}

private fun currentCalendarDate(): String {
    val date = epochDayToCivil(Clock.System.now().epochSeconds.floorDiv(86_400))
    return "%04d%02d%02d".format(date.first, date.second, date.third)
}

// Gregorian civil date conversion adapted from the public-domain algorithm by Howard Hinnant.
private fun epochDayToCivil(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    var year = yearOfEra.toInt() + era.toInt() * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * monthPrime + 2) / 5 + 1).toInt()
    val month = (monthPrime + if (monthPrime < 10) 3 else -9).toInt()
    year += if (month <= 2) 1 else 0
    return Triple(year, month, day)
}

private fun dayOfWeekMondayFirst(year: Int, month: Int, day: Int): Int {
    var adjustedYear = year
    var adjustedMonth = month
    if (adjustedMonth < 3) {
        adjustedMonth += 12
        adjustedYear -= 1
    }
    val k = adjustedYear % 100
    val j = adjustedYear / 100
    val h = (day + (13 * (adjustedMonth + 1)) / 5 + k + k / 4 + j / 4 + 5 * j) % 7
    val sundayFirst = (h + 6) % 7
    return (sundayFirst + 6) % 7
}

internal fun groupwareCalendarDaysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
private val WEEK_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private const val EDITING_MARKER = "__editing__"
