package dev.obiente.nextcloudnative.app

import java.io.File

internal enum class DesktopFolderPickerPlatform {
    Linux,
    MacOs,
    Windows,
    Unsupported,
}

internal data class DesktopFolderPickerCommand(
    val arguments: List<String>,
    val environment: Map<String, String> = emptyMap(),
)

/**
 * Opens the platform folder chooser instead of Swing's cross-platform JFileChooser.
 *
 * Linux deliberately asks GTK to use the XDG desktop portal. That lets the active desktop
 * portal choose the person's preferred native picker and also keeps the packaged app ready for
 * sandboxed distributions. macOS uses Finder's chooser and Windows uses the Explorer-backed
 * FolderBrowserDialog.
 */
internal class DesktopSystemFolderPicker(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val environment: Map<String, String> = System.getenv(),
    private val commandAvailable: (String) -> Boolean = ::desktopFolderPickerCommandAvailable,
    private val runCommand: (DesktopFolderPickerCommand) -> String? = ::runDesktopFolderPickerCommand,
) {
    fun choose(initialDirectory: File?): File? {
        val platform = desktopFolderPickerPlatform(osName)
        val command = desktopFolderPickerCommand(
            platform = platform,
            environment = environment,
            initialDirectory = initialDirectory,
            commandAvailable = commandAvailable,
        ) ?: throw IllegalStateException(desktopFolderPickerUnavailableMessage(platform))
        return runCommand(command)
            ?.let(::desktopFolderPickerPath)
            ?.let(::File)
    }
}

internal fun desktopFolderPickerUnavailableMessage(platform: DesktopFolderPickerPlatform): String = when (platform) {
    DesktopFolderPickerPlatform.Linux ->
        "No native folder picker is available. Install zenity or kdialog and make sure your desktop portal is running."
    DesktopFolderPickerPlatform.MacOs ->
        "The macOS folder picker is unavailable because osascript could not be found."
    DesktopFolderPickerPlatform.Windows ->
        "The Windows folder picker is unavailable because PowerShell could not be found."
    DesktopFolderPickerPlatform.Unsupported ->
        "This operating system does not provide a supported native folder picker."
}

internal fun desktopFolderPickerPlatform(osName: String): DesktopFolderPickerPlatform = when {
    osName.startsWith("Linux", ignoreCase = true) -> DesktopFolderPickerPlatform.Linux
    osName.startsWith("Mac", ignoreCase = true) -> DesktopFolderPickerPlatform.MacOs
    osName.startsWith("Windows", ignoreCase = true) -> DesktopFolderPickerPlatform.Windows
    else -> DesktopFolderPickerPlatform.Unsupported
}

internal fun desktopFolderPickerCommand(
    platform: DesktopFolderPickerPlatform,
    environment: Map<String, String>,
    initialDirectory: File?,
    commandAvailable: (String) -> Boolean,
): DesktopFolderPickerCommand? {
    // The caller owns validation. Keep the path in the target platform's syntax so this pure
    // command planner remains testable when cross-compiling Windows artifacts on another OS.
    val initialPath = initialDirectory
        ?.path
        ?.takeIf(String::isNotBlank)
        ?.let { path ->
            when (platform) {
                DesktopFolderPickerPlatform.Linux,
                DesktopFolderPickerPlatform.MacOs,
                -> path.replace('\\', '/')
                DesktopFolderPickerPlatform.Windows,
                DesktopFolderPickerPlatform.Unsupported,
                -> path
            }
        }
    return when (platform) {
        DesktopFolderPickerPlatform.Linux -> linuxFolderPickerCommand(
            environment = environment,
            initialPath = initialPath,
            commandAvailable = commandAvailable,
        )
        DesktopFolderPickerPlatform.MacOs -> {
            if (!commandAvailable("osascript")) return null
            DesktopFolderPickerCommand(
                arguments = listOf(
                    "osascript",
                    "-e",
                    "POSIX path of (choose folder with prompt \"Choose a folder to sync\")",
                ),
            )
        }
        DesktopFolderPickerPlatform.Windows -> {
            val executable = listOf("pwsh.exe", "powershell.exe").firstOrNull(commandAvailable)
                ?: return null
            DesktopFolderPickerCommand(
                arguments = listOf(
                    executable,
                    "-NoProfile",
                    "-NonInteractive",
                    "-STA",
                    "-Command",
                    WINDOWS_FOLDER_PICKER_SCRIPT,
                ),
                environment = buildMap {
                    put("NC_NATIVE_FOLDER_PICKER_TITLE", "Choose a folder to sync")
                    initialPath?.let { put("NC_NATIVE_INITIAL_FOLDER", it) }
                },
            )
        }
        DesktopFolderPickerPlatform.Unsupported -> null
    }
}

private fun linuxFolderPickerCommand(
    environment: Map<String, String>,
    initialPath: String?,
    commandAvailable: (String) -> Boolean,
): DesktopFolderPickerCommand? {
    val desktop = listOfNotNull(
        environment["XDG_CURRENT_DESKTOP"],
        environment["XDG_SESSION_DESKTOP"],
    ).joinToString(":").lowercase()
    val preferKde = desktop.contains("kde") || desktop.contains("plasma")
    val candidates = if (preferKde) listOf("kdialog", "zenity") else listOf("zenity", "kdialog")
    return candidates.firstNotNullOfOrNull { executable ->
        if (!commandAvailable(executable)) return@firstNotNullOfOrNull null
        when (executable) {
            "zenity" -> DesktopFolderPickerCommand(
                arguments = buildList {
                    add("zenity")
                    add("--file-selection")
                    add("--directory")
                    add("--title=Choose a folder to sync")
                    initialPath?.let { path ->
                        val folder = if (path == "/") path else "${path.trimEnd('/')}/"
                        add("--filename=$folder")
                    }
                },
                environment = mapOf("GTK_USE_PORTAL" to "1"),
            )
            "kdialog" -> DesktopFolderPickerCommand(
                arguments = listOf(
                    "kdialog",
                    "--getexistingdirectory",
                    initialPath.orEmpty(),
                    "--title",
                    "Choose a folder to sync",
                ),
            )
            else -> null
        }
    }
}

internal fun desktopFolderPickerPath(output: String): String? = output
    .lineSequence()
    .firstOrNull { it.isNotBlank() }
    ?.trim()
    ?.let(::trimDesktopFolderPickerPath)
    ?.takeIf(String::isNotBlank)

private fun trimDesktopFolderPickerPath(path: String): String = when {
    path == "/" -> path
    WINDOWS_DRIVE_ROOT.matches(path) -> path
    else -> path.trimEnd('/', '\\')
}

private fun desktopFolderPickerCommandAvailable(executable: String): Boolean = runCatching {
    val finder = if (desktopFolderPickerPlatform(System.getProperty("os.name").orEmpty()) ==
        DesktopFolderPickerPlatform.Windows
    ) {
        listOf("where.exe", executable)
    } else {
        listOf("sh", "-c", "command -v \"\$1\" >/dev/null 2>&1", "sh", executable)
    }
    ProcessBuilder(finder)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor() == 0
}.getOrDefault(false)

private fun runDesktopFolderPickerCommand(command: DesktopFolderPickerCommand): String? = runCatching {
    val builder = ProcessBuilder(command.arguments)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.environment().putAll(command.environment)
    val process = builder.start()
    val output = process.inputStream.bufferedReader().use { it.readText().take(MAX_FOLDER_PICKER_OUTPUT) }
    output.takeIf { process.waitFor() == 0 }
}.getOrNull()

private const val MAX_FOLDER_PICKER_OUTPUT = 16_384
private val WINDOWS_DRIVE_ROOT = Regex("^[A-Za-z]:[\\\\/]$")

private val WINDOWS_FOLDER_PICKER_SCRIPT = """
    Add-Type -AssemblyName System.Windows.Forms
    ${'$'}dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    ${'$'}dialog.Description = ${'$'}env:NC_NATIVE_FOLDER_PICKER_TITLE
    ${'$'}dialog.UseDescriptionForTitle = ${'$'}true
    ${'$'}dialog.ShowNewFolderButton = ${'$'}true
    if (${ '$' }env:NC_NATIVE_INITIAL_FOLDER) {
        ${'$'}dialog.SelectedPath = ${'$'}env:NC_NATIVE_INITIAL_FOLDER
    }
    if (${ '$' }dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        [Console]::Out.WriteLine(${ '$' }dialog.SelectedPath)
    } else {
        exit 1
    }
""".trimIndent()
