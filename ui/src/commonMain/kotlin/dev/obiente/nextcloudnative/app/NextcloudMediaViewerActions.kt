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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    sharingCapabilities: NextcloudFileSharingCapabilities,
    onDismiss: () -> Unit,
    onSourceRemoved: () -> Unit,
) {
    when (action) {
        MediaViewerAction.Move, MediaViewerAction.Copy -> MediaTransferDialog(
            moving = action == MediaViewerAction.Move,
            file = file,
            session = session,
            userId = userId,
            services = services,
            onDismiss = onDismiss,
            onMoved = onSourceRemoved,
        )
        MediaViewerAction.Delete -> MediaDeleteDialog(
            file = file,
            session = session,
            userId = userId,
            services = services,
            onDismiss = onDismiss,
            onDeleted = onSourceRemoved,
        )
        MediaViewerAction.AddToAlbum -> MediaAlbumDialog(
            file = file,
            session = session,
            userId = userId,
            services = services,
            onDismiss = onDismiss,
        )
        MediaViewerAction.ShareNextcloud -> MediaShareDialog(
            file = file,
            session = session,
            services = services,
            capabilities = sharingCapabilities,
            onDismiss = onDismiss,
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
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
) {
    val verb = if (moving) "Move" else "Copy"
    var directory by remember(file.path) {
        mutableStateOf(file.path.substringBeforeLast('/', missingDelimiterValue = ""))
    }
    var name by remember(file.path) { mutableStateOf(file.name) }
    var running by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    val validationError = fileTransferValidationError(file, directory, name)
    val scope = rememberCoroutineScope()
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
                    running = true
                    error = null
                    scope.launch {
                        val mutation = if (moving) {
                            NextcloudFileMutation.Move(file.path, directory, name, etag)
                        } else {
                            NextcloudFileMutation.Copy(file.path, directory, name, etag)
                        }
                        runCatching { services.executeFileMutation(session, userId, mutation) }
                            .onSuccess { if (moving) onMoved() else onDismiss() }
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

@Composable
private fun MediaDeleteDialog(
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    var running by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Delete ${file.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text("This removes the original file from Nextcloud. Album membership and previews are not substitutes for the original.")
                Text(
                    "The delete checks the exact ETag and stops if the file changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = !running,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = {
                    val etag = file.etag?.takeIf(String::isNotBlank)
                    if (etag == null) {
                        error = "Refresh the media item before deleting it."
                        return@Button
                    }
                    running = true
                    error = null
                    scope.launch {
                        runCatching {
                            services.executeFileMutation(
                                session,
                                userId,
                                NextcloudFileMutation.Delete(file.path, etag),
                            )
                        }.onSuccess { onDeleted() }
                            .onFailure { error = it.message ?: "Could not delete this file." }
                        running = false
                    }
                },
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (running) "Deleting..." else "Delete")
            }
        },
    )
}

@Composable
private fun MediaAlbumDialog(
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    onDismiss: () -> Unit,
) {
    val readService = remember(services) { NativeMediaCollectionReadService(services) }
    val mutationService = remember(services) { NativeMediaCollectionMutationService(services) }
    var catalog by remember(file.path) { mutableStateOf<NativeMediaCollectionCatalog?>(null) }
    var loadError by remember(file.path) { mutableStateOf<String?>(null) }
    var plan by remember(file.path) { mutableStateOf<NativeMediaCollectionActionPlan?>(null) }
    var running by remember(file.path) { mutableStateOf(false) }
    var mutationError by remember(file.path) { mutableStateOf<String?>(null) }
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
    var recipient by remember(file.path) { mutableStateOf("") }
    var allowEditing by remember(file.path) { mutableStateOf(false) }
    var details by remember(file.path) { mutableStateOf(FileShareCreationDetails()) }
    var running by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var notice by remember(file.path) { mutableStateOf<String?>(null) }
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
            recipient = ""
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
                        recipient = ""
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
                selectedRecipient = recipient,
                enabled = !running,
                onSelected = {
                    recipient = it?.id.orEmpty()
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
            )
        },
    )
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
