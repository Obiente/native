package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopWorkspaceKind
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import kotlin.test.Test
import kotlin.test.assertTrue

class AppWorkspaceStateTest {
    @Test fun desktopAppSwitchingPreservesGlobalSidebarAndPerAppContent() {
        val app = mutableStateOf("files")
        nativeSceneTest(1440, 900, content = {
            val workspace = rememberAppWorkspaceContent(rememberSaveableStateHolder(), app.value, Screen.Files("/")) {
                SavedCounter("Files")
            }
            RootShell(
                presentation = NextcloudPresentation.Desktop,
                selected = NextcloudDestination.Apps,
                desktopWorkspaceKind = NextcloudDesktopWorkspaceKind.AppWorkspace,
                onSelected = {}, identity = null, activeAppId = app.value, content = workspace,
            )
        }) {
            click("Collapse sidebar")
            click("Change Files state")
            assertTrue(has("Files state: 1"))
            app.value = "photos"
            settle()
            assertTrue(has("Expand sidebar"), "The sidebar must remain collapsed after switching apps")
            assertTrue(has("Files state: 0"), "A different app must start with independent content state")
            app.value = "files"
            settle()
            assertTrue(has("Expand sidebar"))
            assertTrue(has("Files state: 1"))
        }
    }

    @Test fun adaptiveReparentingAndImmersiveNavigationRestoreTheScreenSubtree() {
        val showShell = mutableStateOf(true)
        val route = mutableStateOf<Screen>(Screen.Files("/"))
        nativeSceneTest(390, 800, content = {
            val screen = route.value
            val workspace = rememberAppWorkspaceContent(rememberSaveableStateHolder(), "files", screen) {
                SavedCounter(
                    if (screen is Screen.MediaViewer) "Viewer" else "Files",
                    if (screen is Screen.MediaViewer) screen.navigationKey else "workspace",
                )
            }
            if (showShell.value) {
                RootShell(
                    presentation = NextcloudPresentation.Adaptive,
                    selected = NextcloudDestination.Apps,
                    onSelected = {}, identity = null, activeAppId = "files", content = workspace,
                )
            } else workspace()
        }) {
            click("Change Files state")
            repeat(8) {
                showShell.value = !showShell.value
                settle()
                assertTrue(has("Files state: 1"), "Layout changes must retain the moving screen subtree")
            }
            route.value = Screen.MediaViewer("synthetic-media", 0, 0, Screen.Files("/"))
            showShell.value = false
            settle()
            assertTrue(has("Viewer state: 0"), "Expected isolated viewer state; rendered text: ${nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }}")
            click("Change Viewer state")
            route.value = Screen.Files("/")
            showShell.value = true
            settle()
            assertTrue(has("Files state: 1"), "Returning from the viewer must restore the Files screen state")
            route.value = Screen.MediaViewer("another-synthetic-media", 0, 0, Screen.Files("/"))
            showShell.value = false
            settle()
            assertTrue(has("Viewer state: 0"), "A different record must not restore the previous detail state")
        }
    }

    @Composable
    private fun SavedCounter(label: String, recordKey: String = "workspace") {
        var value by rememberSaveable(recordKey) { mutableStateOf(0) }
        Column {
            Text("$label state: $value")
            Button(onClick = { value += 1 }) { Text("Change $label state") }
        }
    }
}
