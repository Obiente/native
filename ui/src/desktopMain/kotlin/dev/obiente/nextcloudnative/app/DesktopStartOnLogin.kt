package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
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
    private val linuxConfigHome: File = linuxDesktopConfigHome(userHome),
    private val launcherPath: String? = packagedDesktopLauncherPath(),
    private val processRunner: (List<String>) -> Int = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start().also { process ->
            process.inputStream.bufferedReader().use { it.readText() }
        }.waitFor()
    },
    private val linuxSystemdAvailable: () -> Boolean = {
        runCatching {
            processRunner(listOf("systemctl", "--user", "show-environment")) == 0
        }.getOrDefault(false)
    },
    private val linuxGraphicalSessionManaged: () -> Boolean = {
        runCatching {
            processRunner(listOf("systemctl", "--user", "is-active", "graphical-session.target")) == 0
        }.getOrDefault(false)
    },
    private val currentProcessIsLinuxUserService: () -> Boolean = {
        isCurrentProcessOwnedByLinuxUserService()
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
            removeLinuxUserService()
            Files.deleteIfExists(entry.toPath())
            return DesktopStartOnLoginResult(
                platform,
                enabled = false,
                configured = true,
                message = "nati.ve will not start when you sign in.",
            )
        }
        check(File(launcher).isFile) { "The installed Nextcloud Native launcher could not be found." }
        val parent = requireNotNull(entry.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "The desktop autostart folder could not be created." }
        val content = """
            [Desktop Entry]
            Type=Application
            Version=1.0
            Name=nati.ve
            Comment=Keep Nextcloud files and virtual files available
            TryExec=${desktopEntryStringValue(launcher)}
            Exec=${desktopEntryExecArgument(launcher)} --autostart
            Icon=dev.obiente.nextcloudnative
            Terminal=false
            StartupNotify=false
            X-GNOME-Autostart-enabled=true
        """.trimIndent() + "\n"
        publishText(entry, content)
        val supervised = linuxSystemdAvailable() && linuxGraphicalSessionManaged() && runCatching {
            configureLinuxUserService(launcher)
        }.getOrDefault(false)
        if (supervised) {
            runCatching { processRunner(listOf("systemctl", "--user", "daemon-reload")) }
        }
        return DesktopStartOnLoginResult(
            platform,
            enabled = true,
            configured = true,
            message = if (supervised) {
                "nati.ve will start in your desktop session and recover after a crash."
            } else {
                "nati.ve will start when you sign in."
            },
        )
    }

    private fun configureLinuxUserService(launcher: String): Boolean {
        val userDirectory = File(linuxConfigHome, "systemd/user")
        check(userDirectory.isDirectory || userDirectory.mkdirs()) {
            "The user service folder could not be created."
        }
        val service = File(userDirectory, LINUX_USER_SERVICE_NAME)
        publishText(
            service,
            """
                [Unit]
                Description=nati.ve desktop sync
                PartOf=graphical-session.target
                After=graphical-session.target

                [Service]
                Type=exec
                ExecStart=${systemdExecArgument(launcher)} --background --service
                Restart=on-failure
                RestartSec=5s
                TimeoutStopSec=20s

                [Install]
                WantedBy=graphical-session.target
            """.trimIndent() + "\n",
        )
        val wantsDirectory = File(userDirectory, "graphical-session.target.wants")
        check(wantsDirectory.isDirectory || wantsDirectory.mkdirs()) {
            "The graphical session service folder could not be created."
        }
        val link = File(wantsDirectory, LINUX_USER_SERVICE_NAME).toPath()
        val expectedTarget = Path.of("..", LINUX_USER_SERVICE_NAME)
        if (!Files.isSymbolicLink(link) || Files.readSymbolicLink(link) != expectedTarget) {
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, expectedTarget)
        }
        return true
    }

    internal fun refreshLinuxUserServiceLauncher(): Boolean {
        if (platform != DesktopStartOnLoginPlatform.Linux) return true
        val launcher = launcherPath?.takeIf(String::isNotBlank) ?: return true
        val service = File(linuxConfigHome, "systemd/user/$LINUX_USER_SERVICE_NAME")
        if (!service.isFile || linuxUserServiceUsesLauncher(service, launcher)) return true
        if (
            !runCatching {
                processRunner(listOf("systemctl", "--user", "stop", LINUX_USER_SERVICE_NAME)) == 0
            }.getOrDefault(false)
        ) return false
        return runCatching { configureLinux(enabled = true, launcher = launcher).configured }.getOrDefault(false)
    }

    private fun removeLinuxUserService() {
        val userDirectory = File(linuxConfigHome, "systemd/user")
        val service = File(userDirectory, LINUX_USER_SERVICE_NAME)
        val systemdAvailable = linuxSystemdAvailable()
        if (service.isFile && systemdAvailable && !currentProcessIsLinuxUserService()) {
            check(
                runCatching {
                    processRunner(
                        listOf("systemctl", "--user", "--no-block", "stop", LINUX_USER_SERVICE_NAME),
                    ) == 0
                }.getOrDefault(false),
            ) { "The nati.ve background service could not be stopped." }
        }
        Files.deleteIfExists(File(userDirectory, "graphical-session.target.wants/$LINUX_USER_SERVICE_NAME").toPath())
        Files.deleteIfExists(service.toPath())
        if (systemdAvailable) {
            runCatching { processRunner(listOf("systemctl", "--user", "daemon-reload")) }
        }
    }

    private fun publishText(destination: File, content: String) {
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "The startup configuration folder could not be created." }
        val temporary = File.createTempFile("${destination.name}.", ".tmp", parent)
        try {
            temporary.writeText(content)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
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
                "nati.ve will start when you sign in."
            } else {
                "nati.ve will not start when you sign in."
            },
        )
    }

    private companion object {
        const val WINDOWS_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val WINDOWS_VALUE_NAME = "NextcloudNative"
    }
}

internal fun isCurrentProcessOwnedByLinuxUserService(
    systemdExecPid: String? = System.getenv("SYSTEMD_EXEC_PID"),
    currentPid: Long = ProcessHandle.current().pid(),
    cgroup: String = runCatching { File("/proc/self/cgroup").readText() }.getOrDefault(""),
): Boolean = systemdExecPid?.toLongOrNull() == currentPid || cgroup.lineSequence().any { membership ->
    membership.substringAfterLast(':').split('/').any { component -> component == LINUX_USER_SERVICE_NAME }
}

private const val LINUX_USER_SERVICE_NAME = "nextcloud-native.service"

private fun linuxUserServiceUsesLauncher(service: File, launcher: String): Boolean {
    val expected = "ExecStart=${systemdExecArgument(launcher)} --background --service"
    return runCatching {
        service.useLines { lines -> lines.any { line -> line == expected } }
    }.getOrDefault(false)
}

/** Lets the portable XDG launch request enter the supervised unit when one was configured. */
internal fun handoffLinuxAutostartToUserService(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: File = File(System.getProperty("user.home")),
    linuxConfigHome: File = linuxDesktopConfigHome(userHome),
    processRunner: (List<String>) -> Int = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start().also { process ->
            process.inputStream.bufferedReader().use { it.readText() }
        }.waitFor()
    },
): Boolean {
    if (!osName.lowercase().contains("linux")) return false
    if (!File(linuxConfigHome, "systemd/user/$LINUX_USER_SERVICE_NAME").isFile) return false
    return runCatching {
        processRunner(listOf("systemctl", "--user", "start", LINUX_USER_SERVICE_NAME)) == 0
    }.getOrDefault(false)
}

/**
 * Gives an ordinary launcher invocation to the configured user service, then asks that supervised
 * process to show its window. The caller continues normally when either step fails.
 */
internal fun handoffLinuxForegroundLaunchToUserService(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: File = File(System.getProperty("user.home")),
    linuxConfigHome: File = linuxDesktopConfigHome(userHome),
    launcherPath: String? = packagedDesktopLauncherPath(),
    processRunner: (List<String>) -> Int = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start().also { process ->
            process.inputStream.bufferedReader().use { it.readText() }
        }.waitFor()
    },
    activationForwarder: () -> Boolean,
): Boolean {
    if (!osName.lowercase().contains("linux")) return false
    if (!File(linuxConfigHome, "systemd/user/$LINUX_USER_SERVICE_NAME").isFile) return false
    val launcherReady = DesktopStartOnLoginController(
        osName = osName,
        userHome = userHome,
        linuxConfigHome = linuxConfigHome,
        launcherPath = launcherPath,
        processRunner = processRunner,
        linuxSystemdAvailable = { true },
        linuxGraphicalSessionManaged = { true },
    ).refreshLinuxUserServiceLauncher()
    if (!launcherReady) return false
    val started = runCatching {
        processRunner(listOf("systemctl", "--user", "start", LINUX_USER_SERVICE_NAME)) == 0
    }.getOrDefault(false)
    return started && runCatching(activationForwarder).getOrDefault(false)
}

/**
 * Stops a separate supervised instance before an explicit application quit releases the
 * single-instance lock. A process already owned by the unit must exit cleanly by itself so
 * systemd cannot interrupt Compose and FUSE teardown with SIGTERM.
 */
internal fun stopLinuxUserServiceForExplicitQuit(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: File = File(System.getProperty("user.home")),
    linuxConfigHome: File = linuxDesktopConfigHome(userHome),
    currentProcessIsLinuxUserService: () -> Boolean = {
        isCurrentProcessOwnedByLinuxUserService()
    },
    processRunner: (List<String>) -> Int = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start().also { process ->
            process.inputStream.bufferedReader().use { it.readText() }
        }.waitFor()
    },
): Boolean {
    if (!osName.lowercase().contains("linux")) return false
    if (currentProcessIsLinuxUserService()) return true
    return runCatching {
        processRunner(
            listOf("systemctl", "--user", "--no-block", "stop", LINUX_USER_SERVICE_NAME),
        ) == 0
    }.getOrDefault(false)
}

private fun linuxDesktopConfigHome(userHome: File): File = System.getenv("XDG_CONFIG_HOME")
    ?.takeIf(String::isNotBlank)
    ?.let(::File)
    ?: File(userHome, ".config")

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
                '%' -> append("%%")
                else -> append(character)
            }
        }
    }
    return "\"$escaped\""
}

internal fun desktopEntryStringValue(value: String): String {
    require(value.isNotBlank() && '\n' !in value && '\r' !in value)
    return buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                ' ' -> append("\\s")
                '\t' -> append("\\t")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
    }
}

internal fun systemdExecArgument(path: String): String {
    require(path.isNotBlank() && '\n' !in path && '\r' !in path)
    val escaped = buildString(path.length + 8) {
        path.forEach { character ->
            when (character) {
                '\\', '"' -> append('\\').append(character)
                '%' -> append("%%")
                '$' -> append('$').append('$')
                else -> append(character)
            }
        }
    }
    return "\"$escaped\""
}
