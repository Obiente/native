package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeRecipeScalingTest {
    @Test
    fun servingCountsAcceptCommonExactYieldsAndRejectRanges() {
        assertEquals(4.0, parseRecipeServingCount("4 servings"))
        assertEquals(6.0, parseRecipeServingCount("Serves 6"))
        assertEquals(2.5, parseRecipeServingCount("yield 2 1/2"))
        assertEquals(4.0, parseRecipeServingCount("Yield: 4"))
        assertNull(parseRecipeServingCount("4-6 servings"))
        assertNull(parseRecipeServingCount("to taste"))
        assertNull(parseRecipeServingCount("0 servings"))
    }

    @Test
    fun ingredientScalingHandlesWholeDecimalAsciiAndUnicodeFractions() {
        assertEquals("3 cups flour", scaleRecipeIngredient("1 1/2 cups flour", 2.0))
        assertEquals("1 1/2 tsp cinnamon", scaleRecipeIngredient("½ tsp cinnamon", 3.0))
        assertEquals("3 cups flour", scaleRecipeIngredient("1 ½ cups flour", 2.0))
        assertEquals("1/2 kg apples", scaleRecipeIngredient("0.25 kg apples", 2.0))
        assertEquals("3 eggs", scaleRecipeIngredient("2 eggs", 1.5))
        assertEquals("1 1/2 cans tomatoes", scaleRecipeIngredient("1 can tomatoes", 1.5))
    }

    @Test
    fun freeTextRangesAndCompactPackageNotationRemainVerbatim() {
        val ingredients = listOf(
            "Salt to taste",
            "1-2 cloves garlic",
            "2x400 g tomatoes",
            "A generous handful of herbs",
        )

        assertEquals(ingredients, ingredients.map { scaleRecipeIngredient(it, 1.5) })
    }

    @Test
    fun changingServingDisplayNeverMutatesStoredIngredientValues() {
        val stored = listOf("2 eggs", "1 1/2 cups flour", "Salt to taste")
        val snapshot = stored.toList()

        val adjusted = stored.map { ingredient -> scaleRecipeIngredient(ingredient, 6.0 / 4.0) }

        assertEquals(listOf("3 eggs", "2 1/4 cups flour", "Salt to taste"), adjusted)
        assertEquals(snapshot, stored)
    }

    @Test
    fun formattedQuantitiesPreferReadableReducedFractions() {
        assertEquals("2 1/4", formatRecipeQuantity(2.25))
        assertEquals("2/3", formatRecipeQuantity(2.0 / 3.0))
        assertEquals("3", formatRecipeQuantity(3.0))
    }
}
