package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

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
    var pendingLocalRootJson by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf<String?>(null)
    }
    var pendingMediaSuggestionJson by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf<String?>(null)
    }
    var pendingRemotePath by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf<String?>(null)
    }
    var pendingSyncConfigurationJson by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf<String?>(null)
    }
    var remoteFolderPickerVisible by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf(false)
    }
    var syncSelectionPickerVisible by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf(false)
    }
    val pendingLocalRoot = pendingLocalRootJson?.let { encoded ->
        runCatching { fileSyncSetupJson.decodeFromString<FileSyncLocalRoot>(encoded) }.getOrNull()
    }
    val pendingMediaSuggestion = pendingMediaSuggestionJson?.let { encoded ->
        runCatching { fileSyncSetupJson.decodeFromString<MediaSyncFolderSuggestion>(encoded) }.getOrNull()
    }
    val pendingSyncConfiguration = pendingSyncConfigurationJson?.let { encoded ->
        runCatching { fileSyncSetupJson.decodeFromString<FileSyncConfiguration>(encoded) }.getOrNull()
    }
    var pendingMediaPreview by remember(session, userId) { mutableStateOf<MediaSyncFolderPreview?>(null) }
    var mediaPreviewLoading by remember(session, userId) { mutableStateOf(false) }
    var mediaPreviewError by remember(session, userId) { mutableStateOf<String?>(null) }
    var removeSyncPair by remember(session, userId) { mutableStateOf<FileSyncPairSummary?>(null) }
    var pendingSyncDecision by remember(session, userId) {
        mutableStateOf<PendingFileSyncDecision?>(null)
    }
    var virtualStorage by remember(session, userId) { mutableStateOf<VirtualFileStorageSnapshot?>(null) }
    var virtualStorageLoading by remember(session, userId) { mutableStateOf(false) }
    var virtualStorageBusy by remember(session, userId) { mutableStateOf(false) }
    var virtualStorageSettingsVisible by remember(session, userId) { mutableStateOf(false) }
    var virtualLocationVisible by remember(session, userId) { mutableStateOf(false) }
    var virtualLocationError by remember(session, userId) { mutableStateOf<String?>(null) }
    var virtualFolderPickerVisible by remember(session, userId) { mutableStateOf(false) }
    var virtualFolderPickerError by remember(session, userId) { mutableStateOf<String?>(null) }
    var releaseVirtualFolderPath by remember(session, userId) { mutableStateOf<String?>(null) }
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

    fun saveVirtualStoragePolicy(policy: VirtualFileCachePolicy) {
        if (virtualStorageBusy) return
        virtualStorageBusy = true
        actionMessage = null
        scope.launch {
            runCatching { services.saveVirtualFileCachePolicy(session, userId, policy) }
                .onSuccess { result ->
                    actionMessage = result.virtualFileStorageMessage()
                    if (result is VirtualFileStorageActionResult.Completed) {
                        virtualStorageSettingsVisible = false
                        refreshAttempt += 1
                    }
                }
                .onFailure { failure ->
                    actionMessage = failure.message ?: "Could not save virtual file storage rules."
                }
            virtualStorageBusy = false
        }
    }

    fun freeUpVirtualStorage() {
        val requested = virtualStorage?.reclaimableBytes ?: return
        if (virtualStorageBusy || requested == 0L) return
        virtualStorageBusy = true
        actionMessage = null
        scope.launch {
            runCatching { services.freeUpVirtualFileSpace(session, userId, requested) }
                .onSuccess { result ->
                    actionMessage = result.virtualFileStorageMessage()
                    refreshAttempt += 1
                }
                .onFailure { failure ->
                    actionMessage = failure.message ?: "Could not free virtual file storage."
                }
            virtualStorageBusy = false
        }
    }

    fun setVirtualFileProviderActive(active: Boolean) {
        if (virtualStorageBusy) return
        virtualStorageBusy = true
        actionMessage = null
        scope.launch {
            runCatching {
                if (active) {
                    services.activateVirtualFileProvider(session, userId)
                } else {
                    services.deactivateVirtualFileProvider(session, userId)
                }
            }.onSuccess { result ->
                actionMessage = result.virtualFileStorageMessage()
                refreshAttempt += 1
            }.onFailure { failure ->
                actionMessage = failure.message ?: "Could not change the virtual file provider."
            }
            virtualStorageBusy = false
        }
    }

    fun saveVirtualFileLocation(location: VirtualFileProviderLocation) {
        if (virtualStorageBusy) return
        virtualStorageBusy = true
        actionMessage = null
        virtualLocationError = null
        scope.launch {
            runCatching { services.saveVirtualFileProviderLocation(session, userId, location) }
                .onSuccess { result ->
                    val message = result.virtualFileStorageMessage()
                    actionMessage = message
                    if (result is VirtualFileStorageActionResult.Completed) {
                        virtualLocationVisible = false
                        refreshAttempt += 1
                    } else {
                        virtualLocationError = message
                    }
                }
                .onFailure { failure ->
                    val message = failure.message ?: "Could not change the virtual file location."
                    actionMessage = message
                    virtualLocationError = message
                }
            virtualStorageBusy = false
        }
    }

    fun setVirtualFolderRetention(path: String, retention: VirtualFolderRetention) {
        if (virtualStorageBusy) return
        virtualStorageBusy = true
        actionMessage = null
        scope.launch {
            runCatching { services.setVirtualFolderRetention(session, userId, path, retention) }
                .onSuccess { result ->
                    val message = result.virtualFileStorageMessage()
                    actionMessage = message
                    if (result is VirtualFileStorageActionResult.Completed) {
                        virtualFolderPickerVisible = false
                        virtualFolderPickerError = null
                        refreshAttempt += 1
                    } else {
                        virtualFolderPickerError = message
                    }
                }
                .onFailure { failure ->
                    val message = failure.message ?: "Could not change folder availability."
                    actionMessage = message
                    virtualFolderPickerError = message
                }
            virtualStorageBusy = false
        }
    }

    fun retryVirtualFolderHydration(path: String) {
        if (virtualStorageBusy) return
        virtualStorageBusy = true
        actionMessage = null
        scope.launch {
            runCatching { services.retryVirtualFolderHydration(session, userId, path) }
                .onSuccess { result ->
                    actionMessage = result.virtualFileStorageMessage()
                    refreshAttempt += 1
                }
                .onFailure { failure ->
                    actionMessage = failure.message ?: "Could not retry this offline folder."
                }
            virtualStorageBusy = false
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
        loading = false
    }

    LaunchedEffect(session, userId, refreshAttempt) {
        if (userId.isBlank() || !services.supportsVirtualFileStorage) return@LaunchedEffect
        virtualStorageLoading = true
        try {
            while (true) {
                val loaded = services.loadVirtualFileStorage(session, userId)
                virtualStorage = loaded
                virtualStorageLoading = false
                val pollDelay = virtualStorageHydrationPollDelay(
                    loaded.folderHydrationStatuses,
                    nowEpochMillis = Clock.System.now().toEpochMilliseconds().coerceAtLeast(0L),
                ) ?: break
                delay(pollDelay)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            actionMessage = failure.message ?: "Could not load virtual file storage."
        } finally {
            virtualStorageLoading = false
        }
    }

    LaunchedEffect(session, userId, refreshAttempt) {
        if (userId.isBlank() || !services.supportsBidirectionalFileSync) return@LaunchedEffect
        syncLoading = true
        try {
            syncSnapshot = services.loadFileSyncCenter(session, userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            actionMessage = failure.message ?: "Could not load folder sync pairs."
        } finally {
            syncLoading = false
        }
    }

    LaunchedEffect(session, userId, refreshAttempt) {
        if (userId.isBlank() || !services.supportsBidirectionalFileSync) return@LaunchedEffect
        mediaDiscoveryLoading = true
        try {
            mediaFolderDiscovery = services.discoverMediaSyncFolders()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mediaFolderDiscovery = MediaSyncFolderDiscovery(
                support = MediaSyncFolderDiscoverySupport.Unsupported,
                suggestions = emptyList(),
                message = failure.message ?: "Could not inspect local media folders.",
            )
        } finally {
            mediaDiscoveryLoading = false
        }
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
                    enabled = fileOfflineRefreshEnabled(
                        loading = loading,
                        mediaDiscoveryLoading = mediaDiscoveryLoading,
                        actionInProgress = actionKey != null || virtualStorageBusy,
                    ),
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
                                            pendingMediaSuggestionJson = null
                                            pendingLocalRootJson = selected?.let { fileSyncSetupJson.encodeToString(it) }
                                            pendingRemotePath = selected?.let { "" }
                                            pendingSyncConfigurationJson = selected?.let {
                                                defaultFileSyncConfiguration(isMediaSuggestion = false)
                                            }?.let { fileSyncSetupJson.encodeToString(it) }
                                            remoteFolderPickerVisible = false
                                            syncSelectionPickerVisible = false
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
                                pendingMediaSuggestionJson = fileSyncSetupJson.encodeToString(suggestion)
                                pendingLocalRootJson = fileSyncSetupJson.encodeToString(suggestion.localRoot)
                                pendingRemotePath = suggestion.suggestedRemoteRootPath
                                pendingSyncConfigurationJson = fileSyncSetupJson.encodeToString(
                                    defaultFileSyncConfiguration(isMediaSuggestion = true),
                                )
                                remoteFolderPickerVisible = false
                                syncSelectionPickerVisible = false
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
            if (services.supportsVirtualFileStorage) {
                item {
                    VirtualFileStorageCard(
                        snapshot = virtualStorage,
                        loading = virtualStorageLoading,
                        busy = virtualStorageBusy,
                        onManage = { virtualStorageSettingsVisible = true },
                        onFreeUp = ::freeUpVirtualStorage,
                        onActivateProvider = { setVirtualFileProviderActive(true) },
                        onDeactivateProvider = { setVirtualFileProviderActive(false) },
                        onChangeLocation = {
                            virtualLocationError = null
                            virtualLocationVisible = true
                        },
                        onChoosePinnedFolder = {
                            virtualFolderPickerError = null
                            virtualFolderPickerVisible = true
                        },
                        onReleaseFolder = { path ->
                            releaseVirtualFolderPath = path
                        },
                        onRetryFolder = { path ->
                            retryVirtualFolderHydration(path)
                        },
                    )
                }
            }
            item {
                OfflineCenterSummaryCard(snapshot, loading)
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
                                    "- $limitation",
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

    releaseVirtualFolderPath?.let { path ->
        AlertDialog(
            onDismissRequest = { if (!virtualStorageBusy) releaseVirtualFolderPath = null },
            title = { Text("Make this folder online-only?") },
            text = {
                Text(
                    "The downloaded copy of $path and its contents will be removed from this device. " +
                        "Everything stays visible in Nextcloud and downloads again when opened.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !virtualStorageBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        releaseVirtualFolderPath = null
                        setVirtualFolderRetention(path, VirtualFolderRetention.Automatic)
                    },
                ) {
                    Text("Remove local folder copy")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !virtualStorageBusy,
                    onClick = { releaseVirtualFolderPath = null },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (virtualStorageSettingsVisible) {
        virtualStorage?.let { current ->
            VirtualFileStoragePolicyDialog(
                snapshot = current,
                busy = virtualStorageBusy,
                onDismiss = { if (!virtualStorageBusy) virtualStorageSettingsVisible = false },
                onSave = ::saveVirtualStoragePolicy,
            )
        }
    }

    if (virtualLocationVisible) {
        virtualStorage?.providerLocationConfiguration?.let { current ->
            VirtualFileProviderLocationDialog(
                services = services,
                initial = current,
                busy = virtualStorageBusy,
                error = virtualLocationError,
                onDismiss = {
                    if (!virtualStorageBusy) {
                        virtualLocationVisible = false
                        virtualLocationError = null
                    }
                },
                onSave = ::saveVirtualFileLocation,
            )
        }
    }

    if (virtualFolderPickerVisible) {
        RemoteFolderPickerDialog(
            services = services,
            session = session,
            userId = userId,
            initialPath = "",
            selectionError = virtualFolderPickerError,
            onDismiss = {
                if (!virtualStorageBusy) {
                    virtualFolderPickerVisible = false
                    virtualFolderPickerError = null
                }
            },
            onSelected = { path ->
                if (path.isEmpty()) {
                    val message = "Choose a folder below the Files root."
                    actionMessage = message
                    virtualFolderPickerError = message
                } else {
                    virtualFolderPickerError = null
                    setVirtualFolderRetention(path, VirtualFolderRetention.KeepOnDevice)
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
                    pendingLocalRootJson = null
                    pendingMediaSuggestionJson = null
                    pendingSyncConfigurationJson = null
                }
            },
            onSelected = { selectedPath ->
                if (pendingRemotePath != selectedPath) {
                    pendingSyncConfiguration?.let { configuration ->
                        pendingSyncConfigurationJson = fileSyncSetupJson.encodeToString(
                            configuration.copy(selectedPaths = emptyList()),
                        )
                    }
                }
                pendingRemotePath = selectedPath
                remoteFolderPickerVisible = false
            },
        )
    }

    val selectionConfiguration = pendingSyncConfiguration
    val selectionRemoteRoot = pendingRemotePath
    if (
        syncSelectionPickerVisible &&
        selectionConfiguration != null &&
        selectionRemoteRoot != null
    ) {
        RemoteFileSyncSelectionDialog(
            services = services,
            session = session,
            userId = userId,
            remoteRootPath = selectionRemoteRoot,
            initialSelection = selectionConfiguration.selectedPaths,
            onDismiss = { syncSelectionPickerVisible = false },
            onSelected = { selectedPaths ->
                pendingSyncConfigurationJson = fileSyncSetupJson.encodeToString(
                    selectionConfiguration.copy(selectedPaths = selectedPaths),
                )
                syncSelectionPickerVisible = false
            },
        )
    }

    pendingLocalRoot?.takeIf {
        !remoteFolderPickerVisible &&
            !syncSelectionPickerVisible &&
            pendingRemotePath != null &&
            pendingSyncConfiguration != null
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
                    pendingLocalRootJson = null
                    pendingMediaSuggestionJson = null
                    pendingRemotePath = null
                    pendingSyncConfigurationJson = null
                    pendingMediaPreview = null
                    syncSelectionPickerVisible = false
                }
            },
            onChooseDestination = {
                if (syncBusyPairId == null) remoteFolderPickerVisible = true
            },
            onChooseSelectedPaths = {
                if (syncBusyPairId == null) syncSelectionPickerVisible = true
            },
            onConfigurationChanged = { pendingSyncConfigurationJson = fileSyncSetupJson.encodeToString(it) },
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
                            pendingLocalRootJson = null
                            pendingMediaSuggestionJson = null
                            pendingRemotePath = null
                            pendingSyncConfigurationJson = null
                            pendingMediaPreview = null
                            syncSelectionPickerVisible = false
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

internal fun virtualStorageHydrationPollDelay(
    statuses: List<VirtualFolderHydrationStatus>,
    nowEpochMillis: Long,
): Long? {
    require(nowEpochMillis >= 0L)
    if (statuses.any { status ->
            status.phase == VirtualFolderHydrationPhase.Queued ||
                status.phase == VirtualFolderHydrationPhase.Downloading ||
                status.refreshing
        }
    ) return VIRTUAL_STORAGE_HYDRATION_POLL_MILLIS
    val retryAt = statuses.mapNotNull(VirtualFolderHydrationStatus::refreshRetryAtEpochMillis).minOrNull()
        ?: return null
    if (retryAt <= nowEpochMillis) return VIRTUAL_STORAGE_RETRY_POLL_MILLIS
    return (retryAt - nowEpochMillis).coerceAtMost(VIRTUAL_STORAGE_RETRY_POLL_MILLIS)
}

private const val VIRTUAL_STORAGE_HYDRATION_POLL_MILLIS = 750L
private const val VIRTUAL_STORAGE_RETRY_POLL_MILLIS = 10_000L

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
        MediaFolderSuggestions(
            discovery = mediaDiscovery,
            loading = mediaDiscoveryLoading,
            enabled = busyPairId == null,
            onOpen = onOpenMediaSuggestion,
            onRequestPermission = onRequestMediaPermission,
        )
        FileSyncWorkspace(
            snapshot = snapshot,
            loading = loading,
            busyPairId = busyPairId,
            onAdd = onAdd,
            onRun = onRun,
            onRemove = onRemove,
            onResolve = onResolve,
        )
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
                                    "${suggestion.kind.readableMediaFolderKind()} | " +
                                        "${suggestion.imageCount} photos | ${suggestion.videoCount} videos",
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
                "${pair.configuration.direction.readableSyncDirection()} | " +
                    "${pair.readyCount} pending | ${pair.runningCount} syncing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${pair.completedCount} completed | ${pair.conflicts.size} conflicts | ${pair.failedCount} failed",
                style = MaterialTheme.typography.bodySmall,
                color = if (pair.conflicts.size + pair.failedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "${pair.configuration.networkPolicy.readableNetworkPolicy()} | " +
                    pair.configuration.powerPolicy.readablePowerPolicy(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (
                pair.configuration.selectedPaths.isNotEmpty() ||
                pair.configuration.ignoredPatterns.isNotEmpty() ||
                pair.configuration.priorityRules.isNotEmpty()
            ) {
                Text(
                    buildString {
                        if (pair.configuration.selectedPaths.isEmpty()) append("Whole folder")
                        else append(pair.configuration.selectedPaths.size).append(" selected path")
                            .append(if (pair.configuration.selectedPaths.size == 1) "" else "s")
                        append("; ").append(pair.configuration.ignoredPatterns.size).append(" ignore rule")
                            .append(if (pair.configuration.ignoredPatterns.size == 1) "" else "s")
                        append("; ").append(pair.configuration.priorityRules.size).append(" priority group")
                            .append(if (pair.configuration.priorityRules.size == 1) "" else "s")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                        append(" | ").append(formatOfflineBytes(preview.totalBytes))
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
                item.sizeBytes?.let { append(" | ").append(formatOfflineBytes(it)) }
            },
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AddFolderSyncDialog(
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
    onChooseSelectedPaths: () -> Unit,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onAdd: () -> Unit,
) {
    GuidedAddFolderSyncDialog(
        localRoot = localRoot,
        mediaSuggestion = mediaSuggestion,
        remotePath = remotePath,
        configuration = configuration,
        mediaPreview = mediaPreview,
        mediaPreviewLoading = mediaPreviewLoading,
        mediaPreviewError = mediaPreviewError,
        busy = busy,
        onDismiss = onDismiss,
        onChooseDestination = onChooseDestination,
        onChooseSelectedPaths = onChooseSelectedPaths,
        onConfigurationChanged = onConfigurationChanged,
        onAdd = onAdd,
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

private val fileSyncSetupJson = Json {
    encodeDefaults = true
}

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
internal fun VirtualFileStorageCard(
    snapshot: VirtualFileStorageSnapshot?,
    loading: Boolean,
    busy: Boolean,
    onManage: () -> Unit,
    onFreeUp: () -> Unit,
    onActivateProvider: () -> Unit,
    onDeactivateProvider: () -> Unit,
    onChangeLocation: () -> Unit,
    onChoosePinnedFolder: () -> Unit,
    onReleaseFolder: (String) -> Unit,
    onRetryFolder: (String) -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Virtual files", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (snapshot?.integration) {
                            VirtualFilePlatformIntegration.AndroidDocumentsProvider ->
                                "Browse everything in System Files. Content downloads only when opened."
                            VirtualFilePlatformIntegration.LinuxFilesystemMount ->
                                "Browse placeholders in your Linux file manager. Content downloads when opened."
                            VirtualFilePlatformIntegration.InAppOnDemandCache ->
                                "Files opened in Nextcloud Native are kept in a managed on-demand cache."
                            VirtualFilePlatformIntegration.WindowsCloudFiles ->
                                "Browse everything in File Explorer. Files download when opened and local edits sync back."
                            VirtualFilePlatformIntegration.AppleFileProvider ->
                                "Files hydrate through the system File Provider."
                            null -> "Loading on-demand storage status..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading || busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            when (snapshot?.support) {
                                VirtualFileStorageSupport.Available -> "System integrated"
                                VirtualFileStorageSupport.CacheOnly -> "App cache"
                                VirtualFileStorageSupport.Unsupported -> "Unavailable"
                                null -> "Checking"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            if (snapshot != null) {
                val maximum = snapshot.policy.maximumCacheBytes
                if (maximum != null) {
                    LinearProgressIndicator(
                        progress = {
                            (snapshot.cachedBytes.toDouble() / maximum.toDouble())
                                .coerceIn(0.0, 1.0)
                                .toFloat()
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    VirtualFileStorageMetric(
                        label = "Cached",
                        value = formatVirtualFileBytes(snapshot.cachedBytes),
                        modifier = Modifier.weight(1f),
                    )
                    VirtualFileStorageMetric(
                        label = "Pinned",
                        value = formatVirtualFileBytes(snapshot.pinnedBytes),
                        modifier = Modifier.weight(1f),
                    )
                    VirtualFileStorageMetric(
                        label = "Free",
                        value = snapshot.availableFreeBytes?.let(::formatVirtualFileBytes) ?: "Unknown",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (snapshot.policy.automaticCleanup) {
                        buildString {
                            append("Auto cleanup keeps at least ")
                            append(formatVirtualFileBytes(snapshot.policy.minimumFreeSpaceBytes))
                            append(" free")
                            snapshot.policy.unusedFileAgeMillis?.let { age ->
                                append(" and removes unused cached files after ")
                                append(formatVirtualFileAge(age))
                            }
                            append(". Pins and active work are always kept.")
                        }
                    } else {
                        "Automatic cleanup is off. Pins and active work are always kept."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.providerState != VirtualFileProviderState.NotApplicable) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(NextcloudRadii.Small),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                when (snapshot.providerState) {
                                    VirtualFileProviderState.Active -> "Available in your file manager"
                                    VirtualFileProviderState.Inactive -> "File-manager integration is off"
                                    VirtualFileProviderState.Starting -> "Starting file-manager integration"
                                    VirtualFileProviderState.NeedsAttention -> "File-manager integration needs attention"
                                    VirtualFileProviderState.NotApplicable -> ""
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            snapshot.providerLocation?.let { location ->
                                Text(
                                    location,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (snapshot.pendingWritebackCount > 0) {
                                Text(
                                    "${snapshot.pendingWritebackCount} local edit(s) are retained for recovery.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (snapshot.providerLocationCanChange) {
                                TextButton(enabled = !busy, onClick = onChangeLocation) {
                                    Text("Change drive or folder")
                                }
                            }
                        }
                    }
                    if (snapshot.providerState == VirtualFileProviderState.Active) {
                        OutlinedButton(enabled = !busy, onClick = onDeactivateProvider) {
                            Text("Disconnect from file manager")
                        }
                    } else {
                        Button(enabled = !busy, onClick = onActivateProvider) {
                            Text("Connect to file manager")
                        }
                    }
                }
                if (snapshot.integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        Text("Folders kept on this device", style = MaterialTheme.typography.titleSmall)
                        val pinnedFolders = snapshot.folderRetentionRules.filter { rule ->
                            rule.retention == VirtualFolderRetention.KeepOnDevice
                        }
                        if (pinnedFolders.isEmpty()) {
                            Text(
                                "Everything stays visible. Choose only the albums or folders that should also work offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val hydrationByPath = snapshot.folderHydrationStatuses.associateBy(
                                VirtualFolderHydrationStatus::relativePath,
                            )
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            ) {
                                items(
                                    items = pinnedFolders,
                                    key = VirtualFolderRetentionRule::relativePath,
                                ) { rule ->
                                    val status = hydrationByPath[rule.relativePath]
                                    var menuExpanded by remember(rule.relativePath) { mutableStateOf(false) }
                                    val menuActions = buildList {
                                        if (
                                            status?.phase == VirtualFolderHydrationPhase.Failed ||
                                            status?.refreshFailure != null
                                        ) {
                                            add(
                                                NextcloudCardAction(
                                                    label = "Retry",
                                                    enabled = !busy,
                                                    onClick = { onRetryFolder(rule.relativePath) },
                                                ),
                                            )
                                        }
                                        add(
                                            NextcloudCardAction(
                                                label = "Make online-only",
                                                destructive = true,
                                                enabled = !busy,
                                                onClick = { onReleaseFolder(rule.relativePath) },
                                            ),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
                                            onOpen = null,
                                            onShowActions = { menuExpanded = true },
                                            actionsLabel = "Show actions for ${rule.relativePath}",
                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                rule.relativePath,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                when (status?.phase) {
                                                    VirtualFolderHydrationPhase.Queued -> "Waiting to download"
                                                    VirtualFolderHydrationPhase.Downloading -> "Downloading for offline use"
                                                    VirtualFolderHydrationPhase.AvailableOffline -> when {
                                                        status.refreshing -> "Available offline. Checking for updates"
                                                        status.refreshFailure != null -> status.refreshFailure.let { failure ->
                                                            "Available offline. Latest refresh needs attention: $failure"
                                                        }
                                                        else -> "Available offline"
                                                    }
                                                    VirtualFolderHydrationPhase.Failed ->
                                                        status.detail ?: "Download needs attention"
                                                    null -> "Waiting to check offline content"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (status?.phase == VirtualFolderHydrationPhase.Failed) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        NextcloudCardOverflow(
                                            itemLabel = rule.relativePath,
                                            actions = menuActions,
                                            expanded = menuExpanded,
                                            onExpandedChange = { menuExpanded = it },
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedButton(enabled = !busy, onClick = onChoosePinnedFolder) {
                            Text("Keep a folder on this device")
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(enabled = !busy, onClick = onManage) { Text("Manage storage") }
                    OutlinedButton(
                        enabled = !busy && snapshot.reclaimableBytes > 0L,
                        onClick = onFreeUp,
                    ) {
                        Text(
                            if (snapshot.reclaimableBytes > 0L) {
                                "Free up ${formatVirtualFileBytes(snapshot.reclaimableBytes)}"
                            } else {
                                "Nothing to free"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualFileProviderLocationDialog(
    services: NextcloudPlatformServices,
    initial: VirtualFileProviderLocation,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (VirtualFileProviderLocation) -> Unit,
) {
    var parentPath by remember(initial) { mutableStateOf(initial.parentPath) }
    var folderName by remember(initial) { mutableStateOf(initial.folderName) }
    var choosing by remember { mutableStateOf(false) }
    var chooserError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validName = folderName.isValidVirtualFileProviderFolderName()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose virtual file location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(
                    "Choose the drive or parent folder, then give the visible Nextcloud folder a clear name.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = parentPath,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Drive or parent folder") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !busy && !choosing,
                    onClick = {
                        choosing = true
                        chooserError = null
                        scope.launch {
                            runCatching { services.chooseVirtualFileProviderParent(parentPath) }
                                .onSuccess { selected -> if (selected != null) parentPath = selected }
                                .onFailure { chooserError = it.message ?: "Could not open the folder chooser." }
                            choosing = false
                        }
                    },
                ) {
                    Text(if (choosing) "Choosing..." else "Choose drive or folder")
                }
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    label = { Text("Folder name") },
                    isError = folderName.isNotEmpty() && !validName,
                    supportingText = if (!validName) {
                        { Text("Use a normal folder name without slashes, trailing spaces, or reserved device names.") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                chooserError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) }
                error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Current: ${initial.parentPath}/${initial.folderName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && !choosing && parentPath.isNotBlank() && validName,
                onClick = {
                    runCatching { VirtualFileProviderLocation(parentPath, folderName) }
                        .onSuccess(onSave)
                        .onFailure { failure ->
                            chooserError = failure.message ?: "Choose a valid local folder location."
                        }
                },
            ) { Text("Use this location") }
        },
        dismissButton = { TextButton(enabled = !busy && !choosing, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VirtualFileStorageMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun VirtualFileStoragePolicyDialog(
    snapshot: VirtualFileStorageSnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (VirtualFileCachePolicy) -> Unit,
) {
    var policy by remember(snapshot.policy) { mutableStateOf(snapshot.policy) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Virtual file storage") },
        text = {
            VirtualFileStoragePolicyEditor(
                snapshot = snapshot,
                busy = busy,
                policy = policy,
                onPolicyChanged = { policy = it },
            )
        },
        confirmButton = {
            Button(enabled = !busy, onClick = { onSave(policy) }) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save rules")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
internal fun VirtualFileStoragePolicyEditor(
    snapshot: VirtualFileStorageSnapshot,
    busy: Boolean,
    policy: VirtualFileCachePolicy,
    onPolicyChanged: (VirtualFileCachePolicy) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        item {
            Text(
                "Opened files hydrate into the local cache. Pinned offline files, open files, " +
                    "uploads, edits, and conflicts are never removed automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto free up space", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Apply the limits below in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = policy.automaticCleanup,
                    enabled = !busy,
                    onCheckedChange = { enabled ->
                        onPolicyChanged(policy.copy(automaticCleanup = enabled))
                    },
                )
            }
        }
        item {
            VirtualFilePolicyChoice(
                title = "Cache limit",
                subtitle = "Automatic content can use up to this much space.",
                options = VIRTUAL_CACHE_SIZE_OPTIONS,
                selected = policy.maximumCacheBytes,
                enabled = !busy,
                label = { value -> value?.let(::formatVirtualFileBytes) ?: "No limit" },
                onSelected = { selected -> onPolicyChanged(policy.copy(maximumCacheBytes = selected)) },
            )
        }
        item {
            VirtualFilePolicyChoice(
                title = "Always keep free",
                subtitle = "Cleanup starts before device storage drops below this reserve.",
                options = VIRTUAL_FREE_SPACE_OPTIONS,
                selected = policy.minimumFreeSpaceBytes,
                enabled = !busy,
                label = ::formatVirtualFileBytes,
                onSelected = { selected -> onPolicyChanged(policy.copy(minimumFreeSpaceBytes = selected)) },
            )
        }
        item {
            VirtualFilePolicyChoice(
                title = "Remove if unused",
                subtitle = "Recently opened automatic files stay close at hand.",
                options = VIRTUAL_UNUSED_AGE_OPTIONS,
                selected = policy.unusedFileAgeMillis,
                enabled = !busy,
                label = { value -> value?.let(::formatVirtualFileAge) ?: "Never" },
                onSelected = { selected -> onPolicyChanged(policy.copy(unusedFileAgeMillis = selected)) },
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Text(
                    "Currently ${formatVirtualFileBytes(snapshot.cachedBytes)} cached, " +
                        "${formatVirtualFileBytes(snapshot.reclaimableBytes)} reclaimable, and " +
                        "${formatVirtualFileBytes(snapshot.pinnedBytes)} pinned.",
                    modifier = Modifier.padding(NextcloudSpacing.Medium),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun <T> VirtualFilePolicyChoice(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
                Text(label(selected), maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (option == selected) "${label(option)} (selected)" else label(option),
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

private fun formatVirtualFileAge(ageMillis: Long): String {
    val days = ageMillis / (24L * 60L * 60L * 1_000L)
    return when {
        days == 30L -> "1 month"
        days % 30L == 0L -> "${days / 30L} months"
        days == 1L -> "1 day"
        else -> "$days days"
    }
}

private val VIRTUAL_CACHE_SIZE_OPTIONS = listOf<Long?>(
    5L * 1024L * 1024L * 1024L,
    10L * 1024L * 1024L * 1024L,
    20L * 1024L * 1024L * 1024L,
    50L * 1024L * 1024L * 1024L,
    null,
)
private val VIRTUAL_FREE_SPACE_OPTIONS = listOf(
    2L * 1024L * 1024L * 1024L,
    5L * 1024L * 1024L * 1024L,
    10L * 1024L * 1024L * 1024L,
    20L * 1024L * 1024L * 1024L,
)
private val VIRTUAL_UNUSED_AGE_OPTIONS = listOf<Long?>(
    7L * 24L * 60L * 60L * 1_000L,
    30L * 24L * 60L * 60L * 1_000L,
    90L * 24L * 60L * 60L * 1_000L,
    null,
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
                                "${snapshot.items.count { it.availability == FileOfflineAvailability.Available }} available | " +
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
                    metadata.joinToString(" | "),
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

private fun VirtualFileStorageActionResult.virtualFileStorageMessage(): String = when (this) {
    is VirtualFileStorageActionResult.Completed -> message
    is VirtualFileStorageActionResult.Rejected -> reason
    is VirtualFileStorageActionResult.Unsupported -> reason
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
        FileSyncDirection.Bidirectional -> "Device <-> $remote"
        FileSyncDirection.DownloadOnly -> "$remote -> device"
        FileSyncDirection.UploadOnly -> "Device -> $remote"
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

internal fun fileOfflineRefreshEnabled(
    loading: Boolean,
    mediaDiscoveryLoading: Boolean,
    actionInProgress: Boolean,
): Boolean = !loading && !mediaDiscoveryLoading && !actionInProgress

private const val ADD_PAIR_BUSY_ID = "__adding_sync_pair__"
private const val MAX_VISIBLE_PAIR_CONFLICTS = 5
private const val MAX_VISIBLE_MEDIA_FOLDER_SUGGESTIONS = 6
private data class PendingFileSyncDecision(
    val pair: FileSyncPairSummary,
    val conflict: FileSyncConflictSummary,
    val choice: FileSyncDecisionChoice,
)
