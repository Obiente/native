package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudLoadingState
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudStatusState
import dev.obiente.nextcloudnative.nativeui.runtime.LocalNativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeInlineEditorNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun notesListRequiresPendingNavigationGuard(
    mutationInProgress: Boolean,
    renameDraftOpen: Boolean,
): Boolean = mutationInProgress || renameDraftOpen

internal fun removeVerifiedDeletedNote(
    notes: List<NextcloudNote>?,
    noteId: Long,
): List<NextcloudNote>? = notes?.filterNot { note -> note.id == noteId }

/** Server title for a note created from the list; the editor opens on it right away. */
internal const val NEW_NOTE_TITLE = "New note"

/**
 * The Notes workspace. Wide layouts and desktop windows open notes in place: the folder and
 * note list stays beside the editor, or narrow desktop windows show the editor alone with Back
 * to the list. Phones keep the full-page editor through [onOpenNote].
 *
 * The open note is saved selection, so it reopens after resize, rotation, and recreation; a
 * note opened in place stays in place when the window narrows, so its draft is never dropped.
 */
@Composable
internal fun NextcloudNotesScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    noteStore: NotesRepository,
    onBack: () -> Unit,
    /** Opens a note on its own page. Null keeps every note in place, including on phones. */
    onOpenNote: ((NextcloudNote) -> Unit)? = null,
    settings: SemanticSettings? = null,
    navigationCommitInProgress: Boolean = false,
    onMutationInProgressChanged: (Boolean) -> Unit = {},
    filesAccess: NotesFilesAccess? = null,
) {
    val accountScope = remember(session.serverUrl, session.loginName) { durableMutationAccountScope(session) }
    var deletionRecoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var deletionRecoveryState by remember(accountScope, services) { mutableStateOf<String?>(null) }
    val deletionRecovery = remember(accountScope, deletionRecoveryState) {
        deletionRecoveryState?.let { decodeNoteDeletionRecoveryState(it, accountScope, noteStore.appId) }
    }
    var notes by remember(session.serverUrl, session.loginName) {
        mutableStateOf(sharedNextcloudNotesCache.list(session, noteStore.appId))
    }
    var query by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var currentPath by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var openNoteId by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf<Long?>(null) }
    var openNoteSnapshot by remember(session) { mutableStateOf<NextcloudNote?>(null) }
    var listFilter by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf(NotesListFilter.All) }
    var showSettings by remember(session) { mutableStateOf(false) }
    var shareNote by remember(session) { mutableStateOf<NextcloudNote?>(null) }
    var actionMessage by remember(session) { mutableStateOf<String?>(null) }
    var renameFolder by remember(session) { mutableStateOf<NativeNoteFolder?>(null) }
    var deleteFolder by remember(session) { mutableStateOf<NativeNoteFolder?>(null) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var createError by remember(session) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(session) { mutableStateOf(0) }
    var refreshing by remember(session) { mutableStateOf(false) }
    var listVerified by remember(session) { mutableStateOf(false) }
    var showRecoveryOptions by remember(accountScope) { mutableStateOf(false) }
    var recoveryResetInProgress by remember(accountScope) { mutableStateOf(false) }
    var listMutationInProgress by remember(session) { mutableStateOf(false) }
    var editorMutationInProgress by remember(session) { mutableStateOf(false) }
    var createdNoteToOpen by remember(session) { mutableStateOf<NextcloudNote?>(null) }
    var opensInPlace by remember { mutableStateOf(false) }
    val inlineNavigation = LocalNativeInlineEditorNavigation.current ?: remember { NativeInlineEditorNavigation() }
    val notesListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val recoveryMutationInProgress = !deletionRecoveryLoaded || deletionRecoveryState != null
    val mutationInProgress = recoveryMutationInProgress || listMutationInProgress
    val pendingNavigationGuardActive = notesListRequiresPendingNavigationGuard(
        mutationInProgress = mutationInProgress,
        renameDraftOpen = renameFolder != null,
    ) || editorMutationInProgress
    val interactionBlocked = mutationOrLinkCommitBlocksInteraction(
        mutationInProgress,
        navigationCommitInProgress,
    )
    val openNote = openNoteId?.let { id ->
        notes?.firstOrNull { it.id == id } ?: openNoteSnapshot?.takeIf { it.id == id }
            ?: sharedNextcloudNotesCache.detail(session, noteStore.appId, id)
    }

    // Choosing a note goes through the edit guard, which asks before a dirty draft is replaced.
    fun showNote(note: NextcloudNote) {
        if (note.id == openNoteId) return
        val openOnPage = onOpenNote
        if (opensInPlace || openNoteId != null || openOnPage == null) {
            inlineNavigation.navigate {
                openNoteSnapshot = note
                openNoteId = note.id
            }
        } else {
            openOnPage(note)
        }
    }
    val currentShowNote by rememberUpdatedState(::showNote)

    fun toggleFavorite(note: NextcloudNote) {
        if (interactionBlocked || note.id == openNoteId) return
        val cacheProducer = sharedNextcloudNotesCache.producer(session)
        listMutationInProgress = true
        actionMessage = null
        scope.launch {
            try {
                val outcome = setNoteFavorite(noteStore, note.id, !note.favorite)
                val latest = (outcome as? NoteFavoriteOutcome.Saved)?.note ?: (outcome as? NoteFavoriteOutcome.Failed)?.latest
                latest?.let { updated ->
                    notes = notes?.map { listed -> if (listed.id == updated.id) updated else listed }
                    sharedNextcloudNotesCache.storeList(session, noteStore.appId, notes.orEmpty(), cacheProducer)
                }
                when (outcome) {
                    is NoteFavoriteOutcome.Saved -> Unit
                    is NoteFavoriteOutcome.Failed -> actionMessage = outcome.message
                    is NoteFavoriteOutcome.Unknown -> {
                        actionMessage = outcome.message
                        loadAttempt += 1
                    }
                }
            } finally {
                listMutationInProgress = false
            }
        }
    }

    fun noteActions(note: NextcloudNote): List<NextcloudCardAction> = buildList {
        if (!note.readOnly && note.id != openNoteId && noteStore.capabilities.canFavorite) {
            add(NextcloudCardAction(if (note.favorite) "Remove from favorites" else "Add to favorites") { toggleFavorite(note) })
        }
        if (filesAccess?.canShareNotes() == true) add(NextcloudCardAction("Share") { shareNote = note })
    }

    fun createNote() {
        if (interactionBlocked) return
        val cacheProducer = sharedNextcloudNotesCache.producer(session)
        val category = currentPath
        listMutationInProgress = true
        onMutationInProgressChanged(true)
        createError = null
        scope.launch {
            try {
                val created = noteStore.createNote(NEW_NOTE_TITLE, "", category)
                notes = (notes.orEmpty() + created).distinctBy(NextcloudNote::id)
                sharedNextcloudNotesCache.storeList(session, noteStore.appId, notes.orEmpty(), cacheProducer)
                sharedNextcloudNotesCache.storeDetail(session, noteStore.appId, created, cacheProducer)
                createdNoteToOpen = created
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                createError = failure.message ?: "Could not create the note."
            } finally {
                listMutationInProgress = false
            }
        }
    }

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
    LaunchedEffect(pendingNavigationGuardActive, createdNoteToOpen) {
        onMutationInProgressChanged(pendingNavigationGuardActive)
        if (!pendingNavigationGuardActive) {
            createdNoteToOpen?.let { created ->
                createdNoteToOpen = null
                currentShowNote(created)
            }
        }
    }
    LaunchedEffect(recoveryMutationInProgress) {
        if (recoveryMutationInProgress) {
            renameFolder = null
            deleteFolder = null
        }
    }
    // A note deleted elsewhere closes once a completed refresh confirms it is gone.
    val openNoteMissing = openNoteId != null && openNote == null && listVerified && !refreshing
    LaunchedEffect(openNoteMissing) {
        if (openNoteMissing) openNoteId = null
    }
    DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }
    LaunchedEffect(session, loadAttempt, deletionRecoveryLoaded, deletionRecoveryState) {
        if (!deletionRecoveryLoaded) return@LaunchedEffect
        val cacheProducer = sharedNextcloudNotesCache.producer(session)
        error = null
        refreshing = true
        runCatching {
            when (val result: NextcloudConditionalRead<List<NextcloudNote>> =
                NextcloudConditionalRead.Modified(noteStore.listNotes(), null)
            ) {
                is NextcloudConditionalRead.Modified -> {
                    notes = result.value
                    sharedNextcloudNotesCache.storeList(session, noteStore.appId, result.value, cacheProducer, result.responseEtag)
                }
                NextcloudConditionalRead.NotModified -> Unit
            }
            listVerified = true
            deletionRecovery?.let { recovery ->
                val expectedEncoded = deletionRecoveryState ?: return@let
                when (val presence = noteStore.inspectNotePresence(recovery.noteId)) {
                    NextcloudNotePresence.Absent -> {
                        if (services.clearDurableMutationRecovery(
                                accountScope,
                                DurableMutationRecoveryKind.NoteDeletion,
                                expectedEncoded,
                            )
                        ) {
                            notes = removeVerifiedDeletedNote(notes, recovery.noteId)
                            sharedNextcloudNotesCache.remove(session, noteStore.appId, recovery.noteId, cacheProducer)
                            deletionRecoveryState = null
                        } else {
                            error = "The verified note-deletion recovery record could not be cleared safely. Refreshing the current pending change."
                            loadAttempt += 1
                        }
                    }
                    is NextcloudNotePresence.Present -> {
                        sharedNextcloudNotesCache.storeDetail(session, noteStore.appId, presence.note, cacheProducer)
                        currentShowNote(presence.note)
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val besideList = workspaceShowsDetailBesideList(maxWidth)
        val inPlace = LocalNextcloudWorkspaceCapabilities.current.isDesktop || besideList
        SideEffect { opensInPlace = inPlace }
        val editorAlone = openNoteId != null && !besideList
        Column(Modifier.fillMaxSize()) {
            if (!editorAlone) {
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
                            if (noteStore.capabilities.canCreate) {
                                IconButton(
                                    onClick = { inlineNavigation.navigate(::createNote) },
                                    enabled = !interactionBlocked,
                                ) {
                                    Icon(NextcloudIcons.Add, contentDescription = "Create note")
                                }
                            }
                            if (settings != null) {
                                IconButton(
                                    onClick = { inlineNavigation.navigate { showSettings = true } },
                                    enabled = !interactionBlocked,
                                ) {
                                    Icon(NextcloudIcons.Settings, contentDescription = "Notes settings")
                                }
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
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (!editorAlone) {
                    NotesListPane(
                        notes = notes,
                        error = error,
                        createError = createError ?: actionMessage,
                        query = query,
                        filter = listFilter,
                        onFilterChange = { listFilter = it },
                        noteActions = ::noteActions,
                        onQueryChange = { query = it },
                        currentPath = currentPath,
                        onPathChange = { currentPath = it },
                        openNoteId = openNoteId,
                        interactionBlocked = interactionBlocked,
                        listState = notesListState,
                        onRetry = { loadAttempt += 1 },
                        onOpenNote = ::showNote,
                        onRenameFolder = { folder: NativeNoteFolder -> inlineNavigation.navigate { renameFolder = folder } }
                            .takeIf { noteStore.capabilities.canUpdate && noteStore.capabilities.canCategorize },
                        onDeleteFolder = { folder: NativeNoteFolder -> inlineNavigation.navigate { deleteFolder = folder } }
                            .takeIf { noteStore.capabilities.canDelete },
                        modifier = if (besideList) Modifier.width(NotesListPaneWidth).fillMaxHeight() else Modifier.fillMaxSize(),
                    )
                }
                if (besideList) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (openNoteId != null) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val shown = openNote
                        if (shown == null) {
                            NextcloudLoadingState("Loading note")
                        } else {
                            key(shown.id) {
                                NextcloudNoteEditor(
                                    services = services,
                                    session = session,
                                    noteStore = noteStore,
                                    note = shown,
                                    onBack = {
                                        openNoteId = null
                                        openNoteSnapshot = null
                                    },
                                    navigationCommitInProgress = navigationCommitInProgress,
                                    onMutationInProgressChanged = { editorMutationInProgress = it },
                                    filesAccess = filesAccess,
                                    inPlace = NoteEditorInPlace(
                                        navigation = inlineNavigation,
                                        closeIcon = if (besideList) NextcloudIcons.Close else NextcloudIcons.Back,
                                        closeLabel = if (besideList) "Close note" else "Back to notes",
                                        onNoteSaved = { saved ->
                                            notes = notes?.map { listed -> if (listed.id == saved.id) saved else listed }
                                            openNoteSnapshot = saved
                                        },
                                        onNoteDeleted = { noteId -> notes = removeVerifiedDeletedNote(notes, noteId) },
                                    ),
                                )
                            }
                        }
                    }
                } else if (besideList) {
                    NextcloudStatusState(
                        icon = NextcloudIcons.app("notes"),
                        title = "Select a note",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        body = "Choose a note from the list, or create one to start writing.",
                    )
                }
            }
        }
    }
    renameFolder?.takeIf { !recoveryMutationInProgress }?.let { folder ->
        RenameNoteFolderDialog(
            folder = folder,
            noteStore = noteStore,
            session = session,
            onDismiss = { renameFolder = null },
            onReconciled = { refreshed, cacheProducer ->
                notes = refreshed
                sharedNextcloudNotesCache.storeList(session, noteStore.appId, refreshed, cacheProducer)
            },
            onRenamed = { destination, cacheProducer ->
                val oldPrefix = folder.path + "/"
                notes = notes.orEmpty().map { note ->
                    when {
                        note.category == folder.path -> note.copy(category = destination)
                        note.category.startsWith(oldPrefix) ->
                            note.copy(category = destination + "/" + note.category.removePrefix(oldPrefix))
                        else -> note
                    }
                }
                sharedNextcloudNotesCache.storeList(session, noteStore.appId, notes.orEmpty(), cacheProducer)
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
            noteStore = noteStore,
            session = session,
            onDismiss = { deleteFolder = null },
            onReconciled = { refreshed, cacheProducer ->
                notes = refreshed
                sharedNextcloudNotesCache.storeList(session, noteStore.appId, refreshed, cacheProducer)
            },
            onDeleted = { cacheProducer ->
                val prefix = folder.path + "/"
                notes = notes.orEmpty().filterNot { note ->
                    note.category == folder.path || note.category.startsWith(prefix)
                }
                sharedNextcloudNotesCache.storeList(session, noteStore.appId, notes.orEmpty(), cacheProducer)
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
    if (showSettings && settings != null) {
        NotesSettingsSheet(
            services = services,
            session = session,
            settings = settings,
            filesAccess = filesAccess,
            onSettingChanged = {
                openNoteId = null
                currentPath = ""
                loadAttempt += 1
            },
            onDismiss = { showSettings = false },
        )
    }
    shareNote?.let { note ->
        filesAccess?.let { access ->
            NoteFileShareHost(note, services, session, access, { shareNote = null }, { listMutationInProgress = it })
        }
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

private val NotesListPaneWidth = 360.dp

@Composable
private fun NotesListPane(
    notes: List<NextcloudNote>?,
    error: String?,
    createError: String?,
    query: String,
    filter: NotesListFilter,
    onFilterChange: (NotesListFilter) -> Unit,
    noteActions: (NextcloudNote) -> List<NextcloudCardAction>,
    onQueryChange: (String) -> Unit,
    currentPath: String,
    onPathChange: (String) -> Unit,
    openNoteId: Long?,
    interactionBlocked: Boolean,
    listState: LazyListState,
    onRetry: () -> Unit,
    onOpenNote: (NextcloudNote) -> Unit,
    onRenameFolder: ((NativeNoteFolder) -> Unit)?,
    onDeleteFolder: ((NativeNoteFolder) -> Unit)?,
    modifier: Modifier,
) {
    Box(modifier) {
        when {
            error != null && notes == null -> NotesCenteredState(
                title = "Could not load Notes",
                detail = error,
                action = "Retry",
                onAction = onRetry,
            )
            notes == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            notes.isEmpty() -> NotesCenteredState(
                title = if (error == null) "No notes yet" else "Could not refresh Notes",
                detail = createError ?: error ?: "Create a note, or notes created on your server will appear here.",
                action = if (error != null) "Retry" else null,
                onAction = if (error != null) onRetry else null,
            )
            else -> {
                val location = remember(notes, currentPath) { buildNativeNotesLocation(notes, currentPath) }
                val visibleNotes = remember(notes, location, query, filter) {
                    filterNotesForList(notes, location.notes, query, filter)
                }
                val browsingFolders = query.isBlank() && filter == NotesListFilter.All
                Column(modifier = Modifier.fillMaxSize()) {
                    listOfNotNull(error?.let { "Showing saved results. $it" }, createError).forEach { message ->
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
                                message,
                                modifier = Modifier.padding(NextcloudSpacing.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = NextcloudSpacing.XLarge,
                            vertical = NextcloudSpacing.Medium,
                        ),
                        leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                        label = { Text("Search notes") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = NextcloudSpacing.XLarge),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        NotesListFilter.entries.forEach { option ->
                            NextcloudFilterChip(
                                selected = filter == option,
                                onClick = { onFilterChange(option) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        state = listState,
                        contentPadding = PaddingValues(
                            start = NextcloudSpacing.XLarge,
                            end = NextcloudSpacing.XLarge,
                            bottom = NextcloudSpacing.XXLarge,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        if (browsingFolders) {
                            item(key = "breadcrumbs") {
                                NotesBreadcrumbs(location.breadcrumbs, onPathChange)
                            }
                            items(location.folders, key = { folder -> "folder:${folder.path}" }) { folder ->
                                NoteFolderCard(
                                    folder = folder,
                                    mutationEnabled = !interactionBlocked,
                                    onOpen = { onPathChange(folder.path) },
                                    onRename = onRenameFolder?.let { rename -> { rename(folder) } },
                                    onDelete = onDeleteFolder?.let { delete -> { delete(folder) } },
                                )
                            }
                        }
                        items(visibleNotes, key = { note -> "note:${note.id}" }) { note ->
                            NoteCard(
                                note = note,
                                enabled = !interactionBlocked,
                                onClick = { onOpenNote(note) },
                                selected = note.id == openNoteId,
                                actions = noteActions(note),
                            )
                        }
                        if (filter == NotesListFilter.Favorites && visibleNotes.isEmpty()) {
                            item(key = "no-favorites") {
                                Text(
                                    "No favorite notes. Add a note to favorites from its menu or the editor.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(NextcloudSpacing.Large),
                                )
                            }
                        }
                        if (browsingFolders && location.folders.isEmpty() && visibleNotes.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    "This folder is empty. Create a Markdown note here.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(NextcloudSpacing.Large),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
