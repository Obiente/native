package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState

enum class MarketingCapturePurpose(val manifestValue: String) {
    Showcase("showcase"),
    StateCoverage("state-coverage"),
}

enum class MarketingCaptureScenario(
    val id: String,
    val fileName: String,
    val presentation: NextcloudPresentation,
    val feature: String,
    val surface: String,
    val state: String,
    val purpose: MarketingCapturePurpose,
    val platform: String,
    val viewport: String,
    val pullRequest: Int? = null,
    val issue: Int? = null,
    val width: Int,
    val height: Int,
    val density: Float,
) {
    DesktopHome(
        "desktop-home", "desktop-home.png", NextcloudPresentation.Desktop,
        "Workspace", "Home dashboard", "Ready", MarketingCapturePurpose.Showcase,
        "desktop", "wide", width = 1_440, height = 900, density = 1f,
    ),
    MobileHome(
        "mobile-home", "mobile-home.png", NextcloudPresentation.Adaptive,
        "Workspace", "Home dashboard", "Ready", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", width = 1_080, height = 2_400, density = 2.625f,
    ),
    ObsidianSync(
        "obsidian-vault-sync", "obsidian-vault-sync.png", NextcloudPresentation.Adaptive,
        "File sync", "Folder pair", "One pending upload", MarketingCapturePurpose.Showcase,
        "mobile", "phone-compact", width = 1_080, height = 1_000, density = 2.625f,
    ),
    MediaBackup(
        "media-backup-queue", "media-backup-queue.png", NextcloudPresentation.Adaptive,
        "Media backup", "Folder discovery", "Ready with pending uploads",
        MarketingCapturePurpose.Showcase, "mobile", "phone-portrait",
        width = 1_080, height = 2_200, density = 2.625f,
    ),
    AdaptiveApp(
        "adaptive-dynamic-data", "adaptive-dynamic-data.png", NextcloudPresentation.Desktop,
        "Dynamic apps", "Data table", "Ready", MarketingCapturePurpose.Showcase,
        "desktop", "wide", width = 1_440, height = 900, density = 1f,
    ),
    AdaptiveAppMobile(
        "adaptive-dynamic-data-mobile", "adaptive-dynamic-data-mobile.png", NextcloudPresentation.Adaptive,
        "Dynamic apps", "Data table", "Ready", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f,
    ),
    PhotoTimelineRevalidationErrorMobile(
        "photo-timeline-revalidation-error-mobile",
        "photo-timeline-revalidation-error-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Photo timeline",
        "Cached photos with revalidation error",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        pullRequest = 246,
        issue = 242,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoTimelineReturnToNewestErrorMobile(
        "photo-timeline-return-to-newest-error-mobile",
        "photo-timeline-return-to-newest-error-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Photo timeline",
        "Older window with failed return to newest",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        pullRequest = 246,
        issue = 242,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoTimelineRawRetryMobile(
        "photo-timeline-raw-retry-mobile",
        "photo-timeline-raw-retry-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Photo timeline",
        "Ordinary photos with a RAW retry pending",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        pullRequest = 249,
        issue = 248,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoFolderBrowserMobile(
        "photo-folder-browser-mobile",
        "photo-folder-browser-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Folder browser",
        "Trip folder in grid view",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        pullRequest = 245,
        issue = 243,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoFolderBrowserDesktop(
        "photo-folder-browser-desktop",
        "photo-folder-browser-desktop.png",
        NextcloudPresentation.Desktop,
        "Photos",
        "Folder browser",
        "Photo library in list view",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        pullRequest = 245,
        issue = 243,
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    RawPreviewLoadingMobile(
        "raw-preview-loading-mobile", "raw-preview-loading-mobile.png",
        NextcloudPresentation.Adaptive, "Photos", "RAW preview",
        "Loading embedded preview", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-compact", pullRequest = 218, issue = 85,
        width = 1_080, height = 1_200, density = 2.625f,
    ),
    RawPreviewErrorMobile(
        "raw-preview-error-mobile", "raw-preview-error-mobile.png",
        NextcloudPresentation.Adaptive, "Photos", "RAW preview",
        "No usable preview", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-compact", pullRequest = 218, issue = 85,
        width = 1_080, height = 1_200, density = 2.625f,
    ),
    RawPreviewMemoriesReadyMobile(
        "raw-preview-memories-ready-mobile", "raw-preview-memories-ready-mobile.png",
        NextcloudPresentation.Adaptive, "Photos", "RAW preview",
        "Ready from Memories", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 218, issue = 85,
        width = 1_080, height = 1_600, density = 2.625f,
    ),
    RawPreviewHighDetailDesktop(
        "raw-preview-high-detail-desktop", "raw-preview-high-detail-desktop.png",
        NextcloudPresentation.Desktop, "Photos", "RAW preview",
        "Embedded high-detail preview", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 218, issue = 85,
        width = 1_440, height = 900, density = 1f,
    ),
    LivePhotoMotionFailureMobile(
        "live-photo-motion-failure-mobile",
        "live-photo-motion-failure-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Live Photo viewer",
        "Still visible after motion decoder failure",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-compact",
        pullRequest = 249,
        issue = 182,
        width = 1_080,
        height = 1_200,
        density = 2.625f,
    ),
    NativeTiffPreviewMobile(
        "native-tiff-preview-mobile",
        "native-tiff-preview-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "TIFF viewer",
        "Native high-detail preview",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-compact",
        pullRequest = 249,
        issue = 84,
        width = 1_080,
        height = 1_200,
        density = 2.625f,
    ),
    FileShareUserMobile(
        "file-share-user-mobile", "file-share-user-mobile.png", NextcloudPresentation.Adaptive,
        "Files", "Share dialog", "User search results", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 219, issue = 124,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    FileShareGroupDesktop(
        "file-share-group-desktop", "file-share-group-desktop.png", NextcloudPresentation.Desktop,
        "Files", "Share dialog", "Group search results", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 219, issue = 124,
        width = 1_440, height = 900, density = 1f,
    ),
    FileShareLoadingMobile(
        "file-share-loading-mobile", "file-share-loading-mobile.png", NextcloudPresentation.Adaptive,
        "Files", "Share dialog", "Loading recipients", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", pullRequest = 219, issue = 124,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    FileShareErrorMobile(
        "file-share-error-mobile", "file-share-error-mobile.png", NextcloudPresentation.Adaptive,
        "Files", "Share dialog", "Recipient search error", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", pullRequest = 219, issue = 124,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    TransferMobilePending(
        "transfer-mobile-pending", "transfer-mobile-pending.png", NextcloudPresentation.Adaptive,
        "Files", "Transfer center", "Pending uploads", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 220, issue = 168,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    TransferMobileFailed(
        "transfer-mobile-failed-cached", "transfer-mobile-failed-cached.png",
        NextcloudPresentation.Adaptive, "Files", "Transfer center",
        "Failed upload with cached source", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", pullRequest = 220, issue = 168,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    TransferDesktopActive(
        "transfer-desktop-active", "transfer-desktop-active.png", NextcloudPresentation.Desktop,
        "Files", "Transfer center", "Active transfer", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 220, issue = 168,
        width = 1_280, height = 800, density = 1f,
    ),
    TransferDesktopCompleted(
        "transfer-desktop-completed-page", "transfer-desktop-completed-page.png",
        NextcloudPresentation.Desktop, "Files", "Transfer center",
        "Completed transfer history", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 220, issue = 168,
        width = 1_280, height = 800, density = 1f,
    ),
    DeckBoardDesktop(
        "deck-board-desktop", "deck-board-desktop.png", NextcloudPresentation.Desktop,
        "Deck", "Kanban board", "Ready", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 221, issue = 52,
        width = 1_440, height = 900, density = 1f,
    ),
    DeckBoardMobile(
        "deck-board-mobile", "deck-board-mobile.png", NextcloudPresentation.Adaptive,
        "Deck", "Kanban board", "Ready", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 221, issue = 52,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
}

internal data class MarketingCaptureRegistryEntry(
    val id: String,
    val fileName: String,
    val feature: String,
    val surface: String,
    val state: String,
    val purpose: String,
    val platform: String,
    val viewport: String,
    val pullRequest: Int?,
    val issue: Int?,
    val width: Int,
    val height: Int,
    val density: Float,
)

private val marketingCaptureSlug = Regex("[a-z0-9-]+")
private val marketingCapturePngFileName = Regex("[a-z0-9-]+\\.png")

internal fun MarketingCaptureScenario.registryEntry(): MarketingCaptureRegistryEntry =
    MarketingCaptureRegistryEntry(
        id = id,
        fileName = fileName,
        feature = feature,
        surface = surface,
        state = state,
        purpose = purpose.manifestValue,
        platform = platform,
        viewport = viewport,
        pullRequest = pullRequest,
        issue = issue,
        width = width,
        height = height,
        density = density,
    )

internal fun validateMarketingCaptureRegistry(
    entries: List<MarketingCaptureRegistryEntry>,
) {
    require(entries.isNotEmpty()) {
        "The marketing capture registry must not be empty."
    }
    require(entries.map(MarketingCaptureRegistryEntry::id).toSet().size == entries.size) {
        "Marketing capture scenario IDs must be unique."
    }
    require(entries.map(MarketingCaptureRegistryEntry::fileName).toSet().size == entries.size) {
        "Marketing capture file names must be unique."
    }
    entries.forEach { entry ->
        require(entry.id.matches(marketingCaptureSlug)) {
            "Invalid marketing capture scenario ID: ${entry.id}"
        }
        require(entry.fileName.matches(marketingCapturePngFileName)) {
            "Invalid marketing capture PNG file name: ${entry.fileName}"
        }
        require(entry.width > 0 && entry.height > 0) {
            "${entry.id} must have positive pixel dimensions."
        }
        require(entry.density.isFinite() && entry.density > 0f) {
            "${entry.id} must have a positive finite density."
        }
        listOf(
            "feature" to entry.feature,
            "surface" to entry.surface,
            "state" to entry.state,
        ).forEach { (label, value) ->
            require(value.isNotEmpty() && value == value.trim()) {
                "${entry.id} $label must be a non-empty trimmed label."
            }
        }
        require(
            entry.purpose == MarketingCapturePurpose.Showcase.manifestValue ||
                entry.purpose == MarketingCapturePurpose.StateCoverage.manifestValue,
        ) {
            "${entry.id} has an unsupported capture purpose."
        }
        require(entry.platform.matches(marketingCaptureSlug)) {
            "${entry.id} has an invalid platform slug."
        }
        require(entry.viewport.matches(marketingCaptureSlug)) {
            "${entry.id} has an invalid viewport slug."
        }
        require(entry.pullRequest == null || entry.pullRequest > 0) {
            "${entry.id} must use a positive pull request number."
        }
        require(entry.issue == null || entry.issue > 0) {
            "${entry.id} must use a positive issue number."
        }
    }
}

internal val fileShareCaptureScenarios: List<MarketingCaptureScenario> = listOf(
    MarketingCaptureScenario.FileShareUserMobile,
    MarketingCaptureScenario.FileShareGroupDesktop,
    MarketingCaptureScenario.FileShareLoadingMobile,
    MarketingCaptureScenario.FileShareErrorMobile,
)

val marketingCaptureScenarios: List<MarketingCaptureScenario> =
    MarketingCaptureScenario.entries

val rawPreviewCaptureScenarios: List<MarketingCaptureScenario> = listOf(
    MarketingCaptureScenario.RawPreviewLoadingMobile,
    MarketingCaptureScenario.RawPreviewErrorMobile,
    MarketingCaptureScenario.RawPreviewMemoriesReadyMobile,
    MarketingCaptureScenario.RawPreviewHighDetailDesktop,
)

val photoMediaReviewCaptureScenarios: List<MarketingCaptureScenario> = listOf(
    MarketingCaptureScenario.LivePhotoMotionFailureMobile,
    MarketingCaptureScenario.NativeTiffPreviewMobile,
)

data class MarketingCaptureAssets(
    val avatar: ImageBitmap,
    val mediaPreview: ImageBitmap,
    val services: NextcloudPlatformServices,
)

@Composable
internal fun MarketingFileShareScenario(
    scenario: MarketingCaptureScenario,
    fixture: MarketingFileShareFixture = nextcloudNativeMarketingFileShareFixture,
) {
    val capture = marketingFileShareCaptureState(scenario, fixture)
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (desktop) NextcloudSpacing.XLarge else NextcloudSpacing.Medium),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = if (desktop) 760.dp else 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(NextcloudSpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            ) {
                Text(
                    text = "Share ${capture.dialog.file.name}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                FileShareDialogContent(
                    state = capture.dialog,
                    onTargetChanged = { _ -> },
                    onAllowEditingChanged = { _ -> },
                    onDetailsChanged = { _ -> },
                    recipientPicker = { target ->
                        FileShareRecipientPickerContent(
                            target = target,
                            state = capture.recipientPicker,
                            enabled = !capture.dialog.running,
                            onQueryChanged = { _ -> },
                            onSelected = { _ -> },
                        )
                    },
                    existingShare = { share ->
                        ExistingFileShareSummary(
                            share = share,
                            running = false,
                            canCopy = false,
                            showManagementActions = true,
                            onCopy = {},
                            onPermissions = {},
                            onRevoke = {},
                        )
                    },
                    maximumHeight = when {
                        desktop -> 620.dp
                        scenario == MarketingCaptureScenario.FileShareLoadingMobile -> 420.dp
                        scenario == MarketingCaptureScenario.FileShareErrorMobile -> 470.dp
                        else -> 480.dp
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        NextcloudSpacing.Small,
                        Alignment.End,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileShareDialogDismissAction(
                        state = capture.dialog,
                        onDismiss = {},
                    )
                    FileShareDialogConfirmAction(
                        state = capture.dialog,
                        onCreate = { _ -> },
                    )
                }
            }
        }
    }
}

internal data class MarketingFileShareCaptureState(
    val dialog: FileShareDialogUiState,
    val recipientPicker: FileShareRecipientPickerUiState,
)

internal fun marketingFileShareCaptureState(
    scenario: MarketingCaptureScenario,
    fixture: MarketingFileShareFixture = nextcloudNativeMarketingFileShareFixture,
): MarketingFileShareCaptureState {
    val target: FileShareTarget
    val existingShares: List<NextcloudFileShare>
    val picker: FileShareRecipientPickerUiState
    val capabilities: NextcloudFileSharingCapabilities
    when (scenario) {
        MarketingCaptureScenario.FileShareUserMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities
            picker = FileShareRecipientPickerUiState(
                query = "de",
                results = fixture.userResults,
            )
        }
        MarketingCaptureScenario.FileShareGroupDesktop -> {
            target = FileShareTarget.Group
            existingShares = listOf(fixture.existingUserShare)
            capabilities = fixture.capabilities
            picker = FileShareRecipientPickerUiState(
                query = "de",
                results = fixture.groupResults,
            )
        }
        MarketingCaptureScenario.FileShareLoadingMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities.copy(userExpirationSupported = false)
            picker = FileShareRecipientPickerUiState(
                query = "de",
                loading = true,
            )
        }
        MarketingCaptureScenario.FileShareErrorMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities.copy(userExpirationSupported = false)
            picker = FileShareRecipientPickerUiState(
                query = "de",
                error = "Could not search recipients. Check your connection and try again.",
            )
        }
        else -> error("${scenario.id} is not a file-share capture.")
    }
    return MarketingFileShareCaptureState(
        dialog = FileShareDialogUiState(
            file = fixture.file,
            capabilities = capabilities,
            existingShares = existingShares,
            target = target,
        ),
        recipientPicker = picker,
    )
}

@Composable
internal fun MarketingObsidianSyncScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Obsidian Vault",
            subtitle = "Two-way folder sync",
            onBack = {},
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
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
internal fun MarketingAdaptiveAppScenario(scenario: MarketingCaptureScenario) {
    require(
        scenario == MarketingCaptureScenario.AdaptiveApp ||
            scenario == MarketingCaptureScenario.AdaptiveAppMobile,
    ) {
        "${scenario.id} is not an adaptive data capture."
    }
    val schema = marketingAdaptiveSchema
    val view = schema.views.single()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = shouldUseCompactDynamicAppChrome(maxWidth.value, maxHeight.value)
        Column(modifier = Modifier.fillMaxSize()) {
            DynamicAppChromeHeader(
                title = schema.app.name,
                subtitle = view.title,
                onBack = {},
                compact = compact,
                onContractInfo = {},
            )
            GenericNativeAppScreen(
                schema = schema,
                view = view,
                state = NativeScreenState.Ready(marketingAdaptiveRecords),
                actionExecutor = NativeActionExecutor {
                    NativeActionExecutionResult.Failure("This fixture is read-only.")
                },
                onSelectRecord = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun MarketingHomeDashboardScenario(
    scenario: MarketingCaptureScenario,
    fixture: MarketingDemoFixture,
) {
    val formFactor = when (scenario.presentation) {
        NextcloudPresentation.Desktop -> HomeFormFactor.Desktop
        NextcloudPresentation.Adaptive -> HomeFormFactor.Phone
    }
    val workspaceScope = remember(formFactor) {
        HomeWorkspaceScope(
            accountScopeDigest = "8a2df7f31f8de281a514cfe02d04ba13dc793be7b88b890b6c415f7e3290bd85",
            formFactor = formFactor,
        )
    }
    NativeDashboardPresentation(
        state = DashboardSurfaceState.Available(
            snapshot = marketingDashboardSnapshot,
            status = marketingUserStatus,
        ),
        installedApps = fixture.apps,
        workspaceLayout = defaultHomeWorkspaceLayout(workspaceScope),
        onWorkspaceLayoutChanged = { true },
        onOpenApp = {},
        onOpenStatus = {},
        onOpenLink = {},
        onBack = null,
        onRefresh = {},
        onSearch = {},
        onSettings = {},
    )
}

@Composable
internal fun MarketingDeckBoardScenario() {
    NativeDeckBoardSurface(
        state = DeckWorkspaceState.Board(
            board = marketingDeckBoard,
            stacks = marketingDeckStacks,
        ),
        onExit = {},
        onSelectBoard = {},
        onBackToBoards = {},
        onOpenCard = {},
        onSelectCard = {},
        onDismissCard = {},
        onRetry = {},
        onCreateStack = {},
        onCreateCard = {},
        onMoveCard = { _, _, _ -> },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun marketingSyncPair(
    id: String,
    name: String,
    remote: String,
    direction: FileSyncDirection,
    pending: Int,
    completed: Int,
    schedule: String,
) = FileSyncPairSummary(
    id = id,
    localDisplayName = name,
    remoteRootPath = remote,
    configuration = FileSyncConfiguration(
        direction = direction,
        deviceLabel = "fixture-mobile",
    ),
    readyCount = pending,
    runningCount = 0,
    conflicts = emptyList(),
    failedCount = 0,
    skippedCount = 0,
    completedCount = completed,
    lastScanEpochMillis = 1,
    scheduleDescription = schedule,
)

internal val marketingAdaptiveSchema = NativeAppSchema(
    schemaVersion = "0.1",
    app = AppIdentity("fixture-inventory", "Community inventory", "fixture"),
    confidence = Confidence.verified,
    resources = listOf(
        ResourceSpec(
            id = "items",
            name = "Inventory items",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("name", "Item", FieldKind.string, required = true, readOnly = true),
                FieldSpec("category", "Category", FieldKind.string, required = false, readOnly = true),
                FieldSpec(
                    "amount",
                    "Value",
                    FieldKind.currency,
                    required = false,
                    readOnly = true,
                    format = "EUR",
                ),
                FieldSpec("status", "Status", FieldKind.enumeration, required = false, readOnly = true),
                FieldSpec("updated", "Updated", FieldKind.date, required = false, readOnly = true),
            ),
        ),
    ),
    views = listOf(
        ViewSpec(
            id = "items.table",
            title = "Inventory",
            resourceId = "items",
            component = NativeComponent.dataTable,
            sourceActionId = "fixture.items.list",
            confidence = Confidence.verified,
        ),
    ),
)

internal val marketingAdaptiveRecords = listOf(
    NativeRecord(
        id = "item-1",
        values = mapOf(
            "name" to "Field recorder",
            "category" to "Audio",
            "amount" to "219.00",
            "status" to "Available",
            "updated" to "2026-07-24",
        ),
    ),
    NativeRecord(
        id = "item-2",
        values = mapOf(
            "name" to "Tripod",
            "category" to "Camera",
            "amount" to "84.50",
            "status" to "On loan",
            "updated" to "2026-07-23",
        ),
    ),
    NativeRecord(
        id = "item-3",
        values = mapOf(
            "name" to "USB-C hub",
            "category" to "Computer",
            "amount" to "49.95",
            "status" to "Available",
            "updated" to "2026-07-22",
        ),
    ),
    NativeRecord(
        id = "item-4",
        values = mapOf(
            "name" to "Lighting kit",
            "category" to "Camera",
            "amount" to "135.00",
            "status" to "Reserved",
            "updated" to "2026-07-21",
        ),
    ),
    NativeRecord(
        id = "item-5",
        values = mapOf(
            "name" to "Studio monitor",
            "category" to "Audio",
            "amount" to "175.00",
            "status" to "Available",
            "updated" to "2026-07-19",
        ),
    ),
)

internal val marketingDashboardSnapshot = NativeDashboardSnapshot(
    widgets = listOf(
        marketingDashboardWidget("activity", "Recent activity", 10),
        marketingDashboardWidget("calendar", "Upcoming events", 20),
        marketingDashboardWidget("recommendations", "Recent files", 30),
        marketingDashboardWidget("photos", "Photo backup", 40),
    ),
    itemsByWidget = mapOf(
        "activity" to listOf(
            marketingDashboardItem(
                widgetId = "activity",
                title = "Project brief was updated",
                subtitle = "A few minutes ago",
                sinceId = "activity-2",
            ),
            marketingDashboardItem(
                widgetId = "activity",
                title = "A design file was shared",
                subtitle = "Today",
                sinceId = "activity-1",
            ),
        ),
        "calendar" to listOf(
            marketingDashboardItem(
                widgetId = "calendar",
                title = "Product planning",
                subtitle = "Today at 14:00",
                sinceId = "calendar-2",
            ),
            marketingDashboardItem(
                widgetId = "calendar",
                title = "Community call",
                subtitle = "Tomorrow at 10:30",
                sinceId = "calendar-1",
            ),
        ),
        "recommendations" to listOf(
            marketingDashboardItem(
                widgetId = "recommendations",
                title = "Product brief.pdf",
                subtitle = "Projects",
                sinceId = "files-2",
            ),
            marketingDashboardItem(
                widgetId = "recommendations",
                title = "Release notes.md",
                subtitle = "Notes",
                sinceId = "files-1",
            ),
        ),
        "photos" to listOf(
            marketingDashboardItem(
                widgetId = "photos",
                title = "Camera backup is up to date",
                subtitle = "128 photos and 14 videos",
                sinceId = "photos-1",
            ),
        ),
    ),
)

internal val marketingUserStatus = NativeUserStatus(
    userId = "fixture-user",
    presence = NativeUserPresence.Online,
    message = "Building the next native experience",
    icon = null,
    messageId = null,
    clearAtEpochSeconds = null,
    messageIsPredefined = false,
    statusIsUserDefined = true,
)

private fun marketingDashboardWidget(
    id: String,
    title: String,
    order: Int,
) = NativeDashboardWidget(
    id = id,
    title = title,
    order = order,
    iconUrl = null,
    iconClass = null,
    widgetUrl = null,
    itemApiVersions = setOf(2),
    itemIconsRound = false,
    reloadIntervalSeconds = null,
    actions = emptyList(),
)

private fun marketingDashboardItem(
    widgetId: String,
    title: String,
    subtitle: String,
    sinceId: String,
) = NativeDashboardItem(
    widgetId = widgetId,
    title = title,
    subtitle = subtitle,
    link = null,
    iconUrl = null,
    overlayIconUrl = null,
    sinceId = sinceId,
)

private val marketingDeckBoard = DeckBoard(
    id = 1,
    title = "Home renovation",
    color = "8b5cf6",
    archived = false,
    owner = DeckUser("fixture-owner", "Demo owner"),
    labels = emptyList(),
    permissions = DeckPermissions(
        canRead = true,
        canEdit = true,
        canManage = true,
        canShare = false,
    ),
    shared = false,
    lastModified = null,
    etag = null,
)

private val marketingDeckStacks = listOf(
    marketingDeckStack(
        id = 10,
        title = "Planned",
        cards = listOf(
            marketingDeckCard(100, 10, "Measure kitchen cabinets", 100),
            marketingDeckCard(101, 10, "Compare paint samples", 200),
            marketingDeckCard(102, 10, "Request tile samples", 300),
        ),
    ),
    marketingDeckStack(
        id = 20,
        title = "In progress",
        cards = listOf(
            marketingDeckCard(200, 20, "Book the electrician", 100),
            marketingDeckCard(201, 20, "Choose hallway lighting", 200),
            marketingDeckCard(202, 20, "Patch the living room wall", 300),
        ),
    ),
    marketingDeckStack(
        id = 30,
        title = "Done",
        cards = listOf(
            marketingDeckCard(300, 30, "Order shelf brackets", 100),
            marketingDeckCard(301, 30, "Set the renovation budget", 200),
            marketingDeckCard(302, 30, "Photograph existing wiring", 300),
        ),
    ),
)

private fun marketingDeckStack(
    id: Long,
    title: String,
    cards: List<DeckCard>,
) = DeckStack(
    id = id,
    boardId = marketingDeckBoard.id,
    title = title,
    order = id,
    doneColumn = title == "Done",
    cards = cards,
    lastModified = null,
    etag = null,
)

private fun marketingDeckCard(
    id: Long,
    stackId: Long,
    title: String,
    order: Long,
) = DeckCard(
    id = id,
    boardId = marketingDeckBoard.id,
    stackId = stackId,
    title = title,
    descriptionMarkdown = null,
    ownerId = "fixture-owner",
    color = null,
    order = order,
    dueAt = null,
    startAt = null,
    completedAt = null,
    archived = false,
    overdue = false,
    labels = emptyList(),
    assignees = emptyList(),
    attachmentCount = 0,
    unreadCommentCount = 0,
    etag = null,
)
