package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

private enum class TaskFilter { Open, All, Completed }

@Composable
fun NativeGroupwareTasksScreen(
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
    var state by remember(session, userId) { mutableStateOf<TasksLoadState>(TasksLoadState.Loading) }
    var loadAttempt by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var selectedTaskSelection by rememberSaveable(accountScope, stateSaver = GroupwareTaskSelectionSaver) {
        mutableStateOf<GroupwareTaskSelection?>(null)
    }
    var creating by rememberSaveable(accountScope) { mutableStateOf(false) }
    var editing by rememberSaveable(accountScope) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GroupwareTask?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    val operations = remember(accountScope, services) { GroupwareTaskOperations() }
    val mutationRunning = operations.mutationRunning
    var recoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var recoveryEncoded by remember(accountScope, services) { mutableStateOf<String?>(null) }
    var recoveryVerification by remember(accountScope, services) {
        mutableStateOf(TaskRecoveryVerification.Unknown)
    }
    var unreadableRecoveryDiscardRequested by remember(accountScope) { mutableStateOf(false) }
    var filter by rememberSaveable(accountScope) { mutableStateOf(TaskFilter.Open) }
    var query by rememberSaveable(accountScope) { mutableStateOf("") }
    val recoveryPostcondition = remember(accountScope, recoveryEncoded) {
        recoveryEncoded?.let { decodeTaskMutationRecoveryState(it, accountScope) }
    }
    val unreadableRecovery = recoveryLoaded && recoveryEncoded != null && recoveryPostcondition == null
    val durableMutationInProgress = !recoveryLoaded || mutationRunning || recoveryEncoded != null
    val interactionBlocked = mutationOrLinkCommitBlocksInteraction(
        durableMutationInProgress || operations.busy,
        navigationCommitInProgress,
    )
    val scope = rememberCoroutineScope()

    suspend fun retainRecovery(postcondition: TaskMutationPostcondition): Boolean {
        if (!recoveryLoaded || recoveryEncoded != null) {
            mutationError = "Another task change is still awaiting server verification."
            return false
        }
        val encoded = TaskMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
        recoveryVerification = TaskRecoveryVerification.Unknown
        onMutationInProgressChanged(true)
        val saved = try {
            services.saveDurableMutationRecovery(session, accountScope, DurableMutationRecoveryKind.Tasks, encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            var recoveryReloadFailed = false
            recoveryEncoded = try {
                services.loadDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Tasks)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                recoveryReloadFailed = true
                recoveryLoaded = false
                null
            }
            mutationError = when {
                recoveryEncoded != null -> "Another task change is still awaiting server verification."
                recoveryReloadFailed -> "Task recovery storage could not be reloaded safely. Restart Tasks to verify it."
                else -> "The task change could not be recorded safely. Check local storage and try again."
            }
            onMutationInProgressChanged(recoveryEncoded != null || !recoveryLoaded)
            return false
        }
        recoveryEncoded = encoded
        return true
    }

    suspend fun clearRecovery(): Boolean {
        val expected = recoveryEncoded ?: return false
        val cleared = try {
            services.clearDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.Tasks,
                expected,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!cleared) {
            mutationError = "The task recovery record could not be cleared safely. Refresh and try again."
            return false
        }
        recoveryEncoded = null
        recoveryVerification = TaskRecoveryVerification.Unknown
        mutationError = null
        refreshError = null
        onMutationInProgressChanged(operations.busy)
        return true
    }

    LaunchedEffect(durableMutationInProgress) {
        onMutationInProgressChanged(durableMutationInProgress)
    }
    LaunchedEffect(unreadableRecovery) {
        if (!unreadableRecovery) unreadableRecoveryDiscardRequested = false
    }
    DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }

    LaunchedEffect(session, userId, services, loadAttempt) {
        operations.recover recovery@{
            recoveryLoaded = false
            recoveryVerification = TaskRecoveryVerification.Unknown
            try {
                recoveryEncoded = services.loadDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Tasks)
                recoveryLoaded = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                refreshError = "Task recovery storage could not be read securely."
                return@recovery
            }
            val currentPostcondition = recoveryEncoded?.let { decodeTaskMutationRecoveryState(it, accountScope) }
            recoveryVerification = currentPostcondition?.let { postcondition ->
                runCatchingPreservingCancellation {
                    postcondition.verify(
                        services.executeGroupwareDav(session, groupwareDavDetailRequest(postcondition.href)),
                    )
                }.getOrDefault(TaskRecoveryVerification.Unknown)
            } ?: TaskRecoveryVerification.Unknown
            val retained = state as? TasksLoadState.Ready
            if (retained == null) state = TasksLoadState.Loading else refreshing = true
            refreshError = null
            runCatchingPreservingCancellation {
                val discovery = services.executeGroupwareDav(
                    session,
                    groupwareDavCollectionDiscoveryRequest(groupwareCalendarHomeHref(userId)),
                )
                val calendars = parseGroupwareTaskCalendars(discovery)
                val loaded = loadGroupwareTaskCalendars(calendars) { request ->
                    services.executeGroupwareDav(session, request)
                }
                val tasks = loaded.tasks.sortedWith(compareBy<GroupwareTask> { it.completed }.thenBy {
                    it.due ?: "99999999"
                }.thenBy {
                    it.title.lowercase()
                })
                val partialFailureMessage = buildList {
                    loaded.failedCalendarNames.takeIf(List<String>::isNotEmpty)?.let { names ->
                        add(
                            "Some task lists could not be refreshed: ${names.joinToString()}. " +
                                "Other task lists remain available.",
                        )
                    }
                    if (loaded.concurrentlyDeletedObjectCount > 0) {
                        add(
                            "${loaded.concurrentlyDeletedObjectCount} task object changed during refresh; " +
                                "the remaining tasks are current.",
                        )
                    }
                    if (loaded.omittedObjectCount > 0) {
                        add(
                            "${loaded.omittedObjectCount} task objects were not retained because this refresh " +
                                "reached the safe in-memory task-data budget.",
                        )
                    }
                }.joinToString(" ").takeIf(String::isNotEmpty)
                TasksLoadState.Ready(
                    calendars = calendars,
                    tasks = tasks,
                    completedCalendarHrefs = loaded.completedCalendarHrefs,
                    partialFailureMessage = partialFailureMessage,
                )
            }.onSuccess { loaded ->
                state = loaded
                refreshError = loaded.partialFailureMessage
                if (currentPostcondition != null) {
                    when (recoveryVerification) {
                        TaskRecoveryVerification.Applied -> {
                            if (!clearRecovery()) return@onSuccess
                            selectedTaskSelection = null
                            creating = false
                            editing = false
                            deleting = null
                        }
                        TaskRecoveryVerification.Unapplied -> {
                            refreshError = "The server still has the previous task state. Keep that server version or retry verification."
                        }
                        TaskRecoveryVerification.Unknown -> {
                            refreshError = "The task result is still unknown. Refresh to verify it."
                        }
                    }
                }
            }.onFailure { failure ->
                val message = failure.message ?: "Could not load tasks."
                if (retained == null) state = TasksLoadState.Error(message) else refreshError = message
            }
            refreshing = false
        }
    }

    LaunchedEffect(navigationCommitInProgress) {
        if (navigationCommitInProgress) {
            creating = false
            editing = false
        }
    }
    val editorVisible = creating || editing
    LaunchedEffect(navigationRequest?.identity, editorVisible, durableMutationInProgress) {
        navigationRequest
            ?.takeIf { !editorVisible && !durableMutationInProgress }
            ?.let(onNavigationConfirmed)
    }

    val ready = state as? TasksLoadState.Ready
    val selectedTask = ready?.tasks?.firstOrNull { it.instanceId == selectedTaskSelection?.instanceId }
    LaunchedEffect(ready, selectedTaskSelection) {
        if (selectedTaskSelection?.let { ready?.confirmsSelectionRemoved(it) } == true) {
            selectedTaskSelection = null
            editing = false
            deleting = null
        }
    }
    val selectedTaskWritable = selectedTask?.let { task ->
        ready.calendars.any { calendar -> calendar.href == task.calendarHref && calendar.writable }
    } == true
    val selectedTaskDeleteSafe = selectedTask?.let(::isGroupwareTaskObjectDeleteSafe) == true
    LaunchedEffect(selectedTask?.instanceId, selectedTaskWritable) {
        if (selectedTask != null && !selectedTaskWritable) {
            editing = false
            if (deleting?.instanceId == selectedTask.instanceId) deleting = null
        }
    }
    val visibleTasks = remember(ready?.tasks, filter, query) {
        val needle = query.trim().lowercase()
        ready?.tasks.orEmpty().filter { task ->
            (filter == TaskFilter.All || filter == TaskFilter.Completed && task.completed ||
                filter == TaskFilter.Open && !task.completed) &&
                (needle.isBlank() || task.title.lowercase().contains(needle) ||
                    task.description?.lowercase()?.contains(needle) == true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tasks", fontWeight = FontWeight.SemiBold)
                        ready?.let { Text("${it.tasks.count { task -> !task.completed }} open") }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !interactionBlocked) {
                        Icon(NextcloudIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { if (!operations.busy) loadAttempt += 1 }, enabled = !operations.busy) {
                        Icon(NextcloudIcons.Refresh, contentDescription = "Refresh tasks")
                    }
                    IconButton(
                        onClick = { if (!interactionBlocked) creating = true },
                        enabled = !interactionBlocked && ready?.calendars?.any(GroupwareCalendar::writable) == true,
                    ) {
                        Icon(NextcloudIcons.Add, contentDescription = "Create task")
                    }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val statusMessage = if (unreadableRecovery) {
                "A saved task recovery record cannot be read. The server result is unknown; discard the record only after checking the task on another client."
            } else {
                refreshError
            }
            statusMessage?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        if (unreadableRecovery) {
                            TextButton(enabled = !operations.busy, onClick = { unreadableRecoveryDiscardRequested = true }) {
                                Text("Review recovery")
                            }
                        } else if (recoveryVerification == TaskRecoveryVerification.Unapplied) {
                            TextButton(
                                enabled = !operations.busy,
                                onClick = {
                                    scope.launch {
                                        operations.recover {
                                            if (recoveryVerification != TaskRecoveryVerification.Unapplied) return@recover
                                            if (clearRecovery()) {
                                                creating = false
                                                editing = false
                                                deleting = null
                                                loadAttempt += 1
                                            }
                                        }
                                    }
                                },
                            ) { Text("Keep server version") }
                        }
                        TextButton(enabled = !operations.busy, onClick = { if (!operations.busy) loadAttempt += 1 }) { Text("Retry") }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = boundedGroupwareTaskQuery(it) },
                label = { Text("Search tasks") },
                leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                TaskFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = filter == candidate,
                        onClick = { filter = candidate },
                        label = { Text(candidate.name) },
                    )
                }
            }
            when (val current = state) {
                TasksLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is TasksLoadState.Error -> TasksError(current.message) { if (!operations.busy) loadAttempt += 1 }
                is TasksLoadState.Ready -> LazyColumn(
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(visibleTasks, key = GroupwareTask::instanceId) { task ->
                        var menuExpanded by remember(task.instanceId) { mutableStateOf(false) }
                        val taskWritable = current.calendars.any { calendar ->
                            calendar.href == task.calendarHref && calendar.writable
                        }
                        val taskDeleteSafe = isGroupwareTaskObjectDeleteSafe(task)
                        Card(
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClickLabel = "Open ${task.title}",
                                onLongClickLabel = "Show actions for ${task.title}",
                                onClick = { selectedTaskSelection = task.selection() },
                                onLongClick = { menuExpanded = true },
                            ),
                            colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = task.completed, onCheckedChange = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        task.title,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    task.due?.let { due ->
                                        Text("Due ${due.displayTaskDueDate()}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(NextcloudIcons.More, contentDescription = "Actions for ${task.title}")
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("View details") },
                                            onClick = {
                                                menuExpanded = false
                                                selectedTaskSelection = task.selection()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Edit") },
                                            enabled = !interactionBlocked && taskWritable,
                                            onClick = {
                                                menuExpanded = false
                                                selectedTaskSelection = task.selection()
                                                editing = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            enabled = !interactionBlocked && taskWritable && taskDeleteSafe,
                                            onClick = {
                                                menuExpanded = false
                                                selectedTaskSelection = task.selection()
                                                deleting = task
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (visibleTasks.isEmpty()) item {
                        Text("No tasks match this view.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    selectedTask?.takeIf { !editing }?.let { task ->
        TaskDetailsDialog(
            task = task,
            writable = selectedTaskWritable,
            deleteSafe = selectedTaskDeleteSafe,
            interactionBlocked = interactionBlocked,
            error = mutationError,
            onDismiss = { selectedTaskSelection = null },
            onEdit = { editing = true },
            onDelete = { deleting = task },
        )
    }

    if ((creating || editing && selectedTaskWritable) && ready != null) {
        TaskEditorDialog(
            task = selectedTask.takeIf { editing },
            calendars = if (editing) {
                ready.calendars.filter { calendar -> calendar.href == selectedTask?.calendarHref }
            } else {
                ready.calendars.filter(GroupwareCalendar::writable)
            },
            mutationInProgress = interactionBlocked,
            error = mutationError,
            onDismiss = {
                creating = false
                editing = false
                if (selectedTask == null) selectedTaskSelection = null
            },
            onSave = save@{ draft, calendar, editStartEtag ->
                mutationError = null
                if (!calendar.writable) {
                    mutationError = "This task list is read-only."
                    return@save
                }
                val normalizedDraft = draft.normalized()
                val normalizedDue = normalizedDraft.compactDueDateOrNull()
                val uid = selectedTask?.uid ?: "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
                val href = selectedTask?.href ?: "${calendar.href}$uid.ics"
                val content = prepareGroupwareDavMutation(
                    onInvalid = { mutationError = "Review the task fields and try again." },
                ) {
                    selectedTask?.let { task ->
                        updateGroupwareTaskContent(
                            task,
                            normalizedDraft.title,
                            normalizedDue,
                            normalizedDraft.completed,
                            normalizedDraft.description,
                        )
                    } ?: createGroupwareTaskContent(
                        uid,
                        normalizedDraft.title,
                        normalizedDue,
                        normalizedDraft.completed,
                        normalizedDraft.description,
                    )
                } ?: return@save
                val request = prepareGroupwareDavMutation(
                    onInvalid = { mutationError = "The task cannot be changed safely. Refresh and try again." },
                ) {
                    GroupwareDavMutationSpec(
                        kind = GroupwareDavKind.Task,
                        mutation = if (selectedTask == null) {
                            GroupwareDavMutation.Create
                        } else {
                            GroupwareDavMutation.Update
                        },
                        objectHref = href,
                        etag = editStartEtag,
                        content = content,
                    ).toGroupwareDavRequest()
                } ?: return@save
                val postcondition = TaskMutationPostcondition.Upsert(
                    href = href,
                    calendarHref = calendar.href,
                    expectedUid = uid,
                    expectedRecurrenceId = selectedTask?.recurrenceId,
                    previousEtag = editStartEtag,
                    draft = normalizedDraft,
                    expectedDue = expectedGroupwareTaskDueAfterDateEdit(selectedTask, normalizedDue),
                )
                scope.launch {
                    operations.mutate mutation@{
                        if (!retainRecovery(postcondition)) return@mutation
                        try {
                            val response = services.executeGroupwareDav(session, request)
                            if (response.status !in 200..299) {
                                if (groupwareMutationResponseProvesRejection(response.status) && clearRecovery()) {
                                    mutationError = "Saving the task failed (HTTP ${response.status})."
                                } else {
                                    mutationError = "The task result is unknown. Refresh to verify it."
                                    loadAttempt += 1
                                }
                                return@mutation
                            }
                            creating = false
                            editing = false
                            loadAttempt += 1
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            mutationError = "The task result is unknown. Refresh to verify it."
                            loadAttempt += 1
                        }
                    }
                }
            },
        )
    }

    deleting?.takeIf { task ->
        selectedTaskDeleteSafe &&
            ready.calendars.any { calendar -> calendar.href == task.calendarHref && calendar.writable }
    }?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!interactionBlocked) deleting = null },
            title = { Text("Delete ${task.title}?") },
            text = { Text("This permanently removes the task from Nextcloud.") },
            dismissButton = {
                TextButton(enabled = !interactionBlocked, onClick = { deleting = null }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = !interactionBlocked,
                    onClick = delete@{
                        val request = prepareGroupwareDavMutation(
                            onInvalid = { mutationError = "The task cannot be deleted safely. Refresh and try again." },
                        ) {
                            GroupwareDavMutationSpec(
                                kind = GroupwareDavKind.Task,
                                mutation = GroupwareDavMutation.Delete,
                                objectHref = task.href,
                                etag = task.etag,
                            ).toGroupwareDavRequest()
                        } ?: return@delete
                        scope.launch {
                            operations.mutate mutation@{
                                if (!retainRecovery(TaskMutationPostcondition.Delete(task.href, task.etag))) return@mutation
                                try {
                                    val response = services.executeGroupwareDav(session, request)
                                    if (response.status !in 200..299 &&
                                        !groupwareDeleteResponseProvesAbsence(response.status)
                                    ) {
                                        if (groupwareMutationResponseProvesRejection(response.status) && clearRecovery()) {
                                            mutationError = "Deleting the task failed (HTTP ${response.status})."
                                        } else {
                                            mutationError = "The task result is unknown. Refresh to verify it."
                                            loadAttempt += 1
                                        }
                                        return@mutation
                                    }
                                    deleting = null
                                    selectedTaskSelection = null
                                    loadAttempt += 1
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (_: Exception) {
                                    mutationError = "The task result is unknown. Refresh to verify it."
                                    loadAttempt += 1
                                }
                            }
                        }
                    },
                ) { Text("Delete") }
            },
        )
    }

    if (unreadableRecovery && unreadableRecoveryDiscardRequested) {
        AlertDialog(
            onDismissRequest = { unreadableRecoveryDiscardRequested = false },
            title = { Text("Discard unreadable task recovery?") },
            text = {
                Text(
                    "The client cannot verify whether the last task change reached the server. " +
                        "Check the task from another client first. Discarding this record unlocks Tasks but cannot undo a server change.",
                )
            },
            dismissButton = {
                TextButton(onClick = { unreadableRecoveryDiscardRequested = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = !operations.busy,
                    onClick = {
                        scope.launch {
                            operations.recover {
                                if (!unreadableRecovery) return@recover
                                if (clearRecovery()) {
                                    unreadableRecoveryDiscardRequested = false
                                    creating = false
                                    editing = false
                                    deleting = null
                                    loadAttempt += 1
                                }
                            }
                        }
                    },
                ) { Text("Discard and unlock") }
            },
        )
    }
}

internal fun boundedGroupwareTaskQuery(value: String): String =
    value.take(MAX_GROUPWARE_TASK_QUERY_LENGTH)

internal const val MAX_GROUPWARE_TASK_QUERY_LENGTH = 256

@Composable
private fun TasksError(message: String, retry: () -> Unit) {
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

internal fun String.displayTaskDueDate(): String = if (length >= 8 && take(8).all(Char::isDigit)) {
    "${substring(6, 8)}-${substring(4, 6)}-${take(4)}"
} else {
    this
}
