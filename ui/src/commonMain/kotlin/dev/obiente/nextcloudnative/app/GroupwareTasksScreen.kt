package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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

private sealed interface TasksLoadState {
    data object Loading : TasksLoadState
    data class Ready(
        val calendars: List<GroupwareCalendar>,
        val tasks: List<GroupwareTask>,
        val partialFailureMessage: String? = null,
    ) : TasksLoadState
    data class Error(val message: String) : TasksLoadState
}

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
    var selectedTaskHref by rememberSaveable(accountScope) { mutableStateOf<String?>(null) }
    var creating by rememberSaveable(accountScope) { mutableStateOf(false) }
    var editing by rememberSaveable(accountScope) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GroupwareTask?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var mutationRunning by remember(accountScope) { mutableStateOf(false) }
    var recoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var recoveryEncoded by remember(accountScope, services) { mutableStateOf<String?>(null) }
    var recoveryVerification by remember(accountScope, services) {
        mutableStateOf(TaskRecoveryVerification.Unknown)
    }
    var filter by rememberSaveable(accountScope) { mutableStateOf(TaskFilter.Open) }
    var query by rememberSaveable(accountScope) { mutableStateOf("") }
    val recoveryPostcondition = remember(accountScope, recoveryEncoded) {
        recoveryEncoded?.let { decodeTaskMutationRecoveryState(it, accountScope) }
    }
    val durableMutationInProgress = !recoveryLoaded || mutationRunning || recoveryEncoded != null
    val interactionBlocked = mutationOrLinkCommitBlocksInteraction(
        durableMutationInProgress,
        navigationCommitInProgress,
    )
    val scope = rememberCoroutineScope()

    suspend fun retainRecovery(postcondition: TaskMutationPostcondition): Boolean {
        if (!recoveryLoaded || recoveryEncoded != null || mutationRunning) {
            mutationError = "Another task change is still awaiting server verification."
            return false
        }
        val encoded = TaskMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
        mutationRunning = true
        onMutationInProgressChanged(true)
        val saved = try {
            services.saveDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Tasks, encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            mutationError = "The task change could not be recorded safely. Check local storage and try again."
            mutationRunning = false
            onMutationInProgressChanged(false)
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
            mutationError = "The verified task recovery record could not be cleared safely. Refresh and try again."
            return false
        }
        recoveryEncoded = null
        recoveryVerification = TaskRecoveryVerification.Unknown
        mutationRunning = false
        mutationError = null
        refreshError = null
        onMutationInProgressChanged(false)
        return true
    }

    LaunchedEffect(accountScope, services, loadAttempt) {
        recoveryLoaded = false
        recoveryEncoded = null
        try {
            recoveryEncoded = services.loadDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.Tasks,
            )
            recoveryLoaded = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            refreshError = "Task recovery storage could not be read securely."
        }
    }

    LaunchedEffect(durableMutationInProgress) {
        onMutationInProgressChanged(durableMutationInProgress)
    }
    DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }

    LaunchedEffect(session, userId, loadAttempt, recoveryLoaded) {
        if (!recoveryLoaded) return@LaunchedEffect
        recoveryVerification = recoveryPostcondition?.let { postcondition ->
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
            TasksLoadState.Ready(
                calendars = calendars,
                tasks = tasks,
                partialFailureMessage = loaded.failedCalendarNames.takeIf(List<String>::isNotEmpty)?.let { names ->
                    "Some task lists could not be refreshed: ${names.joinToString()}. Other task lists remain available."
                },
            )
        }.onSuccess { loaded ->
            state = loaded
            refreshError = loaded.partialFailureMessage
            if (recoveryPostcondition != null) {
                when (recoveryVerification) {
                    TaskRecoveryVerification.Applied -> {
                        if (!clearRecovery()) return@onSuccess
                        selectedTaskHref = null
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
    val selectedTask = ready?.tasks?.firstOrNull { it.instanceId == selectedTaskHref }
    val selectedTaskWritable = selectedTask?.let { task ->
        ready.calendars.any { calendar -> calendar.href == task.calendarHref && calendar.writable }
    } == true
    val selectedTaskDeleteSafe = selectedTask?.let { task ->
        ready.tasks.count { candidate -> candidate.href == task.href } == 1
    } == true
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
                    IconButton(onClick = { loadAttempt += 1 }) {
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
            refreshError?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        if (recoveryVerification == TaskRecoveryVerification.Unapplied) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (clearRecovery()) {
                                            creating = false
                                            editing = false
                                            deleting = null
                                            loadAttempt += 1
                                        }
                                    }
                                },
                            ) { Text("Keep server version") }
                        }
                        TextButton(onClick = { loadAttempt += 1 }) { Text("Retry") }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
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
                is TasksLoadState.Error -> TasksError(current.message) { loadAttempt += 1 }
                is TasksLoadState.Ready -> LazyColumn(
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(visibleTasks, key = GroupwareTask::instanceId) { task ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { selectedTaskHref = task.instanceId },
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
                                Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
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
        AlertDialog(
            onDismissRequest = { if (!interactionBlocked) selectedTaskHref = null },
            title = { Text(task.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text(if (task.completed) "Completed" else "Open")
                    task.due?.let { Text("Due ${it.displayTaskDueDate()}") }
                    task.description?.let { Text(it) }
                    if (!selectedTaskWritable) {
                        Text("This task list is read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!selectedTaskDeleteSafe) {
                        Text(
                            "This is one component of a recurring task. Edit the selected component; deleting the shared calendar object is withheld.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !interactionBlocked && selectedTaskWritable,
                    onClick = { editing = true },
                ) { Text("Edit") }
            },
            dismissButton = {
                TextButton(
                    enabled = !interactionBlocked && selectedTaskWritable && selectedTaskDeleteSafe,
                    onClick = { deleting = task },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
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
                if (selectedTask == null) selectedTaskHref = null
            },
            onSave = save@{ draft, calendar ->
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
                        etag = selectedTask?.etag,
                        content = content,
                    ).toGroupwareDavRequest()
                } ?: return@save
                val postcondition = TaskMutationPostcondition.Upsert(
                    href = href,
                    calendarHref = calendar.href,
                    expectedUid = uid,
                    expectedRecurrenceId = selectedTask?.recurrenceId,
                    previousEtag = selectedTask?.etag,
                    draft = normalizedDraft,
                    expectedDue = expectedGroupwareTaskDueAfterDateEdit(selectedTask, normalizedDue),
                )
                scope.launch {
                    if (!retainRecovery(postcondition)) return@launch
                    try {
                        val response = services.executeGroupwareDav(session, request)
                        if (response.status !in 200..299) {
                            if (groupwareMutationResponseProvesRejection(response.status) && clearRecovery()) {
                                mutationError = "Saving the task failed (HTTP ${response.status})."
                            } else {
                                mutationError = "The task result is unknown. Refresh to verify it."
                                loadAttempt += 1
                            }
                            return@launch
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
                            if (!retainRecovery(TaskMutationPostcondition.Delete(task.href, task.etag))) return@launch
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
                                    return@launch
                                }
                                deleting = null
                                selectedTaskHref = null
                                loadAttempt += 1
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                mutationError = "The task result is unknown. Refresh to verify it."
                                loadAttempt += 1
                            }
                        }
                    },
                ) { Text("Delete") }
            },
        )
    }
}

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

@Composable
private fun TaskEditorDialog(
    task: GroupwareTask?,
    calendars: List<GroupwareCalendar>,
    mutationInProgress: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (TaskDraft, GroupwareCalendar) -> Unit,
) {
    val editorKey = task?.instanceId ?: "new-task"
    var title by rememberSaveable(editorKey) { mutableStateOf(task?.title.orEmpty()) }
    var dueDate by rememberSaveable(editorKey) {
        mutableStateOf(task?.due?.take(8)?.let { compact ->
            if (compact.length == 8) {
                "${compact.take(4)}-${compact.substring(4, 6)}-${compact.takeLast(2)}"
            } else {
                ""
            }
        }.orEmpty())
    }
    var description by rememberSaveable(editorKey) { mutableStateOf(task?.description.orEmpty()) }
    var completed by rememberSaveable(editorKey) { mutableStateOf(task?.completed == true) }
    var calendarHref by rememberSaveable(editorKey) {
        mutableStateOf(task?.calendarHref ?: calendars.firstOrNull()?.href)
    }
    val calendar = calendars.firstOrNull { it.href == calendarHref } ?: calendars.firstOrNull()
    val dateValid = dueDate.isBlank() || dueDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
        isValidGroupwareTaskDueDate(dueDate.replace("-", ""))
    AlertDialog(
        onDismissRequest = { if (!mutationInProgress) onDismiss() },
        title = { Text(if (task == null) "New task" else "Edit task") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                item {
                    OutlinedTextField(
                        title,
                        { title = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        dueDate,
                        { dueDate = it },
                        label = { Text("Due date") },
                        placeholder = { Text("YYYY-MM-DD") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = !dateValid,
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        description,
                        { description = it },
                        label = { Text("Description") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = completed,
                            onCheckedChange = { completed = it },
                            enabled = !mutationInProgress,
                        )
                        Text("Completed")
                    }
                }
                if (calendars.size > 1) item {
                    Text("Task list", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        calendars.forEach { candidate ->
                            FilterChip(
                                selected = candidate.href == calendar?.href,
                                onClick = { calendarHref = candidate.href },
                                label = { Text(candidate.displayName) },
                                enabled = !mutationInProgress,
                            )
                        }
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        dismissButton = {
            TextButton(enabled = !mutationInProgress, onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = !mutationInProgress && title.isNotBlank() && dateValid && calendar != null,
                onClick = {
                    onSave(TaskDraft(title, dueDate, description, completed), requireNotNull(calendar))
                },
            ) { Text("Save") }
        },
    )
}

private fun String.displayTaskDueDate(): String = if (length >= 8 && take(8).all(Char::isDigit)) {
    "${substring(6, 8)}-${substring(4, 6)}-${take(4)}"
} else {
    this
}
