package dev.obiente.nextcloudnative.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSystemFolderPickerTest {
    @Test
    fun linuxUsesPortalAwareZenityOutsideKde() {
        val command = desktopFolderPickerCommand(
            platform = DesktopFolderPickerPlatform.Linux,
            environment = mapOf("XDG_CURRENT_DESKTOP" to "Hyprland"),
            initialDirectory = File("\\example\\Pictures"),
            commandAvailable = { it in setOf("zenity", "kdialog") },
        )

        requireNotNull(command)
        assertEquals("zenity", command.arguments.first())
        assertTrue("--directory" in command.arguments)
        assertTrue(command.arguments.any { it == "--filename=/example/Pictures/" })
        assertEquals("1", command.environment["GTK_USE_PORTAL"])
    }

    @Test
    fun linuxPrefersKdialogForKdeDesktop() {
        val command = desktopFolderPickerCommand(
            platform = DesktopFolderPickerPlatform.Linux,
            environment = mapOf("XDG_CURRENT_DESKTOP" to "KDE"),
            initialDirectory = null,
            commandAvailable = { true },
        )

        requireNotNull(command)
        assertEquals("kdialog", command.arguments.first())
    }

    @Test
    fun windowsPassesInitialFolderOutsideTheScript() {
        val command = desktopFolderPickerCommand(
            platform = DesktopFolderPickerPlatform.Windows,
            environment = emptyMap(),
            initialDirectory = File("C:\\Example\\Pictures"),
            commandAvailable = { it == "powershell.exe" },
        )

        requireNotNull(command)
        assertEquals("powershell.exe", command.arguments.first())
        assertEquals("C:\\Example\\Pictures", command.environment["NC_NATIVE_INITIAL_FOLDER"])
        assertTrue(command.arguments.last().contains("FolderBrowserDialog"))
    }

    @Test
    fun pickerOutputUsesOnlyTheFirstNonBlankPath() {
        assertEquals("/example/Pictures", desktopFolderPickerPath("\n/example/Pictures/\nignored\n"))
        assertEquals("/", desktopFolderPickerPath("/\n"))
        assertEquals("C:\\", desktopFolderPickerPath("C:\\\n"))
        assertNull(desktopFolderPickerPath("\n  \n"))
    }

    @Test
    fun missingLinuxHelpersProduceAnActionableFailureInsteadOfLookingCancelled() {
        val picker = DesktopSystemFolderPicker(
            osName = "Linux",
            environment = emptyMap(),
            commandAvailable = { false },
        )

        val failure = assertFailsWith<IllegalStateException> { picker.choose(null) }

        assertTrue(failure.message.orEmpty().contains("zenity or kdialog"))
        assertTrue(failure.message.orEmpty().contains("desktop portal"))
    }
}
