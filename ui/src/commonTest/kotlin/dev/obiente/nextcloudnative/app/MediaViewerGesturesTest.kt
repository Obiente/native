package dev.obiente.nextcloudnative.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaViewerGesturesTest {
    private fun swipe(
        drag: Offset,
        zoomedIn: Boolean = false,
        multiTouch: Boolean = false,
        rightToLeft: Boolean = false,
    ) = mediaSwipeNavigationOrNull(
        drag = drag,
        viewportWidth = 1_000f,
        minimumDistance = 56f,
        zoomedIn = zoomedIn,
        multiTouch = multiTouch,
        rightToLeft = rightToLeft,
    )

    @Test
    fun clearHorizontalDragsMoveInReadingDirection() {
        assertEquals(MediaSwipeNavigation.Next, swipe(Offset(-300f, 20f)))
        assertEquals(MediaSwipeNavigation.Previous, swipe(Offset(300f, -20f)))
        assertEquals(MediaSwipeNavigation.Previous, swipe(Offset(-300f, 0f), rightToLeft = true))
        assertEquals(MediaSwipeNavigation.Next, swipe(Offset(300f, 0f), rightToLeft = true))
    }

    @Test
    fun shortDiagonalZoomedAndPinchGesturesNeverNavigate() {
        assertNull(swipe(Offset(-120f, 0f)), "Below 18 percent of the viewport.")
        assertNull(swipe(Offset(-300f, 250f)), "Not clearly horizontal.")
        assertNull(swipe(Offset(-600f, 0f), zoomedIn = true), "Zoomed media pans.")
        assertNull(swipe(Offset(-600f, 0f), multiTouch = true), "Pinches zoom.")
        assertNull(swipe(Offset(Float.NaN, 0f)))
        assertNull(
            mediaSwipeNavigationOrNull(Offset(-600f, 0f), 0f, 56f, false, false, false),
            "No measured viewport yet.",
        )
    }

    @Test
    fun narrowViewportsStillNeedTheMinimumDistance() {
        val narrow = { x: Float ->
            mediaSwipeNavigationOrNull(Offset(x, 0f), 200f, 56f, false, false, false)
        }
        assertNull(narrow(-50f))
        assertEquals(MediaSwipeNavigation.Next, narrow(-60f))
    }

    @Test
    fun feedbackFollowsTheFingerWithinBounds() {
        assertEquals(-60f, mediaSwipeFeedbackOffset(-100f, 1_000f), absoluteTolerance = 0.001f)
        assertEquals(-400f, mediaSwipeFeedbackOffset(-5_000f, 1_000f))
        assertEquals(400f, mediaSwipeFeedbackOffset(5_000f, 1_000f))
        assertEquals(0f, mediaSwipeFeedbackOffset(Float.POSITIVE_INFINITY, 1_000f))
        assertEquals(0f, mediaSwipeFeedbackOffset(100f, 0f))
    }
}
