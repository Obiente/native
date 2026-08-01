package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NextcloudIconsTest {
    @Test
    fun `all signed Pantry version 0 23 0 category and store icon tokens resolve without fallback`() {
        val categoryTokens = listOf(
            "tag",
            "food",
            "fruit",
            "vegetable",
            "bakery",
            "dairy",
            "meat",
            "fish",
            "snacks",
            "cookie",
            "drinks",
            "coffee",
            "frozen",
            "household",
            "pets",
            "baby",
            "home",
            "leaf",
            "pizza",
            "clipboard-check",
            "clipboard-list",
            "format-list-checks",
            "cart",
            "basket",
            "star",
            "heart",
            "calendar",
            "bell",
            "flag",
            "bookmark",
            "pin",
            "map-marker",
            "briefcase",
            "wrench",
            "silverware",
            "gift",
            "book",
            "school",
            "palette",
            "camera",
            "music",
            "gamepad",
            "run",
            "dumbbell",
            "pill",
            "paw",
            "flower",
            "tree",
            "broom",
            "lightbulb",
            "package",
            "car",
            "bike",
            "beach",
        )
        val storeTokens = listOf(
            "store",
            "storefront",
            "market",
            "supermarket",
            "convenience",
            "cart",
            "basket",
            "shopping",
            "online",
            "warehouse",
            "pharmacy",
            "health",
            "bakery",
            "butcher",
            "seafood",
            "produce",
            "garden",
            "florist",
            "hardware",
            "tools",
            "wrench",
            "electronics",
            "phone",
            "clothing",
            "shoes",
            "furniture",
            "homegoods",
            "home",
            "books",
            "toys",
            "pets",
            "liquor",
            "coffee",
            "gas",
            "gift",
            "jewelry",
            "sports",
            "beauty",
            "office",
            "baby",
            "deli",
        )
        val declaredTokens = (categoryTokens + storeTokens).toSet()

        assertEquals(54, categoryTokens.size)
        assertEquals(41, storeTokens.size)
        assertEquals(86, declaredTokens.size)
        declaredTokens.forEach { token ->
            assertNotNull(NextcloudIcons.semantic(token), "Missing signed Pantry icon for $token")
        }
    }

    @Test
    fun `declared food icon tokens resolve to faithful distinct vectors`() {
        val declaredTokens = listOf(
            "food",
            "bakery",
            "dairy",
            "meat",
            "fish",
            "snacks",
            "cookie",
            "drinks",
            "silverware",
            "deli",
            "butcher",
            "seafood",
        )

        val resolved = declaredTokens.map { token ->
            token to assertNotNull(NextcloudIcons.semantic(token), "Missing icon for $token")
        }

        assertEquals(declaredTokens, resolved.map { (token, _) -> token })
        assertEquals(
            declaredTokens.size,
            resolved.map { (_, icon) -> icon.name }.distinct().size,
            "Distinct declared concepts must not collapse onto one generic food glyph.",
        )
    }

    @Test
    fun `semantic icon tokens normalize contract naming variants`() {
        assertEquals(
            NextcloudIcons.semantic("clipboard-check")?.name,
            NextcloudIcons.semantic(" Clipboard_check ")?.name,
        )
        assertEquals(
            NextcloudIcons.semantic("format-list-checks")?.name,
            NextcloudIcons.semantic("format list checks")?.name,
        )
    }

    @Test
    fun `unknown icon tokens share one neutral bundled fallback`() {
        assertNull(NextcloudIcons.semantic("server-specific-icon"))
        assertNull(NextcloudIcons.semantic("another-unknown-icon"))
        assertEquals(
            NextcloudIcons.Apps.name,
            NextcloudIcons.semanticOrFallback("server-specific-icon").name,
        )
        assertEquals(
            NextcloudIcons.semanticOrFallback("server-specific-icon").name,
            NextcloudIcons.semanticOrFallback("another-unknown-icon").name,
        )
    }
}
