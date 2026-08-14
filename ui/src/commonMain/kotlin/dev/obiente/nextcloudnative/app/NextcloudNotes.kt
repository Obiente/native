package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import com.mikepenz.markdown.m3.Markdown
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private enum class NoteViewMode { Edit, Preview }

internal fun String?.isUsableNoteDeletionEtag(): Boolean =
    !isNullOrBlank() && length <= 4_096 && none(Char::isISOControl)

@Serializable
internal data class NoteDeletionRecoveryState(
    val accountScope: String,
    val noteId: Long,
    val originalEtag: String? = null,
    val originalPreconditionRecorded: Boolean = false,
) {
    init {
        require(accountScope.isCanonicalGroupwareMutationAccountScope())
        require(noteId >= 0L)
        require(originalEtag == null || originalEtag.isUsableNoteDeletionEtag())
        require(originalPreconditionRecorded || originalEtag == null)
        require(!originalPreconditionRecorded || originalEtag.isUsableNoteDeletionEtag())
    }
}

private val noteDeletionRecoveryJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun NoteDeletionRecoveryState.encodeForDurableStorage(): String =
    noteDeletionRecoveryJson.encodeToString(this)

internal fun decodeNoteDeletionRecoveryState(
    encoded: String,
    expectedAccountScope: String,
): NoteDeletionRecoveryState? = runCatching {
    noteDeletionRecoveryJson.decodeFromString<NoteDeletionRecoveryState>(encoded)
}.getOrNull()?.takeIf { recovery -> recovery.accountScope == expectedAccountScope }

@Composable
internal fun NextcloudNotesScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
    onOpenNote: (NextcloudNote) -> Unit,
    onMutationInProgressChanged: (Boolean) -> Unit = {},
) {
    val accountScope = remember(session.serverUrl, session.loginName) {
        durableMutationAccountScope(session)
    }
    var deletionRecoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var deletionRecoveryState by remember(accountScope, services) { mutableStateOf<String?>(null) }
    val deletionRecovery = remember(accountScope, deletionRecoveryState) {
        deletionRecoveryState?.let { decodeNoteDeletionRecoveryState(it, accountScope) }
    }
    var notes by remember(session.serverUrl, session.loginName) {
        mutableStateOf(sharedNextcloudNotesCache.list(session))
    }
    var query by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var currentPath by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var createNoteInPath by remember(session) { mutableStateOf<String?>(null) }
    var renameFolder by remember(session) { mutableStateOf<NativeNoteFolder?>(null) }
    var deleteFolder by remember(session) { mutableStateOf<NativeNoteFolder?>(null) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(session) { mutableStateOf(0) }
    var refreshing by remember(session) { mutableStateOf(false) }
    var showRecoveryOptions by remember(accountScope) { mutableStateOf(false) }
    var recoveryResetInProgress by remember(accountScope) { mutableStateOf(false) }
    var listMutationInProgress by remember(session) { mutableStateOf(false) }
    var createdNoteToOpen by remember(session) { mutableStateOf<NextcloudNote?>(null) }
    val scope = rememberCoroutineScope()
    val recoveryMutationInProgress = !deletionRecoveryLoaded || deletionRecoveryState != null
    val mutationInProgress = recoveryMutationInProgress || listMutationInProgress

    LaunchedEffect(accountScope, services, loadAttempt) {
        deletionRecoveryLoaded = false
        deletionRecoveryState = null
        try {
            deletionRecoveryState = services.loadDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.NoteDeletion,
            )
            deletionRecoveryLoaded = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = "Note recovery storage could not be read securely. Check local storage and retry."
        }
    }

    LaunchedEffect(accountScope, deletionRecoveryLoaded, deletionRecoveryState, deletionRecovery) {
        if (deletionRecoveryLoaded && deletionRecoveryState != null && deletionRecovery == null) {
            error = "The previous note-deletion recovery record cannot be read. Writes remain blocked."
            showRecoveryOptions = true
        }
    }

    LaunchedEffect(mutationInProgress, createdNoteToOpen) {
        onMutationInProgressChanged(mutationInProgress)
        if (!mutationInProgress) {
            createdNoteToOpen?.let { created ->
                createdNoteToOpen = null
                onOpenNote(created)
            }
        }
    }
    LaunchedEffect(recoveryMutationInProgress) {
        if (recoveryMutationInProgress) {
            createNoteInPath = null
            renameFolder = null
            deleteFolder = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }

    LaunchedEffect(session, loadAttempt, deletionRecoveryLoaded, deletionRecoveryState) {
        if (!deletionRecoveryLoaded) return@LaunchedEffect
        error = null
        refreshing = true
        val cachedEtag = notes?.let { sharedNextcloudNotesCache.listEtag(session) }
        runCatching {
            when (val result = services.listNotesConditionally(session, cachedEtag)) {
                is NextcloudConditionalRead.Modified -> {
                    notes = result.value
                    sharedNextcloudNotesCache.storeList(session, result.value, result.responseEtag)
                }
                NextcloudConditionalRead.NotModified -> Unit
            }
            deletionRecovery?.let { recovery ->
                val expectedEncoded = deletionRecoveryState ?: return@let
                when (val presence = services.inspectNotePresence(session, recovery.noteId)) {
                    NextcloudNotePresence.Absent -> {
                        if (services.clearDurableMutationRecovery(
                                accountScope,
                                DurableMutationRecoveryKind.NoteDeletion,
                                expectedEncoded,
                            )
                        ) {
                            deletionRecoveryState = null
                            sharedNextcloudNotesCache.remove(session, recovery.noteId)
                        } else {
                            error = "The verified note-deletion recovery record could not be cleared safely. Refreshing the current pending change."
                            loadAttempt += 1
                        }
                    }
                    is NextcloudNotePresence.Present -> {
                        sharedNextcloudNotesCache.storeDetail(session, presence.note)
                        onOpenNote(presence.note)
                    }
                }
            }
        }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not load Notes."
                if (deletionRecoveryState != null) showRecoveryOptions = true
            }
        refreshing = false
    }
    PlatformBackHandler(
        enabled = currentPath.isNotEmpty(),
        onBack = { currentPath = noteFolderParent(currentPath) },
    )

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        NotesHeader(
            title = currentPath.substringAfterLast('/').ifBlank { "Notes" },
            subtitle = notes?.let { values ->
                val location = buildNativeNotesLocation(values, currentPath)
                "${location.notes.size} notes · ${location.folders.size} folders"
            },
            onBack = {
                if (currentPath.isNotEmpty()) currentPath = noteFolderParent(currentPath) else onBack()
            },
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { createNoteInPath = currentPath },
                        enabled = !mutationInProgress,
                    ) {
                        Icon(NextcloudIcons.Add, contentDescription = "Create note")
                    }
                    IconButton(onClick = { loadAttempt += 1 }, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(NextcloudIcons.Refresh, contentDescription = "Refresh notes")
                        }
                    }
                }
            },
        )
        when {
            error != null && notes == null -> NotesCenteredState(
                title = "Could not load Notes",
                detail = requireNotNull(error),
                action = "Retry",
                onAction = { loadAttempt += 1 },
            )
            notes == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            notes?.isEmpty() == true -> NotesCenteredState(
                title = if (error == null) "No notes yet" else "Could not refresh Notes",
                detail = error ?: "Notes created on your server will appear here.",
                action = if (error != null) "Retry" else null,
                onAction = if (error != null) ({ loadAttempt += 1 }) else null,
            )
            else -> {
                val allNotes = requireNotNull(notes)
                val location = remember(allNotes, currentPath) {
                    buildNativeNotesLocation(allNotes, currentPath)
                }
                val visibleNotes = remember(allNotes, location, query) {
                    val candidates = if (query.isBlank()) location.notes else allNotes
                    candidates.filter { note ->
                        query.isBlank() || note.title.contains(query, ignoreCase = true) ||
                            note.category.contains(query, ignoreCase = true) ||
                            note.content?.contains(query, ignoreCase = true) == true
                    }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    error?.let { refreshError ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(
                                start = NextcloudSpacing.XLarge,
                                end = NextcloudSpacing.XLarge,
                                top = NextcloudSpacing.Medium,
                            ),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(NextcloudRadii.Medium),
                        ) {
                            Text(
                                "Showing saved results. $refreshError",
                                modifier = Modifier.padding(NextcloudSpacing.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = NextcloudSpacing.XLarge,
                            vertical = NextcloudSpacing.Medium,
                        ),
                        leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                        label = { Text("Search notes") },
                        singleLine = true,
                    )
                    if (query.isBlank()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(
                                start = NextcloudSpacing.XLarge,
                                end = NextcloudSpacing.XLarge,
                                bottom = NextcloudSpacing.XXLarge,
                            ),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        ) {
                            item {
                                NotesBreadcrumbs(location.breadcrumbs) { destination ->
                                    currentPath = destination
                                }
                            }
                            items(location.folders, key = NativeNoteFolder::path) { folder ->
                                NoteFolderCard(
                                    folder = folder,
                                    mutationEnabled = !mutationInProgress,
                                    onOpen = { currentPath = folder.path },
                                    onRename = { renameFolder = folder },
                                    onDelete = { deleteFolder = folder },
                                )
                            }
                            items(visibleNotes, key = NextcloudNote::id) { note ->
                                NoteCard(
                                    note = note,
                                    enabled = !mutationInProgress,
                                    onClick = { onOpenNote(note) },
                                )
                            }
                            if (location.folders.isEmpty() && visibleNotes.isEmpty()) {
                                item {
                                    Text(
                                        "This folder is empty. Create a Markdown note here.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(NextcloudSpacing.Large),
                                    )
                                }
                            }
                        }
                    } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(
                            start = NextcloudSpacing.XLarge,
                            end = NextcloudSpacing.XLarge,
                            bottom = NextcloudSpacing.XXLarge,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        items(visibleNotes, key = NextcloudNote::id) { note ->
                            NoteCard(
                                note = note,
                                enabled = !mutationInProgress,
                                onClick = { onOpenNote(note) },
                            )
                        }
                    }
                    }
                }
            }
        }
    }
    createNoteInPath?.takeIf { !recoveryMutationInProgress }?.let { category ->
        CreateNoteDialog(
            category = category,
            services = services,
            session = session,
            onDismiss = { createNoteInPath = null },
            onCreated = { created ->
                notes = (notes.orEmpty() + created).distinctBy(NextcloudNote::id)
                sharedNextcloudNotesCache.storeList(session, notes.orEmpty())
                sharedNextcloudNotesCache.storeDetail(session, created)
                createNoteInPath = null
                createdNoteToOpen = created
            },
            onSubmittingChanged = { inProgress ->
                listMutationInProgress = inProgress
                if (inProgress) onMutationInProgressChanged(true)
            },
        )
    }
    renameFolder?.takeIf { !recoveryMutationInProgress }?.let { folder ->
        RenameNoteFolderDialog(
            folder = folder,
            services = services,
            session = session,
            onDismiss = { renameFolder = null },
            onReconciled = { refreshed ->
                notes = refreshed
                sharedNextcloudNotesCache.storeList(session, refreshed)
            },
            onRenamed = { destination ->
                val oldPrefix = folder.path + "/"
                notes = notes.orEmpty().map { note ->
                    when {
                        note.category == folder.path -> note.copy(category = destination)
                        note.category.startsWith(oldPrefix) ->
                            note.copy(category = destination + "/" + note.category.removePrefix(oldPrefix))
                        else -> note
                    }
                }
                sharedNextcloudNotesCache.storeList(session, notes.orEmpty())
                currentPath = destination
                renameFolder = null
            },
            onSubmittingChanged = { inProgress ->
                listMutationInProgress = inProgress
                if (inProgress) onMutationInProgressChanged(true)
            },
        )
    }
    deleteFolder?.takeIf { !recoveryMutationInProgress }?.let { folder ->
        DeleteNoteFolderDialog(
            folder = folder,
            services = services,
            session = session,
            onDismiss = { deleteFolder = null },
            onReconciled = { refreshed ->
                notes = refreshed
                sharedNextcloudNotesCache.storeList(session, refreshed)
            },
            onDeleted = {
                val prefix = folder.path + "/"
                notes = notes.orEmpty().filterNot { note ->
                    note.category == folder.path || note.category.startsWith(prefix)
                }
                sharedNextcloudNotesCache.storeList(session, notes.orEmpty())
                if (currentPath == folder.path || currentPath.startsWith(prefix)) {
                    currentPath = noteFolderParent(folder.path)
                }
                deleteFolder = null
            },
            onSubmittingChanged = { inProgress ->
                listMutationInProgress = inProgress
                if (inProgress) onMutationInProgressChanged(true)
            },
        )
    }
    if (showRecoveryOptions && deletionRecoveryState != null) {
        DurableMutationRecoveryDialog(
            title = "Resolve note recovery",
            recordReadable = deletionRecovery != null,
            resetting = recoveryResetInProgress,
            onCheckAgain = {
                showRecoveryOptions = false
                loadAttempt += 1
            },
            onReset = reset@{
                if (!recoveryResetInProgress) {
                    val expectedEncoded = deletionRecoveryState ?: return@reset
                    recoveryResetInProgress = true
                    scope.launch {
                        val cleared = try {
                            services.clearDurableMutationRecovery(
                                accountScope,
                                DurableMutationRecoveryKind.NoteDeletion,
                                expectedEncoded,
                            )
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            false
                        }
                        if (cleared) {
                            deletionRecoveryState = null
                            error = null
                            showRecoveryOptions = false
                        } else {
                            error = "The note recovery record could not be reset safely. Refreshing the current pending change."
                            loadAttempt += 1
                        }
                        recoveryResetInProgress = false
                    }
                }
            },
            onDismiss = { showRecoveryOptions = false },
        )
    }
}

@Composable
private fun NotesBreadcrumbs(
    breadcrumbs: List<NativeNoteBreadcrumb>,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, breadcrumb ->
            if (index > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(breadcrumb.path) }) {
                Text(breadcrumb.label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NoteFolderCard(
    folder: NativeNoteFolder,
    mutationEnabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(folder.path) { mutableStateOf(false) }
    val actions = listOf(
        NextcloudCardAction(label = "Rename", enabled = mutationEnabled, onClick = onRename),
        NextcloudCardAction(label = "Delete", enabled = mutationEnabled, destructive = true, onClick = onDelete),
    )
    Card(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onOpen,
            onShowActions = { menuExpanded = true },
            openLabel = "Open ${folder.name}",
            actionsLabel = "Show actions for ${folder.name}",
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    NextcloudIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(folder.descendantNoteCount)
                        append(if (folder.descendantNoteCount == 1) " note" else " notes")
                        if (folder.directNoteCount != folder.descendantNoteCount) {
                            append(" · ")
                            append(folder.directNoteCount)
                            append(" here")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NextcloudCardOverflow(
                itemLabel = folder.name,
                actions = actions,
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            )
            Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${folder.name}")
        }
    }
}

@Composable
private fun CreateNoteDialog(
    category: String,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onDismiss: () -> Unit,
    onCreated: (NextcloudNote) -> Unit,
    onSubmittingChanged: (Boolean) -> Unit,
) {
    var title by remember(category) { mutableStateOf("") }
    var content by remember(category) { mutableStateOf("") }
    var submitting by remember(category) { mutableStateOf(false) }
    var error by remember(category) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(category) {
        onDispose { onSubmittingChanged(false) }
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("New note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                if (category.isNotBlank()) {
                    Text(
                        "Folder: $category",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    enabled = !submitting,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Markdown") },
                    minLines = 4,
                    enabled = !submitting,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && !submitting,
                onClick = {
                    submitting = true
                    onSubmittingChanged(true)
                    error = null
                    scope.launch {
                        try {
                            onCreated(services.createNote(session, title, content, category))
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = failure.message ?: "Could not create the note."
                        } finally {
                            submitting = false
                            onSubmittingChanged(false)
                        }
                    }
                },
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@Composable
private fun RenameNoteFolderDialog(
    folder: NativeNoteFolder,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onDismiss: () -> Unit,
    onReconciled: (List<NextcloudNote>) -> Unit,
    onRenamed: (String) -> Unit,
    onSubmittingChanged: (Boolean) -> Unit,
) {
    var name by remember(folder.path) { mutableStateOf(folder.name) }
    var submitting by remember(folder.path) { mutableStateOf(false) }
    var error by remember(folder.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(folder.path) {
        onDispose { onSubmittingChanged(false) }
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Rename folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    enabled = !submitting,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && name != folder.name && !submitting,
                onClick = {
                    val destination = runCatching { noteFolderRenameTarget(folder.path, name) }
                        .onFailure { error = it.message }
                        .getOrNull() ?: return@Button
                    submitting = true
                    onSubmittingChanged(true)
                    scope.launch {
                        try {
                            services.renameNoteCategory(session, folder.path, destination)
                            onRenamed(destination)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            (failure as? PartialNoteFolderMutationException)
                                ?.refreshedSummaries
                                ?.let(onReconciled)
                            error = failure.message ?: "Could not rename the folder."
                        } finally {
                            submitting = false
                            onSubmittingChanged(false)
                        }
                    }
                },
            ) { Text(if (submitting) "Renaming..." else "Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteNoteFolderDialog(
    folder: NativeNoteFolder,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onDismiss: () -> Unit,
    onReconciled: (List<NextcloudNote>) -> Unit,
    onDeleted: () -> Unit,
    onSubmittingChanged: (Boolean) -> Unit,
) {
    var submitting by remember(folder.path) { mutableStateOf(false) }
    var error by remember(folder.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(folder.path) {
        onDispose { onSubmittingChanged(false) }
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Delete ${folder.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(
                    "This deletes the folder and all ${folder.descendantNoteCount} notes inside it. " +
                        "This may not be reversible.",
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting,
                onClick = {
                    submitting = true
                    onSubmittingChanged(true)
                    scope.launch {
                        try {
                            services.deleteNoteCategory(session, folder.path)
                            onDeleted()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            (failure as? PartialNoteFolderMutationException)
                                ?.refreshedSummaries
                                ?.let(onReconciled)
                            error = failure.message ?: "Could not delete the folder."
                        } finally {
                            submitting = false
                            onSubmittingChanged(false)
                        }
                    }
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(if (submitting) "Deleting..." else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@Composable
private fun NoteCard(note: NextcloudNote, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    if (note.favorite) NextcloudIcons.Favorite else NextcloudIcons.app("notes"),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val markdown = remember(note.content) { note.markdownMetadata() }
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                markdown.preview?.let { preview ->
                    Text(
                        preview.trimStart('#', ' '),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val metadata = listOfNotNull(
                    note.category.takeIf(String::isNotBlank),
                    note.internalPath?.substringAfterLast('.')?.takeIf(String::isNotBlank)?.uppercase(),
                    "${markdown.wordCount} words".takeIf { note.content != null },
                    "${markdown.completedTasks}/${markdown.totalTasks} tasks".takeIf { markdown.totalTasks > 0 },
                    note.modified.takeIf { it > 0L }?.let { "Updated ${formatNoteModified(it)}" },
                    "Shared".takeIf { note.isShared },
                    "Read only".takeIf { note.readOnly },
                ).joinToString(" · ")
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${note.title}")
        }
    }
}

private fun formatNoteModified(epochSeconds: Long): String = runCatching {
    Instant.fromEpochSeconds(epochSeconds).toString().replace('T', ' ').take(16)
}.getOrDefault("recently")

@Composable
internal fun NextcloudNoteEditor(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    note: NextcloudNote,
    onBack: () -> Unit,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    onMutationInProgressChanged: (Boolean) -> Unit = {},
) {
    val accountKey = remember(session.serverUrl, session.loginName) {
        session.serverUrl.trimEnd('/').lowercase() + '\u0000' + session.loginName
    }
    val accountScope = remember(session.serverUrl, session.loginName) {
        durableMutationAccountScope(session)
    }
    var deletionRecoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var deletionRecoveryState by remember(accountScope, services) { mutableStateOf<String?>(null) }
    val deletionRecovery = remember(accountScope, deletionRecoveryState) {
        deletionRecoveryState?.let { decodeNoteDeletionRecoveryState(it, accountScope) }
    }
    val cachedNote = remember(accountKey, note.id) { sharedNextcloudNotesCache.detail(session, note.id) }
    val initialNote = cachedNote ?: note
    var loaded by remember(accountKey, note.id) { mutableStateOf(initialNote) }
    var contentAvailable by rememberSaveable(accountKey, note.id) {
        mutableStateOf(initialNote.content != null)
    }
    var draftInitialized by rememberSaveable(accountKey, note.id) {
        mutableStateOf(initialNote.content != null)
    }
    var originalContent by rememberSaveable(accountKey, note.id) {
        mutableStateOf(initialNote.content.orEmpty())
    }
    var originalTitle by rememberSaveable(accountKey, note.id) { mutableStateOf(initialNote.title) }
    var title by rememberSaveable(accountKey, note.id) { mutableStateOf(initialNote.title) }
    var content by rememberSaveable(accountKey, note.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialNote.content.orEmpty()))
    }
    var originalCategory by rememberSaveable(accountKey, note.id) { mutableStateOf(initialNote.category) }
    var category by rememberSaveable(accountKey, note.id) { mutableStateOf(initialNote.category) }
    var originalFavorite by rememberSaveable(accountKey, note.id) { mutableStateOf(initialNote.favorite) }
    var favorite by rememberSaveable(accountKey, note.id) { mutableStateOf(initialNote.favorite) }
    var etag by rememberSaveable(accountKey, note.id) { mutableStateOf(initialNote.etag) }
    var loadError by remember(note.id, session) { mutableStateOf<String?>(null) }
    var saveError by remember(note.id, session) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(note.id, session) { mutableStateOf(0) }
    var saving by remember(note.id, session) { mutableStateOf(false) }
    var deleting by remember(note.id, session) { mutableStateOf(false) }
    var deleteError by remember(note.id, session) { mutableStateOf<String?>(null) }
    var refreshing by remember(note.id, session) { mutableStateOf(false) }
    var showDiscardConfirmation by remember(note.id, session) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(note.id, session) { mutableStateOf(false) }
    var showRecoveryOptions by remember(accountScope) { mutableStateOf(false) }
    var recoveryResetInProgress by remember(accountScope) { mutableStateOf(false) }
    var viewMode by rememberSaveable(accountKey, note.id) { mutableStateOf(NoteViewMode.Edit) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accountScope, services, loadAttempt) {
        deletionRecoveryLoaded = false
        deletionRecoveryState = null
        try {
            deletionRecoveryState = services.loadDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.NoteDeletion,
            )
            deletionRecoveryLoaded = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            val message = "Note recovery storage could not be read securely. Check local storage and retry."
            deleteError = message
            saveError = message
        }
    }

    LaunchedEffect(accountScope, deletionRecoveryLoaded, deletionRecoveryState, deletionRecovery) {
        if (deletionRecoveryLoaded && deletionRecoveryState != null && deletionRecovery == null) {
            deleteError = "The previous note-deletion recovery record cannot be read. Writes remain blocked."
            showRecoveryOptions = true
        }
    }

    LaunchedEffect(note.id, deletionRecoveryLoaded, deletionRecovery?.noteId) {
        if (!deletionRecoveryLoaded) return@LaunchedEffect
        val recovery = deletionRecovery ?: return@LaunchedEffect
        if (deleting) return@LaunchedEffect
        if (recovery.noteId != note.id) {
            navigateAfterReleasingMutationGuard(
                onMutationInProgressChanged = onMutationInProgressChanged,
                onNavigate = onBack,
            )
            return@LaunchedEffect
        }
        showDeleteConfirmation = true
        deleting = true
        try {
            val expectedEncoded = deletionRecoveryState ?: return@LaunchedEffect
            when (val presence = services.inspectNotePresence(session, recovery.noteId)) {
                NextcloudNotePresence.Absent -> {
                    if (services.clearDurableMutationRecovery(
                            accountScope,
                            DurableMutationRecoveryKind.NoteDeletion,
                            expectedEncoded,
                        )
                    ) {
                        deletionRecoveryState = null
                        sharedNextcloudNotesCache.remove(session, recovery.noteId)
                        completeVerifiedNoteDeletion(
                            onDeletingChanged = { deleting = it },
                            onMutationInProgressChanged = onMutationInProgressChanged,
                            onBack = onBack,
                        )
                    } else {
                        deleteError = "The verified note-deletion recovery record could not be cleared safely. Refreshing the current pending change."
                        loadAttempt += 1
                    }
                }
                is NextcloudNotePresence.Present -> {
                    sharedNextcloudNotesCache.storeDetail(session, presence.note)
                    deleteError = "The previous deletion was not confirmed by the server. Retry it before leaving this note."
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            deleteError = failure.message ?: "Could not verify the previous note deletion."
        } finally {
            deleting = false
        }
    }

    LaunchedEffect(note.id, session, loadAttempt) {
        loadError = null
        refreshing = true
        val expectedEtag = loaded.content?.let { loaded.etag }
        runCatching { services.loadNoteConditionally(session, note.id, expectedEtag) }
            .onSuccess { result ->
                if (result is NextcloudConditionalRead.NotModified) return@onSuccess
                val fullNote = (result as NextcloudConditionalRead.Modified).value
                sharedNextcloudNotesCache.storeDetail(session, fullNote)
                val preserveDraft = title != originalTitle || noteDraftIsDirty(
                    initialized = draftInitialized,
                    content = content.text,
                    originalContent = originalContent,
                    category = category,
                    originalCategory = originalCategory,
                    favorite = favorite,
                    originalFavorite = originalFavorite,
                )
                loaded = fullNote
                contentAvailable = true
                if (!preserveDraft) {
                    val serverContent = fullNote.content.orEmpty()
                    originalContent = serverContent
                    content = TextFieldValue(serverContent)
                    originalTitle = fullNote.title
                    title = fullNote.title
                    originalCategory = fullNote.category
                    category = fullNote.category
                    originalFavorite = fullNote.favorite
                    favorite = fullNote.favorite
                    etag = fullNote.etag
                    draftInitialized = true
                }
            }
            .onFailure { loadError = it.message ?: "Could not load this note." }
        refreshing = false
    }

    val dirty = title != originalTitle || noteDraftIsDirty(
        initialized = draftInitialized,
        content = content.text,
        originalContent = originalContent,
        category = category,
        originalCategory = originalCategory,
        favorite = favorite,
        originalFavorite = originalFavorite,
    )
    val readOnly = loaded.readOnly
    val mutationInProgress = !deletionRecoveryLoaded || saving || deleting || deletionRecoveryState != null
    val deletionPreconditionAvailable = if (deletionRecoveryState == null) {
        loaded.etag.isUsableNoteDeletionEtag()
    } else {
        deletionRecovery?.let { recovery ->
            recovery.originalPreconditionRecorded && recovery.originalEtag.isUsableNoteDeletionEtag()
        } == true
    }
    LaunchedEffect(mutationInProgress) {
        onMutationInProgressChanged(mutationInProgress)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }
    val contentBytes = remember(content.text) { content.text.utf8Size() }
    val previewAvailable = contentBytes <= MAX_NOTE_MARKDOWN_PREVIEW_BYTES
    val folderOptions = remember(accountKey, note.id) {
        sharedNextcloudNotesCache.list(session).orEmpty()
            .mapNotNull { candidate ->
                runCatching { normalizeNoteCategory(candidate.category) }.getOrNull()?.takeIf(String::isNotBlank)
            }
            .distinct()
            .sortedBy(String::lowercase)
    }
    fun requestBack() {
        if (mutationInProgress) return
        if (dirty) showDiscardConfirmation = true else onBack()
    }
    LaunchedEffect(navigationRequest?.identity, mutationInProgress) {
        navigationRequest?.let { request ->
            if (!mutationInProgress) {
                if (dirty) showDiscardConfirmation = true else onNavigationConfirmed(request)
            }
        }
    }
    fun saveNote() {
        if (!dirty || readOnly || mutationInProgress || contentBytes > MAX_NOTE_BYTES) return
        saving = true
        saveError = null
        scope.launch {
            runCatching {
                services.updateNote(
                    session,
                    note.id,
                    content.text,
                    category,
                    favorite,
                    etag,
                    title = title,
                )
            }.onSuccess { saved ->
                val savedContent = saved.content ?: content.text
                sharedNextcloudNotesCache.storeDetail(session, saved.copy(content = savedContent))
                loaded = saved
                originalTitle = saved.title
                title = saved.title
                originalContent = savedContent
                if (savedContent != content.text) content = TextFieldValue(savedContent)
                originalCategory = saved.category
                category = saved.category
                originalFavorite = saved.favorite
                favorite = saved.favorite
                etag = saved.etag
            }.onFailure { failure ->
                saveError = failure.message ?: "Could not save this note."
            }
            saving = false
        }
    }
    PlatformBackHandler(enabled = true, onBack = ::requestBack)

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        NotesHeader(
            title = title,
            subtitle = if (readOnly) "Read only" else if (dirty) "Unsaved changes" else "Markdown note",
            onBack = ::requestBack,
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { loadAttempt += 1 }, enabled = !refreshing && !mutationInProgress) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(NextcloudIcons.Refresh, contentDescription = "Refresh note")
                        }
                    }
                    IconButton(
                        onClick = { favorite = !favorite },
                        enabled = contentAvailable && !readOnly && !mutationInProgress,
                    ) {
                        Icon(
                            if (favorite) NextcloudIcons.Favorite else NextcloudIcons.FavoriteBorder,
                            contentDescription = if (favorite) "Remove favorite" else "Add favorite",
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            deleteError = if (loaded.etag.isUsableNoteDeletionEtag()) {
                                null
                            } else {
                                "Refresh this note before deleting it so the server version can be verified."
                            }
                            showDeleteConfirmation = true
                        },
                        enabled = !readOnly && !mutationInProgress,
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
        when {
            loadError != null && !contentAvailable -> NotesCenteredState(
                title = "Could not open note",
                detail = requireNotNull(loadError),
                action = "Retry",
                onAction = { loadAttempt += 1 },
            )
            !contentAvailable -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Title") },
                        singleLine = true,
                        enabled = !readOnly && !mutationInProgress,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Folder") },
                        singleLine = true,
                        enabled = !readOnly && !mutationInProgress,
                    )
                    Button(
                        enabled = dirty && !readOnly && !mutationInProgress && contentBytes <= MAX_NOTE_BYTES,
                        onClick = ::saveNote,
                    ) {
                        Icon(NextcloudIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (saving) "Saving..." else "Save", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (folderOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        FilterChip(
                            selected = category.isBlank(),
                            onClick = { category = "" },
                            label = { Text("Uncategorized") },
                            enabled = !readOnly && !mutationInProgress,
                        )
                        folderOptions.forEach { path ->
                            FilterChip(
                                selected = category == path,
                                onClick = { category = path },
                                label = { Text(path) },
                                enabled = !readOnly && !mutationInProgress,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    FilterChip(
                        selected = viewMode == NoteViewMode.Edit,
                        onClick = { viewMode = NoteViewMode.Edit },
                        label = { Text("Edit") },
                        leadingIcon = { Icon(NextcloudIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = viewMode == NoteViewMode.Preview,
                        onClick = { viewMode = NoteViewMode.Preview },
                        label = { Text("Preview") },
                        leadingIcon = { Icon(NextcloudIcons.File, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        enabled = previewAvailable,
                    )
                    Text(
                        formatNoteSize(contentBytes),
                        modifier = Modifier.padding(start = NextcloudSpacing.Small),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (viewMode == NoteViewMode.Edit) {
                    MarkdownFormattingToolbar(
                        value = content,
                        onValueChange = { content = it },
                        enabled = !readOnly && !mutationInProgress,
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text("Markdown") },
                        enabled = !readOnly && !mutationInProgress,
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        if (!previewAvailable) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Switch to Edit to continue. Large Markdown previews are disabled.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (content.text.isBlank()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("This note is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Markdown(
                                content = content.text,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(NextcloudSpacing.Large),
                            )
                        }
                    }
                }
                if (!previewAvailable) {
                    Text(
                        "Preview is disabled for notes larger than " +
                            "${MAX_NOTE_MARKDOWN_PREVIEW_BYTES / 1024} KiB to keep editing responsive.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (contentBytes > MAX_NOTE_BYTES) {
                    Text(
                        "This note is larger than ${MAX_NOTE_BYTES / (1024 * 1024)} MiB and cannot be saved.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                loadError?.let {
                    Text("Could not refresh from the server. $it", color = MaterialTheme.colorScheme.error)
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showDiscardConfirmation = false
                navigationRequest?.let(onNavigationCancelled)
            },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your local edits to ${loaded.title} have not been saved.") },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        navigationRequest?.let(onNavigationCancelled)
                    },
                ) { Text("Keep editing") }
            },
            confirmButton = {
                Button(onClick = {
                    showDiscardConfirmation = false
                    navigationRequest?.let(onNavigationConfirmed) ?: onBack()
                }) { Text("Discard") }
            },
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!deleting && deletionRecoveryState == null) showDeleteConfirmation = false
            },
            title = { Text("Delete ${loaded.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text("This deletes the note from the server and may not be reversible.")
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = deletionRecoveryLoaded && !deleting && deletionPreconditionAvailable,
                    onClick = delete@{
                        deleting = true
                        deleteError = null
                        scope.launch {
                            var deletionEtag = loaded.etag?.takeIf { it.isUsableNoteDeletionEtag() }
                            if (deletionRecoveryState == null) {
                                if (deletionEtag == null) {
                                    deleteError = "Refresh this note before deleting it so the server version can be verified."
                                    deleting = false
                                    return@launch
                                }
                                val encoded = NoteDeletionRecoveryState(
                                    accountScope = accountScope,
                                    noteId = loaded.id,
                                    originalEtag = deletionEtag,
                                    originalPreconditionRecorded = true,
                                )
                                    .encodeForDurableStorage()
                                val saved = try {
                                    services.saveDurableMutationRecovery(
                                        accountScope,
                                        DurableMutationRecoveryKind.NoteDeletion,
                                        encoded,
                                    )
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (_: Exception) {
                                    false
                                }
                                if (!saved) {
                                    deletionRecoveryState = try {
                                        services.loadDurableMutationRecovery(
                                            accountScope,
                                            DurableMutationRecoveryKind.NoteDeletion,
                                        )
                                    } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (_: Exception) {
                                        null
                                    }
                                    deleteError = if (deletionRecoveryState != null) {
                                        "Another note deletion is still awaiting server verification."
                                    } else {
                                        "The note deletion could not be safely recorded. Check local storage and try again."
                                    }
                                    deleting = false
                                    return@launch
                                }
                                deletionRecoveryState = encoded
                            } else {
                                val recovery = deletionRecovery
                                if (
                                    recovery == null ||
                                    !recovery.originalPreconditionRecorded ||
                                    !recovery.originalEtag.isUsableNoteDeletionEtag()
                                ) {
                                    deleteError = "This recovery record predates safe delete retries. Check the server, then use Recovery options."
                                    showRecoveryOptions = true
                                    deleting = false
                                    return@launch
                                }
                                deletionEtag = recovery.originalEtag
                            }
                            var requestFailure: String? = null
                            try {
                                services.deleteNote(session, loaded.id, deletionEtag)
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                requestFailure = failure.message ?: "The delete request did not complete."
                            }
                            try {
                                when (val presence = services.inspectNotePresence(session, loaded.id)) {
                                    NextcloudNotePresence.Absent -> {
                                        val expectedEncoded = deletionRecoveryState
                                        if (expectedEncoded == null) {
                                            deleteError = "The deletion recovery record is unavailable. Refresh and try again."
                                            return@launch
                                        }
                                        if (!services.clearDurableMutationRecovery(
                                                accountScope,
                                                DurableMutationRecoveryKind.NoteDeletion,
                                                expectedEncoded,
                                            )
                                        ) {
                                            deleteError = "The deletion was verified, but its recovery record could not be cleared safely. Refreshing the current pending change."
                                            loadAttempt += 1
                                            return@launch
                                        }
                                        deletionRecoveryState = null
                                        sharedNextcloudNotesCache.remove(session, note.id)
                                        showDeleteConfirmation = false
                                        completeVerifiedNoteDeletion(
                                            onDeletingChanged = { deleting = it },
                                            onMutationInProgressChanged = onMutationInProgressChanged,
                                            onBack = onBack,
                                        )
                                    }
                                    is NextcloudNotePresence.Present -> {
                                        sharedNextcloudNotesCache.storeDetail(session, presence.note)
                                        deleteError = requestFailure
                                            ?: "The deletion has not appeared on the server yet. Retry it before leaving this note."
                                        loadAttempt += 1
                                    }
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                deleteError = requestFailure
                                    ?: failure.message
                                    ?: "Could not verify whether the note was deleted."
                            } finally {
                                deleting = false
                            }
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(if (deleting) "Deleting..." else "Delete") }
            },
            dismissButton = {
                if (deletionRecoveryState != null) {
                    TextButton(
                        onClick = { showRecoveryOptions = true },
                        enabled = !deleting,
                    ) { Text("Recovery options") }
                } else {
                    TextButton(
                        onClick = { showDeleteConfirmation = false },
                        enabled = !deleting,
                    ) { Text("Cancel") }
                }
            },
        )
    }
    if (showRecoveryOptions && deletionRecoveryState != null) {
        DurableMutationRecoveryDialog(
            title = "Resolve note recovery",
            recordReadable = deletionRecovery != null,
            resetting = recoveryResetInProgress,
            onCheckAgain = {
                showRecoveryOptions = false
                loadAttempt += 1
            },
            onReset = reset@{
                if (!recoveryResetInProgress) {
                    val expectedEncoded = deletionRecoveryState ?: return@reset
                    recoveryResetInProgress = true
                    scope.launch {
                        val cleared = try {
                            services.clearDurableMutationRecovery(
                                accountScope,
                                DurableMutationRecoveryKind.NoteDeletion,
                                expectedEncoded,
                            )
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            false
                        }
                        if (cleared) {
                            deletionRecoveryState = null
                            deleteError = null
                            showDeleteConfirmation = false
                            showRecoveryOptions = false
                        } else {
                            deleteError = "The note recovery record could not be reset safely. Refreshing the current pending change."
                            loadAttempt += 1
                        }
                        recoveryResetInProgress = false
                    }
                }
            },
            onDismiss = { showRecoveryOptions = false },
        )
    }
}

internal fun completeVerifiedNoteDeletion(
    onDeletingChanged: (Boolean) -> Unit,
    onMutationInProgressChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    onDeletingChanged(false)
    navigateAfterReleasingMutationGuard(onMutationInProgressChanged, onBack)
}

internal fun navigateAfterReleasingMutationGuard(
    onMutationInProgressChanged: (Boolean) -> Unit,
    onNavigate: () -> Unit,
) {
    onMutationInProgressChanged(false)
    onNavigate()
}

@Composable
private fun MarkdownFormattingToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarkdownToolbarButton(NextcloudIcons.FormatHeading, "Heading", enabled) {
                onValueChange(applyMarkdownEdit(value, MarkdownEditAction.Heading))
            }
            MarkdownToolbarButton(NextcloudIcons.FormatBold, "Bold", enabled) {
                onValueChange(applyMarkdownEdit(value, MarkdownEditAction.Bold))
            }
            MarkdownToolbarButton(NextcloudIcons.FormatItalic, "Italic", enabled) {
                onValueChange(applyMarkdownEdit(value, MarkdownEditAction.Italic))
            }
            MarkdownToolbarButton(NextcloudIcons.FormatChecklist, "Checklist item", enabled) {
                onValueChange(applyMarkdownEdit(value, MarkdownEditAction.Checklist))
            }
            MarkdownToolbarButton(NextcloudIcons.FormatQuote, "Quote", enabled) {
                onValueChange(applyMarkdownEdit(value, MarkdownEditAction.Quote))
            }
            MarkdownToolbarButton(NextcloudIcons.FormatCode, "Inline code", enabled) {
                onValueChange(applyMarkdownEdit(value, MarkdownEditAction.InlineCode))
            }
            MarkdownToolbarButton(NextcloudIcons.FormatLink, "Link", enabled) {
                onValueChange(applyMarkdownEdit(value, MarkdownEditAction.Link))
            }
        }
    }
}

internal fun noteDraftIsDirty(
    initialized: Boolean,
    content: String,
    originalContent: String,
    category: String,
    originalCategory: String,
    favorite: Boolean,
    originalFavorite: Boolean,
): Boolean = initialized && (
    content != originalContent || category != originalCategory || favorite != originalFavorite
)

internal fun String.utf8Size(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.code <= 0x7F -> bytes += 1
            character.code <= 0x7FF -> bytes += 2
            character.isHighSurrogate() && getOrNull(index + 1)?.isLowSurrogate() == true -> {
                bytes += 4
                index += 1
            }
            else -> bytes += 3
        }
        index += 1
    }
    return bytes
}

private fun formatNoteSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes B"
}

internal const val MAX_NOTE_MARKDOWN_PREVIEW_BYTES = 512L * 1024L

@Composable
private fun MarkdownToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun NotesHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(NextcloudIcons.Back, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun NotesCenteredState(
    title: String,
    detail: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
        }
    }
}
