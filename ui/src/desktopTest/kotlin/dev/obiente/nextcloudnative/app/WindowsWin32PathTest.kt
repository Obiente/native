package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsWin32PathTest {
    @Test
    fun `drive paths use the extended-length prefix`() {
        assertEquals("\\\\?\\C:\\Sync\\Photos", windowsWin32Path("C:\\Sync\\Photos"))
        assertEquals("\\\\?\\C:\\Sync\\Photos", windowsWin32Path("C:/Sync/Photos"))
        assertEquals("\\\\?\\C:\\", windowsWin32Path("C:\\"))
    }

    @Test
    fun `UNC paths use the extended-length UNC prefix`() {
        assertEquals("\\\\?\\UNC\\server\\share\\Photos", windowsWin32Path("\\\\server\\share\\Photos"))
    }

    @Test
    fun `already prefixed paths are unchanged`() {
        assertEquals("\\\\?\\C:\\Sync", windowsWin32Path("\\\\?\\C:\\Sync"))
        assertEquals("\\\\?\\UNC\\server\\share", windowsWin32Path("\\\\?\\UNC\\server\\share"))
        assertEquals("\\\\.\\C:", windowsWin32Path("\\\\.\\C:"))
    }

    @Test
    fun `relative and drive-relative paths are rejected`() {
        assertFailsWith<IllegalArgumentException> { windowsWin32Path("Photos\\2026") }
        assertFailsWith<IllegalArgumentException> { windowsWin32Path("C:Photos") }
        assertFailsWith<IllegalArgumentException> { windowsWin32Path("") }
    }
}
