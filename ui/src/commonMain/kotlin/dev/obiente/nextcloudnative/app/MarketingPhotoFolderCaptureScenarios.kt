package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation

internal val photoFolderCaptureScenarios: List<MarketingCaptureScenario> = listOf(
    MarketingCaptureScenario.PhotoFolderBrowserMobile,
    MarketingCaptureScenario.PhotoFolderBrowserDesktop,
    MarketingCaptureScenario.HomepagePhotosDesktopDark,
    MarketingCaptureScenario.HomepagePhotosDesktopLight,
)

@Composable
internal fun MarketingPhotoFolderScenario(
    scenario: MarketingCaptureScenario,
    assets: MarketingCaptureAssets,
) {
    require(scenario in photoFolderCaptureScenarios) {
        "${scenario.id} is not a photo folder capture."
    }
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    val state = remember(scenario) {
        if (desktop) {
            PhotoFolderBrowseState(
                selectedFolderPath = "Photos",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                preference = PhotoFolderBrowsePreference(PhotoFolderViewMode.List),
            )
        } else {
            PhotoFolderBrowseState(
                selectedFolderPath = "Photos/Trips",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                preference = PhotoFolderBrowsePreference(PhotoFolderViewMode.Grid),
            )
        }
    }
    val inventory = remember(state) {
        buildPhotoFolderPagedInventory(
            pages = marketingPhotoFolderFiles.chunked(4),
            state = state,
        )
    }
    val result = remember(inventory, state) {
        buildPhotoFolderBrowseResult(inventory, state)
    }
    val content: @Composable () -> Unit = {
        MarketingPhotoFolderContent(
            state = state,
            inventory = inventory,
            result = result,
            services = assets.services,
            widthClass = if (desktop) {
                PhotoNavigationWidthClass.Expanded
            } else {
                PhotoNavigationWidthClass.Compact
            },
        )
    }
    if (desktop) {
        NextcloudDesktopShell(
            selected = NextcloudDestination.Apps,
            onSelected = {},
            identity = marketingDesktopIdentity(avatar = assets.avatar),
            content = content,
        )
    } else {
        content()
    }
}

@Composable
private fun MarketingPhotoFolderContent(
    state: PhotoFolderBrowseState,
    inventory: PhotoFolderPagedInventory,
    result: PhotoFolderBrowseResult,
    services: NextcloudPlatformServices,
    widthClass: PhotoNavigationWidthClass,
) {
    val navigationIntent = remember(widthClass) {
        planPhotoNavigation(
            state = PhotoNavigationState(PhotoDestination.Folders),
            capabilities = PhotoNavigationCapabilities(
                albumsAvailable = true,
                peopleAvailable = true,
                favoritesAvailable = false,
            ),
            widthClass = widthClass,
        )
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Photos & Memories",
            subtitle = photoDestinationSubtitle(PhotoDestination.Folders),
            onBack = {},
        )
        PhotoAdaptiveNavigationLayout(
            intent = navigationIntent,
            onDestinationSelected = {},
            modifier = Modifier.weight(1f),
        ) {
            PhotoFolderBrowser(
                inventory = inventory,
                initialResult = Result.success(result),
                selectedFolderPath = state.selectedFolderPath,
                query = state.query,
                scope = state.scope,
                viewMode = state.preference.viewMode,
                backupStatuses = marketingPhotoFolderBackupStatuses,
                gridState = rememberLazyGridState(),
                listState = rememberLazyListState(),
                services = services,
                session = marketingPhotoFolderSession,
                onSelectedFolderPathChanged = {},
                onQueryChanged = {},
                onScopeChanged = {},
                onViewModeChanged = {},
                onOpenMedia = { _, _ -> },
            )
        }
    }
}

private fun marketingPhotoFile(
    id: Long,
    path: String,
    mimeType: String,
    size: Long,
): NextcloudFile = NextcloudFile(
    path = path,
    name = path.substringAfterLast('/'),
    isDirectory = false,
    mimeType = mimeType,
    size = size,
    lastModified = "Mon, 27 Jul 2026 09:00:00 GMT",
    fileId = id,
    hasPreview = true,
    etag = "capture-$id",
)

private val marketingPhotoFolderFiles = listOf(
    marketingPhotoFile(2_450L, "Photos/library-cover.jpg", "image/jpeg", 4_200_000L),
    marketingPhotoFile(2_451L, "Photos/Camera/garden-morning.jpg", "image/jpeg", 5_100_000L),
    marketingPhotoFile(2_452L, "Photos/Camera/workshop.mp4", "video/mp4", 84_000_000L),
    marketingPhotoFile(2_453L, "Photos/Family/picnic.jpg", "image/jpeg", 3_800_000L),
    marketingPhotoFile(2_454L, "Photos/Trips/train-window.jpg", "image/jpeg", 6_200_000L),
    marketingPhotoFile(2_455L, "Photos/Trips/train-window.DNG", "image/x-adobe-dng", 32_000_000L),
    marketingPhotoFile(2_456L, "Photos/Trips/market.jpg", "image/jpeg", 5_700_000L),
    marketingPhotoFile(2_457L, "Photos/Trips/rain-walk.mp4", "video/mp4", 48_000_000L),
    marketingPhotoFile(2_458L, "Photos/Trips/Coast/sunset.jpg", "image/jpeg", 7_300_000L),
    marketingPhotoFile(2_459L, "Photos/Trips/Coast/harbor.jpg", "image/jpeg", 6_900_000L),
    marketingPhotoFile(2_460L, "Photos/Trips/City/evening-lights.jpg", "image/jpeg", 5_900_000L),
    marketingPhotoFile(2_461L, "Photos/Trips/City/cafe.jpg", "image/jpeg", 4_600_000L),
)

private val marketingPhotoFolderBackupStatuses = mapOf(
    "Photos/library-cover.jpg" to MediaBackupStatus.BackedUp,
    "Photos/Trips/train-window.jpg" to MediaBackupStatus.BackedUp,
    "Photos/Trips/market.jpg" to MediaBackupStatus.Pending,
    "Photos/Trips/rain-walk.mp4" to MediaBackupStatus.CloudOnly,
)

private val marketingPhotoFolderSession = NextcloudSession(
    serverUrl = "https://capture.invalid",
    loginName = "mock-user",
    appPassword = "capture-only",
)
