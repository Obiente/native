package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stable product destinations. Folder scope and view-mode controls intentionally do not appear
 * here because they belong to [PhotoDestination.Folders].
 */
@Serializable
enum class PhotoDestination {
    Timeline,
    Folders,
    Albums,
    People,
    Favorites,
}

@Serializable
data class PhotoNavigationCapabilities(
    val albumsAvailable: Boolean = false,
    val peopleAvailable: Boolean = false,
    val favoritesAvailable: Boolean = false,
)

@Serializable
data class PhotoNavigationState(
    val activeDestination: PhotoDestination = PhotoDestination.Timeline,
)

@Serializable
enum class PhotoTimelineViewMode {
    Grid,
    List,
}

/**
 * Account-scoped Photos navigation context that must survive temporary child screens such as the
 * media viewer, as well as platform recreation.
 */
@Serializable
data class PhotoBrowserState(
    val destination: PhotoDestination = PhotoDestination.Timeline,
    val folder: PhotoFolderBrowseState = PhotoFolderBrowseState(),
    val timelineViewMode: PhotoTimelineViewMode = PhotoTimelineViewMode.Grid,
)

private val photoBrowserStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodePhotoBrowserState(state: PhotoBrowserState): String =
    photoBrowserStateJson.encodeToString(state)

internal fun restorePhotoBrowserState(encoded: String): PhotoBrowserState =
    runCatching {
        photoBrowserStateJson.decodeFromString<PhotoBrowserState>(encoded)
    }.getOrDefault(PhotoBrowserState())

enum class PhotoNavigationWidthClass {
    Compact,
    Medium,
    Expanded,
}

/**
 * Platform-neutral intent. Android and desktop choose their native control for the recommended
 * placement rather than sharing one enlarged phone navigation component.
 */
enum class PhotoNavigationPlacement {
    CompactMenu,
    BottomBar,
    NavigationRail,
    Sidebar,
}

data class PhotoNavigationIntent(
    val destinations: List<PhotoDestination>,
    val activeDestination: PhotoDestination,
    val placement: PhotoNavigationPlacement,
) {
    init {
        require(destinations.isNotEmpty() && destinations.distinct().size == destinations.size) {
            "Photo navigation destinations are invalid."
        }
        require(activeDestination in destinations) { "The active Photos destination is unavailable." }
    }
}

fun planPhotoNavigation(
    state: PhotoNavigationState,
    capabilities: PhotoNavigationCapabilities,
    widthClass: PhotoNavigationWidthClass,
): PhotoNavigationIntent {
    val destinations = buildList {
        add(PhotoDestination.Timeline)
        add(PhotoDestination.Folders)
        if (capabilities.albumsAvailable) add(PhotoDestination.Albums)
        if (capabilities.peopleAvailable) add(PhotoDestination.People)
        if (capabilities.favoritesAvailable) add(PhotoDestination.Favorites)
    }
    val placement = when (widthClass) {
        PhotoNavigationWidthClass.Compact ->
            if (destinations.size <= MAX_PHOTO_BOTTOM_DESTINATIONS) {
                PhotoNavigationPlacement.BottomBar
            } else {
                PhotoNavigationPlacement.CompactMenu
            }
        PhotoNavigationWidthClass.Medium -> PhotoNavigationPlacement.NavigationRail
        PhotoNavigationWidthClass.Expanded -> PhotoNavigationPlacement.Sidebar
    }
    return PhotoNavigationIntent(
        destinations = destinations,
        activeDestination = state.activeDestination.takeIf { it in destinations }
            ?: PhotoDestination.Timeline,
        placement = placement,
    )
}

private const val MAX_PHOTO_BOTTOM_DESTINATIONS = 4
