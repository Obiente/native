package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.obiente.nextcloudnative.nativeui.runtime.NativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.rememberNativeInlineEditorCloseRequest


internal fun String?.isUsableNoteDeletionEtag(): Boolean =
    !isNullOrBlank() && length <= 4_096 && none(Char::isISOControl)

@Serializable
internal data class NoteDeletionRecoveryState(
    val accountScope: String,
    val noteId: Long,
    val originalEtag: String? = null,
    val originalPreconditionRecorded: Boolean = false,
    /** The notes app whose note is being deleted. Records written before app scoping have none. */
    val appId: String? = null,
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
    expectedAppId: String? = null,
): NoteDeletionRecoveryState? = runCatching {
    noteDeletionRecoveryJson.decodeFromString<NoteDeletionRecoveryState>(encoded)
}.getOrNull()?.takeIf { recovery ->
    recovery.accountScope == expectedAccountScope &&
        (recovery.appId == null || expectedAppId == null || recovery.appId == expectedAppId)
}

@Composable
internal fun NextcloudNoteEditor(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    noteStore: NotesRepository,
    note: NextcloudNote,
    onBack: () -> Unit,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    navigationCommitInProgress: Boolean = false,
    onMutationInProgressChanged: (Boolean) -> Unit = {},
    inPlace: NoteEditorInPlace? = null,
    filesAccess: NotesFilesAccess? = null,
) {
    val accountKey = remember(session.serverUrl, session.loginName) { session.accountId }
    val accountScope = remember(session.serverUrl, session.loginName) {
        durableMutationAccountScope(session)
    }
    var deletionRecoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var deletionRecoveryState by remember(accountScope, services) { mutableStateOf<String?>(null) }
    val deletionRecovery = remember(accountScope, deletionRecoveryState) {
        deletionRecoveryState?.let { decodeNoteDeletionRecoveryState(it, accountScope, noteStore.appId) }
    }
    val cachedNote = remember(accountKey, note.id) { sharedNextcloudNotesCache.detail(session, noteStore.appId, note.id) }
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
    var sharing by remember(note.id, session) { mutableStateOf(false) }
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
        val cacheProducer = sharedNextcloudNotesCache.producer(session)
        showDeleteConfirmation = true
        deleting = true
        try {
            val expectedEncoded = deletionRecoveryState ?: return@LaunchedEffect
            when (val presence = noteStore.inspectNotePresence(recovery.noteId)) {
                NextcloudNotePresence.Absent -> {
                    if (services.clearDurableMutationRecovery(
                            accountScope,
                            DurableMutationRecoveryKind.NoteDeletion,
                            expectedEncoded,
                        )
                    ) {
                        deletionRecoveryState = null
                        sharedNextcloudNotesCache.remove(session, noteStore.appId, recovery.noteId, cacheProducer)
                        inPlace?.onNoteDeleted?.invoke(recovery.noteId)
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
                    sharedNextcloudNotesCache.storeDetail(session, noteStore.appId, presence.note, cacheProducer)
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
        val cacheProducer = sharedNextcloudNotesCache.producer(session)
        loadError = null
        refreshing = true
        // Revalidate a cached body by its ETag; a list summary without content always reloads.
        val expectedEtag = loaded.content?.let { loaded.etag }
        runCatching { noteStore.loadNoteConditionally(note.id, expectedEtag) }
            .onSuccess { result ->
                if (result is NextcloudConditionalRead.NotModified) return@onSuccess
                val fullNote = (result as NextcloudConditionalRead.Modified).value
                sharedNextcloudNotesCache.storeDetail(session, noteStore.appId, fullNote, cacheProducer)
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
    val readOnly = loaded.readOnly || !noteStore.capabilities.canUpdate
    val durableMutationInProgress =
        !deletionRecoveryLoaded || saving || deleting || deletionRecoveryState != null
    val mutationInProgress = mutationOrLinkCommitBlocksInteraction(
        durableMutationInProgress,
        navigationCommitInProgress,
    )
    val deletionPreconditionAvailable = if (deletionRecoveryState == null) {
        loaded.etag.isUsableNoteDeletionEtag()
    } else {
        deletionRecovery?.let { recovery ->
            recovery.originalPreconditionRecorded && recovery.originalEtag.isUsableNoteDeletionEtag()
        } == true
    }
    LaunchedEffect(durableMutationInProgress) {
        onMutationInProgressChanged(durableMutationInProgress)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }
    val contentBytes = remember(content.text) { content.text.utf8Size() }
    val previewAvailable = contentBytes <= MAX_NOTE_MARKDOWN_PREVIEW_BYTES
    val folderOptions = remember(accountKey, note.id) {
        sharedNextcloudNotesCache.list(session, noteStore.appId).orEmpty()
            .mapNotNull { candidate ->
                runCatching { normalizeNoteCategory(candidate.category) }.getOrNull()?.takeIf(String::isNotBlank)
            }
            .distinct()
            .sortedBy(String::lowercase)
    }
    fun discardDraft() {
        title = originalTitle
        content = TextFieldValue(originalContent)
        category = originalCategory
        favorite = originalFavorite
        saveError = null
    }
    // In place, the shell's edit guard owns leaving: Back, app switches, links, and choosing
    // another note all ask before a dirty draft is discarded, and wait for a running save.
    val closeInPlace = rememberNativeInlineEditorCloseRequest(
        enabled = inPlace != null,
        dirty = dirty,
        submissionBlocked = saving || deleting,
        onClose = {
            discardDraft()
            onBack()
        },
        discardMessage = "Your local edits to ${loaded.title} have not been saved.",
        discardActionLabel = "Discard",
        navigation = inPlace?.navigation,
    )
    fun requestBack() {
        if (mutationInProgress) return
        if (inPlace != null) closeInPlace() else if (dirty) showDiscardConfirmation = true else onBack()
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
        val cacheProducer = sharedNextcloudNotesCache.producer(session)
        saving = true
        saveError = null
        scope.launch {
            runCatching {
                noteStore.updateNote(
                    note.id,
                    content.text,
                    category,
                    favorite,
                    etag,
                    title = title,
                )
            }.onSuccess { saved ->
                val savedContent = saved.content ?: content.text
                sharedNextcloudNotesCache.storeDetail(session, noteStore.appId, saved.copy(content = savedContent), cacheProducer)
                inPlace?.onNoteSaved?.invoke(saved.copy(content = savedContent))
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
    PlatformBackHandler(enabled = inPlace == null, onBack = ::requestBack)

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        NotesHeader(
            title = title,
            subtitle = if (readOnly) "Read only" else if (dirty) "Unsaved changes" else "Markdown note",
            onBack = ::requestBack,
            backIcon = inPlace?.closeIcon ?: NextcloudIcons.Back,
            backLabel = inPlace?.closeLabel ?: "Back",
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
                        enabled = contentAvailable && !readOnly && !mutationInProgress &&
                            noteStore.capabilities.canFavorite,
                    ) {
                        dev.obiente.nextcloudnative.app.design.NextcloudAnimatedIcon(
                            if (favorite) NextcloudIcons.Favorite else NextcloudIcons.FavoriteBorder,
                            contentDescription = if (favorite) "Remove favorite" else "Add favorite",
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (filesAccess?.canShareNotes() == true) {
                        TextButton(onClick = { sharing = true }, enabled = contentAvailable && !mutationInProgress) { Text("Share") }
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
                        enabled = !loaded.readOnly && !mutationInProgress && noteStore.capabilities.canDelete,
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
            else -> NoteEditorContent(
                title = title, titleSharedKey = SharedElementKeys.noteTitle(note.id).takeIf { inPlace == null }, category = category, content = content, folderOptions = folderOptions,
                viewMode = viewMode, readOnly = readOnly, mutationInProgress = mutationInProgress,
                canSave = dirty && !readOnly && !mutationInProgress && contentBytes <= MAX_NOTE_BYTES,
                saving = saving, previewAvailable = previewAvailable, contentBytes = contentBytes,
                loadError = loadError, saveError = saveError,
                onTitleChange = { title = it }, onCategoryChange = { category = it },
                onContentChange = { content = it }, onViewModeChange = { viewMode = it }, onSave = ::saveNote,
                canRename = noteStore.capabilities.canRename, canCategorize = noteStore.capabilities.canCategorize,
            )
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
                    discardDraft()
                    navigationRequest?.let(onNavigationConfirmed) ?: onBack()
                }) { Text("Discard") }
            },
        )
    }
    if (sharing && filesAccess != null) {
        NoteFileShareHost(loaded, services, session, filesAccess, { sharing = false }, {})
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
                        val cacheProducer = sharedNextcloudNotesCache.producer(session)
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
                                    appId = noteStore.appId,
                                )
                                    .encodeForDurableStorage()
                                val saved = try {
                                    services.saveDurableMutationRecovery(
                                        session,
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
                                noteStore.deleteNote(loaded.id, deletionEtag)
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                requestFailure = failure.message ?: "The delete request did not complete."
                            }
                            try {
                                when (val presence = noteStore.inspectNotePresence(loaded.id)) {
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
                                        sharedNextcloudNotesCache.remove(session, noteStore.appId, note.id, cacheProducer)
                                        showDeleteConfirmation = false
                                        inPlace?.onNoteDeleted?.invoke(note.id)
                                        completeVerifiedNoteDeletion(
                                            onDeletingChanged = { deleting = it },
                                            onMutationInProgressChanged = onMutationInProgressChanged,
                                            onBack = onBack,
                                        )
                                    }
                                    is NextcloudNotePresence.Present -> {
                                        sharedNextcloudNotesCache.storeDetail(session, noteStore.appId, presence.note, cacheProducer)
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

/**
 * How a note editor shown inside the Notes workspace, beside or instead of the list, reports
 * back to it. [navigation] is the shell's edit guard, which the editor registers with while a
 * note is open in place.
 */
internal class NoteEditorInPlace(
    val navigation: NativeInlineEditorNavigation,
    val closeIcon: ImageVector,
    val closeLabel: String,
    val onNoteSaved: (NextcloudNote) -> Unit,
    val onNoteDeleted: (Long) -> Unit,
)

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

internal const val MAX_NOTE_MARKDOWN_PREVIEW_BYTES = 512L * 1024L

@Composable
internal fun NotesHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    backIcon: ImageVector = NextcloudIcons.Back,
    backLabel: String = "Back",
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(backIcon, contentDescription = backLabel)
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
internal fun NotesCenteredState(
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
