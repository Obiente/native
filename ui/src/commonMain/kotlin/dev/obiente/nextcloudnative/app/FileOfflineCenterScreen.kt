package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import kotlinx.coroutines.CancellationException
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
    var pendingRemotePath by remember(session, userId) { mutableStateOf<String?>(null) }
    var pendingSyncConfiguration by remember(session, userId) {
        mutableStateOf<FileSyncConfiguration?>(null)
    }
    var remoteFolderPickerVisible by remember(session, userId) { mutableStateOf(false) }
    var pendingMediaPreview by remember(session, userId) { mutableStateOf<MediaSyncFolderPreview?>(null) }
    var mediaPreviewLoading by remember(session, userId) { mutableStateOf(false) }
    var mediaPreviewError by remember(session, userId) { mutableStateOf<String?>(null) }
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

    LaunchedEffect(pendingMediaSuggestion) {
        val suggestion = pendingMediaSuggestion ?: run {
            pendingMediaPreview = null
            mediaPreviewLoading = false
            mediaPreviewError = null
            return@LaunchedEffect
        }
        mediaPreviewLoading = true
        mediaPreviewError = null
        pendingMediaPreview = null
        try {
            pendingMediaPreview = services.previewMediaSyncFolder(suggestion)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mediaPreviewError = failure.message ?: "Could not preview this media folder."
        } finally {
            mediaPreviewLoading = false
        }
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
                                            pendingRemotePath = null
                                            pendingSyncConfiguration = selected?.let {
                                                defaultFileSyncConfiguration(isMediaSuggestion = false)
                                            }
                                            remoteFolderPickerVisible = selected != null
                                        }
                                        .onFailure { failure ->
                                            actionMessage = failure.message ?: "Could not select a local folder."
                                        }
                                }
                            }
                        },
                        onOpenMediaSuggestion = { suggestion ->
                            if (syncBusyPairId == null) {
                                pendingMediaPreview = null
                                mediaPreviewError = null
                                pendingMediaSuggestion = suggestion
                                pendingLocalRoot = suggestion.localRoot
                                pendingRemotePath = null
                                pendingSyncConfiguration = defaultFileSyncConfiguration(isMediaSuggestion = true)
                                remoteFolderPickerVisible = true
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
                            "No files are pinned. Use a file's menu in Files and choose \"Make available offline\".",
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

    val localRootForDestination = pendingLocalRoot
    if (remoteFolderPickerVisible && localRootForDestination != null) {
        RemoteFolderPickerDialog(
            services = services,
            session = session,
            userId = userId,
            initialPath = pendingRemotePath
                ?: pendingMediaSuggestion?.suggestedRemoteRootPath.orEmpty(),
            onDismiss = {
                remoteFolderPickerVisible = false
                if (pendingRemotePath == null) {
                    pendingLocalRoot = null
                    pendingMediaSuggestion = null
                    pendingSyncConfiguration = null
                }
            },
            onSelected = { selectedPath ->
                pendingRemotePath = selectedPath
                remoteFolderPickerVisible = false
            },
        )
    }

    pendingLocalRoot?.takeIf {
        !remoteFolderPickerVisible && pendingRemotePath != null && pendingSyncConfiguration != null
    }?.let { localRoot ->
        AddFolderSyncDialog(
            localRoot = localRoot,
            mediaSuggestion = pendingMediaSuggestion,
            remotePath = requireNotNull(pendingRemotePath),
            configuration = requireNotNull(pendingSyncConfiguration),
            mediaPreview = pendingMediaPreview,
            mediaPreviewLoading = mediaPreviewLoading,
            mediaPreviewError = mediaPreviewError,
            busy = syncBusyPairId == ADD_PAIR_BUSY_ID,
            onDismiss = {
                if (syncBusyPairId == null) {
                    pendingLocalRoot = null
                    pendingMediaSuggestion = null
                    pendingRemotePath = null
                    pendingSyncConfiguration = null
                    pendingMediaPreview = null
                }
            },
            onChooseDestination = {
                if (syncBusyPairId == null) remoteFolderPickerVisible = true
            },
            onConfigurationChanged = { pendingSyncConfiguration = it },
            onAdd = {
                if (syncBusyPairId != null) return@AddFolderSyncDialog
                syncBusyPairId = ADD_PAIR_BUSY_ID
                actionMessage = null
                scope.launch {
                    runCatching {
                        services.addFileSyncPair(
                            session,
                            userId,
                            localRoot,
                            requireNotNull(pendingRemotePath),
                            requireNotNull(pendingSyncConfiguration).let { configuration ->
                                configuration.copy(deviceLabel = configuration.deviceLabel.trim())
                            },
                        )
                    }.onSuccess { result ->
                        actionMessage = result.fileSyncCenterMessage()
                        if (result is FileSyncCenterActionResult.Completed) {
                            pendingLocalRoot = null
                            pendingMediaSuggestion = null
                            pendingRemotePath = null
                            pendingSyncConfiguration = null
                            pendingMediaPreview = null
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
internal fun FolderSyncSection(
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
                    "Choose a detected folder to review a prefilled, upload-only sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Originals stay visible to other Android apps. Backup status is tracked separately, " +
                        "and storage cleanup always requires review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (discovery.access == MediaSyncFolderAccess.LimitedSelection) {
                    OfflineCenterMessageCard(
                        "Android granted partial media access. Counts and previews cover only permitted " +
                            "photos and videos, not necessarily every item stored in these folders.",
                        errorTone = true,
                    )
                }
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
                                    "Estimated size ${formatOfflineBytes(suggestion.totalBytes)}",
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
                fileSyncRouteLabel(pair.configuration.direction, pair.remoteRootPath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${pair.configuration.direction.readableSyncDirection()} · " +
                    "${pair.readyCount} pending · ${pair.runningCount} syncing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${pair.completedCount} completed · ${pair.conflicts.size} conflicts · ${pair.failedCount} failed",
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
private fun MediaFolderPreview(
    suggestion: MediaSyncFolderSuggestion,
    preview: MediaSyncFolderPreview?,
    loading: Boolean,
    error: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text("Review what will upload", style = MaterialTheme.typography.labelLarge)
            when {
                loading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Loading a bounded preview...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                error != null -> {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Sync cannot be enabled until the folder can be reviewed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                preview != null -> {
                    val totalLabel = buildString {
                        append(preview.totalItems).append(if (preview.totalItems == 1) " item" else " items")
                        append(" · ").append(formatOfflineBytes(preview.totalBytes))
                    }
                    Text(totalLabel, style = MaterialTheme.typography.titleSmall)
                    preview.message?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (
                                preview.access == MediaSyncFolderAccess.LimitedSelection ||
                                preview.state != MediaSyncFolderPreviewState.Available
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (preview.access == MediaSyncFolderAccess.LimitedSelection) {
                        Text(
                            "Grant full access for this folder's media types before enabling automatic upload.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (preview.items.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            contentPadding = PaddingValues(vertical = NextcloudSpacing.XSmall),
                        ) {
                            items(
                                items = preview.items,
                                key = MediaSyncFolderPreviewItem::stableId,
                            ) { item ->
                                MediaFolderPreviewTile(item)
                            }
                        }
                        if (preview.totalItems > preview.items.size) {
                            Text(
                                "Showing ${preview.items.size} recent items. " +
                                    "${preview.totalItems - preview.items.size} more are included.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (
                        preview.state == MediaSyncFolderPreviewState.Available ||
                        preview.state == MediaSyncFolderPreviewState.Changed
                    ) {
                        Text(
                            "No representative thumbnails are available, but the folder metadata was verified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Text(
                    "${suggestion.imageCount.toLong() + suggestion.videoCount.toLong()} detected items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaFolderPreviewTile(item: MediaSyncFolderPreviewItem) {
    Column(
        modifier = Modifier.size(width = 112.dp, height = 142.dp),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        val image = remember(item.stableId, item.thumbnailBytes) {
            item.thumbnailBytes?.let(::decodePlatformImage)
        }
        Surface(
            modifier = Modifier.size(112.dp, 96.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(NextcloudRadii.Small),
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(NextcloudRadii.Small)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (item.mimeType?.startsWith("video/") == true) "Video" else "Photo",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Text(
            item.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            buildString {
                append(if (item.mimeType?.startsWith("video/") == true) "Video" else "Photo")
                item.sizeBytes?.let { append(" · ").append(formatOfflineBytes(it)) }
            },
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddFolderSyncDialog(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    configuration: FileSyncConfiguration,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChooseDestination: () -> Unit,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onAdd: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add folder sync") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Text("Local folder: ${mediaSuggestion?.relativePath ?: localRoot.displayName}")
                if (mediaSuggestion != null) {
                    MediaFolderPreview(
                        suggestion = mediaSuggestion,
                        preview = mediaPreview,
                        loading = mediaPreviewLoading,
                        error = mediaPreviewError,
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                ) {
                    Column(
                        modifier = Modifier.padding(NextcloudSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        Text("Nextcloud destination", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (remotePath.isEmpty()) "Files root" else "/$remotePath",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(enabled = !busy, onClick = onChooseDestination) {
                            Text("Choose another folder")
                        }
                    }
                }
                Text("Direction", style = MaterialTheme.typography.labelLarge)
                val directionOptions = if (mediaSuggestion == null) {
                    FileSyncDirection.entries
                } else {
                    listOf(FileSyncDirection.UploadOnly)
                }
                directionOptions.forEach { option ->
                    FilterChip(
                        selected = configuration.direction == option,
                        onClick = { onConfigurationChanged(configuration.copy(direction = option)) },
                        label = { Text(option.readableSyncDirection()) },
                    )
                }
                if (mediaSuggestion != null) {
                    Text(
                        "Detected media folders are upload-only. Nextcloud never writes into this local folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("When both copies changed", style = MaterialTheme.typography.labelLarge)
                FileSyncConflictPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = configuration.conflictPolicy == option,
                        onClick = { onConfigurationChanged(configuration.copy(conflictPolicy = option)) },
                        label = { Text(option.readableConflictPolicy()) },
                    )
                }
                Text("When a file was deleted", style = MaterialTheme.typography.labelLarge)
                FileSyncDeletionPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = configuration.deletionPolicy == option,
                        onClick = { onConfigurationChanged(configuration.copy(deletionPolicy = option)) },
                        label = { Text(option.readableDeletionPolicy()) },
                    )
                }
                Text("Connection", style = MaterialTheme.typography.labelLarge)
                FileSyncNetworkPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = configuration.networkPolicy == option,
                        onClick = { onConfigurationChanged(configuration.copy(networkPolicy = option)) },
                        label = { Text(option.readableNetworkPolicy()) },
                    )
                }
                Text("Power", style = MaterialTheme.typography.labelLarge)
                FileSyncPowerPolicy.entries.forEach { option ->
                    FilterChip(
                        selected = configuration.powerPolicy == option,
                        onClick = { onConfigurationChanged(configuration.copy(powerPolicy = option)) },
                        label = { Text(option.readablePowerPolicy()) },
                    )
                }
                OutlinedTextField(
                    value = configuration.deviceLabel,
                    onValueChange = {
                        onConfigurationChanged(configuration.copy(deviceLabel = it.take(128)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Device label for conflict copies") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy &&
                    configuration.deviceLabel.isNotBlank() &&
                    isMediaFolderPreviewReady(mediaSuggestion, mediaPreview),
                onClick = onAdd,
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

private fun defaultFileSyncConfiguration(isMediaSuggestion: Boolean): FileSyncConfiguration =
    FileSyncConfiguration(
        direction = if (isMediaSuggestion) FileSyncDirection.UploadOnly else FileSyncDirection.Bidirectional,
        conflictPolicy = FileSyncConflictPolicy.Ask,
        deletionPolicy = FileSyncDeletionPolicy.Ask,
        deviceLabel = "mobile",
        networkPolicy = FileSyncNetworkPolicy.AnyConnection,
        powerPolicy = FileSyncPowerPolicy.BatteryNotLow,
    )

internal fun isMediaFolderPreviewReady(
    suggestion: MediaSyncFolderSuggestion?,
    preview: MediaSyncFolderPreview?,
): Boolean =
    suggestion == null ||
        (
            preview != null &&
                preview.access == MediaSyncFolderAccess.FullLibrary &&
                preview.state in setOf(
                    MediaSyncFolderPreviewState.Available,
                    MediaSyncFolderPreviewState.Changed,
                )
        )

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
                            null -> "Loading device status..."
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

internal fun fileSyncRouteLabel(
    direction: FileSyncDirection,
    remoteRootPath: String,
): String {
    val remote = "Nextcloud /${remoteRootPath.trimStart('/')}"
    return when (direction) {
        FileSyncDirection.Bidirectional -> "Device ↔ $remote"
        FileSyncDirection.DownloadOnly -> "$remote → device"
        FileSyncDirection.UploadOnly -> "Device → $remote"
    }
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
