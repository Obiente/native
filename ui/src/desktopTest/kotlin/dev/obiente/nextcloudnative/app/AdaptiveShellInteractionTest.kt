package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudAdaptiveShell
import dev.obiente.nextcloudnative.app.design.NextcloudAppSwitcherContent
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.nativeui.runtime.NativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.rememberNativeInlineEditorCloseRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveShellInteractionTest {
    @Test
    fun tabletWorkspaceWidthDoesNotDependOnWhetherTheAppOpenedFromHomeOrApps() {
        val origin = mutableStateOf(NextcloudDestination.Home)
        var workspaceBounds = Rect.Zero
        nativeSceneTest(1200, 900, content = {
            NextcloudAdaptiveShell(
                selected = origin.value, onSelected = {}, identity = identity(), activeAppId = "calendar",
            ) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { workspaceBounds = it.boundsInRoot() })
            }
        }) {
            val openedFromHome = workspaceBounds
            assertTrue(openedFromHome.width > 720f, "The active app must receive the wider app workspace layout")
            origin.value = NextcloudDestination.Apps
            settle(); settle()
            assertEquals(openedFromHome, workspaceBounds, "The return destination must not change the active app's layout")
        }
    }

    @Test
    fun shortFontScaledTabletRailScrollsToAnOperableSettingsControl() {
        val destinations = mutableListOf<NextcloudDestination>()
        nativeSceneTest(840, 320, fontScale = 1.5f, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Home, onSelected = { destinations += it },
                identity = identity(), activeAppId = "calendar",
            ) { Text("Calendar workspace") }
        }) {
            scrollToEnd()
            assertInsideViewport("Settings", 840, 320)
            click("Settings")
            assertEquals(listOf(NextcloudDestination.Settings), destinations)
            capture("shell-tablet-short-font-scaled-settings")
        }
    }

    @Test
    fun shortFontScaledSwitcherScrollsThroughAppsToOperableFooterActions() {
        val destinations = mutableListOf<NextcloudDestination>()
        val apps = mutableListOf<String>()
        nativeSceneTest(320, 320, fontScale = 1.5f, content = {
            NextcloudAppSwitcherContent(
                identity = identity().copy(
                    shortcuts = emptyList(),
                    availableApps = identity().availableApps + (1..8).map {
                        NextcloudDesktopSidebarApp("demo-$it", "Demo $it")
                    },
                ),
                activeAppId = "calendar", selectedDestination = NextcloudDestination.Apps,
                enabled = true, onOpenApp = { apps += it }, onSelected = { destinations += it }, onDismiss = {},
            )
        }) {
            assertTrue(has("Find an app"))
            scrollToEnd()
            for (label in listOf("Switch to Talk", "Open Folder sync", "Browse apps", "Account settings")) {
                assertInsideViewport(label, 320, 320)
            }
            click("Switch to Talk")
            assertEquals(listOf("spreed"), apps)
            click("Open Folder sync")
            click("Browse apps")
            assertEquals(listOf(NextcloudDestination.FolderSync, NextcloudDestination.Apps), destinations)
            capture("shell-switcher-short-font-scaled-footer")
        }
    }

    @Test
    fun changingAccountClosesTheSwitcherAndDoesNotRetainThePreviousSearch() {
        val account = mutableStateOf(identity().copy(
            availableApps = identity().availableApps + (1..8).map { NextcloudDesktopSidebarApp("demo-$it", "Demo $it") },
        ))
        nativeSceneTest(390, 1000, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Apps, onSelected = {}, identity = account.value, activeAppId = "calendar",
            ) { Text("Calendar workspace") }
        }) {
            click("Calendar. Switch app")
            settle(); settle()
            replaceText("", "Talk")
            account.value = account.value.copy(accountScopeKey = "different-synthetic-account")
            settle(); settle()
            assertFalse(has("Switch app"), "An open sheet must not carry over to another account")
            click("Calendar. Switch app")
            settle(); settle()
            replaceText("", "Calendar")
            assertTrue(has("Switch to Calendar"))
            assertFalse(has("Switch to Talk"))
        }
    }

    @Test
    fun resizingBetweenPhoneAndTabletRetainsRememberOnlyWorkspaceDraft() {
        val width = mutableStateOf(390)
        nativeSceneTest(900, 1000, content = {
            Box(Modifier.fillMaxSize()) {
                NextcloudAdaptiveShell(
                    selected = NextcloudDestination.Home, onSelected = {}, identity = identity(),
                    activeAppId = "calendar", modifier = Modifier.width(width.value.dp),
                ) {
                    var draft by remember { mutableStateOf("Original event") }
                    OutlinedTextField(draft, { draft = it }, label = { Text("Event title") })
                }
            }
        }) {
            replaceText("Original event", "Unsaved phone edit")
            width.value = 840
            settle(); settle()
            replaceText("Unsaved phone edit", "Unsaved tablet edit")
            capture("shell-resized-tablet-draft")
            width.value = 390
            settle(); settle()
            replaceText("Unsaved tablet edit", "Back on phone")
            capture("shell-resized-phone-draft")
        }
    }

    @Test
    fun appOpenedFromHomeOwnsTheSelectedPhoneSlot() {
        nativeSceneTest(390, 844, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Home, onSelected = {}, identity = identity(), activeAppId = "calendar",
            ) { Text("Calendar workspace") }
        }) {
            val selectedControls = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            assertEquals(1, selectedControls.size, "The launch origin must not leave Home selected alongside the active app")
            assertTrue(selectedControls.single().config.getOrNull(SemanticsProperties.ContentDescription)
                ?.contains("Calendar. Switch app") == true)
            assertTrue(has("Calendar workspace"))
            capture("shell-phone-active-calendar")
        }
    }

    @Test
    fun openingAndDismissingSwitcherRetainsTheWorkspaceDraftAndDoesNotNavigate() {
        var appOpens = 0
        var destinationOpens = 0
        nativeSceneTest(390, 844, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Home, onSelected = { destinationOpens++ }, identity = identity(),
                activeAppId = "calendar", onOpenApp = { appOpens++ },
            ) {
                var draft by remember { mutableStateOf("Original title") }
                OutlinedTextField(draft, { draft = it }, label = { Text("Event title") })
            }
        }) {
            replaceText("Original title", "Keep this draft")
            click("Calendar. Switch app")
            settle(); settle()
            assertTrue(has("Switch app"))
            assertTrue(has("Pinned"))
            assertTrue(has("Current"))
            assertTrue(has("Browse apps"))
            assertTrue(has("Open Folder sync"))
            assertEquals(0, appOpens)
            assertEquals(0, destinationOpens)
            capture("shell-phone-app-switcher")
            click("Close")
            settle(); settle()
            assertFalse(has("Switch app"))
            replaceText("Keep this draft", "Still editing")
            assertEquals(0, appOpens)
            assertEquals(0, destinationOpens)
        }
    }

    @Test
    fun switchingToTalkEmitsTheExactInstalledAppIdOnce() {
        val opened = mutableListOf<String>()
        val destinations = mutableListOf<NextcloudDestination>()
        nativeSceneTest(390, 844, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Apps, onSelected = { destinations += it }, identity = identity(),
                activeAppId = "calendar", onOpenApp = { opened += it },
            ) { Text("Calendar workspace") }
        }) {
            click("Calendar. Switch app")
            settle(); settle()
            click("Switch to Talk")
            assertEquals(listOf("spreed"), opened)
            assertTrue(destinations.isEmpty(), "App switching must use the app callback, not reset the host's destination")
            assertTrue(has("Calendar workspace"), "Only the host changes workspace content")
        }
    }

    @Test
    fun choosingTheCurrentAppOnlyDismissesTheSwitcher() {
        var opens = 0
        nativeSceneTest(390, 844, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Apps, onSelected = { opens++ }, identity = identity(),
                activeAppId = "talk", onOpenApp = { opens++ },
            ) { Text("Talk workspace") }
        }) {
            click("Talk. Switch app")
            settle(); settle()
            click("Switch to Talk")
            settle(); settle()
            assertFalse(has("Switch app"))
            assertEquals(0, opens, "Selecting the current alias must not reopen the app or reset its navigation")
        }
    }

    @Test
    fun disabledShellAndSwitcherControlsDoNotNavigate() {
        var opens = 0
        nativeSceneTest(390, 844, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Home, onSelected = { opens++ }, identity = identity(),
                activeAppId = "calendar", onOpenApp = { opens++ }, navigationEnabled = false,
            ) { Text("Checking account") }
        }) {
            click("Home")
            click("Calendar. Switch app")
            assertFalse(has("Switch app"))
            assertEquals(0, opens)
        }
        nativeSceneTest(390, 844, content = {
            NextcloudAppSwitcherContent(
                identity = identity(), activeAppId = "calendar", selectedDestination = NextcloudDestination.Apps,
                enabled = false, onOpenApp = { opens++ }, onSelected = { opens++ }, onDismiss = {},
            )
        }) {
            click("Switch to Talk")
            click("Open Folder sync")
            click("Browse apps")
            assertEquals(0, opens)
        }
    }

    @Test
    fun switcherUsesHostDraftGuardBeforeLeavingTheCurrentApp() {
        val navigation = NativeInlineEditorNavigation()
        val activeApp = mutableStateOf("calendar")
        var closedEditors = 0
        nativeSceneTest(390, 900, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Home, onSelected = {}, identity = identity(),
                activeAppId = activeApp.value, onOpenApp = { id -> navigation.navigate { activeApp.value = id } },
            ) {
                if (activeApp.value == "calendar") {
                    rememberNativeInlineEditorCloseRequest(
                        enabled = true, dirty = true, submissionBlocked = false,
                        onClose = { closedEditors++ }, navigation = navigation,
                    )
                    Text("Unsaved calendar draft")
                } else Text("Talk workspace")
            }
        }) {
            click("Calendar. Switch app")
            settle(); settle()
            click("Switch to Talk")
            settle(); settle()
            assertTrue(has("Discard unsaved changes?"))
            assertEquals("calendar", activeApp.value)
            assertEquals(0, closedEditors)
            click("Keep editing")
            assertTrue(has("Unsaved calendar draft"))
            click("Calendar. Switch app")
            settle(); settle()
            click("Switch to Talk")
            settle(); settle()
            click("Discard changes")
            assertEquals("spreed", activeApp.value)
            assertEquals(1, closedEditors)
            assertTrue(has("Talk workspace"))
        }
    }

    @Test
    fun phoneLargeTextAndTabletSwitcherKeepNavigationWithinTheViewport() {
        for ((width, scale) in listOf(320 to 1.5f, 840 to 1f)) {
            nativeSceneTest(width, 1000, fontScale = scale, content = {
                NextcloudAdaptiveShell(
                    selected = NextcloudDestination.Home, onSelected = {}, identity = identity(), activeAppId = "calendar",
                ) { Box(Modifier.fillMaxSize()) { Text("Calendar workspace") } }
            }) {
                assertInsideViewport("Calendar. Switch app", width, 1000)
                capture("shell-$width-font-$scale")
                click("Calendar. Switch app")
                settle(); settle()
                for (label in listOf("Close", "Switch to Talk", "Open Folder sync", "Browse apps", "Account settings")) {
                    assertInsideViewport(label, width, 1000)
                }
                capture("shell-switcher-$width-font-$scale")
            }
        }
    }

    @Test
    fun switcherSearchSurvivesCountRefreshAndRoutesBrowseAndSyncThroughDestinationCallback() {
        val identityState = mutableStateOf(identity().copy(
            availableApps = identity().availableApps + (1..8).map { NextcloudDesktopSidebarApp("demo-$it", "Demo $it") },
        ))
        val selected = mutableListOf<NextcloudDestination>()
        nativeSceneTest(390, 1000, content = {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Apps, onSelected = { selected += it }, identity = identityState.value,
                activeAppId = "calendar",
            ) { Text("Calendar workspace") }
        }) {
            click("Calendar. Switch app")
            settle(); settle()
            replaceText("", "Talk")
            identityState.value = identityState.value.copy(
                shortcuts = identityState.value.shortcuts.map { it.copy(badge = "2") },
            )
            settle()
            assertTrue(has("Switch to Talk"))
            replaceText("Talk", "No such app")
            assertTrue(has("No matching apps"))
            click("Browse apps")
            assertEquals(listOf(NextcloudDestination.Apps), selected)
            click("Calendar. Switch app")
            settle(); settle()
            click("Open Folder sync")
            assertEquals(listOf(NextcloudDestination.Apps, NextcloudDestination.FolderSync), selected)
        }
    }

    private fun NativeSceneTestDriver.assertInsideViewport(label: String, width: Int, height: Int) {
        val bounds = assertNotNull(node(label), "Missing accessible control: $label").boundsInRoot
        assertTrue(bounds.width > 0 && bounds.height > 0, "$label must have a visible target")
        assertTrue(bounds.left >= -1 && bounds.top >= -1 && bounds.right <= width + 1 && bounds.bottom <= height + 1,
            "$label is outside the $width x $height viewport: $bounds")
    }

    private suspend fun NativeSceneTestDriver.scrollToEnd() {
        val scroller = assertNotNull(nodes().firstOrNull {
            it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
        }, "The shell navigation must expose an accessible scroll action")
        assertTrue(scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10_000f))
        settle(); settle()
    }

    private fun identity() = NextcloudDesktopIdentity(
        displayName = "Demo user", cloudName = "Demo cloud", accountScopeKey = "synthetic-shell-account",
        shortcuts = listOf(NextcloudDesktopSidebarApp("spreed", "Talk")),
        availableApps = listOf(NextcloudDesktopSidebarApp("calendar", "Calendar"), NextcloudDesktopSidebarApp("spreed", "Talk")),
    )
}
