package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaControlsAccessibilityTest {
    @Test
    fun actualSizeAndFitRemainReachableOnSmallPhonesWithLargeText() {
        val image = ImageBitmap(1200, 800)
        val transform = mutableStateOf(MediaViewportTransform())
        nativeSceneTest(320, 640, fontScale = 1.5f, content = {
            MediaImageCanvas(image, "Synthetic image", transform.value,
                { transform.value = it }, Modifier.fillMaxSize())
        }) {
            for (label in listOf("Zoom out", "Zoom in", "Fit", "1:1")) {
                val bounds = assertNotNull(node(label)).boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= 320f, "$label must stay inside the phone")
                assertTrue(bounds.top >= 0f && bounds.bottom <= 640f)
            }
            click("1:1")
            assertEquals(3.75f, transform.value.zoom)
            click("Fit")
            assertEquals(MediaViewportTransform(), transform.value)
            capture("media-small-phone-large-text")
        }
    }

    @Test
    fun motionFailureOffersStillPhotoWithoutStartingExternalPlayback() {
        var stillRequests = 0
        var externalRequests = 0
        nativeSceneTest(320, 640, fontScale = 1.5f, content = {
            NativeVideoFailureOverlay(
                failure = NativeVideoPlaybackFailure.DecodeFailed(
                    NativeVideoFormatSummary(mimeType = "video/hevc", codec = "hvc1",
                        width = 1280, height = 720, frameRate = 30f)),
                motionOnly = true, showCompatibilityAction = true, showExternalAction = true,
                externalActionEnabled = true, externalOpening = false,
                onCompatibilityPlayback = {}, onOpenExternal = { externalRequests++ },
                onShowStillPhoto = { stillRequests++ },
            )
        }) {
            for (label in listOf("Show still photo", "Try compatibility mode", "Open motion externally")) {
                val bounds = assertNotNull(node(label)).boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= 320f)
            }
            click("Show still photo")
            assertEquals(1, stillRequests)
            assertEquals(0, externalRequests)
            capture("motion-recovery-large-text")
        }
    }
}
