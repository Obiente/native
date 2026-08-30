package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

internal data class OpenApiCandidate(
    val path: String,
    val document: String,
    val apiVersion: String?,
    val sourceUrl: String = "",
)

internal fun selectOpenApiCandidate(
    candidates: List<OpenApiCandidate>,
    appId: String,
): OpenApiCandidate? = candidates
    .filter { candidate ->
        isAppOwnedOpenApiDocument(appId, candidate.path, candidate.document)
    }
    .sortedWith { left, right ->
        val preference = openApiPreference(left.path).compareTo(openApiPreference(right.path))
        if (preference != 0) {
            preference
        } else {
            val version = compareSemanticVersions(right.apiVersion.orEmpty(), left.apiVersion.orEmpty())
            if (version != 0) version else left.path.compareTo(right.path)
        }
    }.firstOrNull()

internal fun isAppOwnedOpenApiDocument(
    appId: String,
    specPath: String,
    document: String,
): Boolean {
    if (!appId.matches(APP_ID_PATTERN)) return false
    val normalizedSpecPath = specPath.lowercase().trimStart('/')
    if (normalizedSpecPath.split('/').any(THIRD_PARTY_SPEC_PATH_SEGMENTS::contains)) return false
    val root = runCatching { JSONObject(document) }.getOrNull() ?: return false
    if (!root.hasPortableOpenApiServers()) return false
    val ownedPathMarker = "/apps/${appId.lowercase()}"
    val ownedPathPrefixes = listOf("", "/index.php", "/ocs/v1.php", "/ocs/v2.php").map { it + ownedPathMarker }
    fun String.referencesOwnedApp(): Boolean {
        val normalized = lowercase().substringBefore('?').substringBefore('#').trimEnd('/')
        return ownedPathPrefixes.any { normalized == it || normalized.startsWith("$it/") }
    }
    val servers = root.optJSONArray("servers")
    if (
        servers != null &&
        (0 until servers.length()).asSequence()
            .mapNotNull(servers::optJSONObject)
            .map { server -> server.optString("url") }
            .any { serverUrl -> portableOpenApiServerPath(serverUrl)?.referencesOwnedApp() == true }
    ) {
        return true
    }
    val paths = root.optJSONObject("paths")?.keys()?.asSequence()?.toList().orEmpty()
    if (paths.any { path -> path.referencesOwnedApp() }) return true
    return (servers == null || servers.length() == 0) &&
        normalizedSpecPath in OPEN_API_FILE_PREFERENCE && paths.areAppRelativeApiPaths()
}

private fun JSONObject.hasPortableOpenApiServers(): Boolean {
    val owners = mutableListOf(this)
    optJSONObject("paths")?.let { paths ->
        paths.keys().forEach { path ->
            paths.optJSONObject(path)?.let { item ->
                owners += item
                listOf("get", "put", "post", "delete", "options", "head", "patch", "trace").forEach { method ->
                    item.optJSONObject(method)?.let(owners::add)
                }
            }
        }
    }
    return owners.all { owner ->
        if (!owner.has("servers")) return@all true
        val servers = owner.optJSONArray("servers") ?: return@all false
        (0 until servers.length()).all server@{ index ->
            val url = servers.optJSONObject(index)?.opt("url") as? String ?: return@server false
            portableOpenApiServerPath(url) != null
        }
    }
}

private fun portableOpenApiServerPath(value: String): String? {
    if (value.isBlank() || value.length > 4_096 ||
        value.any { it.isWhitespace() || it.isISOControl() || it in "?#\\" }) return null
    val path = if (value.startsWith('/') && !value.startsWith("//")) {
        value
    } else {
        val separator = value.indexOf("://")
        if (separator <= 0 || value.substring(0, separator).lowercase() !in setOf("http", "https")) return null
        val remainder = value.substring(separator + 3)
        if (!remainder.substringBefore('/').matches(PORTABLE_OPEN_API_HOST)) return null
        remainder.indexOf('/').takeIf { it >= 0 }?.let(remainder::substring).orEmpty()
    }
    return path.takeIf { '{' !in it && '}' !in it && it.split('/').none { part -> part == "." || part == ".." } }
}

private val PORTABLE_OPEN_API_HOST =
    Regex("\\{[A-Za-z_][A-Za-z0-9_.-]*}(?::(?:[0-9]{1,5}|\\{[A-Za-z_][A-Za-z0-9_.-]*}))?")

internal fun OpenApiCandidate.withProvenAppServerBase(appId: String): OpenApiCandidate {
    val root = runCatching { JSONObject(document) }.getOrNull() ?: return this
    if (root.optJSONArray("servers")?.length()?.let { it > 0 } == true) return this
    val paths = root.optJSONObject("paths")?.keys()?.asSequence()?.toList().orEmpty()
    if (path.lowercase().trimStart('/') !in OPEN_API_FILE_PREFERENCE || !paths.areAppRelativeApiPaths()) {
        return this
    }
    root.put("servers", JSONArray().put(JSONObject().put("url", "/apps/$appId")))
    return copy(document = root.toString())
}

private fun List<String>.areAppRelativeApiPaths(): Boolean = isNotEmpty() && all { path ->
    path.equals("/api", ignoreCase = true) || path.startsWith("/api/", ignoreCase = true)
}

private fun openApiPreference(path: String): Int {
    val normalized = path.lowercase()
    return OPEN_API_FILE_PREFERENCE.indexOf(normalized).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
}

private val APP_ID_PATTERN = Regex("[A-Za-z0-9_.-]+")
private val OPEN_API_FILE_PREFERENCE = listOf(
    "openapi.json",
    "openapi.yaml",
    "openapi.yml",
    "openapi-full.json",
    "openapi-public.json",
)
private val THIRD_PARTY_SPEC_PATH_SEGMENTS = setOf("vendor", "node_modules", "third_party")
