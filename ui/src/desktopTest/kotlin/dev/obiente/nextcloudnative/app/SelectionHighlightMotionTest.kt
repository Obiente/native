package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.rememberNextcloudSelectionHighlight
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionHighlightMotionTest {
    @Test
    fun theSelectionPillGlidesThroughTheEntriesBetweenItsEnds() {
        val selected = mutableStateOf("timeline")
        nativeSceneTest(200, 200, content = { Entries(selected.value, reduced = false) }) {
            assertTrue(pillAt(entry = 0))
            assertFalse(pillAt(entry = 1))
            selected.value = "albums"
            var passedThrough = false
            repeat(40) {
                if (pillAt(entry = 1)) passedThrough = true
                delay(16)
            }
            assertTrue(passedThrough, "The pill travels across Folders on its way to Albums")
            settle()
            assertTrue(pillAt(entry = 2))
            assertFalse(pillAt(entry = 0))
            assertFalse(pillAt(entry = 1), "Only the selected entry keeps the highlight")
        }
    }

    @Test
    fun reducedMotionPlacesThePillWithoutTravel() {
        val selected = mutableStateOf("timeline")
        nativeSceneTest(200, 200, content = { Entries(selected.value, reduced = true) }) {
            selected.value = "albums"
            repeat(20) {
                assertFalse(pillAt(entry = 1), "Reduced motion never shows the pill between entries")
                delay(16)
            }
            assertTrue(pillAt(entry = 2))
        }
    }

    @Composable
    private fun Entries(selected: String, reduced: Boolean) {
        CompositionLocalProvider(LocalNextcloudReducedMotion provides reduced) {
            val highlight = rememberNextcloudSelectionHighlight(selected)
            Column(
                Modifier.width(200.dp).then(highlight.containerModifier())
                    .then(highlight.drawModifier(PillColor, PillColor, 0.dp)),
            ) {
                listOf("timeline", "folders", "albums").forEach { key ->
                    Box(Modifier.width(200.dp).height(EntryHeight.dp).then(highlight.entryModifier(key)))
                }
            }
        }
    }

    private fun NativeSceneTestDriver.pillAt(entry: Int): Boolean = scene.render(System.nanoTime()).use { image ->
        val bitmap = Bitmap.makeFromImage(image)
        val color = bitmap.getColor(100, entry * EntryHeight + EntryHeight / 2)
        bitmap.close()
        // Red with little green and blue is the pill; the theme background is near white.
        org.jetbrains.skia.Color.getR(color) > 200 && org.jetbrains.skia.Color.getG(color) < 80
    }

    private companion object {
        const val EntryHeight = 40
        val PillColor = Color.Red
    }
}
