package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import kotlinx.coroutines.launch

@Composable
internal fun FileOfflineCenterScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onBack: () -> Unit,
) {
    var snapshot by remember(session, userId) { mutableStateOf<FileOfflineCenterSnapshot?>(null) }
    var loading by remember(session, userId) { mutableStateOf(true) }
    var loadError by remember(session, userId) { mutableStateOf<String?>(null) }
    var refreshAttempt by remember(session, userId) { mutableStateOf(0) }
    var actionKey by remember(session, userId) { mutableStateOf<FileOfflineKey?>(null) }
    var actionMessage by remember(session, userId) { mutableStateOf<String?>(null) }
    var removeTarget by remember(session, userId) { mutableStateOf<FileOfflineCenterItem?>(null) }
    var syncSnapshot by remember(session, userId) { mutableStateOf<FileSyncCenterSnapshot?>(null) }
    var syncLoading by remember(session, userId) { mutableStateOf(false) }
    var mediaFolderDiscovery by remember(session, userId) { mutableStateOf<MediaSyncFolderDiscovery?>(null) }
    var mediaDiscoveryLoading by remember(session, userId) { mutableStateOf(false) }
    var syncBusyPairId by remember(session, userId) { mutableStateOf<String?>(null) }
    var pendingLocalRoot by remember(session, userId) { mutableStateOf<FileSyncLocalRoot?>(null) }
    var pendingMediaSuggestion by remember(session, userId) { mutableStateOf<MediaSyncFolderSuggestion?>(null) }
    var removeSyncPair by remember(session, userId) { mutableStateOf<FileSyncPairSummary?>(null) }
    var pendingSyncDecision by remember(session, userId) {
        mutableStateOf<PendingFileSyncDecision?>(null)
    }
    val scope = rememberCoroutineScope()

    fun runItemAction(item: FileOfflineCenterItem, remove: Boolean) {
        if (actionKey != null) return
        actionKey = item.key
        actionMessage = null
        scope.launch {
            runCatching {
                if (remove) {
                    services.removeFileOfflineItem(session, userId, item.key)
                } else {
                    services.retryFileOfflineItem(session, userId, item.key)
                }
            }.onSuccess { result ->
                actionMessage = result.offlineCenterActionMessage()
                if (result is FileOfflineCenterActionResult.Completed) refreshAttempt += 1
            }.onFailure { failure ->
                actionMessage = failure.message ?: "Could not update this offline file."
            }
            actionKey = null
        }
    }

    fun runSyncAction(pairId: String, remove: Boolean) {
        if (syncBusyPairId != null) return
        syncBusyPairId = pairId
        actionMessage = null
        scope.launch {
            runCatching {
                if (remove) {
                    services.removeFileSyncPair(session, userId, pairId)
                } else {
                    services.runFileSyncPair(session, userId, pairId)
                }
            }.onSuccess { result ->
                actionMessage = result.fileSyncCenterMessage()
                refreshAttempt += 1
            }.onFailure { failure ->
                actionMessage = failure.message ?: "Could not update this folder sync pair."
            }
            syncBusyPairId = null
        }
    }

    fun resolveSyncConflict(target: PendingFileSyncDecision) {
        if (syncBusyPairId != null) return
        syncBusyPairId = target.pair.id
        actionMessage = null
        scope.launch {
            runCatching {
                services.resolveFileSyncConflict(
                    session,
                    userId,
                    target.pair.id,
                    target.conflict.workId,
                    target.choice,
                )
            }.onSuccess { result ->
                actionMessage = result.fileSyncCenterMessage()
                refreshAttempt += 1
            }.onFailure { failure ->
                actionMessage = failure.message ?: "Could not apply this conflict decision."
            }
            syncBusyPairId = null
        }
    }

    LaunchedEffect(session, userId, refreshAttempt) {
        if (userId.isBlank()) {
            loading = false
            loadError = "Account details are still loading."
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        runCatching { services.loadFileOfflineCenter(session, userId) }
            .onSuccess { snapshot = it }
            .onFailure { failure ->
                loadError = failure.message ?: "Could not load offline file status."
            }
        if (services.supportsBidirectionalFileSync) {
            syncLoading = true
            mediaDiscoveryLoading = true
            runCatching { services.loadFileSyncCenter(session, userId) }
                .onSuccess { syncSnapshot = it }
                .onFailure { failure ->
                    actionMessage = failure.message ?: "Could not load folder sync pairs."
                }
            runCatching { services.discoverMediaSyncFolders() }
                .onSuccess { mediaFolderDiscovery = it }
                .onFailure { failure ->
                    mediaFolderDiscovery = MediaSyncFolderDiscovery(
                        support = MediaSyncFolderDiscoverySupport.Unsupported,
                        suggestions = emptyList(),
                        message = failure.message ?: "Could not inspect local media folders.",
                    )
                }
            syncLoading = false
            mediaDiscoveryLoading = false
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Sync & offline",
            subtitle = "Folder sync and offline availability",
            onBack = onBack,
            trailingContent = {
                TextButton(
                    enabled = !loading && actionKey == null,
                    onClick = { refreshAttempt += 1 },
                ) {
                    Text("Refresh")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            item {
                OfflineCenterSummaryCard(snapshot, loading)
            }
            loadError?.let { error ->
                item {
                    OfflineCenterMessageCard(error, errorTone = true) {
                        OutlinedButton(onClick = { refreshAttempt += 1 }) { Text("Retry") }
                    }
                }
            }
            actionMessage?.let { message ->
                item { OfflineCenterMessageCard(message, errorTone = false) }
            }
            if (services.supportsBidirectionalFileSync) {
                item {
                    FolderSyncSection(
                        snapshot = syncSnapshot,
                        loading = syncLoading,
                        mediaDiscovery = mediaFolderDiscovery,
                        mediaDiscoveryLoading = mediaDiscoveryLoading,
                        busyPairId = syncBusyPairId,
                        onAdd = {
                            if (syncBusyPairId == null) {
                                scope.launch {
                                    runCatching { services.chooseFileSyncLocalRoot() }
                                        .onSuccess { selected ->
                                            pendingMediaSuggestion = null
                                            pendingLocalRoot = selected
                                        }
                                        .onFailure { failure ->
                                            actionMessage = failure.message ?: "Could not select a local folder."
                                        }
                                }
                            }
                        },
                        onOpenMediaSuggestion = { suggestion ->
                            if (syncBusyPairId == null) {
                                scope.launch {
                                    runCatching {
                                        services.chooseFileSyncLocalRoot(suggestion.localRootHint)
                                    }.onSuccess { selected ->
                                        pendingMediaSuggestion = suggestion
                                        pendingLocalRoot = selected
                                    }.onFailure { failure ->
                                        actionMessage = failure.message ?: "Could not select this media folder."
                                    }
                                }
                            }
                        },
                        onRequestMediaPermission = {
                            if (services.requestPlatformCapability(PlatformCapability.MediaLibrary)) {
                                actionMessage = "After allowing access, refresh to discover media folders."
                            }
                        },
                        onRun = { pair -> runSyncAction(pair.id, remove = false) },
                        onRemove = { pair -> removeSyncPair = pair },
                        onResolve = { pair, conflict, choice ->
                            pendingSyncDecision = PendingFileSyncDecision(pair, conflict, choice)
                        },
                    )
                }
            }
            snapshot?.limitations?.takeIf(List<String>::isNotEmpty)?.let { limitations ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.padding(NextcloudSpacing.Large),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            Text("Current scope", style = MaterialTheme.typography.titleMedium)
                            limitations
                                .filterNot {
                                    services.supportsBidirectionalFileSync &&
                                        it == "Bidirectional folder or vault synchronization is not implemented yet."
                                }
                                .forEach { limitation ->
                                Text(
                                    "• $limitation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            val items = snapshot?.items.orEmpty()
            if (snapshot?.support == FileOfflineCenterSupport.Available) {
                item {
                    Text(
                        "Pinned files",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (items.isEmpty() && !loading) {
                    item {
                        OfflineCenterMessageCard(
                            "No files are pinned. Use a file’s menu in Files and choose “Make available offline”.",
                            errorTone = false,
                        )
                    }
                } else {
                    // Lazy layout keys are saved in Android's Bundle. Keep the domain key
                    // strongly typed everywhere else, but expose a collision-free String here.
                    items(
                        items = items,
                        key = { item ->
                            "${item.key.accountId.length}:${item.key.accountId}${item.key.relativePath}"
                        },
                    ) { item ->
                        OfflineCenterItemCard(
                            item = item,
                            busy = actionKey == item.key,
                            onRetry = { runItemAction(item, remove = false) },
                            onRemove = { removeTarget = item },
                        )
                    }
                }
            }
        }
    }

    removeTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { if (actionKey == null) removeTarget = null },
            title = { Text("Remove offline copy?") },
            text = {
                Text(
                    "The app-private copy of ${item.displayName} will be removed from this device. " +
                        "The file on Nextcloud will not be deleted.",
                )
            },
            confirmButton = {
                Button(
                    enabled = actionKey == null,
                    onClick = {
                        removeTarget = null
                        runItemAction(item, remove = true)
                    },
                ) {
                    Text("Remove local copy")
                }
            },
            dismissButton = {
                TextButton(enabled = actionKey == null, onClick = { removeTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingLocalRoot?.let { localRoot ->
        AddFolderSyncDialog(
            localRoot = localRoot,
            mediaSuggestion = pendingMediaSuggestion,
            busy = syncBusyPairId == ADD_PAIR_BUSY_ID,
            onDismiss = {
                if (syncBusyPairId == null) {
                    pendingLocalRoot = null
                    pendingMediaSuggestion = null
                }
            },
            onAdd = { remotePath, configuration ->
                if (syncBusyPairId != null) return@AddFolderSyncDialog
                syncBusyPairId = ADD_PAIR_BUSY_ID
                actionMessage = null
                scope.launch {
                    runCatching {
                        services.addFileSyncPair(
                            session,
                            userId,
                            localRoot,
                            remotePath,
                            configuration,
                        )
                    }.onSuccess { result ->
                        actionMessage = result.fileSyncCenterMessage()
                        if (result is FileSyncCenterActionResult.Completed) {
                            pendingLocalRoot = null
                            pendingMediaSuggestion = null
                            refreshAttempt += 1
                        }
                    }.onFailure { failure ->
                        actionMessage = failure.message ?: "Could not add this folder sync pair."
                    }
                    syncBusyPairId = null
                }
            },
        )
    }

    removeSyncPair?.let { pair ->
        AlertDialog(
            onDismissRequest = { if (syncBusyPairId == null) removeSyncPair = null },
            title = { Text("Remove folder sync?") },
            text = {
                Text(
                    "This forgets the sync relationship between ${pair.localDisplayName} and " +
                        "${pair.remoteRootPath.ifBlank { "the Nextcloud Files root" }}. " +
                        "It does not delete files from either location.",
                )
            },
            confirmButton = {
                Button(
                    enabled = syncBusyPairId == null,
                    onClick = {
                        removeSyncPair = null
                        runSyncAction(pair.id, remove = true)
                    },
                ) { Text("Remove sync") }
            },
            dismissButton = {
                TextButton(
                    enabled = syncBusyPairId == null,
                    onClick = { removeSyncPair = null },
                ) { Text("Cancel") }
            },
        )
    }

    pendingSyncDecision?.let { target ->
        AlertDialog(
            onDismissRequest = { if (syncBusyPairId == null) pendingSyncDecision = null },
            title = { Text("Resolve sync conflict?") },
            text = {
                Text(
                    target.choice.confirmationText(
                        target.conflict.relativePath,
                        target.conflict.reason,
                    ),
                )
            },
            confirmButton = {
                Button(
                    enabled = syncBusyPairId == null,
                    onClick = {
                        pendingSyncDecision = null
                        resolveSyncConflict(target)
                    },
                ) { Text(target.choice.readableDecision()) }
            },
            dismissButton = {
                TextButton(
                    enabled = syncBusyPairId == null,
                    onClick = { pendingSyncDecision = null },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FolderSyncSection(
    snapshot: FileSyncCenterSnapshot?,
    loading: Boolean,
    mediaDiscovery: MediaSyncFolderDiscovery?,
    mediaDiscoveryLoading: Boolean,
    busyPairId: String?,
    onAdd: () -> Unit,
    onOpenMediaSuggestion: (MediaSyncFolderSuggestion) -> Unit,
    onRequestMediaPermission: () -> Unit,
    onRun: (FileSyncPairSummary) -> Unit,
    onRemove: (FileSyncPairSummary) -> Unit,
    onResolve: (FileSyncPairSummary, FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Folder sync",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Revision-guarded local and Nextcloud folder pairs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(enabled = busyPairId == null, onClick = onAdd) {
                Text("Add")
            }
        }
        if (loading && snapshot == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        MediaFolderSuggestions(
            discovery = mediaDiscovery,
            loading = mediaDiscoveryLoading,
            enabled = busyPairId == null,
            onOpen = onOpenMediaSuggestion,
            onRequestPermission = onRequestMediaPermission,
        )
        val pairs = snapshot?.pairs.orEmpty()
        if (!loading && pairs.isEmpty()) {
            OfflineCenterMessageCard(
                "No folder sync pairs yet. Choose a local folder, then connect it to a folder in Nextcloud Files.",
                errorTone = false,
            )
        }
        pairs.forEach { pair ->
            FolderSyncPairCard(
                pair = pair,
                busy = busyPairId == pair.id,
                actionsEnabled = busyPairId == null,
                onRun = { onRun(pair) },
                onRemove = { onRemove(pair) },
                onResolve = { conflict, choice -> onResolve(pair, conflict, choice) },
            )
        }
    }
}

@Composable
private fun MediaFolderSuggestions(
    discovery: MediaSyncFolderDiscovery?,
    loading: Boolean,
    enabled: Boolean,
    onOpen: (MediaSyncFolderSuggestion) -> Unit,
    onRequestPermission: () -> Unit,
) {
    if (loading && discovery == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        return
    }
    when (discovery?.support) {
        MediaSyncFolderDiscoverySupport.NeedsPermission -> {
            OfflineCenterMessageCard(
                discovery.message ?: "Allow media access to find folders for automatic upload.",
                errorTone = false,
            ) {
                OutlinedButton(enabled = enabled, onClick = onRequestPermission) {
                    Text("Allow photos and videos")
                }
            }
        }

        MediaSyncFolderDiscoverySupport.Available -> {
            if (discovery.suggestions.isEmpty()) {
                discovery.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text("Suggested media folders", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Choose a detected folder to prefill a safe upload-only sync. Android will ask you to confirm access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OfflineCenterMessageCard(
                    "Originals stay in their current Android media folders, so Instagram, WhatsApp, Discord, " +
                        "and other media pickers can still see them. Upload status is tracked separately. " +
                        "Future storage cleanup will only remove verified copies after an explicit review.",
                    errorTone = false,
                )
                discovery.suggestions.take(MAX_VISIBLE_MEDIA_FOLDER_SUGGESTIONS).forEach { suggestion ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
                            onOpen = { if (enabled) onOpen(suggestion) },
                            onShowActions = null,
                            actionsLabel = null,
                        ),
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Row(
                            modifier = Modifier.padding(NextcloudSpacing.Large),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(suggestion.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${suggestion.kind.readableMediaFolderKind()} · " +
                                        "${suggestion.imageCount} photos · ${suggestion.videoCount} videos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Upload to /${suggestion.suggestedRemoteRootPath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text("Choose", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        MediaSyncFolderDiscoverySupport.Unsupported -> {
            discovery.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        null -> Unit
    }
}

@Composable
private fun FolderSyncPairCard(
    pair: FileSyncPairSummary,
    busy: Boolean,
    actionsEnabled: Boolean,
    onRun: () -> Unit,
    onRemove: () -> Unit,
    onResolve: (FileSyncConflictSummary, FileSyncDecisionChoice) -> Unit,
) {
    var menuExpanded by remember(pair.id) { mutableStateOf(false) }
    val menuActions = buildList {
        add(NextcloudCardAction("Sync now", enabled = actionsEnabled, onClick = onRun))
        pair.conflicts.take(MAX_VISIBLE_PAIR_CONFLICTS).forEach { conflict ->
            conflict.choices.sortedBy(FileSyncDecisionChoice::ordinal).forEach { choice ->
                add(
                    NextcloudCardAction(
                        label = "${choice.readableDecision()}: ${conflict.relativePath}",
                        enabled = actionsEnabled,
                        onClick = { onResolve(conflict, choice) },
                    ),
                )
            }
        }
        add(NextcloudCardAction("Remove sync", destructive = true, enabled = actionsEnabled, onClick = onRemove))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = null,
            onShowActions = { menuExpanded = true },
            actionsLabel = "Show actions for ${pair.localDisplayName}",
        ),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text(
                    pair.localDisplayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                NextcloudCardOverflow(
                    itemLabel = pair.localDisplayName,
                    actions = menuActions,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
            Text(
                "↔ Nextcloud /${pair.remoteRootPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${pair.configuration.direction.readableSyncDirection()} · " +
                    "${pair.conflicts.size} conflicts · ${pair.failedCount} failed",
                style = MaterialTheme.typography.bodySmall,
                color = if (pair.conflicts.size + pair.failedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "${pair.configuration.networkPolicy.readableNetworkPolicy()} · " +
                    pair.configuration.powerPolicy.readablePowerPolicy(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pair.scheduleDescription?.let { schedule ->
                Text(
                    schedule,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pair.conflicts.take(MAX_VISIBLE_PAIR_CONFLICTS).forEach { conflict ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    conflict.relativePath,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    conflict.reason.readableDecisionReason(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (pair.conflicts.size > MAX_VISIBLE_PAIR_CONFLICTS) {
                Text(
                    "${pair.conflicts.size - MAX_VISIBLE_PAIR_CONFLICTS} more conflicts. " +
                        "Resolve these first, then refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddFolderSyncDialog(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, FileSyncConfiguration) -> Unit,
) {
    var remotePath by remember(localRoot, mediaSuggestion) {
        mutableStateOf(mediaSuggestion?.suggestedRemoteRootPath.orEmpty())
    }
    var direction by remember(localRoot, mediaSuggestion) {
        mutableStateOf(
            if (mediaSuggestion == null) FileSyncDirection.Bidirectional else FileSyncDirection.UploadOnly,
        )
    }
    var conflictPolicy by remember(localRoot) { mutableStateOf(FileSyncConflictPolicy.Ask) }
    var deletionPolicy by remember(localRoot) { mutableStateOf(FileSyncDeletionPolicy.Ask) }
    var networkPolicy by remember(localRoot) { mutableStateOf(FileSyncNetworkPolicy.AnyConnection) }
    var powerPolicy by remember(localRoot) { mutableStateOf(FileSyncPowerPolicy.BatteryNotLow) }
    var deviceLabel by remember(localRoot) { mutableStateOf("mobile") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add folder sync") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Text("Local folder: ${localRoot.displayName}")
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nextcloud folder path") },
                    supportingText = { Text("For example Notes/Obsidian. Leave empty for the Files root.") },
                    singleLine = true,
                )
                Text("Direction", style = MaterialTheme.typography.labelLarge)
                FileSyncDirection.entries.forEach { option ->
                    FilterChip(
                        selected = direction == option,
                        onClick = { direction = option },
                        label = { Text(option.readableSyncDirection()) },
                    )
                }
                Text("When both copies changed", style = MaterialTheme.typography.labelLarge)
                FileSyncConflictPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = conflictPolicy == option,
                        onClick = { conflictPolicy = option },
                        label = { Text(option.readableConflictPolicy()) },
                    )
                }
                Text("When a file was deleted", style = MaterialTheme.typography.labelLarge)
                FileSyncDeletionPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = deletionPolicy == option,
                        onClick = { deletionPolicy = option },
                        label = { Text(option.readableDeletionPolicy()) },
                    )
                }
                Text("Connection", style = MaterialTheme.typography.labelLarge)
                FileSyncNetworkPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = networkPolicy == option,
                        onClick = { networkPolicy = option },
                        label = { Text(option.readableNetworkPolicy()) },
                    )
                }
                Text("Power", style = MaterialTheme.typography.labelLarge)
                FileSyncPowerPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = powerPolicy == option,
                        onClick = { powerPolicy = option },
                        label = { Text(option.readablePowerPolicy()) },
                    )
                }
                OutlinedTextField(
                    value = deviceLabel,
                    onValueChange = { deviceLabel = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Device label for conflict copies") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && deviceLabel.isNotBlank(),
                onClick = {
                    onAdd(
                        remotePath,
                        FileSyncConfiguration(
                            direction = direction,
                            conflictPolicy = conflictPolicy,
                            deletionPolicy = deletionPolicy,
                            deviceLabel = deviceLabel.trim(),
                            networkPolicy = networkPolicy,
                            powerPolicy = powerPolicy,
                        ),
                    )
                },
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Add sync")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun OfflineCenterSummaryCard(
    snapshot: FileOfflineCenterSnapshot?,
    loading: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Offline storage", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (snapshot?.support) {
                            FileOfflineCenterSupport.Available ->
                                "${snapshot.items.count { it.availability == FileOfflineAvailability.Available }} available · " +
                                    "${snapshot.items.size} tracked"
                            FileOfflineCenterSupport.InventoryUnavailable ->
                                if (
                                    snapshot.folderAvailability ==
                                    FileOfflineFolderAvailability.RecursiveDownloadOnly
                                ) {
                                    "One-way recursive folder availability is supported; detailed inventory is not exposed yet."
                                } else {
                                    "Individual pinning is available; detailed inventory is not exposed yet."
                                }
                            FileOfflineCenterSupport.Unsupported ->
                                "Not available on this platform build."
                            null -> "Loading device status…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            snapshot?.storageUsage?.let { usage ->
                val qualifier = if (usage.estimated) "Estimated " else ""
                Text(
                    buildString {
                        append(qualifier)
                        append(formatOfflineBytes(usage.usedBytes))
                        usage.capacityBytes?.let { append(" of ${formatOfflineBytes(it)}") }
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                usage.capacityBytes?.let { capacity ->
                    LinearProgressIndicator(
                        progress = {
                            (usage.usedBytes.toDouble() / capacity.toDouble())
                                .coerceIn(0.0, 1.0)
                                .toFloat()
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineCenterItemCard(
    item: FileOfflineCenterItem,
    busy: Boolean,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember(item.key) { mutableStateOf(false) }
    val menuActions = buildList {
        if (item.canRetry) add(NextcloudCardAction("Retry", enabled = !busy, onClick = onRetry))
        if (item.canRemove) {
            add(NextcloudCardAction("Remove", destructive = true, enabled = !busy, onClick = onRemove))
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = null,
            onShowActions = if (menuActions.isNotEmpty()) {
                { menuExpanded = true }
            } else {
                null
            },
            actionsLabel = "Show actions for ${item.displayName}",
        ),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.key.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OfflineStatusBadge(item.availability)
                NextcloudCardOverflow(
                    itemLabel = item.displayName,
                    actions = menuActions,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
            val metadata = listOfNotNull(
                item.sizeBytes?.let(::formatOfflineBytes),
                item.detail,
            )
            if (metadata.isNotEmpty()) {
                Text(
                    metadata.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.availability in setOf(
                            FileOfflineAvailability.Failed,
                            FileOfflineAvailability.NeedsAttention,
                        )
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun OfflineStatusBadge(availability: FileOfflineAvailability) {
    val problem = availability in setOf(
        FileOfflineAvailability.Failed,
        FileOfflineAvailability.NeedsAttention,
    )
    Surface(
        color = if (problem) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            availability.offlineCenterLabel(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (problem) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@Composable
private fun OfflineCenterMessageCard(
    message: String,
    errorTone: Boolean,
    content: @Composable () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (errorTone) MaterialTheme.colorScheme.errorContainer else NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Text(
                message,
                color = if (errorTone) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            content()
        }
    }
}

private fun FileOfflineCenterActionResult.offlineCenterActionMessage(): String = when (this) {
    is FileOfflineCenterActionResult.Completed -> message
    is FileOfflineCenterActionResult.Rejected -> reason
    is FileOfflineCenterActionResult.Unsupported -> reason
}

private fun FileSyncCenterActionResult.fileSyncCenterMessage(): String = when (this) {
    is FileSyncCenterActionResult.Completed -> message
    is FileSyncCenterActionResult.Rejected -> reason
    is FileSyncCenterActionResult.Unsupported -> reason
}

private fun FileSyncDirection.readableSyncDirection(): String = when (this) {
    FileSyncDirection.Bidirectional -> "Two-way"
    FileSyncDirection.DownloadOnly -> "Nextcloud to device"
    FileSyncDirection.UploadOnly -> "Device to Nextcloud"
}

private fun MediaSyncFolderKind.readableMediaFolderKind(): String = when (this) {
    MediaSyncFolderKind.Camera -> "Camera"
    MediaSyncFolderKind.Screenshots -> "Screenshots"
    MediaSyncFolderKind.Images -> "Images"
    MediaSyncFolderKind.Videos -> "Videos"
    MediaSyncFolderKind.Mixed -> "Photos and videos"
}

private fun FileSyncConflictPolicy.readableConflictPolicy(): String = when (this) {
    FileSyncConflictPolicy.Ask -> "Ask before changing either copy"
    FileSyncConflictPolicy.KeepBoth -> "Keep both copies"
    FileSyncConflictPolicy.PreferLocal -> "Prefer this device"
    FileSyncConflictPolicy.PreferRemote -> "Prefer Nextcloud"
}

private fun FileSyncDeletionPolicy.readableDeletionPolicy(): String = when (this) {
    FileSyncDeletionPolicy.Ask -> "Ask"
    FileSyncDeletionPolicy.Propagate -> "Delete the other copy"
    FileSyncDeletionPolicy.RestoreMissing -> "Restore the missing copy"
}

private fun FileSyncNetworkPolicy.readableNetworkPolicy(): String = when (this) {
    FileSyncNetworkPolicy.AnyConnection -> "Wi-Fi or mobile data"
    FileSyncNetworkPolicy.Unmetered -> "Unmetered network only"
}

private fun FileSyncPowerPolicy.readablePowerPolicy(): String = when (this) {
    FileSyncPowerPolicy.AnyPower -> "Any battery level"
    FileSyncPowerPolicy.BatteryNotLow -> "Pause when battery is low"
    FileSyncPowerPolicy.Charging -> "Only while charging"
}

private fun FileSyncDecisionReason.readableDecisionReason(): String = when (this) {
    FileSyncDecisionReason.FirstSyncCollision -> "Both folders already contain this path."
    FileSyncDecisionReason.SimultaneousEdit -> "Both copies changed since the last completed sync."
    FileSyncDecisionReason.LocalDeletion -> "The device copy was deleted."
    FileSyncDecisionReason.RemoteDeletion -> "The Nextcloud copy was deleted."
    FileSyncDecisionReason.TypeChanged -> "One side is a file and the other is a folder."
}

private fun FileSyncDecisionChoice.readableDecision(): String = when (this) {
    FileSyncDecisionChoice.UseLocal -> "Use device copy"
    FileSyncDecisionChoice.UseRemote -> "Use Nextcloud copy"
    FileSyncDecisionChoice.KeepBoth -> "Keep both"
    FileSyncDecisionChoice.PropagateDeletion -> "Delete other copy"
    FileSyncDecisionChoice.RestoreMissing -> "Restore missing copy"
    FileSyncDecisionChoice.Skip -> "Skip this version"
}

private fun FileSyncDecisionChoice.confirmationText(
    path: String,
    reason: FileSyncDecisionReason,
): String = when (this) {
    FileSyncDecisionChoice.UseLocal ->
        "Use the device version of $path. The current Nextcloud version will be replaced. " +
            "The operation stops if either observed revision changed."
    FileSyncDecisionChoice.UseRemote ->
        "Use the Nextcloud version of $path. The current device version will be replaced. " +
            "The operation stops if either observed revision changed."
    FileSyncDecisionChoice.KeepBoth ->
        "Preserve both versions of $path as named conflict copies and keep the Nextcloud version " +
            "at the original path."
    FileSyncDecisionChoice.PropagateDeletion ->
        "Apply the deletion for $path to the other location. This permanently removes the other copy " +
            "only if its observed revision is unchanged."
    FileSyncDecisionChoice.RestoreMissing ->
        "Restore the missing copy of $path from the surviving unchanged version."
    FileSyncDecisionChoice.Skip ->
        "Skip $path for this exact observed conflict (${reason.readableDecisionReason()}). " +
            "It will be reconsidered if either side changes."
}

private fun formatOfflineBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private const val ADD_PAIR_BUSY_ID = "__adding_sync_pair__"
private const val MAX_VISIBLE_PAIR_CONFLICTS = 5
private const val MAX_VISIBLE_MEDIA_FOLDER_SUGGESTIONS = 6

private data class PendingFileSyncDecision(
    val pair: FileSyncPairSummary,
    val conflict: FileSyncConflictSummary,
    val choice: FileSyncDecisionChoice,
)
