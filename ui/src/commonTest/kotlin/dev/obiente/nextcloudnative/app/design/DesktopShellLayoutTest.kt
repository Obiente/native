package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopShellLayoutTest {
    @Test
    fun `phone presentation retains bottom navigation`() {
        val layout = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Adaptive,
            availableWidthDp = 412,
            destination = NextcloudDestination.Home,
        )

        assertEquals(NextcloudNavigationStyle.BottomBar, layout.navigationStyle)
        assertEquals(0, layout.navigationWidthDp)
        assertFalse(layout.supportsAuxiliaryPane)
    }

    @Test
    fun `large adaptive presentation retains existing rail and content limits`() {
        val home = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Adaptive,
            availableWidthDp = 840,
            destination = NextcloudDestination.Home,
        )
        val apps = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Adaptive,
            availableWidthDp = 840,
            destination = NextcloudDestination.Apps,
        )

        assertEquals(NextcloudNavigationStyle.CompactRail, home.navigationStyle)
        assertEquals(720, home.contentMaximumWidthDp)
        assertEquals(1_120, apps.contentMaximumWidthDp)
    }

    @Test
    fun `normal desktop uses persistent expanded sidebar and unrestricted workspace`() {
        val layout = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Desktop,
            availableWidthDp = 1_280,
            destination = NextcloudDestination.Home,
        )

        assertEquals(NextcloudNavigationStyle.ExpandedSidebar, layout.navigationStyle)
        assertEquals(252, layout.navigationWidthDp)
        assertNull(layout.contentMaximumWidthDp)
        assertTrue(layout.supportsAuxiliaryPane)
    }

    @Test
    fun `narrow desktop collapses its sidebar but never becomes a phone bottom bar`() {
        val layout = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Desktop,
            availableWidthDp = 760,
            destination = NextcloudDestination.Settings,
        )

        assertEquals(NextcloudNavigationStyle.CompactRail, layout.navigationStyle)
        assertEquals(76, layout.navigationWidthDp)
        assertNull(layout.contentMaximumWidthDp)
    }

    @Test
    fun `desktop app workspace reserves the expanded column for contextual navigation`() {
        val layout = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Desktop,
            availableWidthDp = 1_440,
            destination = NextcloudDestination.Apps,
            desktopWorkspaceKind = NextcloudDesktopWorkspaceKind.AppWorkspace,
        )

        assertEquals(NextcloudNavigationStyle.CompactRail, layout.navigationStyle)
        assertEquals(76, layout.navigationWidthDp)
        assertTrue(layout.supportsAuxiliaryPane)
    }

    @Test
    fun `desktop shell persists while app and detail screens are open`() {
        assertTrue(
            shouldUseNextcloudRootShell(
                presentation = NextcloudPresentation.Desktop,
                isRootScreen = false,
            ),
        )
    }

    @Test
    fun `adaptive shell remains limited to root destinations`() {
        assertTrue(
            shouldUseNextcloudRootShell(
                presentation = NextcloudPresentation.Adaptive,
                isRootScreen = true,
            ),
        )
        assertFalse(
            shouldUseNextcloudRootShell(
                presentation = NextcloudPresentation.Adaptive,
                isRootScreen = false,
            ),
        )
    }
}
