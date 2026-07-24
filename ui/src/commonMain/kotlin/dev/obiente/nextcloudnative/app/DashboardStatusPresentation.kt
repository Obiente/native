package dev.obiente.nextcloudnative.app

/**
 * Returns the installed app named by an internal dashboard link. The result is only a navigation
 * hint: callers still have to verify that the app is installed before opening a native surface.
 */
internal fun dashboardAppIdForLink(session: NextcloudSession, link: String): String? {
    val serverBase = session.serverUrl.trim().trimEnd('/')
    val path = when {
        link.startsWith("/") -> link.substringBefore('?').substringBefore('#')
        link.startsWith("$serverBase/") ->
            link.removePrefix(serverBase).substringBefore('?').substringBefore('#')
        else -> return null
    }
    val segments = path.split('/').filter(String::isNotBlank)
    val appsIndex = segments.indexOf("apps")
    val appId = segments.getOrNull(appsIndex + 1) ?: return null
    return appId.takeIf { candidate ->
        candidate.length <= 128 &&
            candidate.isNotBlank() &&
            candidate != "." &&
            candidate != ".." &&
            candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}

/**
 * Resolves a dashboard link for an explicit browser handoff. Relative links stay on the
 * authenticated server. Absolute links remain HTTPS-only and were already bounded by the parser.
 */
internal fun dashboardBrowserUrl(session: NextcloudSession, link: String): String {
    require(link.startsWith("/") || link.startsWith("https://")) {
        "The dashboard link is not safe to open."
    }
    return if (link.startsWith("/")) {
        session.serverUrl.trim().trimEnd('/') + link
    } else {
        link
    }
}

internal fun availableUserPresences(
    capabilities: NativeUserStatusCapabilities,
): List<NativeUserPresence> {
    if (!capabilities.enabled) return emptyList()
    return NativeUserPresence.entries.filter { presence ->
        presence != NativeUserPresence.Busy || capabilities.supportsBusy
    }
}

internal fun NativeUserPresence.displayLabel(): String = when (this) {
    NativeUserPresence.Online -> "Online"
    NativeUserPresence.Away -> "Away"
    NativeUserPresence.DoNotDisturb -> "Do not disturb"
    NativeUserPresence.Invisible -> "Invisible"
    NativeUserPresence.Offline -> "Offline"
    NativeUserPresence.Busy -> "Busy"
}

internal fun NativeUserStatusEdit.confirmationLabel(): String = when (this) {
    is NativeUserStatusEdit.Presence -> "Set presence to ${presence.displayLabel().lowercase()}"
    is NativeUserStatusEdit.CustomMessage -> "Set a custom status message"
    is NativeUserStatusEdit.PredefinedMessage -> "Use the selected status message"
    NativeUserStatusEdit.ClearMessage -> "Clear the current status message"
    is NativeUserStatusEdit.Restore -> "Restore the previous status message"
}
