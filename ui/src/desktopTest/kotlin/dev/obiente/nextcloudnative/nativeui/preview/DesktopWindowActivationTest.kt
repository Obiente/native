package dev.obiente.nextcloudnative.nativeui.preview

import dev.obiente.nextcloudnative.app.NextcloudNativeNavigationRequest
import dev.obiente.nextcloudnative.app.NextcloudNativeRoute
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowActivationTest {
    @Test
    fun `restore leaves a normal window unchanged`() {
        assertEquals(Frame.NORMAL, restoredDesktopFrameState(Frame.NORMAL))
    }

    @Test
    fun `restore clears only the iconified bit`() {
        assertEquals(Frame.NORMAL, restoredDesktopFrameState(Frame.ICONIFIED))
    }

    @Test
    fun `restore preserves maximized state`() {
        val minimizedMaximized = Frame.ICONIFIED or Frame.MAXIMIZED_BOTH

        assertEquals(Frame.MAXIMIZED_BOTH, restoredDesktopFrameState(minimizedMaximized))
    }

    @Test
    fun `handled request is cleared from the desktop owner`() {
        val request = NextcloudNativeNavigationRequest(4L, NextcloudNativeRoute.Settings)

        assertEquals(true, shouldClearDesktopNavigationRequest(request, 4L))
    }

    @Test
    fun `late acknowledgement does not clear a newer request`() {
        val request = NextcloudNativeNavigationRequest(5L, NextcloudNativeRoute.SyncCenter)

        assertEquals(false, shouldClearDesktopNavigationRequest(request, 4L))
    }

    @Test
    fun `each activation advances the focus request`() {
        assertEquals(8L, nextDesktopFocusRequestSequence(7L))
        assertEquals(0L, nextDesktopFocusRequestSequence(Long.MAX_VALUE))
    }
}
