package dev.obiente.nextcloudnative.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoNavigationTest {
    @Test
    fun `destinations keep stable order and unavailable active destination falls back`() {
        val intent = planPhotoNavigation(
            state = PhotoNavigationState(PhotoDestination.People),
            capabilities = PhotoNavigationCapabilities(
                albumsAvailable = true,
                peopleAvailable = false,
                favoritesAvailable = true,
            ),
            widthClass = PhotoNavigationWidthClass.Expanded,
        )

        assertEquals(
            listOf(
                PhotoDestination.Timeline,
                PhotoDestination.Folders,
                PhotoDestination.Albums,
                PhotoDestination.Favorites,
            ),
            intent.destinations,
        )
        assertEquals(PhotoDestination.Timeline, intent.activeDestination)
        assertEquals(PhotoNavigationPlacement.Sidebar, intent.placement)
    }

    @Test
    fun `adaptive intent avoids crowded compact navigation and scales to desktop`() {
        val allCapabilities = PhotoNavigationCapabilities(
            albumsAvailable = true,
            peopleAvailable = true,
            favoritesAvailable = true,
        )
        val compactMenu = planPhotoNavigation(
            PhotoNavigationState(),
            allCapabilities,
            PhotoNavigationWidthClass.Compact,
        )
        val compactBottom = planPhotoNavigation(
            PhotoNavigationState(),
            PhotoNavigationCapabilities(albumsAvailable = true),
            PhotoNavigationWidthClass.Compact,
        )
        val rail = planPhotoNavigation(
            PhotoNavigationState(),
            allCapabilities,
            PhotoNavigationWidthClass.Medium,
        )
        val sidebar = planPhotoNavigation(
            PhotoNavigationState(),
            allCapabilities,
            PhotoNavigationWidthClass.Expanded,
        )

        assertEquals(PhotoNavigationPlacement.CompactMenu, compactMenu.placement)
        assertEquals(PhotoNavigationPlacement.BottomBar, compactBottom.placement)
        assertEquals(PhotoNavigationPlacement.NavigationRail, rail.placement)
        assertEquals(PhotoNavigationPlacement.Sidebar, sidebar.placement)
    }

    @Test
    fun `active destination state serializes without folder-local controls`() {
        val state = PhotoNavigationState(PhotoDestination.Folders)

        val encoded = Json.encodeToString(state)

        assertEquals(state, Json.decodeFromString<PhotoNavigationState>(encoded))
        assertEquals("""{"activeDestination":"Folders"}""", encoded)
    }

    @Test
    fun `photo browser context restores after a media viewer round trip`() {
        val state = PhotoBrowserState(
            destination = PhotoDestination.Folders,
            folder = PhotoFolderBrowseState(
                selectedFolderPath = "Photos/Trips/2026",
                query = "summer",
                scope = PhotoFolderBrowseScope.RecursiveMedia,
                preference = PhotoFolderBrowsePreference(PhotoFolderViewMode.List),
            ),
            timelineViewMode = PhotoTimelineViewMode.List,
        )

        val restored = restorePhotoBrowserState(encodePhotoBrowserState(state))

        assertEquals(state, restored)
    }

    @Test
    fun `invalid saved photo browser context falls back safely`() {
        assertEquals(PhotoBrowserState(), restorePhotoBrowserState("{invalid"))
    }

    @Test
    fun `destination copy distinguishes folder albums and people surfaces`() {
        assertEquals("Browse media by server folder", photoDestinationSubtitle(PhotoDestination.Folders))
        assertEquals("Albums and tags", photoDestinationSubtitle(PhotoDestination.Albums))
        assertEquals("Recognized people", photoDestinationSubtitle(PhotoDestination.People))
    }
}
