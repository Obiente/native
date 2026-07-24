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
import kotlinx.coroutines.launch

private enum class NoteViewMode { Edit, Preview }

@Composable
internal fun NextcloudNotesScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
    onOpenNote: (NextcloudNote) -> Unit,
) {
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

    LaunchedEffect(session, loadAttempt) {
        error = null
        refreshing = true
        val cachedEtag = notes?.let { sharedNextcloudNotesCache.listEtag(session) }
        runCatching { services.listNotesConditionally(session, cachedEtag) }
            .onSuccess { result ->
                when (result) {
                    is NextcloudConditionalRead.Modified -> {
                        notes = result.value
                        sharedNextcloudNotesCache.storeList(session, result.value, result.responseEtag)
                    }
                    NextcloudConditionalRead.NotModified -> Unit
                }
            }
            .onFailure { error = it.message ?: "Could not load Notes." }
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
                    IconButton(onClick = { createNoteInPath = currentPath }) {
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
                                    onOpen = { currentPath = folder.path },
                                    onRename = { renameFolder = folder },
                                    onDelete = { deleteFolder = folder },
                                )
                            }
                            items(visibleNotes, key = NextcloudNote::id) { note ->
                                NoteCard(note = note, onClick = { onOpenNote(note) })
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
                            NoteCard(note = note, onClick = { onOpenNote(note) })
                        }
                    }
                    }
                }
            }
        }
    }
    createNoteInPath?.let { category ->
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
                onOpenNote(created)
            },
        )
    }
    renameFolder?.let { folder ->
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
        )
    }
    deleteFolder?.let { folder ->
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
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(folder.path) { mutableStateOf(false) }
    val actions = listOf(
        NextcloudCardAction(label = "Rename", onClick = onRename),
        NextcloudCardAction(label = "Delete", destructive = true, onClick = onDelete),
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
) {
    var title by remember(category) { mutableStateOf("") }
    var content by remember(category) { mutableStateOf("") }
    var submitting by remember(category) { mutableStateOf(false) }
    var error by remember(category) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
                    error = null
                    scope.launch {
                        runCatching { services.createNote(session, title, content, category) }
                            .onSuccess(onCreated)
                            .onFailure { failure ->
                                error = failure.message ?: "Could not create the note."
                                submitting = false
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
) {
    var name by remember(folder.path) { mutableStateOf(folder.name) }
    var submitting by remember(folder.path) { mutableStateOf(false) }
    var error by remember(folder.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
                    scope.launch {
                        runCatching { services.renameNoteCategory(session, folder.path, destination) }
                            .onSuccess { onRenamed(destination) }
                            .onFailure { failure ->
                                (failure as? PartialNoteFolderMutationException)
                                    ?.refreshedSummaries
                                    ?.let(onReconciled)
                                error = failure.message ?: "Could not rename the folder."
                                submitting = false
                            }
                    }
                },
            ) { Text(if (submitting) "Renaming…" else "Rename") }
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
) {
    var submitting by remember(folder.path) { mutableStateOf(false) }
    var error by remember(folder.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
                    scope.launch {
                        runCatching { services.deleteNoteCategory(session, folder.path) }
                            .onSuccess { onDeleted() }
                            .onFailure { failure ->
                                (failure as? PartialNoteFolderMutationException)
                                    ?.refreshedSummaries
                                    ?.let(onReconciled)
                                error = failure.message ?: "Could not delete the folder."
                                submitting = false
                            }
                    }
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(if (submitting) "Deleting…" else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@Composable
private fun NoteCard(note: NextcloudNote, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
) {
    val accountKey = remember(session.serverUrl, session.loginName) {
        session.serverUrl.trimEnd('/').lowercase() + '\u0000' + session.loginName
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
    var refreshing by remember(note.id, session) { mutableStateOf(false) }
    var showDiscardConfirmation by remember(note.id, session) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(note.id, session) { mutableStateOf(false) }
    var viewMode by rememberSaveable(accountKey, note.id) { mutableStateOf(NoteViewMode.Edit) }
    val scope = rememberCoroutineScope()

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
        if (dirty) showDiscardConfirmation = true else onBack()
    }
    fun saveNote() {
        if (!dirty || readOnly || saving || contentBytes > MAX_NOTE_BYTES) return
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
                    IconButton(onClick = { loadAttempt += 1 }, enabled = !refreshing && !saving) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(NextcloudIcons.Refresh, contentDescription = "Refresh note")
                        }
                    }
                    IconButton(
                        onClick = { favorite = !favorite },
                        enabled = contentAvailable && !readOnly && !saving,
                    ) {
                        Icon(
                            if (favorite) NextcloudIcons.Favorite else NextcloudIcons.FavoriteBorder,
                            contentDescription = if (favorite) "Remove favorite" else "Add favorite",
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !readOnly && !saving,
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
                        enabled = !readOnly && !saving,
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
                        enabled = !readOnly && !saving,
                    )
                    Button(
                        enabled = dirty && !readOnly && !saving && contentBytes <= MAX_NOTE_BYTES,
                        onClick = ::saveNote,
                    ) {
                        Icon(NextcloudIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (saving) "Saving…" else "Save", modifier = Modifier.padding(start = 8.dp))
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
                            enabled = !readOnly && !saving,
                        )
                        folderOptions.forEach { path ->
                            FilterChip(
                                selected = category == path,
                                onClick = { category = path },
                                label = { Text(path) },
                                enabled = !readOnly && !saving,
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
                        enabled = !readOnly && !saving,
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text("Markdown") },
                        enabled = !readOnly && !saving,
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
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your local edits to ${loaded.title} have not been saved.") },
            dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") } },
            confirmButton = {
                Button(onClick = {
                    showDiscardConfirmation = false
                    onBack()
                }) { Text("Discard") }
            },
        )
    }
    if (showDeleteConfirmation) {
        var deleting by remember(note.id) { mutableStateOf(false) }
        var deleteError by remember(note.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirmation = false },
            title = { Text("Delete ${loaded.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text("This deletes the note from the server and may not be reversible.")
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            runCatching { services.deleteNote(session, loaded.id, loaded.etag) }
                                .onSuccess {
                                    sharedNextcloudNotesCache.remove(session, note.id)
                                    showDeleteConfirmation = false
                                    onBack()
                                }
                                .onFailure { failure ->
                                    deleteError = failure.message ?: "Could not delete the note."
                                    deleting = false
                                }
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(if (deleting) "Deleting…" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }, enabled = !deleting) { Text("Cancel") }
            },
        )
    }
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
