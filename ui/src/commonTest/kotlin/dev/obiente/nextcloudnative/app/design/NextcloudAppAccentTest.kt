package dev.obiente.nextcloudnative.app.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NextcloudAppAccentTest {
    @Test
    fun everyAccentMeetsWcagAaInLightAndDarkThemes() {
        val failures = buildList {
            for (dark in listOf(false, true)) {
                val scheme = if (dark) NextcloudDarkColorScheme else NextcloudLightColorScheme
                val accents = nextcloudAppAccents(dark)
                for (family in NextcloudAppAccentFamily.entries) {
                    val tones = accents.tones(family)
                    val theme = if (dark) "dark" else "light"
                    addAll(contrastFailures("$theme $family", tones, scheme))
                }
            }
        }
        assertTrue(failures.isEmpty(), "WCAG AA failures:\n" + failures.joinToString("\n"))
    }

    @Test
    fun brandAccentMatchesTheBaseScheme() {
        for (dark in listOf(false, true)) {
            val scheme = if (dark) NextcloudDarkColorScheme else NextcloudLightColorScheme
            val brand = nextcloudAppAccents(dark).tones(NextcloudAppAccentFamily.Brand)
            assertEquals(scheme.primary, brand.primary)
            assertEquals(scheme.onPrimary, brand.onPrimary)
            assertEquals(scheme.primaryContainer, brand.primaryContainer)
            assertEquals(scheme.onPrimaryContainer, brand.onPrimaryContainer)
        }
    }

    @Test
    fun everyAppFamilyHasItsOwnHue() {
        for (dark in listOf(false, true)) {
            val accents = nextcloudAppAccents(dark)
            val primaries = NextcloudAppAccentFamily.entries.map { accents.tones(it).primary }
            assertEquals(primaries.size, primaries.toSet().size, "Each family needs a distinct accent")
        }
    }

    @Test
    fun homeSettingsAndFilesKeepTheBrandWhileAppsTakeTheirFamily() {
        fun family(destination: NextcloudDestination, app: String? = null) =
            nextcloudAppAccentFamily(nextcloudShellDestinationKey(destination, app))
        assertEquals(NextcloudAppAccentFamily.Brand, family(NextcloudDestination.Home))
        assertEquals(NextcloudAppAccentFamily.Brand, family(NextcloudDestination.Settings))
        assertEquals(NextcloudAppAccentFamily.Brand, family(NextcloudDestination.Activity))
        assertEquals(NextcloudAppAccentFamily.FolderSync, family(NextcloudDestination.FolderSync))
        assertEquals(NextcloudAppAccentFamily.Apps, family(NextcloudDestination.Apps))
        assertEquals(NextcloudAppAccentFamily.Brand, family(NextcloudDestination.Apps, "files"))
        assertEquals(NextcloudAppAccentFamily.Photos, family(NextcloudDestination.Apps, "photos"))
        assertEquals(NextcloudAppAccentFamily.Talk, family(NextcloudDestination.Apps, "talk"))
        assertEquals(NextcloudAppAccentFamily.Talk, family(NextcloudDestination.Apps, "spreed"))
        assertEquals(NextcloudAppAccentFamily.Calendar, family(NextcloudDestination.Apps, "calendar"))
        assertEquals(NextcloudAppAccentFamily.Deck, family(NextcloudDestination.Apps, "Deck"))
        assertEquals(NextcloudAppAccentFamily.Brand, family(NextcloudDestination.Apps, "cookbook"))
        assertNotEquals(
            nextcloudAppAccents(false).tones(NextcloudAppAccentFamily.Talk).primary,
            nextcloudAppAccents(false).tones(NextcloudAppAccentFamily.Brand).primary,
        )
    }

    private fun contrastFailures(label: String, tones: NextcloudAccentTones, scheme: ColorScheme): List<String> {
        val failures = mutableListOf<String>()
        fun require(name: String, foreground: Color, background: Color, minimum: Double) {
            val ratio = contrast(foreground, background)
            if (ratio < minimum) failures += "$label $name: ${"%.2f".format(ratio)} < $minimum"
        }
        val backgrounds = mapOf(
            "background" to scheme.background,
            "surface" to scheme.surface,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
        )
        for ((name, background) in backgrounds) {
            require("primary text on $name", tones.primary, background, TextMinimum)
            val fill = tones.primary.copy(alpha = SelectionFillAlpha).compositeOver(background)
            require("selected icon on $name selection", tones.primary, fill, IconMinimum)
            require("selected label on $name selection", scheme.onSurface, fill, TextMinimum)
        }
        require("onPrimary on primary", tones.onPrimary, tones.primary, TextMinimum)
        require("onPrimaryContainer on primaryContainer", tones.onPrimaryContainer, tones.primaryContainer, TextMinimum)
        require("primary icon on primaryContainer", tones.primary, tones.primaryContainer, IconMinimum)
        return failures
    }

    /** WCAG 2.x contrast ratio from sRGB relative luminance. */
    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private companion object {
        const val TextMinimum = 4.5
        const val IconMinimum = 3.0
    }
}
