package dev.obiente.nextcloudnative.app

import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

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
    private val startCommand: (List<String>) -> DesktopExternalUrlProcess = ::startExternalUrlCommand,
) {
    fun open(url: String): DesktopExternalUrlMethod {
        val uri = externalHandoffUri(url)
        val attempts = mutableListOf<DesktopExternalUrlMethod>()
        var lastFailure: Throwable? = null

        awtBrowser?.takeIf { uri.scheme.isWebScheme() }?.let { browse ->
            attempts += DesktopExternalUrlMethod.Awt
            runCatching { browse(uri) }
                .onSuccess { return DesktopExternalUrlMethod.Awt }
                .onFailure { failure -> lastFailure = failure }
        }

        desktopExternalUrlCommands(osName, uri).forEach { candidate ->
            attempts += candidate.method
            runCatching {
                val process = startCommand(candidate.arguments)
                val exitCode = process.exitCodeWithin(EXTERNAL_URL_HELPER_EXIT_TIMEOUT_MILLIS)
                check(exitCode == null || exitCode == 0) {
                    "The external URL helper exited before accepting the request."
                }
            }
                .onSuccess { return candidate.method }
                .onFailure { failure -> lastFailure = failure }
        }

        throw DesktopExternalUrlLaunchException(
            message = desktopExternalUrlFailureMessage(osName, uri.scheme.isWebScheme()),
            code = if (uri.scheme.isWebScheme()) {
                "BROWSER_HANDOFF_UNAVAILABLE"
            } else {
                "EXTERNAL_HANDOFF_UNAVAILABLE"
            },
            attemptedMethods = attempts,
            cause = lastFailure,
        )
    }
}

internal data class DesktopExternalUrlCommand(
    val method: DesktopExternalUrlMethod,
    val arguments: List<String>,
)

internal fun interface DesktopExternalUrlProcess {
    /** Returns null when the helper is still running after the bounded observation window. */
    fun exitCodeWithin(timeoutMillis: Long): Int?
}

internal fun externalHandoffUri(url: String): URI {
    val uri = runCatching { URI(url) }.getOrElse { failure ->
        throw DesktopExternalUrlLaunchException(
            message = "This link is not a valid web address.",
            code = "BROWSER_URL_INVALID",
            attemptedMethods = emptyList(),
            cause = failure,
        )
    }
    val valid = when (uri.scheme?.lowercase()) {
        "http", "https" -> !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        "mailto" -> uri.isSafeMailtoUri()
        "tel" -> uri.isSafeTelephoneUri()
        else -> false
    }
    if (!valid) {
        throw DesktopExternalUrlLaunchException(
            message = "This link is not a supported web, email, or telephone address.",
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

private fun desktopExternalUrlFailureMessage(osName: String, browser: Boolean): String = when {
    browser && osName.contains("linux", ignoreCase = true) -> {
        "Could not open your browser. Set a default browser or install xdg-utils, then try again."
    }
    browser -> {
        "Could not open your browser. Set a default browser, then try again."
    }
    else -> "Could not open this link with an installed application."
}

private fun supportedAwtBrowser(): ((URI) -> Unit)? = runCatching {
    if (!Desktop.isDesktopSupported()) return@runCatching null
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching null
    { uri: URI -> desktop.browse(uri) }
}.getOrNull()

private fun startExternalUrlCommand(arguments: List<String>): DesktopExternalUrlProcess {
    require(arguments.isNotEmpty())
    val process = ProcessBuilder(arguments)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    return DesktopExternalUrlProcess { timeoutMillis ->
        if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) process.exitValue() else null
    }
}

private fun String?.isWebScheme(): Boolean = equals("http", ignoreCase = true) || equals("https", ignoreCase = true)

private fun URI.isSafeMailtoUri(): Boolean {
    if (!isOpaque || rawQuery != null || rawFragment != null) return false
    val address = rawSchemeSpecificPart.orEmpty()
    return address.length in 3..320 &&
        address.none { it.isWhitespace() || it.isISOControl() || it == '?' || it == '#' } &&
        address.count { it == '@' } == 1 &&
        !address.startsWith('@') &&
        !address.endsWith('@')
}

private fun URI.isSafeTelephoneUri(): Boolean {
    if (!isOpaque || rawQuery != null || rawFragment != null) return false
    val number = rawSchemeSpecificPart.orEmpty()
    return number.length in 3..64 &&
        number.count(Char::isDigit) >= 3 &&
        number.withIndex().all { (index, character) ->
            character.isDigit() || character == '+' && index == 0
        }
}

private const val EXTERNAL_URL_HELPER_EXIT_TIMEOUT_MILLIS = 500L
