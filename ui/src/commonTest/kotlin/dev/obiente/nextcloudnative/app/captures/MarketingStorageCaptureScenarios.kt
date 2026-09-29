package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudBottomNavigation
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDestination

@Composable
internal fun MarketingObsidianSyncScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Obsidian Vault",
            subtitle = "Two-way folder sync",
            onBack = {},
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            item {
                FolderSyncSection(
                    snapshot = FileSyncCenterSnapshot(
                        support = FileSyncCenterSupport.Available,
                        pairs = listOf(
                            marketingSyncPair(
                                id = "fixture-obsidian",
                                name = "Obsidian Vault",
                                remote = "Notes/Obsidian",
                                direction = FileSyncDirection.Bidirectional,
                                pending = 1,
                                completed = 42,
                            schedule = "Background sync · Wi-Fi or mobile data",
                            ),
                        ),
                    ),
                    loading = false,
                    mediaDiscovery = null,
                    mediaDiscoveryLoading = false,
                    busyPairId = null,
                    onAdd = {}, onOpenMediaSuggestion = {},
                    onRequestMediaPermission = {}, onRun = {},
                    onRemove = {},
                    onResolve = { _, _, _ -> },
                )
            }
        }
    }
}

@Composable
internal fun MarketingMediaBackupScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Photo backup",
            subtitle = "Camera and media folders",
            onBack = {},
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            item {
                FolderSyncSection(
                    snapshot = FileSyncCenterSnapshot(
                        support = FileSyncCenterSupport.Available,
                        pairs = listOf(
                            marketingSyncPair(
                                id = "fixture-camera",
                                name = "Camera",
                                remote = "Photos/Phone/Camera",
                                direction = FileSyncDirection.UploadOnly,
                                pending = 3,
                                completed = 128,
                                schedule = "Wi-Fi · battery not low",
                            ),
                        ),
                    ),
                    loading = false,
                    mediaDiscovery = MediaSyncFolderDiscovery(
                        support = MediaSyncFolderDiscoverySupport.Available,
                        suggestions = listOf(
                            MediaSyncFolderSuggestion(
                                localRootHint = "fixture-media-camera",
                                displayName = "Camera",
                                relativePath = "DCIM/Camera",
                                kind = MediaSyncFolderKind.Camera,
                                imageCount = 128,
                                videoCount = 14,
                                suggestedRemoteRootPath = "Photos/Phone/Camera",
                                totalBytes = 3_487_000_000L,
                            ),
                            MediaSyncFolderSuggestion(
                                localRootHint = "fixture-media-screenshots",
                                displayName = "Screenshots",
                                relativePath = "Pictures/Screenshots",
                                kind = MediaSyncFolderKind.Screenshots,
                                imageCount = 36,
                                videoCount = 2,
                                suggestedRemoteRootPath = "Photos/Phone/Screenshots",
                                totalBytes = 412_000_000L,
                            ),
                        ),
                    ),
                    mediaDiscoveryLoading = false,
                    busyPairId = null,
                    onAdd = {},
                    onOpenMediaSuggestion = {},
                    onRequestMediaPermission = {},
                    onRun = {},
                    onRemove = {},
                    onResolve = { _, _, _ -> },
                )
            }
        }
    }
}

@Composable
internal fun MarketingFileSyncRulesScenario(
    initialStep: FileSyncSetupStep = FileSyncSetupStep.Rules,
    initialAdvancedSettingsVisible: Boolean = false,
) {
    var configuration by remember {
        mutableStateOf(
            FileSyncConfiguration(
                direction = FileSyncDirection.Bidirectional,
                conflictPolicy = FileSyncConflictPolicy.Ask,
                deletionPolicy = FileSyncDeletionPolicy.Ask,
                deviceLabel = "Alex's phone",
                networkPolicy = FileSyncNetworkPolicy.Unmetered,
                powerPolicy = FileSyncPowerPolicy.BatteryNotLow,
                ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
                priorityRules = listOf(
                    FileSyncPriorityRule("**/*.raf"),
                    FileSyncPriorityRule("**/*.jpg"),
                    FileSyncPriorityRule("**/*.jpeg"),
                ),
            ),
        )
    }
    FileSyncSetupSurface(
        localRoot = FileSyncLocalRoot("fixture-studio-local", "Pictures/Studio"),
        mediaSuggestion = null,
        remotePath = "Photos/Studio",
        configuration = configuration,
        mediaPreview = null,
        mediaPreviewLoading = false,
        mediaPreviewError = null,
        busy = false,
        onDismiss = {},
        onChooseDestination = {},
        onConfigurationChanged = { configuration = it },
        onAdd = {},
        modifier = Modifier.fillMaxSize(),
        initialStep = initialStep,
        initialAdvancedSettingsVisible = initialAdvancedSettingsVisible,
        syntheticScopeSummary = "18,742 files - 123.4 GB - 2,511 RAW",
    )
}

@Composable
internal fun MarketingFileSyncStatusDesktopScenario() {
    NextcloudDesktopShell(
        selected = NextcloudDestination.FolderSync,
        onSelected = {},
        identity = marketingDesktopIdentity(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            FileOfflineWorkspaceTabs(
                selected = FileOfflineWorkspaceSection.FolderSync,
                onSelected = {},
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FileSyncWorkspace(
                snapshot = FileSyncCenterSnapshot(
                    support = FileSyncCenterSupport.Available,
                    limitation = "Background checks run every two minutes while the desktop app is active.",
                    pairs = listOf(
                        FileSyncPairSummary(
                            id = "fixture-studio",
                            localDisplayName = "Studio archive",
                            localRootPath = "~/Pictures/Studio",
                            remoteRootPath = "Photos/Studio",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Field workstation",
                                selectedPaths = listOf("Shoots/2026", "Exports/Portfolio"),
                                ignoredPatterns = listOf("*.part", "**/.thumbnails/**"),
                                priorityRules = listOf(
                                    FileSyncPriorityRule("**/*.raf"),
                                    FileSyncPriorityRule("**/*.jpg"),
                                ),
                            ),
                            readyCount = 5,
                            runningCount = 1,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 341,
                            lastScanEpochMillis = 1_786_640_400_000L,
                            scheduleDescription = "Background checks every two minutes",
                            networkState = FileSyncNetworkState.Available,
                        ),
                        FileSyncPairSummary(
                            id = "fixture-client",
                            localDisplayName = "Client selects",
                            localRootPath = "~/Pictures/Clients/Selects",
                            remoteRootPath = "Photos/Clients/Selects",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Field workstation",
                                ignoredPatterns = listOf("*.part"),
                            ),
                            readyCount = 5,
                            runningCount = 0,
                            conflicts = listOf(
                                FileSyncConflictSummary(
                                    workId = 42,
                                    relativePath = "cover.jpg",
                                    reason = FileSyncDecisionReason.SimultaneousEdit,
                                    choices = setOf(
                                        FileSyncDecisionChoice.UseLocal,
                                        FileSyncDecisionChoice.UseRemote,
                                        FileSyncDecisionChoice.KeepBoth,
                                        FileSyncDecisionChoice.Skip,
                                    ),
                                    local = FileSyncConflictSideSummary(
                                        kind = SyncEntryKind.File,
                                        sizeBytes = 2_486_272L,
                                        modifiedEpochMillis = 1_786_640_400_000L,
                                    ),
                                    remote = FileSyncConflictSideSummary(
                                        kind = SyncEntryKind.File,
                                        sizeBytes = 2_513_920L,
                                        modifiedEpochMillis = 1_786_640_100_000L,
                                    ),
                                ),
                            ),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 86,
                            lastScanEpochMillis = 1_786_640_400_000L,
                            scheduleDescription = "Background checks every two minutes",
                            networkState = FileSyncNetworkState.Available,
                        ),
                        FileSyncPairSummary(
                            id = "fixture-documents",
                            localDisplayName = "Project documents",
                            localRootPath = "~/Nextcloud/Projects",
                            remoteRootPath = "Work/Projects",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.UploadOnly,
                                deviceLabel = "Field workstation",
                                ignoredPatterns = listOf("*.tmp"),
                            ),
                            readyCount = 0,
                            runningCount = 1,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 219,
                            lastScanEpochMillis = 1_786_640_400_000L,
                            scheduleDescription = "Background checks every two minutes",
                            networkState = FileSyncNetworkState.Available,
                        ),
                        FileSyncPairSummary(
                            id = "fixture-archive",
                            localDisplayName = "Archive 2024",
                            localRootPath = "~/Pictures/Archive/2024",
                            remoteRootPath = "Photos/Archive/2024",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.DownloadOnly,
                                deviceLabel = "Field workstation",
                            ),
                            readyCount = 12,
                            runningCount = 0,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 802,
                            lastScanEpochMillis = 1_786_640_400_000L,
                            scheduleDescription = "Will resume when Nextcloud is reachable",
                            networkState = FileSyncNetworkState.WaitingForNetwork,
                        ),
                    ),
                ),
                loading = false,
                busyPairId = null,
                onAdd = {},
                onRun = {},
                onRemove = {},
                onResolve = { _, _, _ -> },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(
                    start = NextcloudSpacing.Large,
                    end = NextcloudSpacing.Large,
                    bottom = NextcloudSpacing.Large,
                ),
                fillAvailableHeight = true,
            )
        }
    }
}

@Composable
internal fun MarketingActivityWorkspaceDesktopScenario() {
    val activities = remember { marketingActivityFixture() }
    val timeline = remember(activities) {
        ActivityTimelineState(
            activities = activities,
            initialized = true,
            nextSince = 120,
            hasMore = true,
        )
    }
    val feed = remember(activities) { buildActivityFeedPresentation(activities) }
    NextcloudDesktopShell(
        selected = NextcloudDestination.Activity,
        onSelected = {},
        identity = marketingDesktopIdentity(),
    ) {
        ActivityDesktopWorkspace(
            timeline = timeline,
            feed = feed,
            query = "",
            selectedSemantic = null,
            selectedApp = null,
            selectedType = null,
            serverFilters = marketingActivityFilters(),
            selectedServerFilterId = "all",
            onQueryChanged = {},
            onSemanticSelected = {},
            onAppSelected = {},
            onTypeSelected = {},
            onServerFilterSelected = {},
            onClearFilters = {},
            onRefresh = {},
            onLoadMore = {},
            actionFor = { activity ->
                when {
                    activity.subject.contains("conflict", ignoreCase = true) ->
                        ActivityOpenAction("Review conflict", appId = "files")
                    activity.subject.contains("expir", ignoreCase = true) ->
                        ActivityOpenAction("Extend link", appId = "files")
                    activity.subject.contains("failed", ignoreCase = true) ->
                        ActivityOpenAction("Retry upload", appId = "files")
                    else -> null
                }
            },
            onOpenAction = {},
            loadPreview = { null },
            onOpenSettings = {},
        )
    }
}

@Composable
internal fun MarketingActivityWorkspaceMobileScenario() {
    val activities = remember { marketingActivityFixture() }
    val timeline = remember(activities) {
        ActivityTimelineState(
            activities = activities,
            initialized = true,
            nextSince = 120,
            hasMore = true,
        )
    }
    val feed = remember(activities) { buildActivityFeedPresentation(activities) }
    Column(Modifier.fillMaxSize()) {
        ActivityMobileWorkspace(
            timeline = timeline,
            feed = feed,
            query = "",
            selectedSemantic = null,
            selectedApp = null,
            selectedType = null,
            serverFilters = marketingActivityFilters(),
            selectedServerFilterId = "all",
            onQueryChanged = {},
            onSemanticSelected = {},
            onAppSelected = {},
            onTypeSelected = {},
            onServerFilterSelected = {},
            onClearFilters = {},
            onRefresh = {},
            onLoadMore = {},
            actionFor = { activity ->
                when {
                    activity.subject.contains("conflict", ignoreCase = true) ->
                        ActivityOpenAction("Review conflict", appId = "files")
                    activity.subject.contains("expir", ignoreCase = true) ->
                        ActivityOpenAction("Extend link", appId = "files")
                    activity.subject.contains("failed", ignoreCase = true) ->
                        ActivityOpenAction("Retry upload", appId = "files")
                    else -> null
                }
            },
            onOpenAction = {},
            loadPreview = { null },
            onOpenSettings = {},
            modifier = Modifier.weight(1f),
        )
        NextcloudBottomNavigation(selected = NextcloudDestination.Activity, onSelected = {})
    }
}

private fun marketingActivityFilters(): List<NextcloudActivityFilterOption> = listOf(
    NextcloudActivityFilterOption("all", "All activities", 0),
    NextcloudActivityFilterOption("self", "By you", 1),
    NextcloudActivityFilterOption("by", "By others", 2),
    NextcloudActivityFilterOption("files", "File changes", 10),
    NextcloudActivityFilterOption("calendar", "Calendar", 70),
    NextcloudActivityFilterOption("comments", "Comments", 70),
)

private fun marketingActivityFixture(): List<NextcloudActivity> = listOf(
    marketingActivity(150, "files", "sync_conflict", "Sync conflict in Project plan 2026.docx", "Both copies changed", "2026-08-02T09:46:00Z"),
    marketingActivity(149, "files_sharing", "share_expiring", "Public share for Budget Q3.xlsx expires soon", "Shared link expires in 2 days", "2026-08-02T09:31:00Z"),
    marketingActivity(148, "files", "upload_failed", "Background upload failed for IMG_211830.jpg", "The connection was interrupted", "2026-08-02T09:18:00Z"),
    marketingActivity(147, "files_sharing", "shared", "Elena Schneider shared Project Phoenix", "Shared with 6 people via link", "2026-08-02T08:58:00Z"),
    marketingActivity(146, "comments", "comment", "Kai Lind commented on Budget Q3.xlsx", "Please review the updated numbers.", "2026-08-02T08:42:00Z"),
    marketingActivity(145, "spreed", "mention", "You were mentioned in Campaign Assets", "Can you confirm the final version?", "2026-08-02T08:21:00Z"),
    marketingActivity(144, "files", "file_changed", "Jonas Lund changed 3 files in Brand Kit", "logo.svg, colors.css, type-scale.md", "2026-08-02T07:48:00Z"),
    marketingActivity(143, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_201.jpg", null, "2026-08-02T06:15:00Z"),
    marketingActivity(142, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_202.jpg", null, "2026-08-02T06:14:00Z"),
    marketingActivity(141, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_203.jpg", null, "2026-08-02T06:14:00Z"),
    marketingActivity(140, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_204.jpg", null, "2026-08-02T06:13:00Z"),
    marketingActivity(139, "files", "file_created", "Mara created Field notes.md", "Projects/Research", "2026-08-01T18:24:00Z"),
)

private fun marketingActivity(
    id: Long,
    app: String,
    type: String,
    subject: String,
    message: String?,
    dateTime: String,
) = NextcloudActivity(
    id = id,
    app = app,
    type = type,
    subject = subject,
    message = message,
    objectType = null,
    objectId = null,
    objectName = null,
    link = null,
    icon = null,
    dateTime = dateTime,
)

@Composable
internal fun MarketingFileSyncSetupDesktopScenario(
    initialStep: FileSyncSetupStep = FileSyncSetupStep.Rules,
    initialAdvancedSettingsVisible: Boolean = false,
) {
    var configuration by remember {
        mutableStateOf(
            FileSyncConfiguration(
                direction = FileSyncDirection.Bidirectional,
                conflictPolicy = FileSyncConflictPolicy.Ask,
                deletionPolicy = FileSyncDeletionPolicy.Ask,
                deviceLabel = "Field workstation",
                networkPolicy = FileSyncNetworkPolicy.AnyConnection,
                powerPolicy = FileSyncPowerPolicy.BatteryNotLow,
                ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
                priorityRules = listOf(
                    FileSyncPriorityRule("**/*.raf"),
                    FileSyncPriorityRule("**/*.jpg"),
                    FileSyncPriorityRule("**/*.jpeg"),
                ),
            ),
        )
    }
    Box(modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        FileSyncSetupSurface(
            localRoot = FileSyncLocalRoot("fixture-desktop-studio", "~/Pictures/Studio"),
            mediaSuggestion = null,
            remotePath = "Photos/Studio",
            configuration = configuration,
            mediaPreview = null,
            mediaPreviewLoading = false,
            mediaPreviewError = null,
            busy = false,
            onDismiss = {},
            onChooseDestination = {},
            onConfigurationChanged = { configuration = it },
            onAdd = {},
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth().heightIn(max = 760.dp),
            initialStep = initialStep,
            initialAdvancedSettingsVisible = initialAdvancedSettingsVisible,
            syntheticScopeSummary = "18,742 files - 123.4 GB - 2,511 RAW",
        )
    }
}

@Composable
internal fun MarketingFileSyncSelectionScenario(services: NextcloudPlatformServices) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        RemoteFileSyncSelectionDialog(
            services = services,
            session = NextcloudSession("https://cloud.invalid", "alex@example.invalid", "fixture"),
            userId = "alex",
            remoteRootPath = "Photos/Studio",
            initialSelection = listOf("RAW/Day 1"),
            onDismiss = {},
            onSelected = {},
            embedded = true,
        )
    }
}

@Composable
internal fun MarketingFileSyncStatusMobileScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = FileOfflineWorkspaceSection.FolderSync.title,
            subtitle = FileOfflineWorkspaceSection.FolderSync.subtitle,
            onBack = {},
            trailingContent = { androidx.compose.material3.TextButton(onClick = {}) { Text("Refresh") } },
        )
        FileOfflineWorkspaceTabs(selected = FileOfflineWorkspaceSection.FolderSync, onSelected = {})
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            item {
            FileSyncWorkspace(
                snapshot = FileSyncCenterSnapshot(
                    support = FileSyncCenterSupport.Available,
                    limitation = "Background sync resumes automatically when network and power rules allow it.",
                    pairs = listOf(
                        FileSyncPairSummary(
                            id = "fixture-mobile-studio",
                            localDisplayName = "Studio archive",
                            localRootPath = "Pictures/Studio",
                            remoteRootPath = "Photos/Studio",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Alex's phone",
                                ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
                                priorityRules = listOf(
                                    FileSyncPriorityRule("**/*.raf"),
                                    FileSyncPriorityRule("**/*.jpg"),
                                ),
                            ),
                            readyCount = 17,
                            runningCount = 1,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 341,
                            lastScanEpochMillis = 1_786_640_400_000L,
                            scheduleDescription = "Background sync enabled",
                        ),
                        FileSyncPairSummary(
                            id = "fixture-mobile-client",
                            localDisplayName = "Client selects",
                            localRootPath = "Pictures/Clients/Selects",
                            remoteRootPath = "Photos/Clients/Selects",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Alex's phone",
                                ignoredPatterns = listOf("*.part"),
                            ),
                            readyCount = 5,
                            runningCount = 0,
                            conflicts = listOf(
                                FileSyncConflictSummary(
                                    workId = 52,
                                    relativePath = "cover.jpg",
                                    reason = FileSyncDecisionReason.SimultaneousEdit,
                                    choices = setOf(
                                        FileSyncDecisionChoice.UseLocal,
                                        FileSyncDecisionChoice.UseRemote,
                                        FileSyncDecisionChoice.KeepBoth,
                                        FileSyncDecisionChoice.Skip,
                                    ),
                                    local = FileSyncConflictSideSummary(
                                        kind = SyncEntryKind.File,
                                        sizeBytes = 2_486_272L,
                                        modifiedEpochMillis = 1_786_640_400_000L,
                                    ),
                                    remote = FileSyncConflictSideSummary(
                                        kind = SyncEntryKind.File,
                                        sizeBytes = 2_513_920L,
                                        modifiedEpochMillis = 1_786_640_100_000L,
                                    ),
                                ),
                            ),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 86,
                            lastScanEpochMillis = 1_786_640_400_000L,
                            scheduleDescription = "Waiting for your decision",
                        ),
                        FileSyncPairSummary(
                            id = "fixture-mobile-camera",
                            localDisplayName = "Camera backup",
                            localRootPath = "DCIM/Camera",
                            remoteRootPath = "Photos/Phone camera",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.UploadOnly,
                                deviceLabel = "Alex's phone",
                                networkPolicy = FileSyncNetworkPolicy.Unmetered,
                            ),
                            readyCount = 0,
                            runningCount = 0,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 1_842,
                            lastScanEpochMillis = 1_786_640_400_000L,
                            scheduleDescription = "Wi-Fi only",
                        ),
                    ),
                ),
                loading = false,
                busyPairId = null,
                onAdd = {},
                onRun = {},
                onRemove = {},
                onResolve = { _, _, _ -> },
                initialSelectedPairId = "fixture-mobile-client",
            )
            }
        }
    }
}

@Composable
internal fun MarketingVirtualFileStorageMobileScenario() {
    val snapshot = remember {
        marketingVirtualFileStorageSnapshot(
            support = VirtualFileStorageSupport.Available,
            integration = VirtualFilePlatformIntegration.AndroidDocumentsProvider,
        )
    }
    var policy by remember { mutableStateOf(snapshot.policy) }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Virtual file storage",
            subtitle = "Cache and automatic cleanup",
            onBack = {},
        )
        VirtualFileStoragePolicyEditor(
            snapshot = snapshot,
            busy = false,
            policy = policy,
            onPolicyChanged = { policy = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = {}) { Text("Cancel") }
                androidx.compose.material3.Button(onClick = {}) { Text("Save rules") }
            }
        }
    }
}

@Composable
internal fun MarketingVirtualFileStorageOverviewMobileScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Virtual files", subtitle = "System Files and offline storage", onBack = {})
        VirtualFileStorageCard(
            snapshot = marketingVirtualFileStorageSnapshot(
                support = VirtualFileStorageSupport.Available,
                integration = VirtualFilePlatformIntegration.AndroidDocumentsProvider,
            ),
            loading = false,
            busy = false,
            onManage = {},
            onFreeUp = {},
            onActivateProvider = {},
            onDeactivateProvider = {},
            onAcknowledgeRecovery = {},
            onChangeLocation = {},
            onChangeCacheTiers = {},
            onChoosePinnedFolder = {},
            onReleaseFolder = {},
            onRetryFolder = {},
        )
    }
}

@Composable
internal fun MarketingVirtualFileStorageDesktopScenario(scenario: MarketingCaptureScenario) {
    val windows = scenario == MarketingCaptureScenario.WindowsCloudFilesStorageDesktop ||
        scenario == MarketingCaptureScenario.WindowsCloudFilesRecoveryDesktop ||
        scenario == MarketingCaptureScenario.GuideWindowsCloudFilesSettings
    val recovery = scenario == MarketingCaptureScenario.WindowsCloudFilesRecoveryDesktop
    val activation = scenario == MarketingCaptureScenario.GuideWindowsCloudFilesSettings
    NextcloudDesktopShell(
        selected = NextcloudDestination.FolderSync,
        onSelected = {},
        identity = marketingDesktopIdentity(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FileOfflineWorkspaceTabs(
                selected = FileOfflineWorkspaceSection.VirtualFiles,
                onSelected = {},
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                        Text("Virtual files", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Keep your cloud visible locally and choose what must stay available offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            if (recovery) "Edits need review" else if (activation) "Not connected" else "Storage and connection",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                VirtualFileStorageCard(
                    snapshot = marketingVirtualFileStorageSnapshot(
                        support = VirtualFileStorageSupport.Available,
                        integration = if (windows) {
                            VirtualFilePlatformIntegration.WindowsCloudFiles
                        } else {
                            VirtualFilePlatformIntegration.LinuxFilesystemMount
                        },
                        limitations = if (recovery) {
                            listOf(
                                "A local edit conflicts with a newer remote generation and is retained for recovery.",
                                "Review the pending writeback before freeing space or removing this account.",
                            )
                        } else {
                            emptyList()
                        },
                        pendingWritebackCount = if (recovery) 1 else 0,
                        providerActive = !activation,
                    ),
                    loading = false,
                    busy = false,
                    onManage = {},
                    onFreeUp = {},
                    onActivateProvider = {},
                    onDeactivateProvider = {},
                    onAcknowledgeRecovery = {},
                    onChangeLocation = {},
                    onChangeCacheTiers = {},
                    onChoosePinnedFolder = {},
                    onReleaseFolder = {},
                    onRetryFolder = {},
                )
            }
        }
    }
}

private fun marketingVirtualFileStorageSnapshot(
    support: VirtualFileStorageSupport,
    integration: VirtualFilePlatformIntegration,
    limitations: List<String> = emptyList(),
    pendingWritebackCount: Int? = null,
    providerActive: Boolean = true,
): VirtualFileStorageSnapshot = VirtualFileStorageSnapshot(
    support = support,
    integration = integration,
    policy = VirtualFileCachePolicy(
        automaticCleanup = true,
        maximumCacheBytes = 20L * 1024L * 1024L * 1024L,
        minimumFreeSpaceBytes = 10L * 1024L * 1024L * 1024L,
        unusedFileAgeMillis = 30L * 24L * 60L * 60L * 1_000L,
    ),
    cachedBytes = 12_884_901_888L,
    reclaimableBytes = 7_193_722_880L,
    pinnedBytes = 4_482_344_960L,
    hydratedFileCount = 1_842,
    pinnedFileCount = 318,
    availableFreeBytes = 68_719_476_736L,
    storageCapacityBytes = 512L * 1024L * 1024L * 1024L,
    limitations = limitations,
    providerState = if (providerActive) VirtualFileProviderState.Active else VirtualFileProviderState.Inactive,
    providerActive = providerActive,
    providerLocation = when (integration) {
        VirtualFilePlatformIntegration.AndroidDocumentsProvider -> "System Files / nati.ve"
        VirtualFilePlatformIntegration.WindowsCloudFiles -> "nati.ve in File Explorer"
        VirtualFilePlatformIntegration.LinuxFilesystemMount -> "~/Nextcloud Native"
        VirtualFilePlatformIntegration.AppleFileProvider -> "Files / nati.ve"
        VirtualFilePlatformIntegration.InAppOnDemandCache -> null
    },
    providerLocationConfiguration = if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
        VirtualFileProviderLocation("Home folder", "nati.ve")
    } else {
        null
    },
    providerLocationCanChange = integration == VirtualFilePlatformIntegration.LinuxFilesystemMount,
    folderRetentionRules = if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
        listOf(
            VirtualFolderRetentionRule("Projects/Phoenix", VirtualFolderRetention.KeepOnDevice),
            VirtualFolderRetentionRule("Photos/Portfolio", VirtualFolderRetention.KeepOnDevice),
            VirtualFolderRetentionRule("Shared/Field research", VirtualFolderRetention.KeepOnDevice),
        )
    } else {
        emptyList()
    },
    folderHydrationStatuses = if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
        listOf(
            VirtualFolderHydrationStatus(
                relativePath = "Projects/Phoenix",
                phase = VirtualFolderHydrationPhase.AvailableOffline,
                verifiedAtEpochMillis = 1,
            ),
            VirtualFolderHydrationStatus(
                relativePath = "Photos/Portfolio",
                phase = VirtualFolderHydrationPhase.Downloading,
            ),
            VirtualFolderHydrationStatus(
                relativePath = "Shared/Field research",
                phase = VirtualFolderHydrationPhase.Failed,
                detail = "Connection interrupted. Existing offline files remain available.",
            ),
        )
    } else {
        emptyList()
    },
    pendingWritebackCount = pendingWritebackCount
        ?: if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) 1 else 0,
)
