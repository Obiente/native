package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal data class CalendarMonth(val year: Int, val month: Int) {
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
        val month: CalendarMonth,
        val timeWindow: GroupwareDavTimeWindow,
        val calendars: List<GroupwareCalendar>,
        val events: List<GroupwareCalendarEvent>,
    ) : CalendarLoadState
    data class Error(val message: String) : CalendarLoadState
}

private object CalendarWorkspaceMemoryCache {
    private val entries = linkedMapOf<String, CalendarLoadState.Ready>()

    fun get(
        session: NextcloudSession,
        userId: String,
        month: CalendarMonth,
        timeWindow: GroupwareDavTimeWindow,
    ): CalendarLoadState.Ready? {
        val key = key(session, userId, month, timeWindow)
        return entries.remove(key)?.also { entries[key] = it }
    }

    fun store(session: NextcloudSession, userId: String, value: CalendarLoadState.Ready) {
        val key = key(session, userId, value.month, value.timeWindow)
        entries.remove(key)
        entries[key] = value
        while (entries.size > MAXIMUM_RETAINED_CALENDAR_MONTHS) entries.remove(entries.keys.first())
    }

    private fun key(
        session: NextcloudSession,
        userId: String,
        month: CalendarMonth,
        timeWindow: GroupwareDavTimeWindow,
    ): String = "${session.serverUrl.trimEnd('/')}\n${session.loginName}\n$userId\n" +
        "${month.year}-${month.month}\n${timeWindow.startUtc}-${timeWindow.endUtc}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeGroupwareCalendarScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onBack: () -> Unit,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    onMutationInProgressChanged: (Boolean) -> Unit = {},
) {
    val accountScope = remember(session.serverUrl, session.loginName) {
        durableMutationAccountScope(session)
    }
    val initialMonth = remember { currentCalendarMonth() }
    var monthYear by rememberSaveable { mutableStateOf(initialMonth.year) }
    var monthNumber by rememberSaveable { mutableStateOf(initialMonth.month) }
    val month = CalendarMonth(monthYear, monthNumber)
    var selectedDate by rememberSaveable { mutableStateOf(currentCalendarDate()) }
    var viewName by rememberSaveable { mutableStateOf(CalendarWorkspaceView.Month.name) }
    val view = CalendarWorkspaceView.entries.firstOrNull { candidate -> candidate.name == viewName }
        ?: CalendarWorkspaceView.Month
    val queryWindow = calendarWorkspaceQueryWindow(view, month, selectedDate)
    var query by rememberSaveable { mutableStateOf("") }
    var hiddenCalendarHrefs by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var state by remember(session, userId) {
        mutableStateOf<CalendarLoadState>(
            CalendarWorkspaceMemoryCache.get(session, userId, month, queryWindow) ?: CalendarLoadState.Loading,
        )
    }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }
    var activeEventInstanceId by rememberSaveable(accountScope) { mutableStateOf<String?>(null) }
    var eventEditorActive by rememberSaveable(accountScope) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GroupwareCalendarEvent?>(null) }
    var deletingInProgress by remember { mutableStateOf(false) }
    var mutationOperationInProgress by remember(accountScope) { mutableStateOf(false) }
    var mutationRecoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var mutationRecoveryState by remember(accountScope, services) { mutableStateOf<String?>(null) }
    val mutationPostcondition = remember(accountScope, mutationRecoveryState) {
        mutationRecoveryState?.let { decodeCalendarMutationRecoveryState(it, accountScope) }
    }
    val mutationInProgress = !mutationRecoveryLoaded || mutationOperationInProgress || mutationRecoveryState != null
    var creating by rememberSaveable(accountScope) { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop

    suspend fun retainMutationRecovery(postcondition: CalendarMutationPostcondition): Boolean {
        if (!mutationRecoveryLoaded || mutationRecoveryState != null || mutationOperationInProgress) {
            mutationError = "Another calendar change is still awaiting server verification."
            return false
        }
        val encoded = CalendarMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
        mutationOperationInProgress = true
        onMutationInProgressChanged(true)
        val saved = try {
            services.saveDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Calendar, encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            mutationError = "The calendar change could not be safely recorded. Check local storage and try again."
            mutationOperationInProgress = false
            onMutationInProgressChanged(mutationRecoveryState != null || !mutationRecoveryLoaded)
            return false
        }
        mutationRecoveryState = encoded
        return true
    }

    suspend fun clearMutationRecovery(): Boolean {
        val cleared = try {
            services.clearDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Calendar)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!cleared) {
            mutationError = "The verified calendar recovery record could not be cleared. Free local storage and refresh."
            return false
        }
        mutationRecoveryState = null
        mutationOperationInProgress = false
        onMutationInProgressChanged(false)
        return true
    }

    LaunchedEffect(accountScope, services, loadAttempt) {
        mutationRecoveryLoaded = false
        mutationRecoveryState = null
        try {
            mutationRecoveryState = services.loadDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.Calendar,
            )
            mutationRecoveryLoaded = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            mutationError = "Calendar recovery storage could not be read securely. Check local storage and retry."
        }
    }

    LaunchedEffect(accountScope, mutationRecoveryLoaded, mutationRecoveryState, mutationPostcondition) {
        if (mutationRecoveryLoaded && mutationRecoveryState != null && mutationPostcondition == null) {
            val cleared = try {
                services.clearDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Calendar)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                false
            }
            if (cleared) {
                mutationRecoveryState = null
            } else {
                mutationError = "A damaged calendar recovery record could not be cleared. Free local storage and retry."
            }
        }
    }

    LaunchedEffect(mutationInProgress) {
        onMutationInProgressChanged(mutationInProgress)
    }
    DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }

    fun selectMonth(value: CalendarMonth) {
        monthYear = value.year
        monthNumber = value.month
    }

    fun navigateCalendar(direction: Int) {
        if (view == CalendarWorkspaceView.Week) {
            val date = selectedDate.parseCompactCalendarDate()?.plusDays(direction * 7) ?: return
            selectedDate = date.compactValue
            selectMonth(CalendarMonth(date.year, date.month))
        } else {
            val next = if (direction < 0) month.previous() else month.next()
            selectMonth(next)
            selectedDate = "${next.isoPrefix}01"
        }
        selectedEventId = null
    }

    fun selectToday() {
        val today = currentCalendarDate()
        val date = requireNotNull(today.parseCompactCalendarDate())
        selectMonth(CalendarMonth(date.year, date.month))
        selectedDate = today
        selectedEventId = null
    }

    suspend fun reload() {
        val reconciliationConfirmed = mutationPostcondition?.let { postcondition ->
            runCatching {
                val response = services.executeGroupwareDav(
                    session,
                    groupwareDavDetailRequest(postcondition.href),
                )
                postcondition.isSatisfiedBy(response)
            }.getOrDefault(false)
        } == true
        val cached = CalendarWorkspaceMemoryCache.get(session, userId, month, queryWindow)
        if (cached != null) state = cached
        val retained = cached ?: (state as? CalendarLoadState.Ready)?.takeIf { ready ->
            calendarReadyMatchesRequest(ready.month, ready.timeWindow, month, queryWindow)
        }
        refreshError = null
        if (retained == null) {
            state = CalendarLoadState.Loading
        } else {
            refreshing = true
        }
        runCatching {
            val home = groupwareCalendarHomeHref(userId)
            val calendarResponse = services.executeGroupwareDav(
                session,
                groupwareDavCollectionDiscoveryRequest(home),
            )
            val calendars = parseGroupwareCalendars(calendarResponse)
            val events = calendars.flatMap { calendar ->
                val response = services.executeGroupwareDav(
                    session,
                    groupwareDavCollectionQueryRequest(
                        collectionHref = calendar.href,
                        kind = GroupwareDavKind.Event,
                        timeWindow = queryWindow,
                    ),
                )
                expandGroupwareCalendarEvents(
                    parseGroupwareCalendarEvents(calendar.href, response),
                    queryWindow,
                )
            }.sortedWith(compareBy(GroupwareCalendarEvent::start, GroupwareCalendarEvent::title))
            CalendarLoadState.Ready(month, queryWindow, calendars, events)
        }.onSuccess { loaded ->
            state = loaded
            CalendarWorkspaceMemoryCache.store(session, userId, loaded)
            if (mutationPostcondition != null) {
                if (reconciliationConfirmed) {
                    if (!clearMutationRecovery()) return@onSuccess
                    when (mutationPostcondition) {
                        is CalendarMutationPostcondition.Upsert -> {
                            if (mutationPostcondition.previousEtag == null) creating = false
                            activeEventInstanceId = null
                            eventEditorActive = false
                        }
                        is CalendarMutationPostcondition.Delete -> {
                            deleting = null
                            deletingInProgress = false
                            selectedEventId = null
                        }
                    }
                } else {
                    refreshError = "The calendar change has not appeared on the server yet. Refresh to verify it before leaving."
                }
            }
        }.onFailure { failure ->
            val message = failure.message ?: "Could not load calendars."
            if (retained == null) {
                state = CalendarLoadState.Error(message)
            } else {
                refreshError = message
            }
        }
        refreshing = false
    }

    LaunchedEffect(session, userId, month, queryWindow, loadAttempt, mutationRecoveryLoaded) {
        if (mutationRecoveryLoaded) reload()
    }

    Scaffold(
        topBar = {
            if (!desktop) {
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
                        IconButton(onClick = onBack, enabled = !mutationInProgress) {
                            Icon(NextcloudIcons.Back, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { loadAttempt += 1 }) {
                            Icon(NextcloudIcons.Refresh, contentDescription = "Refresh calendars")
                        }
                        IconButton(
                            onClick = { if (!mutationInProgress) creating = true },
                            enabled = !mutationInProgress &&
                                (state as? CalendarLoadState.Ready)?.calendars?.any { it.writable } == true,
                        ) {
                            Icon(NextcloudIcons.Add, contentDescription = "Create event")
                        }
                    },
                )
            }
        },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
            val initialLoading = state == CalendarLoadState.Loading
            val displayed = when (val value = state) {
                CalendarLoadState.Loading -> CalendarLoadState.Ready(
                    month,
                    queryWindow,
                    emptyList(),
                    emptyList(),
                )
                is CalendarLoadState.Ready -> value
                is CalendarLoadState.Error -> null
            }
            if (displayed == null) {
                CalendarError((state as CalendarLoadState.Error).message) { loadAttempt += 1 }
            } else if (!initialLoading && displayed.calendars.isEmpty()) {
                CalendarError("No event calendars were found.") { loadAttempt += 1 }
            } else if (desktop) {
                val selectedEvent = displayed.events.firstOrNull { event ->
                    event.instanceId == selectedEventId
                }
                DesktopGroupwareCalendarWorkspace(
                    month = displayed.month,
                    selectedDate = selectedDate,
                    view = view,
                    calendars = displayed.calendars,
                    events = displayed.events,
                    hiddenCalendarHrefs = hiddenCalendarHrefs.toSet(),
                    query = query,
                    selectedEvent = selectedEvent,
                    loading = initialLoading,
                    mutationsEnabled = !mutationInProgress,
                    onPrevious = { navigateCalendar(-1) },
                    onNext = { navigateCalendar(1) },
                    onToday = ::selectToday,
                    onViewChanged = { selected -> viewName = selected.name },
                    onQueryChanged = { query = it },
                    onCalendarVisibilityChanged = { href, visible ->
                        hiddenCalendarHrefs = if (visible) {
                            hiddenCalendarHrefs - href
                        } else {
                            (hiddenCalendarHrefs + href).distinct()
                        }
                        if (!visible && selectedEvent?.calendarHref == href) selectedEventId = null
                    },
                    onSelectDate = { date -> selectedDate = date; selectedEventId = null },
                    onSelectEvent = { event -> selectedEventId = event?.instanceId },
                    onCreateEvent = { if (!mutationInProgress) creating = true },
                    onRefresh = { loadAttempt += 1 },
                    onEditEvent = { event ->
                        if (!mutationInProgress) {
                            activeEventInstanceId = event.instanceId
                            eventEditorActive = true
                        }
                    },
                    onDeleteEvent = { event ->
                        if (!mutationInProgress) {
                            mutationError = null
                            deleting = event
                        }
                    },
                )
            } else {
                MobileGroupwareCalendarWorkspace(
                    month = displayed.month,
                    selectedDate = selectedDate,
                    view = view,
                    calendars = displayed.calendars,
                    events = displayed.events,
                    hiddenCalendarHrefs = hiddenCalendarHrefs.toSet(),
                    query = query,
                    onPrevious = { navigateCalendar(-1) },
                    onNext = { navigateCalendar(1) },
                    onToday = ::selectToday,
                    onViewChanged = { selected -> viewName = selected.name },
                    onQueryChanged = { query = it },
                    onCalendarVisibilityChanged = { href, visible ->
                        hiddenCalendarHrefs = if (visible) {
                            hiddenCalendarHrefs - href
                        } else {
                            (hiddenCalendarHrefs + href).distinct()
                        }
                    },
                    onSelectDate = { selectedDate = it },
                    onSelectEvent = { event ->
                        activeEventInstanceId = event.instanceId
                        eventEditorActive = false
                    },
                )
            }
            if (initialLoading || refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
            refreshError?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { loadAttempt += 1 }) { Text("Retry") }
                    }
                }
            }
        }
    }

    val ready = state as? CalendarLoadState.Ready
    val activeEvent = ready?.events?.firstOrNull { event -> event.instanceId == activeEventInstanceId }
    LaunchedEffect(ready, activeEventInstanceId) {
        if (ready != null && activeEventInstanceId != null && activeEvent == null) {
            activeEventInstanceId = null
            eventEditorActive = false
        }
    }
    val eventEditorVisible = creating || (eventEditorActive && activeEventInstanceId != null)
    LaunchedEffect(navigationRequest?.identity, eventEditorVisible, mutationInProgress) {
        navigationRequest
            ?.takeIf { !eventEditorVisible && !mutationInProgress }
            ?.let(onNavigationConfirmed)
    }
    if (creating && ready != null) {
        EventEditorDialog(
            event = null,
            initialDate = selectedDate,
            calendars = ready.calendars.filter(GroupwareCalendar::writable),
            onDismiss = { creating = false },
            error = mutationError,
            navigationRequest = navigationRequest,
            onNavigationConfirmed = onNavigationConfirmed,
            onNavigationCancelled = onNavigationCancelled,
            mutationInProgress = mutationInProgress,
            onSave = save@{ draft, calendar ->
                mutationError = null
                val uid = "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
                val objectHref = "${calendar.href}$uid.ics"
                val request = GroupwareDavMutationSpec(
                    kind = GroupwareDavKind.Event,
                    mutation = GroupwareDavMutation.Create,
                    objectHref = objectHref,
                    content = createGroupwareCalendarEventContent(
                        uid = uid,
                        title = draft.title,
                        start = draft.startValue(),
                        end = draft.endValue(),
                        allDay = draft.allDay,
                        location = draft.location,
                        description = draft.description,
                        recurrenceRule = draft.recurrenceRule,
                    ),
                ).toGroupwareDavRequest()
                scope.launch {
                    if (!retainMutationRecovery(
                        CalendarMutationPostcondition.Upsert(
                            href = objectHref,
                            calendarHref = calendar.href,
                            expectedUid = uid,
                            previousEtag = null,
                            draft = draft,
                        ),
                    )) return@launch
                    try {
                        val response = services.executeGroupwareDav(session, request)
                        if (response.status !in 200..299) {
                            if (groupwareMutationResponseProvesRejection(response.status)) {
                                if (clearMutationRecovery()) {
                                    mutationError = "Creating the event failed (HTTP ${response.status})."
                                }
                            } else {
                                mutationError = CALENDAR_MUTATION_RESULT_UNKNOWN_MESSAGE
                                loadAttempt += 1
                            }
                            return@launch
                        }
                        creating = false
                        loadAttempt += 1
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        mutationError = CALENDAR_MUTATION_RESULT_UNKNOWN_MESSAGE
                        loadAttempt += 1
                    }
                }
            },
        )
    }

    activeEvent?.let { event ->
        val calendar = ready.calendars.firstOrNull { it.href == event.calendarHref }
        if (eventEditorActive && calendar != null) {
            EventEditorDialog(
                event = event,
                initialDate = event.start.take(8),
                calendars = listOf(calendar),
                onDismiss = {
                    activeEventInstanceId = null
                    eventEditorActive = false
                },
                error = mutationError,
                navigationRequest = navigationRequest,
                onNavigationConfirmed = onNavigationConfirmed,
                onNavigationCancelled = onNavigationCancelled,
                mutationInProgress = mutationInProgress,
                onSave = save@{ draft, _ ->
                    mutationError = null
                    val updated = updateGroupwareCalendarEventContent(
                        event = event,
                        title = draft.title,
                        start = draft.startValue(),
                        end = draft.endValue(),
                        allDay = draft.allDay,
                        location = draft.location,
                        description = draft.description,
                        recurrenceRule = draft.recurrenceRule,
                    )
                    val request = GroupwareDavMutationSpec(
                        kind = GroupwareDavKind.Event,
                        mutation = GroupwareDavMutation.Update,
                        objectHref = event.href,
                        etag = event.etag,
                        content = updated,
                    ).toGroupwareDavRequest()
                    scope.launch {
                        if (!retainMutationRecovery(
                            CalendarMutationPostcondition.Upsert(
                                href = event.href,
                                calendarHref = event.calendarHref,
                                expectedUid = event.uid,
                                previousEtag = event.etag,
                                draft = draft,
                            ),
                        )) return@launch
                        try {
                            val response = services.executeGroupwareDav(session, request)
                            if (response.status !in 200..299) {
                                if (groupwareMutationResponseProvesRejection(response.status)) {
                                    if (clearMutationRecovery()) {
                                        mutationError = "Saving the event failed (HTTP ${response.status})."
                                    }
                                } else {
                                    mutationError = CALENDAR_MUTATION_RESULT_UNKNOWN_MESSAGE
                                    loadAttempt += 1
                                }
                                return@launch
                            }
                            activeEventInstanceId = null
                            eventEditorActive = false
                            loadAttempt += 1
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            mutationError = CALENDAR_MUTATION_RESULT_UNKNOWN_MESSAGE
                            loadAttempt += 1
                        }
                    }
                },
            )
        } else {
            EventDetailDialog(
                event = event,
                canEdit = !mutationInProgress && calendar?.writable == true &&
                    event.etag != null && !event.isGeneratedOccurrence,
                onDismiss = {
                    activeEventInstanceId = null
                    eventEditorActive = false
                },
                onEdit = {
                    if (!mutationInProgress) {
                        creating = false
                        eventEditorActive = true
                    }
                },
                onDelete = {
                    if (!mutationInProgress) {
                        activeEventInstanceId = null
                        eventEditorActive = false
                        mutationError = null
                        deleting = event
                    }
                },
                error = mutationError,
            )
        }
    }

    deleting?.let { event ->
        AlertDialog(
            onDismissRequest = { if (!deletingInProgress && !mutationInProgress) deleting = null },
            title = { Text("Delete ${event.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text("This permanently removes the event from its Nextcloud calendar.")
                    if (event.recurrenceRule != null) {
                        Text(
                            "This event repeats. Deleting it removes the complete series.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deletingInProgress && !mutationInProgress,
                    onClick = { deleting = null; mutationError = null },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = !deletingInProgress && !mutationInProgress,
                    onClick = {
                        deletingInProgress = true
                        mutationError = null
                        val request = GroupwareDavMutationSpec(
                            kind = GroupwareDavKind.Event,
                            mutation = GroupwareDavMutation.Delete,
                            objectHref = event.href,
                            etag = event.etag,
                        ).toGroupwareDavRequest()
                        scope.launch {
                            if (!retainMutationRecovery(CalendarMutationPostcondition.Delete(event.href))) {
                                deletingInProgress = false
                                return@launch
                            }
                            try {
                                val response = services.executeGroupwareDav(session, request)
                                if (response.status !in 200..299) {
                                    if (groupwareDeleteResponseProvesAbsence(response.status)) {
                                        if (clearMutationRecovery()) {
                                            deleting = null
                                            selectedEventId = null
                                            loadAttempt += 1
                                        }
                                    } else if (groupwareMutationResponseProvesRejection(response.status)) {
                                        if (clearMutationRecovery()) {
                                            mutationError = "Deleting the event failed (HTTP ${response.status})."
                                        }
                                    } else {
                                        mutationError = CALENDAR_MUTATION_RESULT_UNKNOWN_MESSAGE
                                        loadAttempt += 1
                                    }
                                    return@launch
                                }
                                deleting = null
                                selectedEventId = null
                                loadAttempt += 1
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                mutationError = CALENDAR_MUTATION_RESULT_UNKNOWN_MESSAGE
                                loadAttempt += 1
                            } finally {
                                deletingInProgress = false
                            }
                        }
                    },
                ) {
                    if (deletingInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Delete")
                    }
                }
            },
        )
    }
}

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
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var sourcesVisible by rememberSaveable { mutableStateOf(false) }
    val presentation = remember(events, calendars, hiddenCalendarHrefs, query, selectedDate) {
        buildCalendarWorkspacePresentation(events, calendars, hiddenCalendarHrefs, query, selectedDate)
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(NextcloudIcons.Back, contentDescription = "Previous ${view.mobileNavigationPeriod()}")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (view == CalendarWorkspaceView.Week) presentation.weekDates.mobileWeekTitle() else month.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${presentation.visibleEvents.size} visible events",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onToday) { Text("Today") }
            IconButton(onClick = onNext) {
                Icon(NextcloudIcons.ChevronRight, contentDescription = "Next ${view.mobileNavigationPeriod()}")
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(CalendarWorkspaceView.entries, key = CalendarWorkspaceView::name) { candidate ->
                FilterChip(
                    selected = view == candidate,
                    onClick = { onViewChanged(candidate) },
                    label = { Text(candidate.name) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { searchVisible = !searchVisible }) {
                Icon(NextcloudIcons.Search, contentDescription = "Search events")
            }
            IconButton(onClick = { sourcesVisible = !sourcesVisible }) {
                Icon(NextcloudIcons.Filter, contentDescription = "Choose visible calendars")
            }
        }
        if (searchVisible || query.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
                singleLine = true,
                leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                placeholder = { Text("Search events") },
            )
        }
        if (sourcesVisible) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = NextcloudSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                items(calendars, key = GroupwareCalendar::href) { calendar ->
                    val visible = calendar.href !in hiddenCalendarHrefs
                    FilterChip(
                        selected = visible,
                        onClick = { onCalendarVisibilityChanged(calendar.href, !visible) },
                        label = {
                            Text("${calendar.displayName} ${presentation.eventCountByCalendar[calendar.href] ?: 0}")
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (view) {
            CalendarWorkspaceView.Month -> MonthCalendar(
                month = month,
                selectedDate = selectedDate,
                events = presentation.visibleEvents,
                onSelectDate = onSelectDate,
                onSelectEvent = onSelectEvent,
                modifier = Modifier.weight(1f),
            )
            CalendarWorkspaceView.Week -> CalendarAgenda(
                events = presentation.visibleEvents.filter { event ->
                    event.overlapsCalendarDateRange(
                        presentation.weekDates.firstOrNull(),
                        presentation.weekDates.lastOrNull(),
                    )
                },
                onSelectEvent = onSelectEvent,
                emptyPeriod = "week",
                earliestDisplayDate = presentation.weekDates.firstOrNull(),
                modifier = Modifier.weight(1f),
            )
            CalendarWorkspaceView.Agenda -> CalendarAgenda(
                events = presentation.visibleEvents,
                onSelectEvent = onSelectEvent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun CalendarWorkspaceView.mobileNavigationPeriod(): String =
    if (this == CalendarWorkspaceView.Week) "week" else "month"

private fun List<String>.mobileWeekTitle(): String {
    val first = firstOrNull()?.parseCompactCalendarDate() ?: return "Week"
    val last = lastOrNull()?.parseCompactCalendarDate() ?: return "Week"
    return if (first.month == last.month) {
        "${first.day}-${last.day} ${MONTH_NAMES[first.month - 1]}"
    } else {
        "${first.day} ${MONTH_NAMES[first.month - 1]} - ${last.day} ${MONTH_NAMES[last.month - 1]}"
    }
}

internal fun calendarReadyMatchesRequest(
    readyMonth: CalendarMonth,
    readyWindow: GroupwareDavTimeWindow,
    requestedMonth: CalendarMonth,
    requestedWindow: GroupwareDavTimeWindow,
): Boolean = readyMonth == requestedMonth && readyWindow == requestedWindow

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
internal fun MonthCalendar(
    month: CalendarMonth,
    selectedDate: String,
    events: List<GroupwareCalendarEvent>,
    onSelectDate: (String) -> Unit,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byDay = remember(events, month) {
        val firstMonthDate = "${month.isoPrefix}01"
        val lastMonthDate = "${month.isoPrefix}${month.days().toString().padStart(2, '0')}"
        buildMap {
            events.forEach { event ->
                event.occupiedCalendarDates(firstMonthDate, lastMonthDate).forEach { date ->
                    getOrPut(date) { mutableListOf() }.add(event)
                }
            }
        }
    }
    val leading = dayOfWeekMondayFirst(month.year, month.month, 1)
    val cells = remember(month) {
        (List<Int?>(leading) { null } + (1..month.days()).map<Int, Int?> { it })
            .let { days -> days + List(42 - days.size) { null } }
            .chunked(7)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
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
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        itemsIndexed(cells, key = { index, _ -> calendarMonthWeekKey(month, index) }) { _, week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { day ->
                    if (day == null) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = "${month.isoPrefix}${day.toString().padStart(2, '0')}"
                        val dayEvents = byDay[date].orEmpty()
                        val selected = date == selectedDate
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                    RoundedCornerShape(NextcloudRadii.Medium),
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                                )
                                .semantics {
                                    contentDescription = "$date, ${dayEvents.size} ${if (dayEvents.size == 1) "event" else "events"}"
                                }
                                .clickable { onSelectDate(date) }
                                .padding(5.dp),
                        ) {
                            Text(
                                day.toString(),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                            if (dayEvents.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    dayEvents.take(3).forEach { event ->
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(mobileCalendarEventColor(event), CircleShape),
                                        )
                                    }
                                    if (dayEvents.size > 3) {
                                        Text(
                                            "+${dayEvents.size - 3}",
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

internal fun calendarMonthWeekKey(month: CalendarMonth, weekIndex: Int): String =
    "${month.isoPrefix}-week-$weekIndex"

internal fun GroupwareCalendarEvent.overlapsCalendarDateRange(
    firstDate: String?,
    lastDate: String?,
): Boolean {
    val rangeStart = firstDate ?: return false
    val rangeEnd = lastDate ?: return false
    val eventStart = start.take(8)
    val eventEnd = end?.take(8)?.takeIf { it.length == 8 }
    val reachesRange = when {
        eventEnd == null -> true
        allDay -> eventEnd > rangeStart
        else -> eventEnd >= rangeStart
    }
    return eventStart <= rangeEnd && reachesRange
}

internal fun GroupwareCalendarEvent.occupiedCalendarDates(
    windowStart: String? = null,
    windowEnd: String? = null,
): List<String> {
    val first = start.take(8).takeIf { it.length == 8 } ?: return emptyList()
    val explicitEnd = end?.take(8)?.takeIf { it.length == 8 }
    val last = explicitEnd ?: first
    if (last < first) return listOf(first)
    val boundedFirst = windowStart?.takeIf { it.length == 8 && it > first } ?: first
    val boundedLast = windowEnd?.takeIf { it.length == 8 && it < last } ?: last
    if (boundedLast < boundedFirst) return emptyList()
    return buildList {
        var current: String? = boundedFirst
        while (
            current != null && current <= boundedLast &&
            !(allDay && explicitEnd != null && current == last)
        ) {
            add(current)
            if (current == last) break
            current = nextCompactDate(current)
        }
    }
}

private fun nextCompactDate(date: String): String? =
    nextIsoDate(date.compactDateToIso())?.isoDateToCompact()

@Composable
private fun mobileCalendarEventColor(event: GroupwareCalendarEvent): Color {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    return colors[event.calendarHref.hashCode().ushr(1) % colors.size]
}

@Composable
internal fun CalendarAgenda(
    events: List<GroupwareCalendarEvent>,
    onSelectEvent: (GroupwareCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    emptyPeriod: String = "month",
    earliestDisplayDate: String? = null,
) {
    val groups = events.groupBy { event ->
        val startDate = event.start.take(8)
        earliestDisplayDate?.let { maxOf(startDate, it) } ?: startDate
    }.toSortedMap()
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
                Text("No events this $emptyPeriod.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                event.recurrenceRule?.let { Text("Repeats: ${calendarRecurrenceDescription(it)}") }
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

@Serializable
internal data class EventDraft(
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val allDay: Boolean,
    val location: String,
    val description: String,
    val recurrenceRule: String?,
) {
    fun normalizedForDav(): EventDraft = copy(
        location = location.takeUnless(String::isBlank).orEmpty(),
        description = description.takeUnless(String::isBlank).orEmpty(),
        recurrenceRule = recurrenceRule?.trim()?.takeUnless(String::isBlank),
    )

    fun startValue(): String = date.isoDateToCompact() + if (allDay) "" else "T${startTime.timeToCompact()}00Z"
    fun endValue(): String? = if (allDay) nextIsoDate(date)?.isoDateToCompact()
    else date.isoDateToCompact() + "T${endTime.timeToCompact()}00Z"
}

@Serializable
internal sealed interface CalendarMutationPostcondition {
    val href: String
    fun isSatisfiedBy(response: NextcloudApiResponse): Boolean

    @Serializable
    data class Upsert(
        override val href: String,
        val calendarHref: String,
        val expectedUid: String,
        val previousEtag: String?,
        val draft: EventDraft,
    ) : CalendarMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean {
            if (response.status !in 200..299) return false
            val expected = draft.normalizedForDav()
            val event = parseGroupwareCalendarEvent(
                calendarHref = calendarHref,
                href = href,
                etag = response.etag,
                content = response.body.decodeToString(),
            ) ?: return false
            return event.href == href &&
                event.uid == expectedUid &&
                (previousEtag == null || event.etag != null && event.etag != previousEtag) &&
                event.title == expected.title &&
                event.allDay == expected.allDay &&
                event.location.orEmpty() == expected.location &&
                event.description.orEmpty() == expected.description &&
                event.recurrenceRule == expected.recurrenceRule &&
                event.start == expected.startValue() &&
                event.end == expected.endValue()
        }
    }

    @Serializable
    data class Delete(override val href: String) : CalendarMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean =
            groupwareDeleteResponseProvesAbsence(response.status)
    }
}

@Serializable
internal data class CalendarMutationRecoveryState(
    val accountScope: String,
    val postcondition: CalendarMutationPostcondition,
) {
    init {
        require(accountScope.isCanonicalGroupwareMutationAccountScope())
    }
}

private val calendarMutationRecoveryJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun durableMutationAccountScope(session: NextcloudSession): String =
    publicContentSha256(
        listOf(session.serverUrl.trimEnd('/'), session.loginName)
            .joinToString("|") { value -> "${value.length}:$value" }
            .encodeToByteArray(),
    )

internal fun String.isCanonicalGroupwareMutationAccountScope(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }

internal fun CalendarMutationRecoveryState.encodeForSavedState(): String =
    calendarMutationRecoveryJson.encodeToString(this)

internal fun decodeCalendarMutationRecoveryState(
    encoded: String,
    expectedAccountScope: String,
): CalendarMutationPostcondition? = runCatching {
    calendarMutationRecoveryJson.decodeFromString<CalendarMutationRecoveryState>(encoded)
}.getOrNull()?.takeIf { recovery -> recovery.accountScope == expectedAccountScope }?.postcondition

internal fun calendarEventDraftIsDirty(
    initial: EventDraft,
    current: EventDraft,
    initialCalendarHref: String?,
    currentCalendarHref: String?,
): Boolean = initial != current || initialCalendarHref != currentCalendarHref

private enum class EventRecurrencePreset(
    val label: String,
    val rule: String?,
) {
    None("Does not repeat", null),
    Daily("Daily", "FREQ=DAILY"),
    Weekly("Weekly", "FREQ=WEEKLY"),
    Monthly("Monthly", "FREQ=MONTHLY"),
    Custom("Custom", null),
    ;

    companion object {
        fun forRule(rule: String?): EventRecurrencePreset = entries.firstOrNull { preset ->
            preset != Custom && preset.rule?.equals(rule, ignoreCase = true) == true
        } ?: if (rule.isNullOrBlank()) None else Custom
    }
}

@Composable
internal fun MarketingCalendarEventEditorCapture() {
    Box(Modifier.fillMaxSize().padding(NextcloudSpacing.Large), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            shape = RoundedCornerShape(NextcloudRadii.Large),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(NextcloudSpacing.Large), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text("New event", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(value = "Design review", onValueChange = {}, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                Text("Repeats", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    items(listOf("Does not repeat", "Daily", "Weekly", "Monthly")) { label ->
                        FilterChip(selected = label == "Weekly", onClick = {}, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = "2026-08-04", onValueChange = {}, label = { Text("Date") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    OutlinedTextField(value = "13:30", onValueChange = {}, label = { Text("Starts") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = "14:30", onValueChange = {}, label = { Text("Ends") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = "Product room - Talk", onValueChange = {}, label = { Text("Location") }, modifier = Modifier.fillMaxWidth())
                Text("Calendar: Product team", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {}) { Text("Cancel") }
                    Button(onClick = {}) { Text("Save") }
                }
            }
        }
    }
}

@Composable
internal fun MarketingCalendarRecurringEventDetailCapture() {
    Box(Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(NextcloudRadii.Large), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text("Product stand-up", style = MaterialTheme.typography.headlineSmall)
                Text("3 August 2026")
                Text("08:30 - 09:00")
                Text("Location: Product room - Talk")
                Text("Repeats: ${calendarRecurrenceDescription("FREQ=WEEKLY;BYDAY=MO")}")
                Text("Review current work and blockers.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {}) { Text("Delete series", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {}) { Text("Edit series") }
                }
            }
        }
    }
}

internal fun calendarRecurrenceDescription(rule: String): String {
    val parts = rule.uppercase().split(';').mapNotNull { token ->
        token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
    val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 1 }
    val frequency = when (parts["FREQ"]) {
        "DAILY" -> if (interval == null) "Daily" else "Every $interval days"
        "WEEKLY" -> if (interval == null) "Weekly" else "Every $interval weeks"
        "MONTHLY" -> if (interval == null) "Monthly" else "Every $interval months"
        else -> return "Custom recurrence"
    }
    val days = parts["BYDAY"]?.split(',')?.mapNotNull { day ->
        mapOf("MO" to "Monday", "TU" to "Tuesday", "WE" to "Wednesday", "TH" to "Thursday", "FR" to "Friday", "SA" to "Saturday", "SU" to "Sunday")[day]
    }.orEmpty()
    return if (days.isEmpty()) frequency else "$frequency on ${days.joinToString()}"
}

@Composable
private fun EventEditorDialog(
    event: GroupwareCalendarEvent?,
    initialDate: String,
    calendars: List<GroupwareCalendar>,
    onDismiss: () -> Unit,
    error: String?,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    mutationInProgress: Boolean = false,
    onSave: (EventDraft, GroupwareCalendar) -> Unit,
) {
    val initialIsoDate = (event?.start?.take(8) ?: initialDate).compactDateToIso()
    val initialTitle = event?.title.orEmpty()
    val initialStartTime = event?.start?.compactTime() ?: "09:00"
    val initialEndTime = event?.end?.compactTime() ?: "10:00"
    val initialAllDay = event?.allDay ?: false
    val initialLocation = event?.location.orEmpty()
    val initialDescription = event?.description.orEmpty()
    val initialRecurrencePreset = EventRecurrencePreset.forRule(event?.recurrenceRule)
    val initialRecurrenceRule = when (initialRecurrencePreset) {
        EventRecurrencePreset.None -> null
        EventRecurrencePreset.Custom -> event?.recurrenceRule?.trim()?.takeIf(String::isNotBlank)
        else -> initialRecurrencePreset.rule
    }
    val initialCalendarHref = calendars.firstOrNull()?.href
    val initialDraft = remember(event, initialDate) {
        EventDraft(
            title = initialTitle,
            date = initialIsoDate,
            startTime = initialStartTime,
            endTime = initialEndTime,
            allDay = initialAllDay,
            location = initialLocation,
            description = initialDescription,
            recurrenceRule = initialRecurrenceRule,
        )
    }
    val editorStateKey = event?.instanceId ?: "new:$initialDate"
    var title by rememberSaveable(editorStateKey) { mutableStateOf(initialTitle) }
    var date by rememberSaveable(editorStateKey) { mutableStateOf(initialIsoDate) }
    var startTime by rememberSaveable(editorStateKey) { mutableStateOf(initialStartTime) }
    var endTime by rememberSaveable(editorStateKey) { mutableStateOf(initialEndTime) }
    var allDay by rememberSaveable(editorStateKey) { mutableStateOf(initialAllDay) }
    var location by rememberSaveable(editorStateKey) { mutableStateOf(initialLocation) }
    var description by rememberSaveable(editorStateKey) { mutableStateOf(initialDescription) }
    var recurrencePresetName by rememberSaveable(editorStateKey) {
        mutableStateOf(initialRecurrencePreset.name)
    }
    var customRecurrenceRule by rememberSaveable(editorStateKey) {
        mutableStateOf(event?.recurrenceRule.orEmpty())
    }
    var selectedCalendarHref by rememberSaveable(editorStateKey) {
        mutableStateOf(initialCalendarHref)
    }
    val calendar = calendars.firstOrNull { candidate -> candidate.href == selectedCalendarHref }
        ?: calendars.firstOrNull()
    val recurrencePreset = EventRecurrencePreset.entries.firstOrNull { it.name == recurrencePresetName }
        ?: EventRecurrencePreset.None
    val recurrenceRule = when (recurrencePreset) {
        EventRecurrencePreset.None -> null
        EventRecurrencePreset.Custom -> customRecurrenceRule.trim().takeIf(String::isNotBlank)
        else -> recurrencePreset.rule
    }
    val recurrenceValid = recurrencePreset != EventRecurrencePreset.Custom ||
        recurrenceRule?.let(::isSupportedCalendarRecurrenceRuleForWrite) == true
    val currentDraft = EventDraft(
        title = title,
        date = date,
        startTime = startTime,
        endTime = endTime,
        allDay = allDay,
        location = location,
        description = description,
        recurrenceRule = recurrenceRule,
    )
    val dirty = calendarEventDraftIsDirty(
        initial = initialDraft,
        current = currentDraft,
        initialCalendarHref = initialCalendarHref,
        currentCalendarHref = calendar?.href,
    )
    var confirmNavigationDiscard by remember(event) { mutableStateOf(false) }
    LaunchedEffect(navigationRequest?.identity, dirty, mutationInProgress) {
        val request = navigationRequest
        when {
            request == null -> confirmNavigationDiscard = false
            mutationInProgress -> confirmNavigationDiscard = false
            dirty -> confirmNavigationDiscard = true
            else -> onNavigationConfirmed(request)
        }
    }
    val valid = title.isNotBlank() && date.isIsoCalendarDate() &&
        (allDay || startTime.isCalendarTime() && endTime.isCalendarTime()) && recurrenceValid

    AlertDialog(
        onDismissRequest = { if (!mutationInProgress) onDismiss() },
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
                    Text("Repeats", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        items(EventRecurrencePreset.entries) { preset ->
                            FilterChip(
                                selected = recurrencePreset == preset,
                                onClick = {
                                    recurrencePresetName = preset.name
                                    if (preset == EventRecurrencePreset.Custom && customRecurrenceRule.isBlank()) {
                                        customRecurrenceRule = "FREQ=WEEKLY;INTERVAL=2"
                                    }
                                },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                }
                if (recurrencePreset == EventRecurrencePreset.Custom) {
                    item {
                        OutlinedTextField(
                            value = customRecurrenceRule,
                            onValueChange = { customRecurrenceRule = it },
                            label = { Text("Recurrence rule") },
                            supportingText = {
                                Text("Daily, weekly, or monthly rule, for example FREQ=WEEKLY;BYDAY=MO,WE")
                            },
                            isError = !recurrenceValid,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                                    onClick = { selectedCalendarHref = candidate.href },
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
                enabled = valid && calendar != null && !mutationInProgress,
                onClick = {
                    onSave(
                        currentDraft,
                        requireNotNull(calendar),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !mutationInProgress) { Text("Cancel") }
        },
    )

    if (confirmNavigationDiscard) {
        navigationRequest?.let { request ->
            AlertDialog(
                onDismissRequest = {
                    confirmNavigationDiscard = false
                    onNavigationCancelled(request)
                },
                title = { Text("Discard unsaved event changes?") },
                text = { Text("Your event draft has not been saved.") },
                dismissButton = {
                    TextButton(
                        onClick = {
                            confirmNavigationDiscard = false
                            onNavigationCancelled(request)
                        },
                    ) { Text("Keep editing") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmNavigationDiscard = false
                            onNavigationConfirmed(request)
                        },
                    ) { Text("Discard") }
                },
            )
        }
    }
}

internal fun GroupwareCalendarEvent.displayTimeRange(): String {
    if (allDay) return "All day"
    val startTime = start.compactTime()
    val endTime = end?.compactTime()
    return if (endTime == null) startTime else "$startTime - $endTime"
}

internal fun String.compactTime(): String =
    if (length >= 13 && getOrNull(8) == 'T') "${substring(9, 11)}:${substring(11, 13)}" else "09:00"

internal fun String.displayCalendarDate(): String {
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
private const val MAXIMUM_RETAINED_CALENDAR_MONTHS = 24
private const val CALENDAR_MUTATION_RESULT_UNKNOWN_MESSAGE =
    "The server response was interrupted, so the calendar result is unknown. " +
        "Refresh to verify it before trying another change."
