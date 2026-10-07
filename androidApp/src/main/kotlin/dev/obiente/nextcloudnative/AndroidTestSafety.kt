package dev.obiente.nextcloudnative

import android.content.Context
import java.net.URI
import java.util.Locale

internal const val TEST_PREFERENCES_NAME = ANDROID_ACCOUNT_PREFERENCES_NAME
internal const val KEY_TEST_READ_ONLY = "emulator_test_read_only"
internal const val KEY_TEST_WRITE_SCOPE_SERVER = "emulator_test_write_scope_server"
internal const val KEY_TEST_WRITE_SCOPE_PATH = "emulator_test_write_scope_path"

internal fun Context.isReadOnlyTestMode(): Boolean =
    getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_TEST_READ_ONLY, false)

internal fun Context.cloudMutationGate(): () -> Boolean = {
    !isReadOnlyTestMode()
}

internal fun Context.isAllowedTestRequest(method: String, url: String, destination: String? = null): Boolean {
    if (!isReadOnlyTestMode() || method.isReadOnlyTestRequestMethod()) return true
    if (!BuildConfig.DEBUG) return false
    val preferences = getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val serverUrl = preferences.getString(KEY_TEST_WRITE_SCOPE_SERVER, null) ?: return false
    val apiPathPrefix = preferences.getString(KEY_TEST_WRITE_SCOPE_PATH, null) ?: return false
    return ScopedTestWriteAuthorization.create(serverUrl, apiPathPrefix)
        ?.allows(method, url, destination) == true
}

internal class ScopedTestWriteAuthorization private constructor(
    private val scheme: String,
    private val host: String,
    private val port: Int,
    private val absolutePathPrefix: String,
    private val scopeKind: ScopedTestPathKind,
) {
    fun allows(method: String, url: String, destination: String? = null): Boolean {
        val normalizedMethod = method.uppercase(Locale.ROOT)
        val allowedMethods = if (scopeKind == ScopedTestPathKind.FilesFolder) {
            SCOPED_TEST_FILES_MUTATION_METHODS
        } else {
            SCOPED_TEST_MUTATION_METHODS
        }
        if (normalizedMethod !in allowedMethods || !containsTarget(url)) return false
        return normalizedMethod !in setOf("MOVE", "COPY") ||
            (destination != null && containsTarget(destination))
    }

    private fun containsTarget(url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        if (
            target.scheme?.lowercase(Locale.ROOT) != scheme ||
            target.host?.lowercase(Locale.ROOT) != host ||
            target.effectivePort() != port ||
            target.userInfo != null ||
            target.fragment != null
        ) {
            return false
        }
        val path = target.rawPath ?: return false
        val checkedPath = if (scopeKind == ScopedTestPathKind.FilesFolder) {
            if (target.rawQuery != null) return false
            path.replace("%20", " ")
        } else {
            path
        }
        if (checkedPath.contains('%') || checkedPath.contains('\\') || checkedPath.contains("//")) return false
        if (checkedPath.split('/').any { it == "." || it == ".." }) return false
        if (scopeKind == ScopedTestPathKind.FilesFolder &&
            !checkedPath.split('/').filter(String::isNotEmpty).all { segment ->
                segment.all { it.isLetterOrDigit() || it in " ._~-" }
            }
        ) return false
        return (scopeKind == ScopedTestPathKind.AppApi && path == absolutePathPrefix) ||
            (path.startsWith("$absolutePathPrefix/") && path.removePrefix("$absolutePathPrefix/").isNotEmpty())
    }

    companion object {
        fun create(serverUrl: String, apiPathPrefix: String): ScopedTestWriteAuthorization? {
            val server = runCatching { URI(serverUrl.trim().trimEnd('/')) }.getOrNull() ?: return null
            val scheme = server.scheme?.lowercase(Locale.ROOT)
            val host = server.host?.lowercase(Locale.ROOT)
            if (
                scheme != "https" ||
                host.isNullOrBlank() ||
                server.userInfo != null ||
                server.query != null ||
                server.fragment != null
            ) {
                return null
            }
            val normalizedPathPrefix = apiPathPrefix.trim().trimEnd('/')
            val scopeKind = normalizedPathPrefix.safeScopedTestPathKind() ?: return null
            val serverPath = server.rawPath.orEmpty().trimEnd('/')
            if (!serverPath.isSafeServerBasePath()) return null
            return ScopedTestWriteAuthorization(
                scheme = scheme,
                host = host,
                port = server.effectivePort(),
                absolutePathPrefix = "$serverPath$normalizedPathPrefix",
                scopeKind = scopeKind,
            )
        }
    }
}

private fun String.safeScopedTestPathKind(): ScopedTestPathKind? {
    if (
        !startsWith('/') ||
        contains('\\') ||
        contains('?') ||
        contains('#') ||
        contains("//")
    ) {
        return null
    }
    val segments = split('/').filter(String::isNotEmpty)
    if (segments.size == 5 && segments.take(3) == listOf("remote.php", "dav", "files") &&
        segments.take(4).all(String::isSafeScopedTestPathSegment) &&
        segments.last().replace("%20", " ").let { folder ->
            folder.isNotBlank() && folder !in setOf(".", "..") &&
                folder.all { it.isLetterOrDigit() || it in " ._~-" }
        }
    ) return ScopedTestPathKind.FilesFolder
    if (segments.isSafeScopedTestDavCollection()) {
        return ScopedTestPathKind.DavCollection
    }
    val apiSegmentIndex = when {
        segments.take(3) == listOf("ocs", "v2.php", "apps") -> 4
        segments.take(2) == listOf("index.php", "apps") -> 3
        segments.firstOrNull() == "apps" -> 2
        else -> return null
    }
    if (segments.getOrNull(apiSegmentIndex) != "api") {
        return null
    }
    val scopedSegments = segments.drop(apiSegmentIndex + 1)
    if (
        scopedSegments.isEmpty() ||
        (scopedSegments.first().isApiVersionSegment() && scopedSegments.size < 2)
    ) return null
    return if (segments.all(String::isSafeScopedTestPathSegment)) {
        ScopedTestPathKind.AppApi
    } else {
        null
    }
}

private fun List<String>.isSafeScopedTestDavCollection(): Boolean {
    val hasRecognizedShape = when {
        size == 6 -> take(4) == listOf("remote.php", "dav", "addressbooks", "users")
        size == 5 -> take(3) == listOf("remote.php", "dav", "calendars")
        else -> false
    }
    return hasRecognizedShape && all(String::isSafeScopedTestPathSegment)
}

private fun String.isSafeScopedTestPathSegment(): Boolean =
    this !in setOf(".", "..") &&
        isNotEmpty() &&
        all { character ->
            character.isLetterOrDigit() || character in "._~-"
        }

private enum class ScopedTestPathKind {
    AppApi,
    DavCollection,
    FilesFolder,
}

private fun String.isApiVersionSegment(): Boolean =
    matches(Regex("v?[0-9]+(?:\\.[0-9]+)*", RegexOption.IGNORE_CASE))

private fun String.isSafeServerBasePath(): Boolean =
    isEmpty() ||
        (
            startsWith('/') &&
                !contains('%') &&
                !contains('\\') &&
                !contains("//") &&
                split('/').filter(String::isNotEmpty).all { segment ->
                    segment !in setOf(".", "..")
                }
            )

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    else -> -1
}

private val SCOPED_TEST_MUTATION_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
private val SCOPED_TEST_FILES_MUTATION_METHODS = setOf("PUT", "DELETE", "MKCOL", "MOVE", "COPY")
