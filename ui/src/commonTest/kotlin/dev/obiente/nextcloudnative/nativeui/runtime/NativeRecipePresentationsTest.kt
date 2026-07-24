package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRecipePresentationsTest {
    @Test
    fun `recipe list stubs become photo first native recipe cards by semantic shape`() {
        val resource = recipeResource("mealEntries", "Meal entries")
        val record = NativeRecord(
            id = "123",
            values = mapOf(
                "@type" to "Recipe",
                "name" to "Baked bananas",
                "recipeCategory" to "Dessert",
                "recipeYield" to "4",
                "totalTime" to "PT0H40M",
                "keywords" to "fruit, warm dessert",
                "imageUrl" to "/index.php/apps/food/api/v1/recipes/123/image?size=thumb",
                "imagePlaceholderUrl" to "/index.php/apps/food/api/v1/recipes/123/image?size=thumb16",
            ),
        )

        val recipe = assertNotNull(nativeRecipePresentation(resource, record))

        assertEquals("Baked bananas", recipe.title)
        assertEquals("Dessert · 40 min · 4 servings", recipe.collectionMetadata)
        assertEquals(listOf("fruit", "warm dessert"), recipe.keywords)
        assertEquals("/index.php/apps/food/api/v1/recipes/123/image?size=thumb", recipe.imagePath)
        assertEquals(
            "/index.php/apps/food/api/v1/recipes/123/image?size=thumb16",
            recipe.placeholderImagePath,
        )
    }

    @Test
    fun `standard ingredient and direction shapes receive recipe semantics without recipe resource names`() {
        val resource = recipeResource("entries", "Entries")
        val record = NativeRecord(
            id = "123",
            values = mapOf("title" to "Shape based soup"),
            structuredValues = mapOf(
                "ingredients" to listValue("Water", "Vegetables"),
                "directions" to listValue("Bring to a boil.", "Simmer for ten minutes."),
            ),
        )

        val recipe = assertNotNull(nativeRecipePresentation(resource, record))
        val collection = nativeRecipeCollectionPresentations(resource, listOf(record))

        assertEquals("Shape based soup", recipe.title)
        assertEquals("Shape based soup", collection?.single()?.second?.title)
    }

    @Test
    fun `recipe collection promotion is conservative for mixed datasets`() {
        val resource = recipeResource("entries", "Entries")
        val recipe = NativeRecord(
            id = "recipe",
            values = mapOf("@type" to "Recipe", "name" to "Soup"),
        )
        val unrelated = NativeRecord(
            id = "document",
            values = mapOf("name" to "Meeting notes", "description" to "Not food"),
        )

        assertNull(nativeRecipeCollectionPresentations(resource, listOf(recipe, unrelated)))
    }

    @Test
    fun `recipe search and categories use native recipe meaning`() {
        fun row(
            id: String,
            title: String,
            category: String,
            description: String,
            keywords: List<String>,
        ) = NativeRecord(id, mapOf("name" to title)) to NativeRecipePresentation(
            title = title,
            description = description,
            category = category,
            servings = null,
            preparationTime = null,
            cookingTime = null,
            totalTime = null,
            keywords = keywords,
            imagePath = null,
            placeholderImagePath = null,
        )
        val rows = listOf(
            row("1", "Tomato soup", "Dinner", "A warming bowl", listOf("vegan")),
            row("2", "Apple crumble", "Dessert", "Served warm", listOf("fruit")),
            row("3", "Berry tart", "dessert", "Summer pastry", listOf("berries")),
        )

        assertEquals(listOf("Dessert", "Dinner"), nativeRecipeCategories(rows))
        assertEquals(
            listOf("Apple crumble"),
            filterNativeRecipeCollection(rows, query = "fruit", category = "dessert")
                .map { (_, recipe) -> recipe.title },
        )
        assertEquals(
            listOf("Tomato soup"),
            filterNativeRecipeCollection(rows, query = "warming", category = null)
                .map { (_, recipe) -> recipe.title },
        )
    }

    @Test
    fun `external and traversal image references never reach the authenticated image loader`() {
        val resource = recipeResource("recipes", "Recipes")
        val external = NativeRecord(
            id = "external",
            values = mapOf(
                "name" to "External",
                "imageUrl" to "https://media.example.test/recipe.jpg",
            ),
        )
        val traversal = external.copy(
            id = "traversal",
            values = external.values + ("imageUrl" to "/apps/food/../files/secret"),
        )

        assertNull(nativeRecipePresentation(resource, external)?.imagePath)
        assertNull(nativeRecipePresentation(resource, traversal)?.imagePath)
    }

    @Test
    fun `ingredient and instruction arrays remain structured for checklist and ordered cooking steps`() {
        val ingredients = NativeStructuredDetailSection(
            fieldId = "recipeIngredient",
            label = "Ingredients",
            value = listValue("3 bananas", "1 tsp cinnamon"),
            ordered = false,
        )
        val instructions = NativeStructuredDetailSection(
            fieldId = "recipeInstructions",
            label = "Instructions",
            value = listValue("Peel the bananas.", "Bake until golden."),
            ordered = true,
        )

        assertTrue(ingredients.isRecipeIngredientSection())
        assertFalse(ingredients.isRecipeInstructionSection())
        assertTrue(instructions.isRecipeInstructionSection())
        assertEquals(listOf("3 bananas", "1 tsp cinnamon"), ingredients.recipeTextItems())
        assertEquals(listOf("Peel the bananas.", "Bake until golden."), instructions.recipeTextItems())
    }

    @Test
    fun `simple markdown instruction headings become native headings`() {
        assertEquals(
            NativeRecipeInstructionPresentation(heading = "Kruimellaag", body = null),
            nativeRecipeInstructionPresentation("## Kruimellaag"),
        )
        assertEquals(
            NativeRecipeInstructionPresentation(
                heading = "Afwerking",
                body = "Bestrooi de taart met suiker.",
            ),
            nativeRecipeInstructionPresentation(
                """
                ### Afwerking
                Bestrooi de taart met suiker.
                """.trimIndent(),
            ),
        )
        assertEquals(
            NativeRecipeInstructionPresentation(heading = null, body = "#geen kop"),
            nativeRecipeInstructionPresentation("#geen kop"),
        )
    }

    private fun recipeResource(id: String, name: String) = ResourceSpec(
        id = id,
        name = name,
        confidence = Confidence.high,
        fields = listOf(
            FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
            FieldSpec("imageUrl", "Image", FieldKind.image, required = false, readOnly = true),
            FieldSpec("recipeYield", "Servings", FieldKind.string, required = false, readOnly = false),
            FieldSpec("totalTime", "Total time", FieldKind.string, required = false, readOnly = false),
        ),
    )

    private fun listValue(vararg values: String) = NativeStructuredValue.ListValue(
        items = values.map { value ->
            NativeStructuredValue.Scalar(value, NativeStructuredScalarKind.string)
        },
    )
}
