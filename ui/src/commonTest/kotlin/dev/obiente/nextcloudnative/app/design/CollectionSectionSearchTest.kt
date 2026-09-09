package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionSectionSearchTest {
    private val destinations = listOf(
        NextcloudCollectionDestination("recipes", "Recipes", supportingText = "Meals and ingredients"),
        NextcloudCollectionDestination("shopping", "Shopping lists"),
        NextcloudCollectionDestination("settings", "Preferences", section = NextcloudCollectionDestinationSection.Manage),
    )

    @Test
    fun searchMatchesVisibleLabelsAndDescriptionsWithoutChangingIdentityOrOrder() {
        assertEquals(destinations, filterCollectionSections(destinations, "  "))
        assertEquals(listOf(destinations[0]), filterCollectionSections(destinations, " MEALS "))
        assertEquals(listOf(destinations[1]), filterCollectionSections(destinations, "Shopping"))
        assertEquals(emptyList(), filterCollectionSections(destinations, "settings"))
        assertEquals(emptyList(), filterCollectionSections(destinations, "unmatched"))
    }
}
