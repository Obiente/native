package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
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
            processRunner = { 0 },
            linuxSystemdAvailable = { true },
            linuxGraphicalSessionManaged = { true },
        )

        assertTrue(controller.configure(enabled = true).configured)
        val entry = File(root, ".config/autostart/nextcloud-native.desktop")
        assertTrue(entry.isFile)
        assertTrue(entry.readText().contains("TryExec=${desktopEntryStringValue(launcher.absolutePath)}"))
        assertTrue(entry.readText().contains("Exec=${desktopEntryExecArgument(launcher.absolutePath)} --autostart"))
        assertFalse(entry.readText().contains("X-systemd-skip"))
        assertFalse(entry.readText().contains("Terminal=true"))
        val service = File(root, ".config/systemd/user/nextcloud-native.service")
        assertTrue(service.isFile)
        assertTrue(
            service.readText().contains(
                "ExecStart=${systemdExecArgument(launcher.absolutePath)} --background --service",
            ),
        )
        assertTrue(service.readText().contains("PartOf=graphical-session.target"))
        assertTrue(service.readText().contains("Restart=on-failure"))
        assertTrue(Files.isSymbolicLink(File(root, ".config/systemd/user/graphical-session.target.wants/nextcloud-native.service").toPath()))

        assertTrue(controller.configure(enabled = false).configured)
        assertFalse(entry.exists())
        assertFalse(service.exists())
    }

    @Test
    fun linuxPortableAutostartHandsOffToTheConfiguredUserService() {
        val root = createTempDirectory("nextcloud-native-startup-handoff").toFile()
        File(root, ".config/systemd/user").mkdirs()
        File(root, ".config/systemd/user/nextcloud-native.service").writeText("configured")
        var command: List<String>? = null

        assertTrue(
            handoffLinuxAutostartToUserService(
                osName = "Linux",
                userHome = root,
                linuxConfigHome = File(root, ".config"),
                processRunner = {
                    command = it
                    0
                },
            ),
        )
        assertEquals(listOf("systemctl", "--user", "start", "nextcloud-native.service"), command)
    }

    @Test
    fun linuxForegroundLaunchStartsAndActivatesTheConfiguredUserService() {
        val root = createTempDirectory("nextcloud-native-startup-foreground-handoff").toFile()
        File(root, ".config/systemd/user").mkdirs()
        File(root, ".config/systemd/user/nextcloud-native.service").writeText("configured")
        var command: List<String>? = null
        var forwarded = false

        assertTrue(
            handoffLinuxForegroundLaunchToUserService(
                osName = "Linux",
                userHome = root,
                linuxConfigHome = File(root, ".config"),
                processRunner = {
                    command = it
                    0
                },
                activationForwarder = {
                    forwarded = true
                    true
                },
            ),
        )
        assertEquals(listOf("systemctl", "--user", "start", "nextcloud-native.service"), command)
        assertTrue(forwarded)
    }

    @Test
    fun explicitQuitStopsTheConfiguredUserServiceWithoutWaitingForItself() {
        val root = createTempDirectory("nextcloud-native-startup-explicit-quit").toFile()
        File(root, ".config/systemd/user").mkdirs()
        File(root, ".config/systemd/user/nextcloud-native.service").writeText("configured")
        var command: List<String>? = null

        assertTrue(
            stopLinuxUserServiceForExplicitQuit(
                osName = "Linux",
                userHome = root,
                linuxConfigHome = File(root, ".config"),
                processRunner = {
                    command = it
                    0
                },
            ),
        )
        assertEquals(
            listOf("systemctl", "--user", "--no-block", "stop", "nextcloud-native.service"),
            command,
        )
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
            linuxSystemdAvailable = { false },
        ).configure(enabled = true)

        assertTrue(result.configured)
        assertEquals("reg.exe", command.first())
        assertTrue(command.contains("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"))
        assertTrue(command.contains("\"${launcher.absolutePath}\" --background"))
    }

    @Test
    fun linuxWithoutSystemdKeepsThePortableDesktopAutostartPath() {
        val root = createTempDirectory("nextcloud-native-startup-portable").toFile()
        val launcher = File(root, "NextcloudNative").apply { writeText("launcher") }
        val controller = DesktopStartOnLoginController(
            osName = "Linux",
            userHome = root,
            linuxConfigHome = File(root, ".config"),
            launcherPath = launcher.absolutePath,
            processRunner = { 1 },
            linuxSystemdAvailable = { false },
            linuxGraphicalSessionManaged = { false },
        )

        assertTrue(controller.configure(enabled = true).configured)
        val entry = File(root, ".config/autostart/nextcloud-native.desktop")
        assertTrue(entry.isFile)
        assertFalse(entry.readText().contains("X-systemd-skip=true"))
        assertTrue(entry.readText().contains(" --autostart"))
        assertFalse(File(root, ".config/systemd/user/nextcloud-native.service").exists())
    }

    @Test
    fun developmentLaunchDoesNotWriteStartupState() {
        val result = DesktopStartOnLoginController(
            osName = "Linux",
            userHome = createTempDirectory("nextcloud-native-startup-dev").toFile(),
            linuxConfigHome = createTempDirectory("nextcloud-native-startup-dev-config").toFile(),
            launcherPath = null,
            linuxSystemdAvailable = { false },
            linuxGraphicalSessionManaged = { false },
        ).configure(enabled = true)

        assertFalse(result.configured)
    }
}
