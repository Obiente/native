package dev.obiente.nextcloudnative.app.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.PinnableContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextcloudBoardDragAutoScrollTest {
    private val boardViewport = Rect(left = 0f, top = 0f, right = 320f, bottom = 640f)
    private val laneViewports = linkedMapOf(
        "visible" to Rect(left = 16f, top = 80f, right = 300f, bottom = 620f),
        "offscreen" to Rect(left = 340f, top = 80f, right = 624f, bottom = 620f),
    )

    @Test
    fun `edge scroll velocity is zero away from viewport edges`() {
        assertEquals(
            0f,
            resolveBoardDragEdgeScrollVelocity(
                pointer = 160f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 64f,
                maxVelocity = 800f,
            ),
        )
    }

    @Test
    fun `edge scroll velocity scales toward both viewport edges`() {
        assertEquals(
            -400f,
            resolveBoardDragEdgeScrollVelocity(
                pointer = 32f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 64f,
                maxVelocity = 800f,
            ),
        )
        assertEquals(
            600f,
            resolveBoardDragEdgeScrollVelocity(
                pointer = 304f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 64f,
                maxVelocity = 800f,
            ),
        )
    }

    @Test
    fun `edge scroll velocity is capped outside the viewport`() {
        assertEquals(
            -800f,
            resolveBoardDragEdgeScrollVelocity(
                pointer = -20f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 64f,
                maxVelocity = 800f,
            ),
        )
        assertEquals(
            800f,
            resolveBoardDragEdgeScrollVelocity(
                pointer = 340f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 64f,
                maxVelocity = 800f,
            ),
        )
    }

    @Test
    fun `edge scroll velocity rejects invalid inputs`() {
        assertEquals(
            0f,
            resolveBoardDragEdgeScrollVelocity(
                pointer = Float.NaN,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 64f,
                maxVelocity = 800f,
            ),
        )
        assertEquals(
            0f,
            resolveBoardDragEdgeScrollVelocity(
                pointer = 10f,
                viewportStart = 20f,
                viewportEnd = 20f,
                edgeThreshold = 64f,
                maxVelocity = 800f,
            ),
        )
    }

    @Test
    fun `horizontal edge scroll requires outward displacement from the drag origin`() {
        assertEquals(
            0f,
            resolveBoardDragHorizontalEdgeScrollVelocity(
                pointer = 32f,
                dragOrigin = 32f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 56f,
                intentThreshold = 12f,
                maxVelocity = 720f,
            ),
        )
        assertEquals(
            0f,
            resolveBoardDragHorizontalEdgeScrollVelocity(
                pointer = 44f,
                dragOrigin = 32f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 56f,
                intentThreshold = 12f,
                maxVelocity = 720f,
            ),
        )
        assertTrue(
            resolveBoardDragHorizontalEdgeScrollVelocity(
                pointer = 20f,
                dragOrigin = 32f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 56f,
                intentThreshold = 12f,
                maxVelocity = 720f,
            ) < 0f,
        )
        assertTrue(
            resolveBoardDragHorizontalEdgeScrollVelocity(
                pointer = 300f,
                dragOrigin = 280f,
                viewportStart = 0f,
                viewportEnd = 320f,
                edgeThreshold = 56f,
                intentThreshold = 12f,
                maxVelocity = 720f,
            ) > 0f,
        )
    }

    @Test
    fun `vertical lane selection uses visible lane geometry`() {
        assertEquals(
            "visible",
            resolveBoardDragVerticalLane(
                position = Offset(280f, 600f),
                boardViewport = boardViewport,
                laneViewports = laneViewports,
                verticalActivationHalo = 16f,
            ),
        )
        assertNull(
            resolveBoardDragVerticalLane(
                position = Offset(360f, 600f),
                boardViewport = boardViewport,
                laneViewports = laneViewports,
                verticalActivationHalo = 16f,
            ),
        )
    }

    @Test
    fun `vertical lane activation has only a bounded off-viewport halo`() {
        val fullHeightLane = mapOf(
            "visible" to Rect(left = 16f, top = 0f, right = 300f, bottom = 640f),
        )

        assertEquals(
            "visible",
            resolveBoardDragVerticalLane(
                position = Offset(160f, -12f),
                boardViewport = boardViewport,
                laneViewports = fullHeightLane,
                verticalActivationHalo = 16f,
            ),
        )
        assertNull(
            resolveBoardDragVerticalLane(
                position = Offset(160f, -80f),
                boardViewport = boardViewport,
                laneViewports = fullHeightLane,
                verticalActivationHalo = 16f,
            ),
        )
    }

    @Test
    fun `drop lane resolves through board padding and inter-lane gaps`() {
        val lanes = linkedMapOf(
            "first" to Rect(left = 16f, top = 80f, right = 140f, bottom = 620f),
            "second" to Rect(left = 156f, top = 80f, right = 300f, bottom = 620f),
        )

        assertEquals(
            "first",
            resolveBoardDragLaneDropTarget(
                position = Offset(4f, 300f),
                boardViewport = boardViewport,
                laneViewports = lanes,
                allowedLaneKeys = lanes.keys,
            ),
        )
        assertEquals(
            "second",
            resolveBoardDragLaneDropTarget(
                position = Offset(151f, 300f),
                boardViewport = boardViewport,
                laneViewports = lanes,
                allowedLaneKeys = lanes.keys,
            ),
        )
        assertEquals(
            "second",
            resolveBoardDragLaneDropTarget(
                position = Offset(316f, 300f),
                boardViewport = boardViewport,
                laneViewports = lanes,
                allowedLaneKeys = lanes.keys,
            ),
        )
    }

    @Test
    fun `drop lane does not redirect a direct hit on a disallowed lane`() {
        assertNull(
            resolveBoardDragLaneDropTarget(
                position = Offset(160f, 300f),
                boardViewport = boardViewport,
                laneViewports = linkedMapOf(
                    "source" to Rect(left = 16f, top = 80f, right = 200f, bottom = 620f),
                    "target" to Rect(left = 216f, top = 80f, right = 300f, bottom = 620f),
                ),
                allowedLaneKeys = setOf("target"),
            ),
        )
    }

    @Test
    fun `drop target refresh requires consumed scroll distance`() {
        assertFalse(shouldRefreshBoardDragTarget(horizontalConsumed = 0f, verticalConsumed = 0f))
        assertFalse(shouldRefreshBoardDragTarget(horizontalConsumed = Float.NaN, verticalConsumed = 0f))
        assertTrue(shouldRefreshBoardDragTarget(horizontalConsumed = 8f, verticalConsumed = 0f))
        assertTrue(shouldRefreshBoardDragTarget(horizontalConsumed = 0f, verticalConsumed = -6f))
    }

    @Test
    fun `consumed scroll schedules exactly one refresh on the next frame`() {
        var state = BoardDragTargetRefreshState()

        val initialFrame = state.beginFrame()
        assertFalse(initialFrame.shouldRefresh)
        state = initialFrame.nextState.afterScroll(horizontalConsumed = 8f, verticalConsumed = 0f)
        assertTrue(state.pending)

        val postScrollFrame = state.beginFrame()
        assertTrue(postScrollFrame.shouldRefresh)
        state = postScrollFrame.nextState.afterScroll(horizontalConsumed = 0f, verticalConsumed = 0f)

        val settledFrame = state.beginFrame()
        assertFalse(settledFrame.shouldRefresh)
        assertFalse(settledFrame.nextState.pending)
    }

    @Test
    fun `last consumed scroll still refreshes after reaching an edge`() {
        var state = BoardDragTargetRefreshState()
        state = state.afterScroll(horizontalConsumed = 0f, verticalConsumed = 4f)

        val finalRefreshFrame = state.beginFrame()
        assertTrue(finalRefreshFrame.shouldRefresh)
        state = finalRefreshFrame.nextState.afterScroll(
            horizontalConsumed = 0f,
            verticalConsumed = 0f,
        )

        assertFalse(state.beginFrame().shouldRefresh)
    }

    @Test
    fun `release while refresh is pending refreshes before terminal drop`() {
        val pendingState = BoardDragTargetRefreshState()
            .afterScroll(horizontalConsumed = 8f, verticalConsumed = 0f)

        val releaseFrame = pendingState.beginFrame(terminalDropRequested = true)

        assertTrue(releaseFrame.shouldRefresh)
        assertTrue(releaseFrame.terminalDropReady)
        assertFalse(releaseFrame.nextState.pending)
    }

    @Test
    fun `release without pending scroll still refreshes current target before terminal drop`() {
        val releaseFrame = BoardDragTargetRefreshState()
            .beginFrame(terminalDropRequested = true)

        assertTrue(releaseFrame.shouldRefresh)
        assertTrue(releaseFrame.terminalDropReady)
        assertFalse(releaseFrame.nextState.pending)
    }

    @Test
    fun `pending refresh without release does not signal terminal drop`() {
        val pendingState = BoardDragTargetRefreshState()
            .afterScroll(horizontalConsumed = 0f, verticalConsumed = 4f)

        val refreshFrame = pendingState.beginFrame()

        assertTrue(refreshFrame.shouldRefresh)
        assertFalse(refreshFrame.terminalDropReady)
    }

    @Test
    fun `drag pin owner releases a pin exactly once`() {
        val container = CountingPinnableContainer()
        val owner = BoardDragPinOwner()

        owner.acquire(container)
        owner.release()
        owner.release()

        assertEquals(1, container.pinCount)
        assertEquals(1, container.releaseCount)
    }

    @Test
    fun `drag pin owner releases the old pin when ownership is replaced or cleared`() {
        val container = CountingPinnableContainer()
        val owner = BoardDragPinOwner()

        owner.acquire(container)
        owner.acquire(container)
        owner.acquire(null)
        owner.release()

        assertEquals(2, container.pinCount)
        assertEquals(2, container.releaseCount)
    }

    @Test
    fun `drag pin owner preserves both nested card and enclosing lane`() {
        val cardContainer = CountingPinnableContainer()
        val laneContainer = CountingPinnableContainer()
        val owner = BoardDragPinOwner()

        owner.acquire(cardContainer, laneContainer)

        assertEquals(1, cardContainer.pinCount)
        assertEquals(1, laneContainer.pinCount)
        owner.release()
        assertEquals(1, cardContainer.releaseCount)
        assertEquals(1, laneContainer.releaseCount)
    }

    private class CountingPinnableContainer : PinnableContainer {
        var pinCount = 0
            private set
        var releaseCount = 0
            private set

        override fun pin(): PinnableContainer.PinnedHandle {
            pinCount += 1
            return object : PinnableContainer.PinnedHandle {
                private var released = false

                override fun release() {
                    check(!released) { "A pinned handle was released more than once." }
                    released = true
                    releaseCount += 1
                }
            }
        }
    }
}
