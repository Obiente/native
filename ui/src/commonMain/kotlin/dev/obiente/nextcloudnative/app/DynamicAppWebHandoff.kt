package dev.obiente.nextcloudnative.app

/**
 * Resolves the server-advertised web entry point for an app without allowing a navigation entry to
 * move the user to another origin. The conventional app route remains available when older server
 * metadata omits its href.
 */
internal fun verifiedEmbeddedWebAppUrl(
    serverUrl: String,
    appId: String,
    advertisedHref: String?,
): String? {
    if (appId !in VERIFIED_EMBEDDED_WEB_APP_IDS) return null
    val base = serverUrl.trim().trimEnd('/').takeIf(::isSafeAbsoluteWebUrl) ?: return null
    val candidate = advertisedHref?.trim().orEmpty()
    if (candidate.isNotEmpty()) {
        if (
            candidate.startsWith('/') && !candidate.startsWith("//") &&
            candidate.none { it.isWhitespace() || it.isISOControl() } && '\\' !in candidate
        ) {
            return base + candidate
        }
        if (isSafeAbsoluteWebUrl(candidate) && dynamicWebOrigin(candidate) == dynamicWebOrigin(base)) {
            return candidate
        }
    }
    val safeAppId = appId.takeIf { id ->
        id.length in 1..64 && id.all { character ->
            character.isLowerCase() || character.isDigit() || character == '_'
        }
    } ?: return null
    return "$base/index.php/apps/$safeAppId/"
}

private val VERIFIED_EMBEDDED_WEB_APP_IDS = setOf(
    "office",
    "onlyoffice",
    "richdocuments",
)

private fun isSafeAbsoluteWebUrl(value: String): Boolean {
    if (value.length !in 1..4_096 || value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) {
        return false
    }
    val schemeLength = when {
        value.startsWith("https://", ignoreCase = true) -> 8
        value.startsWith("http://", ignoreCase = true) -> 7
        else -> return false
    }
    val authority = value.drop(schemeLength).substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.isNotBlank() && '@' !in authority && authority !in setOf(".", "..")
}

private fun dynamicWebOrigin(value: String): String? {
    val schemeLength = when {
        value.startsWith("https://", ignoreCase = true) -> 8
        value.startsWith("http://", ignoreCase = true) -> 7
        else -> return null
    }
    val authority = value.drop(schemeLength).substringBefore('/').substringBefore('?').substringBefore('#')
    return value.take(schemeLength).lowercase() + authority.lowercase()
}
