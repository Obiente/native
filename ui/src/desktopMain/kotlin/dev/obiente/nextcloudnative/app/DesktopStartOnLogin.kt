package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal enum class DesktopStartOnLoginPlatform {
    Linux,
    Windows,
    Unsupported,
}

internal data class DesktopStartOnLoginResult(
    val platform: DesktopStartOnLoginPlatform,
    val enabled: Boolean,
    val configured: Boolean,
    val message: String,
)

internal class DesktopStartOnLoginController(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: File = File(System.getProperty("user.home")),
    private val linuxConfigHome: File = System.getenv("XDG_CONFIG_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?: File(userHome, ".config"),
    private val launcherPath: String? = packagedDesktopLauncherPath(),
    private val processRunner: (List<String>) -> Int = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start().also { process ->
            process.inputStream.bufferedReader().use { it.readText() }
        }.waitFor()
    },
) {
    private val platform = when {
        osName.lowercase().contains("linux") -> DesktopStartOnLoginPlatform.Linux
        osName.lowercase().contains("windows") -> DesktopStartOnLoginPlatform.Windows
        else -> DesktopStartOnLoginPlatform.Unsupported
    }

    fun configure(enabled: Boolean): DesktopStartOnLoginResult {
        val launcher = launcherPath?.takeIf(String::isNotBlank)
            ?: return DesktopStartOnLoginResult(
                platform = platform,
                enabled = enabled,
                configured = false,
                message = "Start on login is applied by installed desktop packages, not development launches.",
            )
        return when (platform) {
            DesktopStartOnLoginPlatform.Linux -> configureLinux(enabled, launcher)
            DesktopStartOnLoginPlatform.Windows -> configureWindows(enabled, launcher)
            DesktopStartOnLoginPlatform.Unsupported -> DesktopStartOnLoginResult(
                platform = platform,
                enabled = enabled,
                configured = false,
                message = "Start on login is not available on this desktop platform yet.",
            )
        }
    }

    private fun configureLinux(enabled: Boolean, launcher: String): DesktopStartOnLoginResult {
        val entry = File(linuxConfigHome, "autostart/nextcloud-native.desktop")
        if (!enabled) {
            Files.deleteIfExists(entry.toPath())
            return DesktopStartOnLoginResult(
                platform,
                enabled = false,
                configured = true,
                message = "Nextcloud Native will not start when you sign in.",
            )
        }
        check(File(launcher).isFile) { "The installed Nextcloud Native launcher could not be found." }
        val parent = requireNotNull(entry.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "The desktop autostart folder could not be created." }
        val content = """
            [Desktop Entry]
            Type=Application
            Version=1.0
            Name=Nextcloud Native
            Comment=Keep Nextcloud files and virtual files available
            Exec=${desktopEntryExecArgument(launcher)} --background
            Icon=nextcloud-native
            Terminal=false
            StartupNotify=false
            X-GNOME-Autostart-enabled=true
        """.trimIndent() + "\n"
        val temporary = File.createTempFile("nextcloud-native.", ".desktop", parent)
        try {
            temporary.writeText(content)
            try {
                Files.move(
                    temporary.toPath(),
                    entry.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), entry.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        return DesktopStartOnLoginResult(
            platform,
            enabled = true,
            configured = true,
            message = "Nextcloud Native will start when you sign in.",
        )
    }

    private fun configureWindows(enabled: Boolean, launcher: String): DesktopStartOnLoginResult {
        check(File(launcher).isFile) { "The installed Nextcloud Native launcher could not be found." }
        val command = if (enabled) {
            listOf(
                "reg.exe",
                "add",
                WINDOWS_RUN_KEY,
                "/v",
                WINDOWS_VALUE_NAME,
                "/t",
                "REG_SZ",
                "/d",
                "\"$launcher\" --background",
                "/f",
            )
        } else {
            listOf("reg.exe", "delete", WINDOWS_RUN_KEY, "/v", WINDOWS_VALUE_NAME, "/f")
        }
        val exitCode = processRunner(command)
        check(exitCode == 0 || !enabled && exitCode == 1) {
            "Windows could not update start on login (exit code $exitCode)."
        }
        return DesktopStartOnLoginResult(
            platform,
            enabled = enabled,
            configured = true,
            message = if (enabled) {
                "Nextcloud Native will start when you sign in."
            } else {
                "Nextcloud Native will not start when you sign in."
            },
        )
    }

    private companion object {
        const val WINDOWS_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val WINDOWS_VALUE_NAME = "NextcloudNative"
    }
}

internal fun packagedDesktopLauncherPath(): String? {
    System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)?.let { return it }
    val command = ProcessHandle.current().info().command().orElse(null)?.takeIf(String::isNotBlank) ?: return null
    val executableName = File(command).name.lowercase()
    return command.takeUnless {
        executableName == "java" || executableName == "java.exe" || executableName.startsWith("gradle")
    }
}

internal fun desktopEntryExecArgument(path: String): String {
    require(path.isNotBlank() && '\n' !in path && '\r' !in path)
    val escaped = buildString(path.length + 8) {
        path.forEach { character ->
            when (character) {
                '\\', '"', '`', '$' -> append('\\').append(character)
                else -> append(character)
            }
        }
    }
    return "\"$escaped\""
}
