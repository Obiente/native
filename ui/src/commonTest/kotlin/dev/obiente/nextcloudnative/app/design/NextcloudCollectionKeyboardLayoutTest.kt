package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextcloudCollectionKeyboardLayoutTest {
    private val rows = NextcloudCollectionDestination("rows", "Rows")
    private val views = NextcloudCollectionDestination("views", "Views")
    private val columns = NextcloudCollectionDestination("columns", "Columns", section = NextcloudCollectionDestinationSection.Manage)
    private val shares = NextcloudCollectionDestination("shares", "Shares", section = NextcloudCollectionDestinationSection.Manage)

    @Test
    fun groupedListUsesVisibleOrderAndCountsTheManageHeading() {
        val model = NextcloudCollectionNavigationModel.create(listOf(columns, rows, shares, views), "rows")
        val layout = nextcloudCollectionKeyboardLayout(model, groupedSections = true)

        assertEquals(listOf("rows", "views", "columns", "shares"), layout.navigationModel.destinations.map { it.id })
        assertEquals(mapOf("rows" to 0, "views" to 1, "columns" to 3, "shares" to 4), layout.lazyItemIndexByDestinationId)
        assertEquals("columns", resolveNextcloudCollectionKeyboardDestination(
            layout.navigationModel, "views", NextcloudCollectionNavigationMove.Next,
        )?.id)
        assertEquals("shares", resolveNextcloudCollectionKeyboardDestination(
            layout.navigationModel, "rows", NextcloudCollectionNavigationMove.Last,
        )?.id)
        assertEquals("rows", layout.navigationModel.selectedDestinationId)
        assertNull(layout.lazyItemIndexByDestinationId["missing"])
    }

    @Test
    fun manageOnlyAndEmptyListsDoNotInventDestinationRows() {
        val manageOnly = nextcloudCollectionKeyboardLayout(
            NextcloudCollectionNavigationModel.create(listOf(columns, shares), "columns"), true,
        )
        assertEquals(mapOf("columns" to 1, "shares" to 2), manageOnly.lazyItemIndexByDestinationId)
        val empty = nextcloudCollectionKeyboardLayout(NextcloudCollectionNavigationModel.create(emptyList(), null), true)
        assertEquals(emptyMap(), empty.lazyItemIndexByDestinationId)
        assertEquals(emptyList(), empty.navigationModel.destinations)
    }

    @Test
    fun railKeepsUngroupedOrderWithoutAHeaderOffset() {
        val model = NextcloudCollectionNavigationModel.create(listOf(columns, rows, shares, views), "rows")
        val layout = nextcloudCollectionKeyboardLayout(model, groupedSections = false)
        assertEquals(model.destinations, layout.navigationModel.destinations)
        assertEquals(mapOf("columns" to 0, "rows" to 1, "shares" to 2, "views" to 3), layout.lazyItemIndexByDestinationId)
    }
}
