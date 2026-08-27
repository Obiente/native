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
    val ownedPathMarker = "/apps/${appId.lowercase()}"
    fun String.referencesOwnedApp(): Boolean {
        val normalized = lowercase().substringBefore('?').substringBefore('#').trimEnd('/')
        return normalized == ownedPathMarker || "$ownedPathMarker/" in normalized
    }
    val servers = root.optJSONArray("servers")
    if (
        servers != null &&
        (0 until servers.length()).asSequence()
            .mapNotNull(servers::optJSONObject)
            .map { server -> server.optString("url") }
            .any { serverUrl -> serverUrl.referencesOwnedApp() }
    ) {
        return true
    }
    val paths = root.optJSONObject("paths")?.keys()?.asSequence()?.toList().orEmpty()
    if (paths.any { path -> path.referencesOwnedApp() }) return true
    return normalizedSpecPath in OPEN_API_FILE_PREFERENCE && paths.areAppRelativeApiPaths()
}

internal fun OpenApiCandidate.withProvenAppServerBase(appId: String): OpenApiCandidate {
    val root = runCatching { JSONObject(document) }.getOrNull() ?: return this
    if (root.optJSONArray("servers") != null) return this
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
