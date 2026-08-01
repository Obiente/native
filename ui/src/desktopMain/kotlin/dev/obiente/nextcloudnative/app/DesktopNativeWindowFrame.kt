package dev.obiente.nextcloudnative.app

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Keeps the operating-system window frame aligned with the app theme without replacing it. */
internal fun applyDesktopNativeWindowFrame(window: Window, darkTheme: Boolean) {
    if (!isWindowsDesktop() || !window.isDisplayable) return
    runCatching {
        val windowHandle = HWND(Native.getWindowPointer(window))
        applyWindowsNativeWindowTheme(
            api = WindowsDesktopWindowApi.instance,
            windowHandle = windowHandle,
            darkTheme = darkTheme,
        )
    }
}

internal fun applyWindowsNativeWindowTheme(
    api: WindowsDesktopWindowApi,
    windowHandle: HWND,
    darkTheme: Boolean,
): Int {
    val enabled = IntByReference(if (darkTheme) 1 else 0)
    return api.DwmSetWindowAttribute(
        windowHandle,
        DWMWA_USE_IMMERSIVE_DARK_MODE,
        enabled.pointer,
        Int.SIZE_BYTES,
    )
}

internal interface WindowsDesktopWindowApi : StdCallLibrary {
    fun DwmSetWindowAttribute(
        window: HWND,
        attribute: Int,
        value: Pointer,
        valueSize: Int,
    ): Int

    companion object {
        val instance: WindowsDesktopWindowApi by lazy {
            Native.load("dwmapi", WindowsDesktopWindowApi::class.java)
        }
    }
}

internal const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
