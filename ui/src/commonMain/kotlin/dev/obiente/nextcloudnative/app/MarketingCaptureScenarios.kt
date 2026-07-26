package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
    val width: Int,
    val height: Int,
    val density: Float,
) {
    DesktopHome(
        id = "desktop-home",
        fileName = "desktop-home.png",
        presentation = NextcloudPresentation.Desktop,
        feature = "Workspace",
        surface = "Home dashboard",
        state = "Ready",
        purpose = MarketingCapturePurpose.Showcase,
        platform = "desktop",
        viewport = "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    MobileHome(
        id = "mobile-home",
        fileName = "mobile-home.png",
        presentation = NextcloudPresentation.Adaptive,
        feature = "Workspace",
        surface = "Home dashboard",
        state = "Ready",
        purpose = MarketingCapturePurpose.Showcase,
        platform = "mobile",
        viewport = "phone-portrait",
        width = 1_080,
        height = 2_400,
        density = 2.625f,
    ),
    ObsidianSync(
        id = "obsidian-vault-sync",
        fileName = "obsidian-vault-sync.png",
        presentation = NextcloudPresentation.Adaptive,
        feature = "File sync",
        surface = "Folder pair",
        state = "One pending upload",
        purpose = MarketingCapturePurpose.Showcase,
        platform = "mobile",
        viewport = "phone-compact",
        width = 1_080,
        height = 1_000,
        density = 2.625f,
    ),
    MediaBackup(
        id = "media-backup-queue",
        fileName = "media-backup-queue.png",
        presentation = NextcloudPresentation.Adaptive,
        feature = "Media backup",
        surface = "Folder discovery",
        state = "Ready with pending uploads",
        purpose = MarketingCapturePurpose.Showcase,
        platform = "mobile",
        viewport = "phone-portrait",
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    AdaptiveApp(
        id = "adaptive-dynamic-data",
        fileName = "adaptive-dynamic-data.png",
        presentation = NextcloudPresentation.Desktop,
        feature = "Dynamic apps",
        surface = "Data table",
        state = "Ready",
        purpose = MarketingCapturePurpose.Showcase,
        platform = "desktop",
        viewport = "embedded-wide",
        width = 960,
        height = 360,
        density = 1f,
    ),
}

val marketingCaptureScenarios: List<MarketingCaptureScenario> =
    MarketingCaptureScenario.entries

data class MarketingCaptureAssets(
    val avatar: ImageBitmap,
)

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
