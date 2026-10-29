package dev.obiente.nextcloudnative.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import dev.obiente.nextcloudnative.app.design.NextcloudAdaptiveShell
import dev.obiente.nextcloudnative.app.design.NextcloudAppAccentFamily
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudLightColorScheme
import dev.obiente.nextcloudnative.app.design.nextcloudAppAccents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ShellAccentInteractionTest {
    private val light = nextcloudAppAccents(darkTheme = false)

    @Test
    fun desktopWorkspaceTakesTheOpenAppAccentAndHomeReturnsToBrand() {
        val selected = mutableStateOf(NextcloudDestination.Apps)
        val activeApp = mutableStateOf<String?>("calendar")
        var scheme: ColorScheme? = null
        nativeSceneTest(1280, 800, content = {
            NextcloudDesktopShell(
                selected = selected.value,
                onSelected = { selected.value = it; activeApp.value = null },
                identity = shellIdentity(),
                activeAppId = activeApp.value,
            ) {
                val current = MaterialTheme.colorScheme
                SideEffect { scheme = current }
                Text("Workspace")
            }
        }) {
            val calendar = light.tones(NextcloudAppAccentFamily.Calendar)
            val inApp = assertNotNull(scheme)
            assertEquals(calendar.primary, inApp.primary)
            assertEquals(calendar.primaryContainer, inApp.primaryContainer)
            assertEquals(calendar.onPrimaryContainer, inApp.onPrimaryContainer)
            assertEquals(NextcloudLightColorScheme.error, inApp.error, "Status colors never take an app accent")
            assertEquals(NextcloudLightColorScheme.secondary, inApp.secondary)
            click("Home")
            assertEquals(NextcloudLightColorScheme.primary, scheme?.primary, "Home keeps the brand accent")
            click("Folder sync")
            assertEquals(light.tones(NextcloudAppAccentFamily.FolderSync).primary, scheme?.primary)
        }
    }

    @Test
    fun phoneShellAppliesTheSameAccentToItsWorkspace() {
        var scheme: ColorScheme? = null
        nativeSceneTest(400, 800, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Apps,
                onSelected = {},
                identity = shellIdentity(),
                activeAppId = "talk",
            ) {
                val current = MaterialTheme.colorScheme
                SideEffect { scheme = current }
                Text("Workspace")
            }
        }) {
            assertEquals(light.tones(NextcloudAppAccentFamily.Talk).primary, scheme?.primary)
        }
    }

    private fun shellIdentity() = NextcloudDesktopIdentity(
        displayName = "Example User",
        cloudName = "Example Cloud",
        shortcuts = listOf(
            NextcloudDesktopSidebarApp("calendar", "Calendar"),
            NextcloudDesktopSidebarApp("talk", "Talk"),
        ),
    )
}
