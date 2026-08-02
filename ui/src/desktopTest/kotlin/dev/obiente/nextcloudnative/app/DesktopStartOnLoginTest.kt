package dev.obiente.nextcloudnative.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStartOnLoginTest {
    @Test
    fun linuxAutostartEntryIsOwnedQuotedAndReversible() {
        val root = createTempDirectory("nextcloud-native-startup").toFile()
        val launcher = File(root, "Nextcloud Native/bin/Nextcloud Native").apply {
            parentFile.mkdirs()
            writeText("launcher")
        }
        val controller = DesktopStartOnLoginController(
            osName = "Linux",
            userHome = root,
            linuxConfigHome = File(root, ".config"),
            launcherPath = launcher.absolutePath,
        )

        assertTrue(controller.configure(enabled = true).configured)
        val entry = File(root, ".config/autostart/nextcloud-native.desktop")
        assertTrue(entry.isFile)
        assertTrue(entry.readText().contains("Exec=${desktopEntryExecArgument(launcher.absolutePath)} --background"))
        assertFalse(entry.readText().contains("Terminal=true"))

        assertTrue(controller.configure(enabled = false).configured)
        assertFalse(entry.exists())
    }

    @Test
    fun windowsRegistrationUsesTheCurrentUserRunKey() {
        val root = createTempDirectory("nextcloud-native-startup-windows").toFile()
        val launcher = File(root, "NextcloudNative.exe").apply { writeText("launcher") }
        var command = emptyList<String>()
        val result = DesktopStartOnLoginController(
            osName = "Windows 11",
            userHome = root,
            linuxConfigHome = File(root, ".config"),
            launcherPath = launcher.absolutePath,
            processRunner = {
                command = it
                0
            },
        ).configure(enabled = true)

        assertTrue(result.configured)
        assertEquals("reg.exe", command.first())
        assertTrue(command.contains("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"))
        assertTrue(command.contains("\"${launcher.absolutePath}\" --background"))
    }

    @Test
    fun developmentLaunchDoesNotWriteStartupState() {
        val result = DesktopStartOnLoginController(
            osName = "Linux",
            userHome = createTempDirectory("nextcloud-native-startup-dev").toFile(),
            linuxConfigHome = createTempDirectory("nextcloud-native-startup-dev-config").toFile(),
            launcherPath = null,
        ).configure(enabled = true)

        assertFalse(result.configured)
    }
}
