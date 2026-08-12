package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextcloudCollectionNavigatorTest {
    @Test
    fun `navigation stays hidden when there is no choice`() {
        NextcloudCollectionNavigationHost.entries.forEach { host ->
            listOf(0, 1).forEach { destinationCount ->
                assertEquals(
                    NextcloudCollectionNavigationMode.Hidden,
                    resolveNextcloudCollectionNavigationMode(host, 1_400, destinationCount),
                )
            }
        }
    }

    @Test
    fun `adaptive Android uses tabs for a small compact destination set`() {
        assertEquals(
            NextcloudCollectionNavigationMode.Tabs,
            resolveNextcloudCollectionNavigationMode(
                NextcloudCollectionNavigationHost.AdaptiveAndroid,
                NextcloudWorkspaceBreakpoints.AdaptiveRailDp - 1,
                2,
            ),
        )
        assertEquals(
            NextcloudCollectionNavigationMode.Rail,
            resolveNextcloudCollectionNavigationMode(
                NextcloudCollectionNavigationHost.AdaptiveAndroid,
                NextcloudWorkspaceBreakpoints.AdaptiveRailDp,
                2,
            ),
        )
    }

    @Test
    fun `adaptive Android uses a drawer when compact apps expose many destinations`() {
        assertEquals(
            NextcloudCollectionNavigationMode.Drawer,
            resolveNextcloudCollectionNavigationMode(
                NextcloudCollectionNavigationHost.AdaptiveAndroid,
                NextcloudWorkspaceBreakpoints.AdaptiveRailDp - 1,
                5,
            ),
        )
    }

    @Test
    fun `desktop switches from rail to sidebar at shared breakpoint`() {
        assertEquals(
            NextcloudCollectionNavigationMode.Rail,
            resolveNextcloudCollectionNavigationMode(
                NextcloudCollectionNavigationHost.Desktop,
                NextcloudWorkspaceBreakpoints.DesktopSidebarDp - 1,
                3,
            ),
        )
        assertEquals(
            NextcloudCollectionNavigationMode.Sidebar,
            resolveNextcloudCollectionNavigationMode(
                NextcloudCollectionNavigationHost.Desktop,
                NextcloudWorkspaceBreakpoints.DesktopSidebarDp,
                3,
            ),
        )
    }

    @Test
    fun `compact drawer keeps menu in the leading slot at every depth`() {
        assertEquals(
            NextcloudCollectionLeadingControl.Menu,
            resolveNextcloudCollectionLeadingControl(
                mode = NextcloudCollectionNavigationMode.Drawer,
                hasHierarchyBack = false,
            ),
        )
        assertEquals(
            NextcloudCollectionLeadingControl.Menu,
            resolveNextcloudCollectionLeadingControl(
                mode = NextcloudCollectionNavigationMode.Drawer,
                hasHierarchyBack = true,
            ),
        )
        listOf(
            NextcloudCollectionNavigationMode.Hidden,
            NextcloudCollectionNavigationMode.Tabs,
            NextcloudCollectionNavigationMode.Rail,
            NextcloudCollectionNavigationMode.Sidebar,
        ).forEach { mode ->
            assertEquals(
                NextcloudCollectionLeadingControl.Back,
                resolveNextcloudCollectionLeadingControl(
                    mode = mode,
                    hasHierarchyBack = false,
                ),
            )
        }
    }

    @Test
    fun `nested compact route keeps drawer access in its stable leading slot`() {
        assertEquals(
            NextcloudCollectionLeadingControl.Menu,
            resolveNextcloudCollectionLeadingControl(
                mode = NextcloudCollectionNavigationMode.Drawer,
                hasHierarchyBack = true,
            ),
        )
        assertFalse(
            shouldShowNextcloudCollectionTrailingNavigation(
                mode = NextcloudCollectionNavigationMode.Drawer,
                hasHierarchyBack = true,
            ),
        )
        assertFalse(
            shouldShowNextcloudCollectionTrailingNavigation(
                mode = NextcloudCollectionNavigationMode.Drawer,
                hasHierarchyBack = false,
            ),
        )
        assertFalse(
            shouldShowNextcloudCollectionTrailingNavigation(
                mode = NextcloudCollectionNavigationMode.Rail,
                hasHierarchyBack = true,
            ),
        )
    }

    @Test
    fun `drawer and sidebar give long destination labels a second line`() {
        assertEquals(
            2,
            resolveNextcloudCollectionDestinationLabelMaxLines(
                NextcloudCollectionNavigationMode.Drawer,
            ),
        )
        assertEquals(
            2,
            resolveNextcloudCollectionDestinationLabelMaxLines(
                NextcloudCollectionNavigationMode.Sidebar,
            ),
        )
        assertEquals(
            1,
            resolveNextcloudCollectionDestinationLabelMaxLines(
                NextcloudCollectionNavigationMode.Tabs,
            ),
        )
        assertEquals(
            1,
            resolveNextcloudCollectionDestinationLabelMaxLines(
                NextcloudCollectionNavigationMode.Rail,
            ),
        )
        assertEquals(
            1,
            resolveNextcloudCollectionDestinationLabelMaxLines(
                NextcloudCollectionNavigationMode.Hidden,
            ),
        )
    }

    @Test
    fun `model copies destinations and resolves selection`() {
        val mutableDestinations = mutableListOf(
            NextcloudCollectionDestination("all", "All items", 4),
            NextcloudCollectionDestination("assigned", "Assigned to me"),
        )
        val model = NextcloudCollectionNavigationModel.create(mutableDestinations, "assigned")

        mutableDestinations.clear()

        assertEquals(2, model.destinations.size)
        assertEquals("assigned", model.selectedDestination?.id)
    }

    @Test
    fun `empty model permits only an empty selection`() {
        assertNull(NextcloudCollectionNavigationModel.create(emptyList(), null).selectedDestination)
        assertFailsWith<IllegalArgumentException> {
            NextcloudCollectionNavigationModel.create(emptyList(), "missing")
        }
    }

    @Test
    fun `model permits no primary selection while a contextual view is active`() {
        val destinations = listOf(
            NextcloudCollectionDestination("today", "Today"),
            NextcloudCollectionDestination("upcoming", "Upcoming"),
        )

        val model = NextcloudCollectionNavigationModel.create(destinations, null)

        assertNull(model.selectedDestinationId)
        assertNull(model.selectedDestination)
        assertEquals(-1, resolveNextcloudCollectionSelectedIndex(model))
    }

    @Test
    fun `model rejects missing and duplicate identities`() {
        val destinations = listOf(
            NextcloudCollectionDestination("today", "Today"),
            NextcloudCollectionDestination("upcoming", "Upcoming"),
        )
        assertFailsWith<IllegalArgumentException> {
            NextcloudCollectionNavigationModel.create(destinations, "missing")
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudCollectionNavigationModel.create(
                listOf(
                    NextcloudCollectionDestination("today", "Today"),
                    NextcloudCollectionDestination("today", "Also today"),
                ),
                "today",
            )
        }
    }

    @Test
    fun `keyboard movement follows destination order and wraps at the ends`() {
        val model = NextcloudCollectionNavigationModel.create(
            destinations = listOf(
                NextcloudCollectionDestination("all", "All"),
                NextcloudCollectionDestination("open", "Open"),
                NextcloudCollectionDestination("done", "Done"),
            ),
            selectedDestinationId = "open",
        )

        assertEquals(
            "done",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = "open",
                move = NextcloudCollectionNavigationMove.Next,
            )?.id,
        )
        assertEquals(
            "all",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = "done",
                move = NextcloudCollectionNavigationMove.Next,
            )?.id,
        )
        assertEquals(
            "done",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = "all",
                move = NextcloudCollectionNavigationMove.Previous,
            )?.id,
        )
        assertEquals(
            "all",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = "open",
                move = NextcloudCollectionNavigationMove.First,
            )?.id,
        )
        assertEquals(
            "done",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = "open",
                move = NextcloudCollectionNavigationMove.Last,
            )?.id,
        )
    }

    @Test
    fun `keyboard movement from a contextual view focuses without inventing selection`() {
        val model = NextcloudCollectionNavigationModel.create(
            destinations = listOf(
                NextcloudCollectionDestination("all", "All"),
                NextcloudCollectionDestination("open", "Open"),
            ),
            selectedDestinationId = null,
        )

        assertEquals(
            "all",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = null,
                move = NextcloudCollectionNavigationMove.Next,
            )?.id,
        )
        assertEquals(
            "open",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = null,
                move = NextcloudCollectionNavigationMove.Previous,
            )?.id,
        )
        assertNull(model.selectedDestination)
    }

    @Test
    fun `keyboard movement tolerates stale focus and empty destinations`() {
        val model = NextcloudCollectionNavigationModel.create(
            destinations = listOf(
                NextcloudCollectionDestination("all", "All"),
                NextcloudCollectionDestination("open", "Open"),
            ),
            selectedDestinationId = "open",
        )
        assertEquals(
            "all",
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = "removed",
                move = NextcloudCollectionNavigationMove.Next,
            )?.id,
        )
        assertNull(
            resolveNextcloudCollectionKeyboardDestination(
                model = NextcloudCollectionNavigationModel.create(emptyList(), null),
                focusedDestinationId = null,
                move = NextcloudCollectionNavigationMove.Next,
            ),
        )
    }

    @Test
    fun `large dynamic destination sets retain full keyboard reachability`() {
        val destinations = List(10_000) { index ->
            NextcloudCollectionDestination(
                id = "destination-$index",
                label = "Destination $index",
            )
        }
        val model = NextcloudCollectionNavigationModel.create(
            destinations = destinations,
            selectedDestinationId = destinations.first().id,
        )

        assertEquals(
            destinations.last().id,
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = destinations.first().id,
                move = NextcloudCollectionNavigationMove.Last,
            )?.id,
        )
        assertEquals(
            destinations.first().id,
            resolveNextcloudCollectionKeyboardDestination(
                model = model,
                focusedDestinationId = destinations.last().id,
                move = NextcloudCollectionNavigationMove.Next,
            )?.id,
        )
    }

    @Test
    fun `large destination models retain focus state only for composed lazy items`() {
        val destinationIds = List(10_000) { index -> "destination-$index" }
        val registry = NextcloudCollectionComposedDestinationRegistry<Any>()
        val firstVisible = Any()
        val secondVisible = Any()
        val recycled = Any()

        assertEquals(0, registry.retainedCount)
        registry.attach(destinationIds.first(), firstVisible)
        registry.attach(destinationIds[1], secondVisible)
        assertEquals(2, registry.retainedCount)

        registry.attach(destinationIds.first(), recycled)
        registry.detach(destinationIds.first(), firstVisible)
        assertEquals(2, registry.retainedCount)
        assertEquals(recycled, registry[destinationIds.first()])

        registry.detach(destinationIds.first(), recycled)
        registry.detach(destinationIds[1], secondVisible)
        assertEquals(0, registry.retainedCount)
        assertNull(registry[destinationIds.last()])
    }

    @Test
    fun `destination and policy reject impossible values`() {
        val preferences = NextcloudCollectionDestination(
            id = "preferences-layout",
            label = "Preferences",
            accessibilityId = "prefs-get-user-prefs",
        )
        assertEquals(
            "prefs-get-user-prefs",
            preferences.accessibilityId,
        )
        assertEquals("Open destination Preferences", preferences.accessibilityDescription())
        assertEquals(
            "collection-destination:prefs-get-user-prefs:preferences-layout",
            preferences.automationTestTag(),
        )
        assertFailsWith<IllegalArgumentException> {
            NextcloudCollectionDestination("", "Today")
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudCollectionDestination("today", " ")
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudCollectionDestination("today", "Today", -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudCollectionDestination(
                id = "today",
                label = "Today",
                accessibilityId = " ",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            resolveNextcloudCollectionNavigationMode(
                NextcloudCollectionNavigationHost.Desktop,
                -1,
                2,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            resolveNextcloudCollectionNavigationMode(
                NextcloudCollectionNavigationHost.Desktop,
                800,
                -1,
            )
        }
    }

    @Test
    fun `automation tags remain unique when destinations share a source action`() {
        val first = NextcloudCollectionDestination(
            id = "active-items",
            label = "Active",
            accessibilityId = "items-index",
        )
        val second = NextcloudCollectionDestination(
            id = "archived-items",
            label = "Archived",
            accessibilityId = "items-index",
        )

        NextcloudCollectionNavigationModel.create(
            destinations = listOf(first, second),
            selectedDestinationId = first.id,
        )

        assertEquals("collection-destination:items-index:active-items", first.automationTestTag())
        assertEquals("collection-destination:items-index:archived-items", second.automationTestTag())
        assertTrue(first.automationTestTag() != second.automationTestTag())
    }
}
