package dev.obiente.nextcloudnative.app

/** Keep a precise native link ahead of broad collection/provider hints. No network or ID synthesis. */
internal fun preferredNativeEntryLink(serverUrl: String, link: String?, installedAppIds: Set<String>): String? {
    val target = link?.let { nextcloudLinkDestination(serverUrl, it) } ?: return null
    return when (target) {
        is NextcloudLinkDestination.FileId, is NextcloudLinkDestination.FilesPath,
        is NextcloudLinkDestination.Home -> target.browserUrl
        is NextcloudLinkDestination.App -> target.browserUrl.takeIf {
            installedAppIds.any { installed -> installed.equals(target.appId, ignoreCase = true) }
        }
        is NextcloudLinkDestination.Browser, is NextcloudLinkDestination.Rejected -> null
    }
}

internal sealed interface UnifiedSearchOpenTarget {
    data class Link(val url: String) : UnifiedSearchOpenTarget
    data class FilesPath(val path: String) : UnifiedSearchOpenTarget
    data class App(val appId: String) : UnifiedSearchOpenTarget
}

internal fun UnifiedSearchSelection.openTarget(serverUrl: String, installedAppIds: Set<String>): UnifiedSearchOpenTarget? {
    preferredNativeEntryLink(serverUrl, entry.resourceUrl, installedAppIds)?.let { return UnifiedSearchOpenTarget.Link(it) }
    nativeFileParentPathOrNull()?.let { return UnifiedSearchOpenTarget.FilesPath(it) }
    provider.installedAppId(installedAppIds)?.let { return UnifiedSearchOpenTarget.App(it) }
    return entry.resourceUrl?.let { url ->
        when (val target = nextcloudLinkDestination(serverUrl, url)) {
            is NextcloudLinkDestination.Rejected -> null
            else -> target.browserUrl?.let(UnifiedSearchOpenTarget::Link)
        }
    }
}

/**
 * The provider's own app ID wins. Otherwise only a whole provider-ID segment may match, and the
 * longest installed ID is chosen, so `newsletter-search` never opens an installed `news` app.
 */
private fun UnifiedSearchProvider.installedAppId(installedAppIds: Set<String>): String? =
    installedAppIds.firstOrNull { it == appId }
        ?: installedAppIds
            .filter { installed ->
                id == installed || (id.startsWith(installed) && id[installed.length] in PROVIDER_ID_DELIMITERS)
            }
            .maxByOrNull(String::length)

private val PROVIDER_ID_DELIMITERS = setOf('-', '_', '.')

private fun UnifiedSearchSelection.nativeFileParentPathOrNull(): String? {
    if (provider.appId != "files" && !provider.id.startsWith("files")) return null
    val candidate = entry.attributes["path"] ?: entry.attributes["filePath"]
        ?: entry.subline?.takeIf { it.startsWith('/') } ?: return null
    if ('\\' in candidate) return null
    val segments = candidate.substringBefore('?').trim('/').split('/').filter(String::isNotBlank)
    if (segments.any { it == "." || it == ".." || it.any(Char::isISOControl) }) return null
    val path = segments.joinToString("/")
    return if (segments.lastOrNull() == entry.title) path.substringBeforeLast('/', "") else path
}
