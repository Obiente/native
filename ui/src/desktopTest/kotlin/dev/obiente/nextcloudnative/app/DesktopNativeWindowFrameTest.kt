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

    @Test
    fun `Windows native frame retries the legacy dark mode attribute`() {
        val api = RecordingWindowsDesktopWindowApi(rejectCurrentAttribute = true)
        val handle = HWND(Pointer.createConstant(42L))

        assertEquals(0, applyWindowsNativeWindowTheme(api, handle, darkTheme = true))
        assertEquals(
            listOf(DWMWA_USE_IMMERSIVE_DARK_MODE, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY),
            api.attributes,
        )
        assertEquals(1, api.value)
    }

    private class RecordingWindowsDesktopWindowApi(
        private val rejectCurrentAttribute: Boolean = false,
    ) : WindowsDesktopWindowApi {
        var window: HWND? = null
        var attribute: Int? = null
        val attributes = mutableListOf<Int>()
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
            attributes += attribute
            this.value = value.getInt(0L)
            this.valueSize = valueSize
            if (rejectCurrentAttribute && attribute == DWMWA_USE_IMMERSIVE_DARK_MODE) return -1
            return 0
        }
    }
}
