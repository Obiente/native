package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    navigationCommitInProgress: Boolean = false,
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
    val durableMutationInProgress =
        !mutationRecoveryLoaded || mutationOperationInProgress || mutationRecoveryState != null
    val mutationInProgress = mutationOrLinkCommitBlocksInteraction(
        durableMutationInProgress,
        navigationCommitInProgress,
    )
    var creating by rememberSaveable(accountScope) { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var showRecoveryOptions by remember(accountScope) { mutableStateOf(false) }
    var recoveryResetInProgress by remember(accountScope) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop

    LaunchedEffect(navigationCommitInProgress) {
        if (navigationCommitInProgress) {
            creating = false
            eventEditorActive = false
        }
    }

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
            mutationRecoveryState = try {
                services.loadDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Calendar)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
            mutationError = if (mutationRecoveryState != null) {
                "Another calendar change is still awaiting server verification."
            } else {
                "The calendar change could not be safely recorded. Check local storage and try again."
            }
            mutationOperationInProgress = false
            onMutationInProgressChanged(mutationRecoveryState != null || !mutationRecoveryLoaded)
            return false
        }
        mutationRecoveryState = encoded
        return true
    }

    suspend fun clearMutationRecovery(): Boolean {
        val expectedEncoded = mutationRecoveryState ?: return false
        val cleared = try {
            services.clearDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.Calendar,
                expectedEncoded,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!cleared) {
            val current = try {
                services.loadDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Calendar)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
            if (current != null) mutationRecoveryState = current
            mutationError = "The verified calendar recovery record could not be cleared safely. Refresh to inspect the current pending change."
            return false
        }
        mutationRecoveryState = null
        mutationOperationInProgress = false
        mutationError = null
        refreshError = null
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
            val message = "Calendar recovery storage could not be read securely. Check local storage and retry."
            mutationError = message
            refreshError = message
        }
    }

    LaunchedEffect(accountScope, mutationRecoveryLoaded, mutationRecoveryState, mutationPostcondition) {
        if (mutationRecoveryLoaded && mutationRecoveryState != null && mutationPostcondition == null) {
            refreshError = "The previous calendar recovery record cannot be read. Writes remain blocked."
            showRecoveryOptions = true
        }
    }

    LaunchedEffect(durableMutationInProgress) {
        onMutationInProgressChanged(durableMutationInProgress)
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
            runCatchingPreservingCancellation {
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
        runCatchingPreservingCancellation {
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
            if (mutationRecoveryState != null) showRecoveryOptions = true
        }
        refreshing = false
    }

    LaunchedEffect(session, userId, month, queryWindow, loadAttempt, mutationRecoveryLoaded) {
        if (mutationRecoveryLoaded) reload()
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
    fun closeEvent() { activeEventInstanceId = null; eventEditorActive = false }

    Scaffold(
        topBar = {
            if (!desktop && activeEvent == null) {
                CalendarPhoneTopBar(
                    onBack = onBack,
                    onRefresh = { loadAttempt += 1 },
                    onCreate = { if (!mutationInProgress) creating = true },
                    navigationEnabled = !mutationInProgress,
                    createEnabled = !mutationInProgress &&
                        (state as? CalendarLoadState.Ready)?.calendars?.any { it.writable } == true,
                )
            }
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            refreshError?.let { message ->
                CalendarWorkspaceNotice(message, onRetry = { loadAttempt += 1 },
                    onRecovery = if (mutationRecoveryState != null) ({ showRecoveryOptions = true }) else null)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                if (activeEvent != null) {
                    activeEvent.let { event ->
                        val calendar = ready.calendars.firstOrNull { it.href == event.calendarHref }
                        if (eventEditorActive && calendar != null) {
                            EventEditorDialog(
                                inPlace = true,
                                backLabel = if (desktop) "Back to calendar" else "Back to event",
                                event = event,
                                initialDate = event.start.take(8),
                                calendars = listOf(calendar),
                                onDismiss = {
                                    eventEditorActive = false
                                    if (desktop) activeEventInstanceId = null
                                },
                                error = mutationError,
                                navigationRequest = navigationRequest,
                                recoveryAvailable = mutationRecoveryState != null,
                                onOpenRecovery = {
                                    closeEvent()
                                    showRecoveryOptions = true
                                },
                                onNavigationConfirmed = onNavigationConfirmed,
                                onNavigationDiscardConfirmed = { request ->
                                    closeEvent()
                                    onNavigationConfirmed(request)
                                },
                                onNavigationCancelled = onNavigationCancelled,
                                mutationInProgress = mutationInProgress,
                                onSave = save@{ draft, _ ->
                                    mutationError = null
                                    val request = prepareGroupwareDavMutation(
                                        onInvalid = {
                                            mutationError = "The event is too large or contains invalid data. Review its fields and try again."
                                        },
                                    ) {
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
                                        GroupwareDavMutationSpec(
                                            kind = GroupwareDavKind.Event,
                                            mutation = GroupwareDavMutation.Update,
                                            objectHref = event.href,
                                            etag = event.etag,
                                            content = updated,
                                        ).toGroupwareDavRequest()
                                    } ?: return@save
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
                                            closeEvent()
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
                                inPlace = true,
                                event = event,
                                canEdit = !mutationInProgress && calendar?.writable == true &&
                                    event.etag != null && !event.isGeneratedOccurrence,
                                onDismiss = ::closeEvent,
                                onEdit = {
                                    if (!mutationInProgress) {
                                        creating = false
                                        eventEditorActive = true
                                    }
                                },
                                onDelete = {
                                    if (!mutationInProgress) {
                                        closeEvent()
                                        mutationError = null
                                        deleting = event
                                    }
                                },
                                error = mutationError,
                            )
                        }
                    }
                } else if (displayed == null) {
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

            }
        }
    }

    if (showRecoveryOptions && mutationRecoveryState != null) {
        DurableMutationRecoveryDialog(
            title = "Resolve calendar recovery",
            recordReadable = mutationPostcondition != null,
            resetting = recoveryResetInProgress,
            onCheckAgain = {
                showRecoveryOptions = false
                loadAttempt += 1
            },
            onReset = {
                if (!recoveryResetInProgress) {
                    recoveryResetInProgress = true
                    scope.launch {
                        if (clearMutationRecovery()) showRecoveryOptions = false
                        recoveryResetInProgress = false
                    }
                }
            },
            onDismiss = { showRecoveryOptions = false },
        )
    }

    if (creating && ready != null) {
        EventEditorDialog(
            event = null,
            initialDate = selectedDate,
            calendars = ready.calendars.filter(GroupwareCalendar::writable),
            onDismiss = { creating = false },
            error = mutationError,
            navigationRequest = navigationRequest,
            recoveryAvailable = mutationRecoveryState != null,
            onOpenRecovery = {
                creating = false
                showRecoveryOptions = true
            },
            onNavigationConfirmed = onNavigationConfirmed,
            onNavigationDiscardConfirmed = { request ->
                creating = false
                onNavigationConfirmed(request)
            },
            onNavigationCancelled = onNavigationCancelled,
            mutationInProgress = mutationInProgress,
            onSave = save@{ draft, calendar ->
                mutationError = null
                val uid = "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
                val objectHref = "${calendar.href}$uid.ics"
                val request = prepareGroupwareDavMutation(
                    onInvalid = {
                        mutationError = "The event is too large or contains invalid data. Review its fields and try again."
                    },
                ) {
                    GroupwareDavMutationSpec(
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
                } ?: return@save
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
                        val request = prepareGroupwareDavMutation(
                            onInvalid = {
                                mutationError = "The event cannot be changed safely. Refresh it and try again."
                                deletingInProgress = false
                            },
                        ) {
                            GroupwareDavMutationSpec(
                                kind = GroupwareDavKind.Event,
                                mutation = GroupwareDavMutation.Delete,
                                objectHref = event.href,
                                etag = event.etag,
                            ).toGroupwareDavRequest()
                        } ?: return@Button
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
        title = title.normalizeGroupwareTextLineEndings(),
        location = location.takeUnless(String::isBlank).orEmpty().normalizeGroupwareTextLineEndings(),
        description = description.takeUnless(String::isBlank).orEmpty().normalizeGroupwareTextLineEndings(),
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
            val event = parseGroupwareCalendarEventsFromContent(
                calendarHref = calendarHref,
                href = href,
                etag = response.etag,
                content = response.body.decodeToString(),
            ).firstOrNull { candidate ->
                candidate.uid == expectedUid && candidate.recurrenceId == null
            } ?: return false
            return event.href == href &&
                event.uid == expectedUid &&
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

internal fun calendarEventDraftHasDavChanges(
    initial: EventDraft,
    current: EventDraft,
    initialCalendarHref: String?,
    currentCalendarHref: String?,
): Boolean = initial.normalizedForDav() != current.normalizedForDav() ||
    initialCalendarHref != currentCalendarHref

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

internal fun String.compactDateToIso(): String =
    if (length == 8) "${take(4)}-${substring(4, 6)}-${takeLast(2)}" else this

internal fun String.isoDateToCompact(): String = replace("-", "")
internal fun String.timeToCompact(): String = replace(":", "")

internal fun String.isIsoCalendarDate(): Boolean {
    if (length != 10 || getOrNull(4) != '-' || getOrNull(7) != '-') return false
    val year = take(4).toIntOrNull() ?: return false
    val month = substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return false
    val day = takeLast(2).toIntOrNull() ?: return false
    return year in 1..9999 && day in 1..groupwareCalendarDaysInMonth(year, month)
}

internal fun String.isCalendarTime(): Boolean {
    if (length != 5 || getOrNull(2) != ':') return false
    val hour = take(2).toIntOrNull() ?: return false
    val minute = takeLast(2).toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

internal fun nextIsoDate(date: String): String? {
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

internal fun currentCalendarDate(): String {
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
