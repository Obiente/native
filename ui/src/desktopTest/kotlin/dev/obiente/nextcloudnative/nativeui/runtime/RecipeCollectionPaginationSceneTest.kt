package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.mutableStateOf
import dev.obiente.nextcloudnative.app.nativeSceneTest
import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.*

class RecipeCollectionPaginationSceneTest {
    private val resource = ResourceSpec("recipes", "Recipes", Confidence.high, listOf(
        FieldSpec("name", "Name", FieldKind.string, required = false, readOnly = true),
        FieldSpec("recipeYield", "Servings", FieldKind.string, required = false, readOnly = true)))
    private val view = ViewSpec("recipes", "Recipes", "recipes", NativeComponent.collectionList, "recipes.list", Confidence.high)
    private val action = ActionSpec("recipes.list", "Recipes", "recipes",
        ApiBinding(HttpMethod.GET, "/synthetic/recipes", "recipes.list"),
        ActionIntent.list, ActionRisk.readOnly, false, Confidence.high)
    private val schema = NativeAppSchema("0.1", AppIdentity("synthetic-recipes", "Recipes", "1"), Confidence.high,
        resources = listOf(resource), actions = listOf(action), views = listOf(view))
    private fun recipe(id: String, name: String) = NativeRecord(id, mapOf("name" to name, "recipeYield" to "2"))
    private val executor = NativeActionExecutor { error("Collection pagination must remain owned by its host") }

    @Test
    fun specializedRecipeRendererKeepsLoadingErrorRetryAndFilteredNextPages() {
        val records = mutableStateOf(listOf(recipe("1", "Synthetic soup")))
        val loading = mutableStateOf(true)
        val failure = mutableStateOf<String?>(null)
        val complete = mutableStateOf(false)
        var loads = 0
        val loadMore = { loads++; loading.value = true }
        nativeSceneTest(390, 844, content = {
            GenericNativeAppScreen(schema, view, NativeScreenState.Ready(records.value), executor,
                onLoadMore = loadMore.takeUnless { complete.value }, loadingMore = loading.value, loadMoreError = failure.value)
        }) {
            assertTrue(has("Search recipes"))
            assertTrue(has("Loading more..."))
            assertEquals(0, loads)
            replaceText("", "cake")
            assertTrue(has("No loaded recipes match your search."))
            loading.value = false
            failure.value = "Could not load more recipes."
            settle()
            assertTrue(has("Could not load more recipes."))
            assertEquals(0, loads)
            click("Try again")
            assertEquals(1, loads)
            assertTrue(has("Loading more..."))
            records.value += recipe("2", "Synthetic cake")
            failure.value = null
            loading.value = false
            complete.value = true
            settle()
            assertTrue(has("Synthetic cake"))
            assertFalse(has("Synthetic soup"))
            assertFalse(has("Loading more..."))
            assertFalse(has("Try again"))
            replaceText("cake", "absent")
            assertTrue(has("No recipes match your search."))
            assertEquals(1, loads)
        }
    }

    @Test
    fun nearEndRequestsNextPageOnlyWhileAvailableAndNotAlreadyLoading() {
        val loading = mutableStateOf(false)
        val complete = mutableStateOf(false)
        var loads = 0
        val loadMore = { loads++; loading.value = true }
        nativeSceneTest(390, 844, content = {
            GenericNativeAppScreen(schema, view, NativeScreenState.Ready(listOf(recipe("1", "Synthetic soup"))), executor,
                onLoadMore = loadMore.takeUnless { complete.value }, loadingMore = loading.value)
        }) {
            assertEquals(1, loads)
            settle()
            assertEquals(1, loads)
            complete.value = true
            loading.value = false
            settle()
            assertEquals(1, loads)
        }
    }
}
