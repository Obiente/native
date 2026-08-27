package dev.obiente.nextcloudnative

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun resolveAndroidNextcloudRedirectLocation(
    requestUrl: HttpUrl,
    serverUrl: String,
    location: String?,
): String? {
    val target = location?.let(requestUrl::resolve) ?: return null
    if (target.fragment != null) return null
    val account = serverUrl.toHttpUrlOrNull() ?: return null
    if (
        target.scheme != account.scheme ||
        target.host != account.host ||
        target.port != account.port
    ) {
        return null
    }
    val accountPath = account.encodedPath.trimEnd('/').takeUnless { it == "/" }.orEmpty()
    if (
        accountPath.isNotEmpty() &&
        target.encodedPath != accountPath &&
        !target.encodedPath.startsWith("$accountPath/")
    ) {
        return null
    }
    val relativePath = target.encodedPath.removePrefix(accountPath)
    if (!relativePath.startsWith('/') || relativePath.startsWith("//")) return null
    return buildString {
        append(relativePath)
        target.encodedQuery?.let { query ->
            append('?')
            append(query)
        }
    }
}
