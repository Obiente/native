package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HomeDashboardPresentationTest {
    @Test
    fun `home refresh accessibility label follows the visible title`() {
        assertEquals("Refresh Home", dashboardRefreshDescription("Home"))
        assertEquals("Refresh User Status", dashboardRefreshDescription("User Status"))
    }

    @Test
    fun `root home renders the customizable workspace instead of the legacy launcher`() {
        assertEquals(
            RootDestinationContent.HomeWorkspace,
            rootDestinationContent(NextcloudDestination.Home),
        )
        assertEquals(
            RootDestinationContent.Apps,
            rootDestinationContent(NextcloudDestination.Apps),
        )
        assertEquals(
            RootDestinationContent.FolderSync,
            rootDestinationContent(NextcloudDestination.FolderSync),
        )
        assertEquals(
            RootDestinationContent.Activity,
            rootDestinationContent(NextcloudDestination.Activity),
        )
        assertEquals(
            RootDestinationContent.Settings,
            rootDestinationContent(NextcloudDestination.Settings),
        )
    }

    @Test
    fun `known dashboard widgets use reusable semantic home sections`() {
        val bindings = homeDashboardWidgetBindings(
            listOf(
                widget(id = "calendar", title = "Upcoming events"),
                widget(id = "activity", title = "Recent activity"),
                widget(id = "recommendations", title = "Recommended files"),
            ),
        )

        assertEquals(
            listOf(
                HomeSectionIds.Upcoming,
                HomeSectionIds.Activity,
                HomeSectionIds.RecentFiles,
            ),
            bindings.map(HomeDashboardWidgetBinding::sectionId),
        )
    }

    @Test
    fun `unknown and colliding widgets receive stable distinct bounded sections`() {
        val widgets = listOf(
            widget(id = "weather", title = "Weather"),
            widget(id = "photos", title = "Photos"),
            widget(id = "memories", title = "Memories"),
        )

        val first = homeDashboardWidgetBindings(widgets)
        val second = homeDashboardWidgetBindings(widgets)

        assertEquals(first.map(HomeDashboardWidgetBinding::sectionId), second.map(HomeDashboardWidgetBinding::sectionId))
        assertEquals(HomeSectionIds.PhotoBackup, first[1].sectionId)
        assertNotEquals(first[1].sectionId, first[2].sectionId)
        first.forEach { binding ->
            assertTrue(binding.sectionId.value.length <= 80)
        }
    }

    @Test
    fun `dynamically hashed section collisions are disambiguated deterministically`() {
        val collidingWidgets = List(4) { index ->
            widget(id = "weather", title = "Weather source $index")
        }

        val first = homeDashboardWidgetBindings(collidingWidgets)
        val second = homeDashboardWidgetBindings(collidingWidgets)

        assertEquals(
            first.map(HomeDashboardWidgetBinding::sectionId),
            second.map(HomeDashboardWidgetBinding::sectionId),
        )
        assertEquals(
            collidingWidgets.size,
            first.map(HomeDashboardWidgetBinding::sectionId).distinct().size,
        )
        first.forEach { binding ->
            assertTrue(binding.sectionId.value.length <= 80)
        }
    }

    @Test
    fun `section size controls bounded initial information density`() {
        assertEquals(2, dashboardCollapsedItemCount(HomeSectionSize.Compact))
        assertEquals(4, dashboardCollapsedItemCount(HomeSectionSize.Comfortable))
        assertEquals(8, dashboardCollapsedItemCount(HomeSectionSize.Dense))
    }

    private fun widget(id: String, title: String): NativeDashboardWidget = NativeDashboardWidget(
        id = id,
        title = title,
        order = 0,
        iconUrl = null,
        iconClass = null,
        widgetUrl = null,
        itemApiVersions = setOf(1),
        itemIconsRound = false,
        reloadIntervalSeconds = null,
        actions = emptyList(),
    )
}
