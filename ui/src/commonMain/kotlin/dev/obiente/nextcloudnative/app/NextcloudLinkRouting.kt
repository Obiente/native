package dev.obiente.nextcloudnative.app

/** A bounded, account-aware destination derived from a server-provided or operating-system link. */
internal sealed interface NextcloudLinkDestination {
    val browserUrl: String?

    data class FileId(
        val value: Long,
        override val browserUrl: String,
    ) : NextcloudLinkDestination

    data class FilesPath(
        val value: String,
        override val browserUrl: String,
    ) : NextcloudLinkDestination

    data class App(
        val appId: String,
        override val browserUrl: String,
    ) : NextcloudLinkDestination

    data class Browser(
        override val browserUrl: String,
        val sameAccount: Boolean,
    ) : NextcloudLinkDestination

    data class Rejected(val message: String) : NextcloudLinkDestination {
        override val browserUrl: String? = null
    }

    data class Home(override val browserUrl: String) : NextcloudLinkDestination
}

/** One operating-system link delivery. The sequence makes warm and restored launches idempotent. */
data class NextcloudNativeLinkRequest(
    val sequence: Long,
    val url: String,
) {
    init {
        require(sequence > 0L) { "The native link sequence must be positive." }
        require(url.isSafeIncomingNextcloudLink()) { "The native link is invalid." }
    }
}

/**
 * Resolves a link without performing network access. Same-account routes become typed native
 * destinations; unknown safe routes retain an explicit browser fallback.
 */
internal fun nextcloudLinkDestination(
    session: NextcloudSession,
    rawLink: String,
): NextcloudLinkDestination {
    val unwrapped = unwrapNextcloudNativeLink(rawLink)
        ?: return NextcloudLinkDestination.Rejected("This link is invalid or unsupported.")
    val resolved = resolveAccountLink(session.serverUrl, unwrapped)
        ?: return NextcloudLinkDestination.Rejected("This link is invalid or unsupported.")
    if (!resolved.sameAccount) {
        return NextcloudLinkDestination.Browser(resolved.browserUrl, sameAccount = false)
    }

    val parsed = parseRelativeAccountLink(resolved.relativeUrl)
        ?: return NextcloudLinkDestination.Rejected("This Nextcloud link is malformed.")
    val segments = parsed.pathSegments.let { values ->
        if (values.firstOrNull() == "index.php") values.drop(1) else values
    }
    if (segments.isEmpty()) return NextcloudLinkDestination.Home(resolved.browserUrl)

    if (segments.size == 2 && segments[0] == "f") {
        val fileId = segments[1].toPositiveFileId()
            ?: return NextcloudLinkDestination.Rejected("This Files link has an invalid file ID.")
        return NextcloudLinkDestination.FileId(fileId, resolved.browserUrl)
    }

    val appsIndex = segments.indexOf("apps").takeIf { it == 0 } ?: -1
    val appId = appsIndex.takeIf { it >= 0 }
        ?.let { index -> segments.getOrNull(index + 1) }
        ?.takeIf(String::isSafeNextcloudAppId)
    if (appId == "files") {
        if (parsed.queryParameters.hasInvalidFileIdentity()) {
            return NextcloudLinkDestination.Rejected("This Files link has an invalid file ID.")
        }
        val appRouteSegments = segments.drop(appsIndex + 2)
        val routeFileIdValue = appRouteSegments.getOrNull(1)
            ?.takeIf { appRouteSegments.firstOrNull() == "files" && appRouteSegments.size == 2 }
        if (routeFileIdValue != null && routeFileIdValue.toPositiveFileId() == null) {
            return NextcloudLinkDestination.Rejected("This Files link has an invalid file ID.")
        }
        val suppliedFileIds = listOfNotNull(
            parsed.queryParameters["openfile"]?.singleOrNull()?.toPositiveFileId(),
            parsed.queryParameters["fileid"]?.singleOrNull()?.toPositiveFileId(),
            routeFileIdValue?.toPositiveFileId(),
        )
        if (suppliedFileIds.distinct().size > 1) {
            return NextcloudLinkDestination.Rejected("This Files link has conflicting file IDs.")
        }
        val explicitFileId = suppliedFileIds.firstOrNull()
        if (explicitFileId != null) {
            return NextcloudLinkDestination.FileId(explicitFileId, resolved.browserUrl)
        }
        if (appRouteSegments.isNotEmpty()) {
            return NextcloudLinkDestination.Browser(resolved.browserUrl, sameAccount = true)
        }
        val pathValues = parsed.queryParameters["dir"]
        if (pathValues != null && pathValues.size != 1) {
            return NextcloudLinkDestination.Rejected("This Files link has an invalid folder path.")
        }
        val pathValue = pathValues?.singleOrNull()
        val path = when {
            pathValue == null -> ""
            else -> normalizeNextcloudFilesPath(pathValue)
                ?: return NextcloudLinkDestination.Rejected("This Files link has an invalid folder path.")
        }
        return NextcloudLinkDestination.FilesPath(path, resolved.browserUrl)
    }
    if (appId == "dashboard") return NextcloudLinkDestination.Home(resolved.browserUrl)
    if (appId != null) return NextcloudLinkDestination.App(appId, resolved.browserUrl)

    return NextcloudLinkDestination.Browser(resolved.browserUrl, sameAccount = true)
}

internal fun String.isSafeIncomingNextcloudLink(): Boolean {
    val value = trim()
    if (value.length !in 1..MAX_NEXTCLOUD_LINK_LENGTH) return false
    if (value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) return false
    return value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("nextcloudnative://open?", ignoreCase = true)
}

private data class ResolvedAccountLink(
    val sameAccount: Boolean,
    val relativeUrl: String,
    val browserUrl: String,
)

private data class ParsedWebUrl(
    val scheme: String,
    val authority: String,
    val path: String,
    val suffix: String,
) {
    val origin: String = "$scheme://${normalizedAuthority(scheme, authority)}"
}

private data class ParsedRelativeAccountLink(
    val pathSegments: List<String>,
    val queryParameters: Map<String, List<String>>,
)

private fun unwrapNextcloudNativeLink(rawLink: String): String? {
    val value = rawLink.trim()
    if (value.startsWith('/')) {
        return value.takeIf { relative ->
            relative.length <= MAX_NEXTCLOUD_LINK_LENGTH &&
                !relative.startsWith("//") &&
                '\\' !in relative &&
                relative.none { it.isWhitespace() || it.isISOControl() }
        }
    }
    if (!value.startsWith("nextcloudnative://", ignoreCase = true)) {
        return value.takeIf(String::isSafeIncomingNextcloudLink)
    }
    if (!value.startsWith("nextcloudnative://open?", ignoreCase = true) || '#' in value) return null
    val query = value.substringAfter('?')
    val parameters = parseQueryParameters(query) ?: return null
    if (parameters.keys != setOf("url")) return null
    return parameters.getValue("url").singleOrNull()?.takeIf { unwrapped ->
        !unwrapped.startsWith("nextcloudnative://", ignoreCase = true) &&
            unwrapped.isSafeIncomingNextcloudLink()
    }
}

private fun resolveAccountLink(serverUrl: String, link: String): ResolvedAccountLink? {
    val server = parseWebUrl(serverUrl.trim().trimEnd('/')) ?: return null
    if ('?' in server.suffix || '#' in server.suffix) return null
    val serverPath = server.path.trimEnd('/').takeUnless { it == "/" }.orEmpty()
    if (link.startsWith('/')) {
        if (link.startsWith("//")) return null
        val relative = link
        return ResolvedAccountLink(
            sameAccount = true,
            relativeUrl = relative,
            browserUrl = "${server.scheme}://${server.authority}$serverPath$relative",
        )
    }

    val candidate = parseWebUrl(link) ?: return null
    if (candidate.origin != server.origin) {
        return candidate.takeIf { it.scheme == "https" }?.let {
            ResolvedAccountLink(false, "", link)
        }
    }
    val candidatePath = candidate.path
    val belongsToBase = serverPath.isEmpty() ||
        candidatePath == serverPath ||
        candidatePath.startsWith("$serverPath/")
    if (!belongsToBase) return ResolvedAccountLink(false, "", link)
    val relativePath = candidatePath.removePrefix(serverPath).ifEmpty { "/" }
    return ResolvedAccountLink(
        sameAccount = true,
        relativeUrl = relativePath + candidate.suffix,
        browserUrl = link,
    )
}

private fun parseWebUrl(value: String): ParsedWebUrl? {
    if (value.length !in 1..MAX_NEXTCLOUD_LINK_LENGTH) return null
    if (value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = value.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .let { if (it < 0) value.length else it }
    val authority = value.substring(authorityStart, authorityEnd)
    if (authority.isBlank() || '@' in authority || authority == "." || authority == "..") return null
    val remainder = value.substring(authorityEnd)
    val path = remainder.substringBefore('?').substringBefore('#').ifEmpty { "/" }
    if (!path.startsWith('/')) return null
    val suffix = remainder.removePrefix(path.takeUnless { remainder.startsWith('?') || remainder.startsWith('#') }.orEmpty())
    return ParsedWebUrl(scheme, authority, path, suffix)
}

private fun normalizedAuthority(scheme: String, authority: String): String {
    val lowered = authority.lowercase()
    return when {
        scheme == "https" && lowered.endsWith(":443") -> lowered.removeSuffix(":443")
        scheme == "http" && lowered.endsWith(":80") -> lowered.removeSuffix(":80")
        else -> lowered
    }
}

private fun parseRelativeAccountLink(value: String): ParsedRelativeAccountLink? {
    if (!value.startsWith('/') || value.startsWith("//") || value.length > MAX_NEXTCLOUD_LINK_LENGTH) return null
    val beforeFragment = value.substringBefore('#')
    val path = beforeFragment.substringBefore('?')
    val pathSegments = path.split('/').filter(String::isNotEmpty).map { rawSegment ->
        decodeUrlComponent(rawSegment, plusAsSpace = false)?.takeIf { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." &&
                '/' !in segment && '\\' !in segment &&
                segment.none { it.isISOControl() }
        } ?: return null
    }
    val query = beforeFragment.substringAfter('?', "")
    val parameters = parseQueryParameters(query) ?: return null
    return ParsedRelativeAccountLink(pathSegments, parameters)
}

private fun parseQueryParameters(query: String): Map<String, List<String>>? {
    if (query.isEmpty()) return emptyMap()
    val parameters = linkedMapOf<String, MutableList<String>>()
    for (part in query.split('&')) {
        if (part.isEmpty()) return null
        val name = decodeUrlComponent(part.substringBefore('='), plusAsSpace = true)
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_NEXTCLOUD_QUERY_NAME_LENGTH }
            ?: return null
        val value = decodeUrlComponent(part.substringAfter('=', ""), plusAsSpace = true)
            ?.takeIf { it.length <= MAX_NEXTCLOUD_QUERY_VALUE_LENGTH }
            ?: return null
        parameters.getOrPut(name) { mutableListOf() } += value
    }
    return parameters
}

private fun decodeUrlComponent(value: String, plusAsSpace: Boolean): String? {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            character == '+' && plusAsSpace -> {
                output.append(' ')
                index += 1
            }
            character == '%' -> {
                val bytes = mutableListOf<Byte>()
                while (index < value.length && value[index] == '%') {
                    if (index + 2 >= value.length) return null
                    val high = value[index + 1].digitToIntOrNull(16) ?: return null
                    val low = value[index + 2].digitToIntOrNull(16) ?: return null
                    bytes += ((high shl 4) or low).toByte()
                    index += 3
                }
                val decoded = runCatching {
                    bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
                }.getOrNull() ?: return null
                output.append(decoded)
            }
            else -> {
                output.append(character)
                index += 1
            }
        }
        if (output.length > MAX_NEXTCLOUD_QUERY_VALUE_LENGTH) return null
    }
    return output.toString().takeIf { decoded -> decoded.none(Char::isISOControl) }
}

private fun normalizeNextcloudFilesPath(value: String): String? {
    val normalized = value.trim('/').takeIf { it.length <= MAX_NEXTCLOUD_FILES_PATH_LENGTH }.orEmpty()
    if (normalized.isEmpty()) return ""
    val segments = normalized.split('/')
    if (segments.any { segment ->
            segment.isBlank() || segment == "." || segment == ".." || '\\' in segment ||
                segment.any { it.isISOControl() }
        }
    ) return null
    return segments.joinToString("/")
}

private fun Map<String, List<String>>.hasInvalidFileIdentity(): Boolean =
    listOf("openfile", "fileid").any { name ->
        val values = this[name]
        values != null && (values.size != 1 || values.single().toPositiveFileId() == null)
    }

private fun String.toPositiveFileId(): Long? = toLongOrNull()?.takeIf { it > 0L }

private fun String.isSafeNextcloudAppId(): Boolean =
    length in 1..128 && this != "." && this != ".." &&
        all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

private const val MAX_NEXTCLOUD_LINK_LENGTH = 8_192
private const val MAX_NEXTCLOUD_QUERY_NAME_LENGTH = 128
private const val MAX_NEXTCLOUD_QUERY_VALUE_LENGTH = 4_096
private const val MAX_NEXTCLOUD_FILES_PATH_LENGTH = 4_096
