package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.NextcloudAdaptiveShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShellMotionInteractionTest {
    @Test
    fun desktopSelectionMovesToExactlyOneEntryAndKeepsTheWorkspaceInstance() {
        val selected = mutableStateOf(NextcloudDestination.Home)
        var instances = 0
        nativeSceneTest(1280, 800, content = {
            NextcloudDesktopShell(
                selected = selected.value,
                onSelected = { selected.value = it },
                identity = shellIdentity(),
            ) { CountedWorkspace(selected.value) { instances++ } }
        }) {
            assertEquals(listOf("Home"), selectedEntries())
            clickWithoutSettling("Activity")
            assertEquals(1, workspaceNodes().size, "The entrance must not compose a second workspace")
            settle()
            assertEquals(listOf("Activity"), selectedEntries())
            assertEquals("Workspace 1 showing Activity", workspaceNodes().single().text())
            click("Settings")
            assertEquals(listOf("Settings"), selectedEntries(), "The footer entry shares the same highlight")
            assertEquals(1, instances, "Switching destinations keeps one workspace composition")
        }
    }

    @Test
    fun desktopEntranceLiftsTheNewScreenAndReducedMotionPlacesItAtRest() {
        for (reduced in listOf(false, true)) {
            val selected = mutableStateOf(NextcloudDestination.Home)
            nativeSceneTest(1280, 800, content = {
                CompositionLocalProvider(LocalNextcloudReducedMotion provides reduced) {
                    NextcloudDesktopShell(
                        selected = selected.value,
                        onSelected = { selected.value = it },
                        identity = shellIdentity(),
                    ) { CountedWorkspace(selected.value) {} }
                }
            }) {
                val restTop = workspaceNodes().single().boundsInRoot.top
                clickWithoutSettling("Activity")
                val firstFrameTop = workspaceNodes().single().boundsInRoot.top
                if (reduced) {
                    assertEquals(restTop, firstFrameTop, "Reduced motion shows the new screen in place")
                } else {
                    assertTrue(firstFrameTop > restTop, "The new screen starts slightly lower and rises")
                }
                settleUntil { workspaceNodes().single().boundsInRoot.top == restTop }
                assertEquals(restTop, workspaceNodes().single().boundsInRoot.top)
            }
        }
    }

    @Test
    fun adaptiveBottomBarSwitchKeepsTheWorkspaceInstance() {
        val selected = mutableStateOf(NextcloudDestination.Home)
        var instances = 0
        nativeSceneTest(400, 800, content = {
            NextcloudAdaptiveShell(
                selected = selected.value,
                onSelected = { selected.value = it },
                identity = shellIdentity(),
            ) { CountedWorkspace(selected.value) { instances++ } }
        }) {
            val restTop = workspaceNodes().single().boundsInRoot.top
            clickWithoutSettling("Activity")
            assertEquals(1, workspaceNodes().size)
            assertTrue(workspaceNodes().single().boundsInRoot.top > restTop)
            settleUntil { workspaceNodes().single().boundsInRoot.top == restTop }
            assertEquals("Workspace 1 showing Activity", workspaceNodes().single().text())
            assertEquals(1, instances)
        }
    }

    @Composable
    private fun CountedWorkspace(destination: NextcloudDestination, onCreated: () -> Unit) {
        val instance = remember { onCreated(); 1 }
        Text("Workspace $instance showing ${destination.label()}")
    }

    private fun NextcloudDestination.label(): String = when (this) {
        NextcloudDestination.Activity -> "Activity"
        NextcloudDestination.Settings -> "Settings"
        else -> name
    }

    private fun NativeSceneTestDriver.workspaceNodes(): List<SemanticsNode> = nodes().filter {
        it.text()?.startsWith("Workspace ") == true
    }

    private fun NativeSceneTestDriver.selectedEntries(): List<String> = nodes()
        .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
        .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    /** Delivers a click and renders a single frame so the first transition frame is observable. */
    private fun NativeSceneTestDriver.clickWithoutSettling(label: String) {
        val target = assertNotNull(node(label), "No shell entry labeled '$label'")
        val position = target.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        scene.render(System.nanoTime()).close()
    }

    private fun SemanticsNode.text(): String? =
        config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun shellIdentity() = NextcloudDesktopIdentity(
        displayName = "Example User",
        cloudName = "Example Cloud",
        shortcuts = listOf(NextcloudDesktopSidebarApp("files", "Files")),
        availableApps = listOf(NextcloudDesktopSidebarApp("files", "Files")),
    )
}
