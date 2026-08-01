package dev.obiente.nextcloudnative.app

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNativeWindowFrameTest {
    @Test
    fun `Windows native frame follows the active app theme`() {
        val api = RecordingWindowsDesktopWindowApi()
        val handle = HWND(Pointer.createConstant(42L))

        assertEquals(0, applyWindowsNativeWindowTheme(api, handle, darkTheme = true))
        assertEquals(DWMWA_USE_IMMERSIVE_DARK_MODE, api.attribute)
        assertEquals(1, api.value)
        assertEquals(Int.SIZE_BYTES, api.valueSize)
        assertEquals(handle, api.window)

        applyWindowsNativeWindowTheme(api, handle, darkTheme = false)
        assertEquals(0, api.value)
    }

    private class RecordingWindowsDesktopWindowApi : WindowsDesktopWindowApi {
        var window: HWND? = null
        var attribute: Int? = null
        var value: Int? = null
        var valueSize: Int? = null

        override fun DwmSetWindowAttribute(
            window: HWND,
            attribute: Int,
            value: Pointer,
            valueSize: Int,
        ): Int {
            this.window = window
            this.attribute = attribute
            this.value = value.getInt(0L)
            this.valueSize = valueSize
            return 0
        }
    }
}
