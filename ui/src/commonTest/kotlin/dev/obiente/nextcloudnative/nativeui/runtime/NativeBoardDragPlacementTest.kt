package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.obiente.nextcloudnative.app.design.resolveBoardDragVerticalLane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeBoardDragPlacementTest {
    private val lanes = linkedMapOf(
        "planned" to Rect(left = 0f, top = 0f, right = 100f, bottom = 400f),
        "active" to Rect(left = 120f, top = 0f, right = 220f, bottom = 400f),
        "done" to Rect(left = 240f, top = 0f, right = 340f, bottom = 400f),
    )

    @Test
    fun resolvesOnlyAnAllowedLaneUnderThePointer() {
        assertEquals(
            "active",
            resolveNativeBoardLaneDropTarget(
                position = Offset(180f, 160f),
                laneBounds = lanes,
                allowedLaneKeys = setOf("active", "done"),
            ),
        )
    }

    @Test
    fun rejectsTheSourceLaneWhenItIsNotAContractTarget() {
        assertNull(
            resolveNativeBoardLaneDropTarget(
                position = Offset(40f, 160f),
                laneBounds = lanes,
                allowedLaneKeys = setOf("active", "done"),
            ),
        )
    }

    @Test
    fun rejectsSpaceOutsideEveryLane() {
        assertNull(
            resolveNativeBoardLaneDropTarget(
                position = Offset(400f, 160f),
                laneBounds = lanes,
                allowedLaneKeys = setOf("active", "done"),
            ),
        )
    }

    @Test
    fun reusableBoardAutoScrollSelectsOnlyTheVisibleLaneAtThePointer() {
        val boardViewport = Rect(left = 0f, top = 0f, right = 220f, bottom = 400f)

        assertEquals(
            "active",
            resolveBoardDragVerticalLane(
                position = Offset(180f, 380f),
                boardViewport = boardViewport,
                laneViewports = lanes,
                verticalActivationHalo = 16f,
            ),
        )
        assertNull(
            resolveBoardDragVerticalLane(
                position = Offset(280f, 380f),
                boardViewport = boardViewport,
                laneViewports = lanes,
                verticalActivationHalo = 16f,
            ),
        )
    }

    @Test
    fun preservesTheInitialLaneOrderWhenRecordsMoveBetweenLanes() {
        assertEquals(
            listOf("planned", "active", "done"),
            stableNativeBoardLaneOrder(
                initialLaneKeys = listOf("planned", "active", "done"),
                currentLaneKeys = listOf("done", "planned", "active"),
            ),
        )
    }

    @Test
    fun appendsNewlyDiscoveredLanesAfterTheInitialOrder() {
        assertEquals(
            listOf("planned", "done", "blocked"),
            stableNativeBoardLaneOrder(
                initialLaneKeys = listOf("planned", "done"),
                currentLaneKeys = listOf("blocked", "done", "planned"),
            ),
        )
    }
}
