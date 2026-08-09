package dev.obiente.nextcloudnative.app

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DesktopSupportBundleExporter(
    private val diagnostics: JvmSupportDiagnostics,
    private val chooseDestination: (String) -> File? = ::chooseDesktopSupportBundleDestination,
) {
    suspend fun export(
        reproductionSteps: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ): SupportDiagnosticsExportResult = withContext(Dispatchers.IO) {
        val fileName = "nextcloud-native-support-${System.currentTimeMillis().coerceAtLeast(0L)}.zip"
        val destination = runCatching { chooseDestination(fileName) }
            .getOrElse { failure ->
                return@withContext SupportDiagnosticsExportResult.Failed(
                    failure.message ?: "The system save dialog could not be opened.",
                )
            }
            ?: return@withContext SupportDiagnosticsExportResult.Cancelled
        val normalized = destination.absoluteFile.normalizeSupportBundleDestination()
        runCatching {
            diagnostics.writeBundle(normalized, reproductionSteps, featureState)
        }.fold(
            onSuccess = {
                SupportDiagnosticsExportResult.Exported(normalized.absolutePath)
            },
            onFailure = { failure ->
                SupportDiagnosticsExportResult.Failed(
                    failure.message ?: "The anonymized support report could not be saved.",
                )
            },
        )
    }
}

internal fun desktopSupportDiagnosticsDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    environment: Map<String, String> = System.getenv(),
    userHome: File = File(System.getProperty("user.home")),
): File = when {
    osName.startsWith("Windows", ignoreCase = true) -> {
        val localAppData = environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(userHome, "AppData/Local")
        File(localAppData, "Nextcloud Native/Diagnostics")
    }
    osName.startsWith("Mac", ignoreCase = true) ->
        File(userHome, "Library/Application Support/Nextcloud Native/Diagnostics")
    else -> {
        val stateRoot = environment["XDG_STATE_HOME"]?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(userHome, ".local/state")
        File(stateRoot, "nextcloud-native/diagnostics")
    }
}.absoluteFile

internal fun desktopSupportPlatformName(osName: String = System.getProperty("os.name").orEmpty()): String = when {
    osName.startsWith("Windows", ignoreCase = true) -> "Windows"
    osName.startsWith("Mac", ignoreCase = true) -> "macOS"
    osName.startsWith("Linux", ignoreCase = true) -> "Linux"
    else -> "Desktop"
}

private fun chooseDesktopSupportBundleDestination(fileName: String): File? {
    require(fileName.matches(Regex("[a-z0-9-]+\\.zip")))
    check(!GraphicsEnvironment.isHeadless()) { "A graphical save dialog is unavailable." }
    val selected = AtomicReference<File?>()
    val choose = {
        val dialog = FileDialog(null as Frame?, "Export anonymized support report", FileDialog.SAVE)
        try {
            dialog.file = fileName
            defaultDesktopExportDirectory()?.let { directory -> dialog.directory = directory.absolutePath }
            dialog.isVisible = true
            val directory = dialog.directory
            val name = dialog.file
            if (!directory.isNullOrBlank() && !name.isNullOrBlank()) {
                selected.set(File(directory, name))
            }
        } finally {
            dialog.dispose()
        }
    }
    if (EventQueue.isDispatchThread()) choose() else EventQueue.invokeAndWait { choose() }
    return selected.get()
}

private fun defaultDesktopExportDirectory(): File? = sequenceOf(
    File(System.getProperty("user.home"), "Downloads"),
    File(System.getProperty("user.home"), "Desktop"),
    File(System.getProperty("user.home")),
).firstOrNull(File::isDirectory)

private fun File.normalizeSupportBundleDestination(): File {
    val name = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
    require(name.length <= MAX_SUPPORT_BUNDLE_FILE_NAME_LENGTH)
    require(name.none { it == '/' || it == '\\' || it.code < 0x20 })
    val parent = requireNotNull(parentFile).absoluteFile
    require(parent.isDirectory) { "The selected export folder is unavailable." }
    return File(parent, name).absoluteFile
}

private const val MAX_SUPPORT_BUNDLE_FILE_NAME_LENGTH = 180
