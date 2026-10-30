package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class MediaViewerZoomInteractionTest {
    @Test
    fun theViewerOpensOverTheTimelineWhichKeepsItsStateAndNeverTakesInput() {
        val viewerOpen = mutableStateOf(false)
        var timelineCompositions = 0
        var timelineTaps = 0
        var closes = 0
        var timelineFocused = false
        nativeSceneTest(400, 700, content = {
            AppRouteTransition(
                screen = Screen.Media,
                modifier = Modifier.fillMaxSize(),
                overlay = if (viewerOpen.value) {
                    {
                        MediaViewerZoom("/Photos/beach.jpg", onClosed = { closes++; viewerOpen.value = false }) { close ->
                            Box(Modifier.fillMaxSize()) {
                                TextButton(onClick = close, modifier = Modifier.padding(top = 600.dp)) { Text("Close viewer") }
                            }
                        }
                    }
                } else {
                    null
                },
            ) {
                remember { timelineCompositions++ }
                Column {
                    Image(
                        ImageBitmap(160, 90),
                        contentDescription = "Beach",
                        modifier = Modifier.size(100.dp).padding(8.dp)
                            .mediaZoomSource("/Photos/beach.jpg", ImageBitmap(160, 90), cropped = true)
                            .onFocusChanged { if (it.isFocused && viewerOpen.value) timelineFocused = true }
                            .clickable { timelineTaps++; viewerOpen.value = true },
                    )
                    Text("Timeline", Modifier.semantics { contentDescription = "Timeline" })
                }
            }
        }) {
            click("Beach")
            assertEquals(1, timelineTaps)
            settleUntil { has("Close viewer") }
            assertFalse(has("Timeline"), "The timeline below is hidden from accessibility services while the viewer is open")
            click(androidx.compose.ui.geometry.Offset(50f, 50f))
            assertEquals(1, timelineTaps, "A tap on the viewer never reaches the photo below it")
            // A keyboard must not walk out of the viewer into the hidden timeline either.
            repeat(4) {
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
                settle()
            }
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            settle()
            assertFalse(timelineFocused, "Tab never moves focus into the timeline below the viewer")
            assertEquals(1, timelineTaps, "Enter never activates a photo below the viewer")
            val close = requireNotNull(node("Close viewer")).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, close)
            scene.sendPointerEvent(PointerEventType.Release, close)
            scene.render(System.nanoTime()).close()
            assertEquals(0, closes, "Closing first zooms the photo back into its tile")
            settleUntil { closes == 1 }
            assertEquals(1, closes)
            settleUntil { has("Timeline") }
            assertTrue(has("Timeline"))
            assertEquals(1, timelineCompositions, "The timeline stayed composed below the viewer, so it keeps its place")
        }
    }
}
