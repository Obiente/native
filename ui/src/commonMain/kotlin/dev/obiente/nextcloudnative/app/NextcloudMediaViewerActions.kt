package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.launch

internal enum class MediaViewerAction(val label: String) {
    SendCopy("Send a copy..."),
    ShareNextcloud("Manage Nextcloud sharing"),
    AddToAlbum("Add to album"),
    Move("Move"),
    Copy("Copy"),
    OpenWith("Open in another app"),
    Info("File information"),
    Delete("Delete"),
}

internal fun availableMediaViewerActions(
    file: NextcloudFile,
    userId: String,
    taggingAvailable: Boolean,
    sharingCapabilities: NextcloudFileSharingCapabilities,
    externalActions: Set<ExternalFileHandoffAction>,
): List<MediaViewerAction> {
    val authoritativeDavAccess = file.hasAuthoritativeMediaDavAccess(userId)
    return buildList {
        if (authoritativeDavAccess && ExternalFileHandoffAction.Share in externalActions) {
            add(MediaViewerAction.SendCopy)
        }
        if (authoritativeDavAccess && sharingCapabilities.supportsAnyCreation) {
            add(MediaViewerAction.ShareNextcloud)
        }
        if (
            authoritativeDavAccess &&
            taggingAvailable &&
            file.fileId != null
        ) {
            add(MediaViewerAction.AddToAlbum)
        }
        if (authoritativeDavAccess && !file.etag.isNullOrBlank()) {
            add(MediaViewerAction.Move)
            add(MediaViewerAction.Copy)
        }
        if (authoritativeDavAccess && ExternalFileHandoffAction.OpenWith in externalActions) {
            add(MediaViewerAction.OpenWith)
        }
        add(MediaViewerAction.Info)
        if (authoritativeDavAccess && !file.etag.isNullOrBlank()) {
            add(MediaViewerAction.Delete)
        }
    }
}

@Composable
internal fun MediaViewerActionDialog(
    action: MediaViewerAction,
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    writeFence: FileWriteFence,
    sharingCapabilities: NextcloudFileSharingCapabilities,
    onDismiss: () -> Unit,
    onSourceRemoved: () -> Unit,
    onMutationRunningChanged: (Boolean) -> Unit,
) {
    // Read in composition, so a dialog that is open when a block appears switches to its reason.
    if (action in mediaWriteActions) writeFence.writeBlock(file.path)?.let { block -> return MediaWriteBlockedDialog(block, onDismiss) }
    when (action) {
        MediaViewerAction.Move, MediaViewerAction.Copy -> MediaTransferDialog(
            moving = action == MediaViewerAction.Move,
            file = file,
            session = session,
            userId = userId,
            services = services,
            writeFence = writeFence,
            onDismiss = onDismiss,
            onMoved = onSourceRemoved,
            onMutationRunningChanged = onMutationRunningChanged,
        )
        // The viewer's MediaDeleteHost owns delete, so its outcome survives this dialog.
        MediaViewerAction.Delete -> Unit
        MediaViewerAction.AddToAlbum -> MediaAlbumDialog(
            file = file,
            session = session,
            userId = userId,
            services = services,
            onDismiss = onDismiss,
            onMutationRunningChanged = onMutationRunningChanged,
        )
        MediaViewerAction.ShareNextcloud -> MediaShareDialog(
            file = file,
            session = session,
            services = services,
            capabilities = sharingCapabilities,
            onDismiss = onDismiss,
            onMutationRunningChanged = onMutationRunningChanged,
        )
        MediaViewerAction.Info -> MediaInformationDialog(
            file = file,
            session = session,
            userId = userId,
            services = services,
            onDismiss = onDismiss,
        )
        MediaViewerAction.SendCopy, MediaViewerAction.OpenWith -> Unit
    }
}

@Composable
private fun MediaTransferDialog(
    moving: Boolean,
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    writeFence: FileWriteFence,
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
    onMutationRunningChanged: (Boolean) -> Unit,
) {
    val verb = if (moving) "Move" else "Copy"
    var directory by remember(file.path) {
        mutableStateOf(file.path.substringBeforeLast('/', missingDelimiterValue = ""))
    }
    var name by remember(file.path) { mutableStateOf(file.name) }
    var running by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var followUpNotice by remember(file.path) { mutableStateOf<String?>(null) }
    MediaMutationRunningEffect(running, onMutationRunningChanged)
    val validationError = fileTransferValidationError(file, directory, name)
    val scope = rememberCoroutineScope()
    val finished = if (moving) onMoved else onDismiss
    followUpNotice?.let { notice -> return MediaLocalFollowUpDialog(notice, onClose = finished) }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("$verb ${file.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(
                    "Choose a folder relative to your Nextcloud root. Existing files are never overwritten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = directory,
                    onValueChange = {
                        directory = it
                        error = null
                    },
                    label = { Text("Destination folder") },
                    placeholder = { Text("Photos/Edited") },
                    enabled = !running,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Name at destination") },
                    enabled = !running,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                (error ?: validationError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = !running && validationError == null,
                onClick = {
                    val etag = file.etag?.takeIf(String::isNotBlank)
                    if (etag == null) {
                        error = "Refresh the media item before changing it."
                        return@Button
                    }
                    // The destination may be inside a folder whose delete is not verified yet.
                    writeFence.writeBlock(if (directory.isEmpty()) name else "$directory/$name")?.let { block ->
                        error = block.message
                        return@Button
                    }
                    running = true
                    error = null
                    scope.launch {
                        val mutation = if (moving) {
                            NextcloudFileMutation.Move(file.path, directory, name, etag)
                        } else {
                            NextcloudFileMutation.Copy(file.path, directory, name, etag)
                        }
                        runCatching { services.executeFileMutation(session, userId, mutation) }
                            .onSuccess { result ->
                                val done = if (moving) "Moved ${file.name}" else "Copied ${file.name}"
                                if (result.localFollowUp == FileMutationLocalFollowUp.Completed) finished()
                                else followUpNotice = result.localFollowUp.reportFor(done)
                            }
                            .onFailure { error = it.message ?: "Could not ${verb.lowercase()} this file." }
                        running = false
                    }
                },
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (running) "${verb}ing..." else verb)
            }
        },
    )
}

/**
 * The media viewer's deletes. They run in the account's shared [deletes] owner exactly like a Files
 * delete: the same in-flight and refresh blocks for every screen, verification of an unknown
 * result, retry rules, local follow-up, and completion fencing. The host stays composed with the
 * viewer and watches every item in [items], not only [selected], so a delete whose dialog was
 * closed is applied even after the user moved to another item or the viewer was recreated.
 *
 * An item leaves the viewer, through [onRemoved], only when the server confirmed its delete or it
 * is no longer at its path. Any other outcome keeps it, and its reason is shown in the open dialog
 * or, for an item without one, as a notice once that item is selected.
 */
@Composable
internal fun MediaDeleteHost(
    items: List<NextcloudFile>,
    selected: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    deletes: FileDeleteCoordinator,
    open: Boolean,
    onClose: () -> Unit,
    onRemoved: (NextcloudFile) -> Unit,
) {
    val dialog = remember(deletes, selected.path) { FileDeleteDialogState(deletes) }
    var removalNotices by remember(deletes) { mutableStateOf(emptyList<Pair<NextcloudFile, String>>()) }
    var keptNotices by remember(deletes) { mutableStateOf(emptyMap<String, String>()) }
    val currentOnRemoved by rememberUpdatedState(onRemoved)
    val paths = remember(items, selected) { items.mapTo(HashSet()) { it.path } + selected.path }
    val completion = deletes.completionFor(paths)
    LaunchedEffect(deletes, completion) {
        if (completion == null || !deletes.consume(completion)) return@LaunchedEffect
        val file = completion.request.file
        val shown = file.path == selected.path
        val effect = if (shown) dialog.complete(completion) else completion.outcome.screenEffect(file.name, dialogShowsResult = false)
        when (val outcome = completion.outcome) {
            is FileDeleteOutcome.Deleted ->
                if (outcome.localFollowUp == FileMutationLocalFollowUp.Completed) currentOnRemoved(file)
                else removalNotices = removalNotices + (file to requireNotNull(effect.notice))
            is FileDeleteOutcome.NoLongerAtLocation -> removalNotices = removalNotices + (file to requireNotNull(effect.notice))
            // An open dialog for the item shows the reason itself; otherwise it waits for that item.
            else -> if (!shown || dialog.target == null) effect.notice?.let { keptNotices = keptNotices + (file.path to it) }
        }
    }
    LaunchedEffect(open, selected.path) { if (open && dialog.target == null) dialog.open(selected) }
    removalNotices.firstOrNull()?.let { (file, notice) ->
        return MediaLocalFollowUpDialog(notice) {
            removalNotices = removalNotices.drop(1)
            currentOnRemoved(file)
        }
    }
    keptNotices[selected.path]?.let { notice ->
        return AlertDialog(
            onDismissRequest = { keptNotices = keptNotices - selected.path },
            title = { Text("${selected.name} was not deleted") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { keptNotices = keptNotices - selected.path }) { Text("Close") } },
        )
    }
    dialog.target?.let { target ->
        FileDeleteDialog(
            target = target,
            running = dialog.running,
            error = dialog.error,
            retryBlocked = dialog.retryBlocked,
            onConfirm = {
                dialog.begin()?.let { request ->
                    deletes.start(request, { mutation -> services.executeFileMutation(session, userId, mutation) }) { folder ->
                        services.listFilesWithSource(session, userId, folder)
                    }
                }
            },
            // Closing never cancels a running delete; its outcome is still applied by this host.
            onDismiss = {
                dialog.dismiss()
                onClose()
            },
        )
    }
}

/** The viewer's items: [media] without items whose delete was applied, or only [selected] when it is not listed. */
internal fun mediaViewerItems(media: List<NextcloudFile>, selected: NextcloudFile, removedPaths: Collection<String>): List<NextcloudFile> {
    val visible = if (removedPaths.isEmpty()) media else media.filterNot { it.path in removedPaths }
    return if (mediaViewerNavigationIndex(visible, selected) >= 0) visible else listOf(selected)
}

/**
 * Drops [removed] from the viewer. Another item is simply hidden. When the shown item goes, the
 * next item, or else the previous one, is selected; when none is left, [closeLast] closes the viewer.
 */
internal fun mediaViewerRemove(
    removed: NextcloudFile,
    items: List<NextcloudFile>,
    selected: NextcloudFile,
    markRemoved: (String) -> Unit,
    select: (NextcloudFile) -> Unit,
    closeLast: () -> Unit,
) {
    if (removed.path != selected.path) return markRemoved(removed.path)
    val remaining = items.filterNot { it.path == removed.path }
    val index = items.indexOfFirst { it.path == removed.path }
    val neighbor = remaining.getOrNull(index.coerceAtLeast(0)) ?: remaining.lastOrNull()
    if (neighbor == null) return closeLast()
    markRemoved(removed.path)
    select(neighbor)
}

/** Actions that change the item, create files next to it, or bind it to a share or album. */
private val mediaWriteActions = setOf(
    MediaViewerAction.Move,
    MediaViewerAction.Copy,
    MediaViewerAction.ShareNextcloud,
    MediaViewerAction.AddToAlbum,
)

/** A media change refused by the account's write fence, with its typed reason. */
@Composable
internal fun MediaWriteBlockedDialog(block: FileWriteBlock, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This file cannot be changed yet") },
        text = { Text(block.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The server already applied the change, but local bookkeeping is incomplete. The viewer moves on
 * only after the user has seen that, so stale local state is never reported as a plain success.
 */
@Composable
private fun MediaLocalFollowUpDialog(notice: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Saved on the server") },
        text = { Text(notice) },
        confirmButton = { Button(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun MediaAlbumDialog(
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    onDismiss: () -> Unit,
    onMutationRunningChanged: (Boolean) -> Unit,
) {
    val readService = remember(services) { NativeMediaCollectionReadService(services) }
    val mutationService = remember(services) { NativeMediaCollectionMutationService(services) }
    var catalog by remember(file.path) { mutableStateOf<NativeMediaCollectionCatalog?>(null) }
    var loadError by remember(file.path) { mutableStateOf<String?>(null) }
    var plan by remember(file.path) { mutableStateOf<NativeMediaCollectionActionPlan?>(null) }
    var running by remember(file.path) { mutableStateOf(false) }
    var mutationError by remember(file.path) { mutableStateOf<String?>(null) }
    MediaMutationRunningEffect(running, onMutationRunningChanged)
    val scope = rememberCoroutineScope()
    LaunchedEffect(file.path, session) {
        runCatching { readService.loadCatalog(session) }
            .onSuccess { catalog = it }
            .onFailure { loadError = it.message ?: "Could not load albums." }
    }
    val activePlan = plan
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Text(activePlan?.confirmation?.title ?: "Add ${file.name} to album")
        },
        text = {
            when {
                activePlan != null -> Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(activePlan.confirmation.message)
                    Text(
                        activePlan.disabledReason
                            ?: mutationError
                            ?: "Only album membership changes. The original remains in Files.",
                        color = if (activePlan.disabledReason != null || mutationError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                loadError != null -> Text(requireNotNull(loadError), color = MaterialTheme.colorScheme.error)
                catalog == null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Loading albums...")
                }
                catalog?.albums.isNullOrEmpty() -> Text("No writable albums are available yet.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(requireNotNull(catalog).albums, key = NativeMediaCollection::key) { album ->
                        val candidate = planAddFileToMediaCollection(album, file, userId)
                        TextButton(
                            enabled = candidate.enabled,
                            onClick = {
                                plan = candidate
                                mutationError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(album.name)
                                Text(
                                    buildString {
                                        append(album.itemCount?.let { "$it items" } ?: "Album")
                                        if (album.ownerUserId != null && album.ownerUserId != userId) {
                                            append(" · Shared by ")
                                            append(album.ownerDisplayName ?: album.ownerUserId)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !running,
                onClick = {
                    if (activePlan != null) {
                        plan = null
                        mutationError = null
                    } else {
                        onDismiss()
                    }
                },
            ) { Text(if (activePlan != null) "Back" else "Close") }
        },
        confirmButton = {
            if (activePlan != null && activePlan.enabled) {
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        mutationError = null
                        scope.launch {
                            runCatching {
                                mutationService.executeConfirmed(session, activePlan, confirmed = true)
                            }.onSuccess { onDismiss() }
                                .onFailure { mutationError = it.message ?: "Could not add this file to the album." }
                            running = false
                        }
                    },
                ) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (running) "Adding..." else activePlan.confirmation.confirmLabel)
                }
            }
        },
    )
}

@Composable
private fun MediaShareDialog(
    file: NextcloudFile,
    session: NextcloudSession,
    services: NextcloudPlatformServices,
    capabilities: NextcloudFileSharingCapabilities,
    onDismiss: () -> Unit,
    onMutationRunningChanged: (Boolean) -> Unit,
) {
    var effectiveCapabilities by remember(file.path, capabilities) { mutableStateOf(capabilities) }
    val supportedTargets = remember(effectiveCapabilities) {
        FileShareTarget.entries.filter(effectiveCapabilities::canOffer)
    }
    var shares by remember(file.path) { mutableStateOf<List<NextcloudFileShare>?>(null) }
    var target by remember(file.path) {
        mutableStateOf(
            FileShareTarget.PublicLink.takeIf { it in supportedTargets }
                ?: supportedTargets.firstOrNull()
                ?: FileShareTarget.PublicLink,
        )
    }
    var recipient by remember(file.path) { mutableStateOf<FileShareRecipient?>(null) }
    var allowEditing by remember(file.path) { mutableStateOf(false) }
    var details by remember(file.path) { mutableStateOf(FileShareCreationDetails()) }
    var running by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var notice by remember(file.path) { mutableStateOf<String?>(null) }
    MediaMutationRunningEffect(running, onMutationRunningChanged)
    val scope = rememberCoroutineScope()
    LaunchedEffect(file.path, session) {
        runCatching { services.listFileShares(session, file.path) }
            .onSuccess { shares = it }
            .onFailure {
                shares = emptyList()
                error = it.message ?: "Could not load existing shares."
            }
    }
    FileShareDialog(
        state = FileShareDialogUiState(
            file = file,
            capabilities = effectiveCapabilities,
            existingShares = shares,
            target = target,
            recipient = recipient,
            allowEditing = allowEditing,
            details = details,
            running = running,
            notice = notice,
            error = error,
        ),
        onDismiss = onDismiss,
        onTargetChanged = { choice ->
            target = choice
            recipient = null
            details = details.copy(
                password = "",
                expiration = FileShareExpiration.ServerDefault,
            )
            error = null
        },
        onAllowEditingChanged = { allowEditing = it },
        onDetailsChanged = {
            details = it
            error = null
        },
        onCreate = { ready ->
            running = true
            error = null
            notice = null
            scope.launch {
                runCatching { services.createFileShare(session, ready.request) }
                    .onSuccess { created ->
                        val safeUrl = safeFileShareUrl(session, created)
                        val copied = safeUrl != null &&
                            services.copyTextToClipboard("Nextcloud share link", safeUrl)
                        notice = if (copied) "Share created and link copied" else "Share created"
                        shares = runCatching { services.listFileShares(session, file.path) }
                            .getOrElse { shares.orEmpty() + created }
                        recipient = null
                    }
                    .onFailure { error = it.message ?: "Could not create this share." }
                running = false
            }
        },
        recipientPicker = { selectedTarget ->
            FileShareRecipientPicker(
                session = session,
                services = services,
                target = selectedTarget,
                file = file,
                selectedRecipient = recipient?.id.orEmpty(),
                enabled = !running,
                onSelected = {
                    recipient = it
                    error = null
                },
                onResultsObserved = { recipients ->
                    effectiveCapabilities = effectiveCapabilities.withObservedRecipientProvider(
                        selectedTarget,
                        recipients,
                    )
                },
            )
        },
        existingShare = { share ->
            ExistingFileShareManager(
                share = share,
                sourceIsDirectory = file.isDirectory,
                session = session,
                services = services,
                capabilities = effectiveCapabilities,
                onChanged = { changed ->
                    shares = shares.orEmpty().map {
                        if (it.id == changed.id) changed else it
                    }
                },
                onRevoked = { revoked ->
                    shares = shares.orEmpty().filterNot { it.id == revoked.id }
                    notice = "Access revoked"
                },
                onMutationRunningChanged = onMutationRunningChanged,
            )
        },
    )
}

@Composable
internal fun MediaMutationRunningEffect(
    running: Boolean,
    onMutationRunningChanged: (Boolean) -> Unit,
) {
    if (running) {
        DisposableEffect(Unit) {
            onMutationRunningChanged(true)
            onDispose { onMutationRunningChanged(false) }
        }
    }
}

@Composable
private fun MediaInformationDialog(
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    onDismiss: () -> Unit,
) {
    var information by remember(file) { mutableStateOf(file.basicMediaInformation()) }
    var loading by remember(file) { mutableStateOf(true) }
    var embeddedInformationUnavailable by remember(file) { mutableStateOf(false) }
    var showTechnical by remember(file) { mutableStateOf(false) }

    LaunchedEffect(session, userId, file) {
        loading = true
        embeddedInformationUnavailable = false
        runCatching {
            services.loadMediaInformation(session, userId, file)
        }.onSuccess {
            information = file.basicMediaInformation().mergedWith(it)
        }.onFailure {
            embeddedInformationUnavailable = true
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                if (loading) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                "Reading available media information...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (embeddedInformationUnavailable) {
                    item {
                        Text(
                            "Embedded camera and format information could not be read. File details remain available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                information.sections.forEach { section ->
                    val visibleFields = section.fields.filter { field ->
                        showTechnical || field.importance != MediaInformationImportance.Technical
                    }
                    if (visibleFields.isNotEmpty()) {
                        item(section.key) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        visibleFields.forEach { field ->
                            item("${section.key}:${field.key}") {
                                MediaInfoLine(field.label, field.value)
                            }
                        }
                    }
                }
                if (information.sections.any { section ->
                        section.fields.any { it.importance == MediaInformationImportance.Technical }
                    }
                ) {
                    item {
                        TextButton(onClick = { showTechnical = !showTechnical }) {
                            Text(if (showTechnical) "Hide technical details" else "Show technical details")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun MediaInfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
