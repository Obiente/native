package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRefreshBar
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

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
    var taskListFilterHref by rememberSaveable(accountScope) { mutableStateOf<String?>(null) }
    val taskListState = rememberLazyListState()
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
                groupwareTasksRefreshedState(calendars, loaded, retained)
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
                if (retained == null) state = TasksLoadState.Error(message) else {
                    state = retained.copy(completedCalendarHrefs = emptySet(), partialFailureMessage = message)
                    refreshError = message
                }
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
        ready?.canWrite(task.calendarHref) == true
    } == true
    val selectedTaskDeleteSafe = selectedTask?.let(::isGroupwareTaskObjectDeleteSafe) == true
    LaunchedEffect(selectedTask?.instanceId, selectedTaskWritable) {
        if (selectedTask != null && !selectedTaskWritable) {
            editing = false
            if (deleting?.instanceId == selectedTask.instanceId) deleting = null
        }
    }
    val filterCalendar = taskFilterCalendar(ready, taskListFilterHref)
    val visibleTasks = remember(ready?.tasks, filter, filterCalendar, query) {
        filterGroupwareTasks(ready?.tasks.orEmpty(), filter, filterCalendar, query)
    }
    val detailState = selectedTask?.let { task ->
        TaskDetailState(
            writable = selectedTaskWritable,
            deleteSafe = selectedTaskDeleteSafe,
            interactionBlocked = interactionBlocked,
            error = mutationError,
            onEdit = { editing = true },
            onDelete = { deleting = task },
        )
    }

    // Saves a new or changed task through the same guarded write as the editor: the list's
    // checkbox uses it to complete a task, keeping its other fields as they are.
    fun saveTask(target: GroupwareTask?, draft: TaskDraft, calendar: GroupwareCalendar, editStartEtag: String?, onSaved: () -> Unit) {
        val writable = ready ?: return
        mutationError = null
        if (!writable.canWrite(calendar.href)) {
            mutationError = "This task list is read-only."
            return
        }
        val normalizedDraft = draft.normalized()
        val normalizedDue = normalizedDraft.compactDueDateOrNull()
        val uid = target?.uid ?: "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
        val href = target?.href ?: "${calendar.href}$uid.ics"
        val content = prepareGroupwareDavMutation(
            onInvalid = { mutationError = "Review the task fields and try again." },
        ) {
            target?.let { task ->
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
        } ?: return
        val request = prepareGroupwareDavMutation(
            onInvalid = { mutationError = "The task cannot be changed safely. Refresh and try again." },
        ) {
            GroupwareDavMutationSpec(
                kind = GroupwareDavKind.Task,
                mutation = if (target == null) {
                    GroupwareDavMutation.Create
                } else {
                    GroupwareDavMutation.Update
                },
                objectHref = href,
                etag = editStartEtag,
                content = content,
            ).toGroupwareDavRequest()
        } ?: return
        val postcondition = TaskMutationPostcondition.Upsert(
            href = href,
            calendarHref = calendar.href,
            expectedUid = uid,
            expectedRecurrenceId = target?.recurrenceId,
            previousEtag = editStartEtag,
            draft = normalizedDraft,
            expectedDue = expectedGroupwareTaskDueAfterDateEdit(target, normalizedDue),
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
                    onSaved()
                    loadAttempt += 1
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    mutationError = "The task result is unknown. Refresh to verify it."
                    loadAttempt += 1
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val besideList = workspaceShowsDetailBesideList(maxWidth)
        val destinations = remember(ready) { taskListDestinations(ready) }
        val navigation = remember(destinations, filterCalendar) {
            NextcloudCollectionNavigationModel.create(
                destinations,
                if (destinations.isEmpty()) null else filterCalendar?.href ?: ALL_TASK_LISTS_DESTINATION_ID,
            )
        }
        NextcloudCollectionWorkspaceScaffold(
            model = navigation,
            mode = workspaceCollectionNavigationMode(maxWidth, destinations.size),
            workspaceLabel = "Tasks",
            contentTitle = filterCalendar?.displayName?.ifBlank { null } ?: "Tasks",
            contentSubtitle = ready?.countSummary,
            onBack = { if (!interactionBlocked) onBack() },
            hasHierarchyBack = false,
            onDestinationSelected = { destination ->
                taskListFilterHref = destination.id.takeUnless { it == ALL_TASK_LISTS_DESTINATION_ID }
            },
            destinationIcon = { NextcloudIcons.Task },
            headerActions = {
                IconButton(onClick = { if (!operations.busy) loadAttempt += 1 }, enabled = !operations.busy) {
                    Icon(NextcloudIcons.Refresh, contentDescription = "Refresh tasks")
                }
                IconButton(
                    onClick = { if (!interactionBlocked) creating = true },
                    enabled = !interactionBlocked && ready?.calendars?.any { ready.canWrite(it.href) } == true,
                ) {
                    Icon(NextcloudIcons.Add, contentDescription = "Create task")
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NextcloudRefreshBar(refreshing)
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
                            NextcloudFilterChip(
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
                        is TasksLoadState.Error -> WorkspaceLoadError(current.message) { if (!operations.busy) loadAttempt += 1 }
                        is TasksLoadState.Ready -> TaskList(
                            tasks = visibleTasks,
                            emptyMessage = current.emptyMessage,
                            selectedInstanceId = selectedTask?.instanceId,
                            listState = taskListState,
                            actions = TaskRowActions(
                                canEdit = { task -> !interactionBlocked && current.canWrite(task.calendarHref) },
                                canDelete = { task ->
                                    !interactionBlocked && current.canWrite(task.calendarHref) &&
                                        isGroupwareTaskObjectDeleteSafe(task)
                                },
                                canToggle = { task -> !interactionBlocked && current.canWrite(task.calendarHref) && task.etag != null },
                                onToggleDone = { task, done ->
                                    current.calendars.firstOrNull { it.href == task.calendarHref }?.let { calendar ->
                                        saveTask(task, task.toTaskDraft().copy(completed = done), calendar, task.etag) {}
                                    }
                                },
                                onOpen = { task -> selectedTaskSelection = task.selection() },
                                onEdit = { task ->
                                    selectedTaskSelection = task.selection()
                                    editing = true
                                },
                                onDelete = { task ->
                                    selectedTaskSelection = task.selection()
                                    deleting = task
                                },
                            ),
                        )
                    }
                }
                if (besideList) {
                    WorkspaceSideInspector(selectedTask?.let { it to requireNotNull(detailState) }, { (task, _) -> task.instanceId }) {
                        (task, detail) -> TaskInspector(task, detail)
                    }
                }
            }
        }
        if (!besideList && !editing) {
            selectedTask?.let { task ->
                TaskDetailOverlay(task, requireNotNull(detailState), onDismiss = { selectedTaskSelection = null })
            }
        }
    }

    if ((creating || editing && selectedTaskWritable) && ready != null) {
        TaskEditorDialog(
            task = selectedTask.takeIf { editing },
            calendars = if (editing) {
                ready.calendars.filter { calendar -> calendar.href == selectedTask?.calendarHref }
            } else {
                ready.calendars.filter { ready.canWrite(it.href) }
                    .sortedByDescending { calendar -> calendar.href == filterCalendar?.href }
            },
            mutationInProgress = interactionBlocked,
            error = mutationError,
            onDismiss = {
                creating = false
                editing = false
                if (selectedTask == null) selectedTaskSelection = null
            },
            onSave = { draft, calendar, editStartEtag ->
                saveTask(selectedTask, draft, calendar, editStartEtag) { creating = false; editing = false }
            },
        )
    }

    deleting?.takeIf { task ->
        selectedTaskDeleteSafe &&
            ready?.canWrite(task.calendarHref) == true
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

internal fun String.displayTaskDueDate(): String = if (length >= 8 && take(8).all(Char::isDigit)) {
    "${substring(6, 8)}-${substring(4, 6)}-${take(4)}"
} else {
    this
}
