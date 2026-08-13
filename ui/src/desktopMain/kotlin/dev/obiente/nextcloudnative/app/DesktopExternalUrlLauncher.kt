package dev.obiente.nextcloudnative.app

import java.awt.Desktop
import java.net.URI

internal enum class DesktopExternalUrlMethod(val diagnosticName: String) {
    Awt("awt"),
    LinuxXdgOpen("xdg-open"),
    LinuxGio("gio"),
    MacOpen("open"),
    WindowsShell("windows-shell"),
}

internal class DesktopExternalUrlLaunchException(
    message: String,
    val code: String,
    val attemptedMethods: List<DesktopExternalUrlMethod>,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class DesktopExternalUrlLauncher(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val awtBrowser: ((URI) -> Unit)? = supportedAwtBrowser(),
    private val startCommand: (List<String>) -> Unit = ::startExternalUrlCommand,
) {
    fun open(url: String): DesktopExternalUrlMethod {
        val uri = externalBrowserUri(url)
        val attempts = mutableListOf<DesktopExternalUrlMethod>()
        var lastFailure: Throwable? = null

        awtBrowser?.let { browse ->
            attempts += DesktopExternalUrlMethod.Awt
            runCatching { browse(uri) }
                .onSuccess { return DesktopExternalUrlMethod.Awt }
                .onFailure { failure -> lastFailure = failure }
        }

        desktopExternalUrlCommands(osName, uri).forEach { candidate ->
            attempts += candidate.method
            runCatching { startCommand(candidate.arguments) }
                .onSuccess { return candidate.method }
                .onFailure { failure -> lastFailure = failure }
        }

        throw DesktopExternalUrlLaunchException(
            message = desktopExternalUrlFailureMessage(osName),
            code = "BROWSER_HANDOFF_UNAVAILABLE",
            attemptedMethods = attempts,
            cause = lastFailure,
        )
    }
}

internal data class DesktopExternalUrlCommand(
    val method: DesktopExternalUrlMethod,
    val arguments: List<String>,
)

internal fun externalBrowserUri(url: String): URI {
    val uri = runCatching { URI(url) }.getOrElse { failure ->
        throw DesktopExternalUrlLaunchException(
            message = "This link is not a valid web address.",
            code = "BROWSER_URL_INVALID",
            attemptedMethods = emptyList(),
            cause = failure,
        )
    }
    if (
        uri.scheme?.lowercase() !in setOf("http", "https") ||
        uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null
    ) {
        throw DesktopExternalUrlLaunchException(
            message = "This link is not a supported http:// or https:// address.",
            code = "BROWSER_URL_INVALID",
            attemptedMethods = emptyList(),
        )
    }
    return uri
}

internal fun desktopExternalUrlCommands(
    osName: String,
    uri: URI,
): List<DesktopExternalUrlCommand> {
    val url = uri.toASCIIString()
    return when {
        osName.contains("linux", ignoreCase = true) -> listOf(
            DesktopExternalUrlCommand(
                method = DesktopExternalUrlMethod.LinuxXdgOpen,
                arguments = listOf("xdg-open", url),
            ),
            DesktopExternalUrlCommand(
                method = DesktopExternalUrlMethod.LinuxGio,
                arguments = listOf("gio", "open", url),
            ),
        )

        osName.contains("mac", ignoreCase = true) -> listOf(
            DesktopExternalUrlCommand(
                method = DesktopExternalUrlMethod.MacOpen,
                arguments = listOf("open", url),
            ),
        )

        osName.contains("windows", ignoreCase = true) -> listOf(
            DesktopExternalUrlCommand(
                method = DesktopExternalUrlMethod.WindowsShell,
                arguments = listOf("rundll32", "url.dll,FileProtocolHandler", url),
            ),
        )

        else -> emptyList()
    }
}

internal fun desktopExternalUrlPlatformName(osName: String): String = when {
    osName.contains("linux", ignoreCase = true) -> "linux"
    osName.contains("mac", ignoreCase = true) -> "macos"
    osName.contains("windows", ignoreCase = true) -> "windows"
    else -> "other"
}

internal fun desktopExternalUrlFailureDiagnostic(
    failure: DesktopExternalUrlLaunchException,
    osName: String = System.getProperty("os.name").orEmpty(),
): SupportDiagnosticEventDraft = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Error,
    component = SupportDiagnosticComponent.Platform,
    operation = "browser.open",
    outcome = "failed",
    code = failure.code,
    fields = listOf(
        SupportDiagnosticFieldDraft("platform", desktopExternalUrlPlatformName(osName)),
        SupportDiagnosticFieldDraft(
            "attempted_methods",
            failure.attemptedMethods.joinToString(",") { it.diagnosticName }.ifEmpty { "none" },
        ),
    ),
)

private fun desktopExternalUrlFailureMessage(osName: String): String =
    if (osName.contains("linux", ignoreCase = true)) {
        "Could not open your browser. Set a default browser or install xdg-utils, then try again."
    } else {
        "Could not open your browser. Set a default browser, then try again."
    }

private fun supportedAwtBrowser(): ((URI) -> Unit)? = runCatching {
    if (!Desktop.isDesktopSupported()) return@runCatching null
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching null
    { uri: URI -> desktop.browse(uri) }
}.getOrNull()

private fun startExternalUrlCommand(arguments: List<String>) {
    require(arguments.isNotEmpty())
    ProcessBuilder(arguments)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
