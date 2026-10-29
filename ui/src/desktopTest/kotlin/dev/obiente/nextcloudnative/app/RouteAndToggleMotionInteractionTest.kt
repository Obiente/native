package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.NextcloudAnimatedIcon
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteAndToggleMotionInteractionTest {
    @Test
    fun deeperPagesArriveFromTheEndAndBackStepsFromTheStart() {
        for (reduced in listOf(false, true)) {
            val route = mutableStateOf("Documents")
            nativeSceneTest(400, 300, content = {
                CompositionLocalProvider(LocalNextcloudReducedMotion provides reduced) {
                    NextcloudContentEntrance(route.value, NextcloudEntranceAxis.Horizontal, directional = true, distance = 36.dp) {
                        Text("Page ${route.value}")
                    }
                }
            }) {
                val rest = left("Page Documents")
                route.value = "Work"
                frames()
                val forward = left("Page Work")
                settleUntil { left("Page Work") == rest }
                route.value = "Documents"
                frames()
                val back = left("Page Documents")
                settleUntil { left("Page Documents") == rest }
                if (reduced) {
                    assertEquals(rest, forward, "Reduced motion places the deeper page at rest")
                    assertEquals(rest, back)
                } else {
                    assertTrue(forward > rest, "A deeper page arrives from the end")
                    assertTrue(back < rest, "Going back brings the parent in from the start")
                }
            }
        }
    }

    @Test
    fun filterChipKeepsItsSelectionSemanticsWhileItEasesBetweenStates() {
        val selected = mutableStateOf(false)
        nativeSceneTest(400, 200, content = {
            NextcloudFilterChip(selected = selected.value, onClick = { selected.value = !selected.value }, label = { Text("Shared") })
        }) {
            assertEquals(false, chipSelected())
            click("Shared")
            assertEquals(true, selected.value)
            assertEquals(true, chipSelected())
            click("Shared")
            assertEquals(false, chipSelected())
        }
    }

    @Test
    fun toggledIconAnnouncesOnlyItsNewState() {
        val favorite = mutableStateOf(false)
        nativeSceneTest(300, 200, content = {
            NextcloudAnimatedIcon(
                if (favorite.value) NextcloudIcons.Favorite else NextcloudIcons.FavoriteBorder,
                contentDescription = if (favorite.value) "Remove favorite" else "Add favorite",
            )
        }) {
            assertTrue(has("Add favorite"))
            favorite.value = true
            frames()
            assertTrue(has("Remove favorite"), "The arriving icon is announced at once")
            assertTrue(!has("Add favorite"), "The leaving icon is never announced with the old state")
            settle()
            assertTrue(has("Remove favorite"))
        }
    }

    private fun NativeSceneTestDriver.frames() {
        scene.render(System.nanoTime()).close()
        scene.render(System.nanoTime() + 16_000_000L).close()
    }

    private fun NativeSceneTestDriver.left(label: String): Float = assertNotNull(node(label), label).boundsInRoot.left

    private fun NativeSceneTestDriver.chipSelected(): Boolean? = nodes()
        .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Shared" } == true }
        ?.config?.getOrNull(SemanticsProperties.Selected)
}
