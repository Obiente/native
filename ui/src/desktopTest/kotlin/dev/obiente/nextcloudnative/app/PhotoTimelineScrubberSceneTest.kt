package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import kotlin.test.*

class PhotoTimelineScrubberSceneTest {
    private suspend fun NativeSceneTestDriver.touch(position: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, position, type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, position, type = PointerType.Touch)
        settle()
    }

    private val dates = PhotoTimelineDateIndex(
        (0..2).map { PhotoTimelineMonthSection(PhotoTimelineMonth(2026, 9 - it), it * 10, 10) }, 30,
    )

    @Test
    fun transparentLanePassesPhotoTapsWhileRailAndAccessibleThumbStillNavigate() {
        val active = mutableStateOf(0)
        var photoTaps = 0
        nativeSceneTest(390, 600, fontScale = 1.5f, content = {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { photoTaps++ }) { Text("Synthetic photo") }
                PhotoTimelineDateScrubber(dates, active.value, { active.value = it },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }) {
            val thumb = assertNotNull(node("Photo timeline date scrubber"))
            assertEquals(48f, thumb.boundsInRoot.width)
            assertEquals(48f, thumb.boundsInRoot.height)
            touch(Offset(365f, 300f))
            assertEquals(1, photoTaps)
            assertEquals(0, active.value)
            touch(Offset(389f, 599f))
            assertEquals(32, active.value)
            assertEquals(1, photoTaps)
            val control = assertNotNull(node("Photo timeline date scrubber"))
            assertTrue(control.config[SemanticsActions.SetProgress].action!!.invoke(1f))
            settle()
            assertEquals(11, active.value)
        }
    }

    @Test
    fun thumbKeepsItsGrabPointAsItsBoundsMoveDuringDrag() {
        val active = mutableStateOf(0)
        val jumps = mutableListOf<Int>()
        nativeSceneTest(390, 600, content = {
            Box(Modifier.fillMaxSize()) {
                PhotoTimelineDateScrubber(dates, active.value, { active.value = it; jumps += it },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }) {
            val start = assertNotNull(node("Photo timeline date scrubber")).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Touch)
            settle()
            assertEquals(0, active.value)
            listOf(180f, 360f, 598f).forEach { y ->
                scene.sendPointerEvent(PointerEventType.Move, Offset(start.x, y), type = PointerType.Touch)
                settle()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(start.x, 598f), type = PointerType.Touch)
            settle()
            assertEquals(32, active.value)
            assertTrue(jumps.size >= 3)
            assertTrue(jumps.zipWithNext().all { (first, second) -> second >= first })
        }
    }
}
