package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.app.nativeSceneTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericRecipeCollectionPagingSceneTest {
    @Test
    fun filteredSearchDoesNotAutoPageAndOffersOneExplicitPage() {
        var loads = 0
        nativeSceneTest(800, 900, content = {
            GenericRecipeCollection(
                rows = listOf(recipe("1", "Banana bread"), recipe("2", "Tomato soup")),
                onSelectRecord = null,
                imageLoader = null,
                onLoadMore = { loads += 1 },
            )
        }) {
            // An unfiltered short grid may page once on its own; filtering must not page further.
            val unfilteredLoads = loads
            replaceText("", "no synthetic recipe matches this")
            settle()
            settle()
            assertEquals(unfilteredLoads, loads)
            assertTrue(has("Load more recipes"))

            click("Load more recipes")
            assertEquals(unfilteredLoads + 1, loads)
        }
    }

    private fun recipe(id: String, title: String) = NativeRecord(id, mapOf("id" to id, "name" to title)) to
        NativeRecipePresentation(
            title = title,
            description = null,
            category = null,
            servings = null,
            preparationTime = null,
            cookingTime = null,
            totalTime = null,
            keywords = emptyList(),
            imagePath = null,
            placeholderImagePath = null,
        )
}
