package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopShellInteractionTest {
    @Test
    fun clickingAnAppBadgeOpensTheSameExactAppAsTheRow() {
        val opened = mutableListOf<String>()
        nativeSceneTest(1280, 800, content = {
            NextcloudDesktopShell(
                selected = NextcloudDestination.Home,
                onSelected = {},
                onOpenApp = opened::add,
                identity = shellIdentity().copy(
                    shortcuts = listOf(NextcloudDesktopSidebarApp("files", "Files", badge = "12")),
                ),
            ) { Text("Home workspace") }
        }) {
            click("12")
            assertEquals(listOf("files"), opened, "The badge is part of the app row's primary hit target")
            clickShellButton("Files")
            assertEquals(listOf("files", "files"), opened)
        }
    }

    @Test
    fun collapseAndExpandPreserveWorkspaceDraftAndNeverNavigate() {
        var navigations = 0
        nativeSceneTest(1280, 800, content = {
            NextcloudDesktopShell(
                selected = NextcloudDestination.Apps,
                onSelected = { navigations++ },
                onOpenApp = { navigations++ },
                identity = shellIdentity(),
                activeAppId = "calendar",
            ) {
                var draft by remember { mutableStateOf(0) }
                Column {
                    Text("Draft revision $draft")
                    Button(onClick = { draft++ }) { Text("Change draft") }
                }
            }
        }) {
            click("Change draft")
            assertTrue(has("Draft revision 1"))
            click("Collapse sidebar")
            assertTrue(has("Expand sidebar"))
            assertTrue(has("Draft revision 1"))
            assertEquals(0, navigations)
            assertTrue(has("Calendar"))
            assertTrue(has("Files"))
            click("Expand sidebar")
            assertTrue(has("Collapse sidebar"))
            assertTrue(has("Draft revision 1"))
            assertEquals(0, navigations)
            capture("desktop-shell-expanded-draft")
        }
    }

    @Test
    fun narrowRailKeepsPinnedRecentAndCurrentAppsAndSelectsOnlyTheAliasMatch() {
        val opened = mutableListOf<String>()
        nativeSceneTest(760, 1100, content = {
            NextcloudDesktopShell(
                selected = NextcloudDestination.Apps,
                onSelected = {},
                onOpenApp = opened::add,
                identity = shellIdentity(),
                activeAppId = "spreed",
            ) { Text("Talk workspace") }
        }) {
            assertFalse(has("Expand sidebar"), "Narrow windows must not offer a sidebar that crowds the workspace")
            assertTrue(has("Files"))
            assertTrue(has("Calendar"))
            assertTrue(has("Talk"))
            val selectedLabels = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
                .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertEquals(listOf("Talk"), selectedLabels)
            val talk = assertNotNull(nodes().lastOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Talk") == true &&
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Button
            })
            assertTrue(talk.config.getOrNull(SemanticsActions.OnClick)?.action != null)
            click("Files")
            click("Calendar")
            click("Talk")
            assertEquals(listOf("files", "calendar"), opened, "The active app must not reopen or trigger a draft guard")
            capture("desktop-shell-narrow-active-app")
        }
    }

    @Test
    fun disabledNavigationBlocksAppAndAccountCallbacksButAllowsLayoutChanges() {
        val enabled = mutableStateOf(false)
        val destinations = mutableListOf<NextcloudDestination>()
        val apps = mutableListOf<String>()
        nativeSceneTest(1280, 900, content = {
            NextcloudDesktopShell(
                selected = NextcloudDestination.Home,
                onSelected = destinations::add,
                identity = shellIdentity(),
                onOpenApp = apps::add,
                navigationEnabled = enabled.value,
            ) { Text("Pending save") }
        }) {
            click("Files")
            click("Settings")
            click("Account settings for Example User")
            assertTrue(destinations.isEmpty())
            assertTrue(apps.isEmpty())
            click("Collapse sidebar")
            assertTrue(has("Expand sidebar"))
            assertTrue(has("Pending save"))
            enabled.value = true
            settle()
            click("Account settings for Example User")
            assertEquals(listOf(NextcloudDestination.Settings), destinations, "A pointer click on the avatar must open account settings")
            assertTooltipDoesNotCoverShellButton("Account settings for Example User", "Settings")
            clickShellButton("Settings")
            assertEquals(listOf(NextcloudDestination.Settings, NextcloudDestination.Settings), destinations)
        }
    }

    @Test
    fun accountAndSettingsRemainReachableInShortFontScaledWindows() {
        val destinations = mutableListOf<NextcloudDestination>()
        nativeSceneTest(760, 320, fontScale = 1.5f, content = {
            NextcloudDesktopShell(
                selected = NextcloudDestination.Home,
                onSelected = destinations::add,
                identity = shellIdentity(),
                activeAppId = "talk",
            ) { Text("Workspace") }
        }) {
            val scroller = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            })
            assertTrue(scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10_000f))
            settle()
            val account = assertNotNull(node("Account settings for Example User"))
            assertTrue(account.boundsInRoot.top >= 0f)
            assertTrue(account.boundsInRoot.bottom <= 320f)
            click("Account settings for Example User")
            assertEquals(listOf(NextcloudDestination.Settings), destinations, "The visible account avatar must receive the pointer click")
            assertTooltipDoesNotCoverShellButton("Account settings for Example User", "Settings")
            capture("desktop-shell-short-tooltip-placement")
            clickShellButton("Settings")
            assertEquals(listOf(NextcloudDestination.Settings, NextcloudDestination.Settings), destinations)
            capture("desktop-shell-short-font-scaled")
        }
    }

    private suspend fun NativeSceneTestDriver.clickShellButton(label: String) {
        val target = assertNotNull(nodes().lastOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null
        }, "No actionable shell button labeled '$label'")
        // A tooltip can repeat a button label. Target the button, then move the mouse before clicking.
        val position = target.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, position)
        settle()
        click(position)
    }

    private fun NativeSceneTestDriver.assertTooltipDoesNotCoverShellButton(tooltipLabel: String, buttonLabel: String) {
        val tooltip = assertNotNull(nodes().lastOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == tooltipLabel } == true
        }, "The account tooltip must be visible to verify its placement")
        val button = assertNotNull(nodes().lastOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(buttonLabel) == true &&
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button
        })
        assertFalse(tooltip.boundsInRoot.overlaps(button.boundsInRoot), "Account tooltip must not cover the Settings button")
    }

    private fun shellIdentity() = NextcloudDesktopIdentity(
        displayName = "Example User",
        cloudName = "Example Cloud",
        shortcuts = listOf(NextcloudDesktopSidebarApp("files", "Files")),
        recentApp = NextcloudDesktopSidebarApp("calendar", "Calendar"),
        availableApps = listOf(
            NextcloudDesktopSidebarApp("files", "Files"),
            NextcloudDesktopSidebarApp("calendar", "Calendar"),
            NextcloudDesktopSidebarApp("talk", "Talk"),
        ),
    )
}
