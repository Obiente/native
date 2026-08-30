package dev.obiente.nextcloudnative.app

import java.net.URI

/** The top-level document is fixed for the lifetime of one editor, including its one-time token. */
internal class OfficeEditorNavigation(serverUrl: String, initialUrl: String) {
    private val document = requireNotNull(parse(initialUrl)) { "Invalid Office session." }

    init {
        val server = requireNotNull(parse(serverUrl)) { "Invalid Office server." }
        require(server.scheme.equals("https", ignoreCase = true) && sameOrigin(server, document))
        val basePath = server.rawPath.orEmpty().trimEnd('/')
        val prefix = listOf(
            "$basePath/apps/files/directEditing/",
            "$basePath/index.php/apps/files/directEditing/",
        ).firstOrNull { document.rawPath.orEmpty().startsWith(it) }
        val token = prefix?.let { document.rawPath.removePrefix(it) }.orEmpty()
        require(token.isNotBlank() && token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        require(document.rawQuery == null && document.rawFragment == null)
    }

    fun allowsMainFrame(url: String?): Boolean {
        val candidate = parse(url) ?: return false
        return sameOrigin(document, candidate) &&
            document.rawPath == candidate.rawPath && document.rawQuery == candidate.rawQuery
    }

    fun allowsNavigation(url: String?, mainFrame: Boolean, userGesture: Boolean): Boolean {
        if (mainFrame) return allowsMainFrame(url)
        // Editor bootstrap may load a provider iframe; clicking a link cannot turn it into a browser.
        if (userGesture) return false
        val scheme = parse(url)?.scheme?.lowercase() ?: return false
        return scheme in setOf("https", "http", "about", "blob", "data")
    }

    override fun toString(): String = "OfficeEditorNavigation(document=<redacted>)"

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            !first.host.isNullOrBlank() && first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private fun parse(value: String?): URI? {
        if (value.isNullOrBlank() || value.any { it.isWhitespace() || it.isISOControl() }) return null
        return runCatching { URI(value) }.getOrNull()?.takeIf { it.rawUserInfo == null }
    }
}
