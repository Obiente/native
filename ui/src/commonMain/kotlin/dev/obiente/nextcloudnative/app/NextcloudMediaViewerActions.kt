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
import androidx.compose.material3.FilterChip
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
        MediaViewerAction.Info -> MediaInformationDialog(file, onDismiss)
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
    val supportedTargets = remember(capabilities) {
        FileShareTarget.entries.filter {
            when (it) {
                FileShareTarget.PublicLink -> capabilities.publicLinks
                FileShareTarget.User -> capabilities.userShares
                FileShareTarget.Group -> capabilities.groupShares
            }
        }
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
    val plan = planFileShareCreation(
        file = file,
        target = target,
        recipient = recipient.takeUnless { target == FileShareTarget.PublicLink },
        permissions = FileSharePermissions(read = true, update = allowEditing),
        capabilities = capabilities,
    )
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Share ${file.name}") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                item {
                    Text("Existing access", style = MaterialTheme.typography.titleSmall)
                }
                when (val currentShares = shares) {
                    null -> item { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    else -> if (currentShares.isEmpty()) {
                        item {
                            Text(
                                "Not shared yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(currentShares.take(12), key = NextcloudFileShare::id) { share ->
                            ExistingFileShareManager(
                                share = share,
                                sourceIsDirectory = file.isDirectory,
                                session = session,
                                services = services,
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
                        }
                    }
                }
                if (supportedTargets.isNotEmpty()) {
                    item { Text("Create access", style = MaterialTheme.typography.titleSmall) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            supportedTargets.forEach { choice ->
                                FilterChip(
                                    selected = target == choice,
                                    enabled = !running,
                                    onClick = {
                                        target = choice
                                        recipient = ""
                                        error = null
                                    },
                                    label = {
                                        Text(
                                            when (choice) {
                                                FileShareTarget.PublicLink -> "Public link"
                                                FileShareTarget.User -> "User"
                                                FileShareTarget.Group -> "Group"
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (target != FileShareTarget.PublicLink) {
                        item {
                            FileShareRecipientPicker(
                                session = session,
                                services = services,
                                target = target,
                                selectedRecipient = recipient,
                                enabled = !running,
                                onSelected = {
                                    recipient = it?.id.orEmpty()
                                    error = null
                                },
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            FilterChip(
                                selected = !allowEditing,
                                onClick = { allowEditing = false },
                                label = { Text("Can view") },
                            )
                            FilterChip(
                                selected = allowEditing,
                                onClick = { allowEditing = true },
                                label = { Text("Can edit") },
                            )
                        }
                    }
                }
                (plan as? FileShareCreationPlan.Blocked)?.let { blocked ->
                    item {
                        Text(
                            blocked.reason,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                notice?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.primary) }
                }
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text("Close") }
        },
        confirmButton = {
            Button(
                enabled = plan is FileShareCreationPlan.Ready && !running,
                onClick = {
                    val ready = plan as? FileShareCreationPlan.Ready ?: return@Button
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
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (running) "Creating..." else "Create share")
            }
        },
    )
}

@Composable
private fun MediaInformationDialog(file: NextcloudFile, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                item { MediaInfoLine("Path", file.path) }
                item { MediaInfoLine("Type", file.mimeType ?: "Unknown") }
                item { MediaInfoLine("Size", formatMediaBytes(file.size)) }
                file.lastModified?.takeIf(String::isNotBlank)?.let { item { MediaInfoLine("Modified", it) } }
                file.fileId?.let { item { MediaInfoLine("File ID", it.toString()) } }
                file.etag?.takeIf(String::isNotBlank)?.let { item { MediaInfoLine("Version", it) } }
                file.permissions?.takeIf(String::isNotBlank)?.let { item { MediaInfoLine("Permissions", it) } }
                if (file.checksums.isNotEmpty()) {
                    item { MediaInfoLine("Checksums", file.checksums.joinToString("\n")) }
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

private fun formatMediaBytes(bytes: Long?): String = when {
    bytes == null -> "Unknown"
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GiB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
    bytes >= 1024L -> "${bytes / 1024L} KiB"
    else -> "$bytes B"
}
