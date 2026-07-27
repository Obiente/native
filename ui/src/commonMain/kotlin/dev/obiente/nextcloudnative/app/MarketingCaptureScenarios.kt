package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

enum class MarketingCaptureScenario(
    val id: String,
    val fileName: String,
    val presentation: NextcloudPresentation,
    val width: Int,
    val height: Int,
    val density: Float,
) {
    DesktopHome("desktop-home", "desktop-home.png", NextcloudPresentation.Desktop, 1_440, 900, 1f),
    MobileHome("mobile-home", "mobile-home.png", NextcloudPresentation.Adaptive, 1_080, 2_400, 2.625f),
    ObsidianSync("obsidian-vault-sync", "obsidian-vault-sync.png", NextcloudPresentation.Adaptive, 1_080, 1_000, 2.625f),
    MediaBackup("media-backup-queue", "media-backup-queue.png", NextcloudPresentation.Adaptive, 1_080, 1_800, 2.625f),
    AdaptiveApp("adaptive-dynamic-data", "adaptive-dynamic-data.png", NextcloudPresentation.Desktop, 960, 360, 1f),
    RawPreviewLoadingMobile(
        "raw-preview-loading-mobile",
        "raw-preview-loading-mobile.png",
        NextcloudPresentation.Adaptive,
        1_080,
        1_200,
        2.625f,
    ),
    RawPreviewErrorMobile(
        "raw-preview-error-mobile",
        "raw-preview-error-mobile.png",
        NextcloudPresentation.Adaptive,
        1_080,
        1_200,
        2.625f,
    ),
    RawPreviewMemoriesReadyMobile(
        "raw-preview-memories-ready-mobile",
        "raw-preview-memories-ready-mobile.png",
        NextcloudPresentation.Adaptive,
        1_080,
        1_600,
        2.625f,
    ),
    RawPreviewHighDetailDesktop(
        "raw-preview-high-detail-desktop",
        "raw-preview-high-detail-desktop.png",
        NextcloudPresentation.Desktop,
        1_440,
        900,
        1f,
    ),
    FileShareUserMobile(
        id = "file-share-user-mobile",
        fileName = "file-share-user-mobile.png",
        presentation = NextcloudPresentation.Adaptive,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    FileShareGroupDesktop(
        id = "file-share-group-desktop",
        fileName = "file-share-group-desktop.png",
        presentation = NextcloudPresentation.Desktop,
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    FileShareLoadingMobile(
        id = "file-share-loading-mobile",
        fileName = "file-share-loading-mobile.png",
        presentation = NextcloudPresentation.Adaptive,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    FileShareErrorMobile(
        id = "file-share-error-mobile",
        fileName = "file-share-error-mobile.png",
        presentation = NextcloudPresentation.Adaptive,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    TransferMobilePending(
        "transfer-mobile-pending",
        "transfer-mobile-pending.png",
        NextcloudPresentation.Adaptive,
        1_080,
        1_800,
        2.625f,
    ),
    TransferMobileFailed(
        "transfer-mobile-failed-cached",
        "transfer-mobile-failed-cached.png",
        NextcloudPresentation.Adaptive,
        1_080,
        1_800,
        2.625f,
    ),
    TransferDesktopActive(
        "transfer-desktop-active",
        "transfer-desktop-active.png",
        NextcloudPresentation.Desktop,
        1_280,
        800,
        1f,
    ),
    TransferDesktopCompleted(
        "transfer-desktop-completed-page",
        "transfer-desktop-completed-page.png",
        NextcloudPresentation.Desktop,
        1_280,
        800,
        1f,
    ),
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

data class MarketingCaptureAssets(
    val avatar: ImageBitmap,
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
                            ),
                            MediaSyncFolderSuggestion(
                                localRootHint = "fixture-media-screenshots",
                                displayName = "Screenshots",
                                relativePath = "Pictures/Screenshots",
                                kind = MediaSyncFolderKind.Screenshots,
                                imageCount = 36,
                                videoCount = 2,
                                suggestedRemoteRootPath = "Photos/Phone/Screenshots",
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
internal fun MarketingAdaptiveAppScenario() {
    val schema = marketingAdaptiveSchema
    val view = schema.views.single()
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Community inventory",
            subtitle = "Discovered native data",
            onBack = {},
        )
        GenericNativeAppScreen(
            schema = schema,
            view = view,
            state = NativeScreenState.Ready(marketingAdaptiveRecords),
            actionExecutor = NativeActionExecutor {
                NativeActionExecutionResult.Failure("This fixture is read-only.")
            },
            modifier = Modifier.weight(1f),
        )
    }
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

private val marketingAdaptiveSchema = NativeAppSchema(
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
                FieldSpec("amount", "Value", FieldKind.currency, required = false, readOnly = true),
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

private val marketingAdaptiveRecords = listOf(
    NativeRecord(
        id = "item-1",
        values = mapOf(
            "name" to "Field recorder",
            "category" to "Audio",
            "amount" to "€ 219.00",
            "status" to "Available",
            "updated" to "2026-07-24",
        ),
    ),
    NativeRecord(
        id = "item-2",
        values = mapOf(
            "name" to "Tripod",
            "category" to "Camera",
            "amount" to "€ 84.50",
            "status" to "On loan",
            "updated" to "2026-07-23",
        ),
    ),
    NativeRecord(
        id = "item-3",
        values = mapOf(
            "name" to "USB-C hub",
            "category" to "Computer",
            "amount" to "€ 49.95",
            "status" to "Available",
            "updated" to "2026-07-22",
        ),
    ),
)
