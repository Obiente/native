package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Notes app settings through the documented Notes API v1 (`GET` and `PUT /settings`, API 1.2+).
 *
 * Source: https://github.com/nextcloud/notes/blob/main/docs/api/v1.md#settings. `notesPath` is
 * relative to the user's Files root. `fileSuffix` accepts `.md` or `.txt` on API 1.2 and a
 * custom suffix since API 1.3. The server sanitizes every value and returns the stored result,
 * so the client always shows the server's answer and never assumes its own request was kept.
 * Supported API versions come from the `notes.api_version` capability.
 */
data class NotesApiCapabilities(
    val appVersion: String?,
    val apiVersions: List<String>,
) {
    private val highest: Pair<Int, Int>?
        get() = apiVersions.mapNotNull(::parseNotesApiVersion).filter { it.first == 1 }
            .maxWithOrNull(compareBy({ it.first }, { it.second }))

    val supportsSettings: Boolean
        get() = highest?.let { it.second >= 2 } == true

    val supportsCustomSuffix: Boolean
        get() = highest?.let { it.second >= 3 } == true
}

data class NotesSettings(
    val notesPath: String,
    val fileSuffix: String,
)

data class NotesSettingsChange(
    val notesPath: String? = null,
    val fileSuffix: String? = null,
) {
    init {
        require(notesPath != null || fileSuffix != null) { "Choose a setting to change." }
    }
}

fun notesCapabilitiesRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/ocs/v2.php/cloud/capabilities",
    queryParameters = mapOf("format" to "json"),
    ocsApiRequest = true,
    maximumResponseBytes = NOTES_CAPABILITIES_RESPONSE_BYTES,
)

fun parseNotesApiCapabilities(response: NextcloudApiResponse): NotesApiCapabilities? {
    require(response.status in 200..299) { "Server capabilities failed to load (HTTP ${response.status})." }
    require(response.body.size <= NOTES_CAPABILITIES_RESPONSE_BYTES) { "Server capabilities are too large." }
    val root = runCatching { notesSettingsJson.parseToJsonElement(response.body.decodeToString()) }
        .getOrNull() as? JsonObject ?: error("Server capabilities returned invalid JSON.")
    val capabilities = ((root["ocs"] as? JsonObject)?.get("data") as? JsonObject)?.get("capabilities") as? JsonObject
        ?: error("Server capabilities returned an unexpected response.")
    val notes = capabilities["notes"] as? JsonObject ?: return null
    val versions = (notes["api_version"] as? JsonArray).orEmpty().mapNotNull { value ->
        if (value is JsonNull) null else runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
    }
    return NotesApiCapabilities(
        appVersion = (notes["version"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
        apiVersions = versions,
    )
}

fun notesSettingsRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = NOTES_SETTINGS_PATH,
    maximumResponseBytes = NOTES_SETTINGS_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
)

fun updateNotesSettingsRequest(
    capabilities: NotesApiCapabilities,
    change: NotesSettingsChange,
): NextcloudApiRequest {
    require(capabilities.supportsSettings) { "This Notes version cannot change settings from other apps." }
    val path = change.notesPath?.let(::normalizeNotesFolderSetting)
    val suffix = change.fileSuffix?.let { normalizeNotesFileSuffix(it, capabilities.supportsCustomSuffix) }
    val body = buildJsonObject {
        path?.let { put("notesPath", it) }
        suffix?.let { put("fileSuffix", it) }
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.PUT,
        relativePath = NOTES_SETTINGS_PATH,
        contentType = "application/json; charset=utf-8",
        body = body.toString().encodeToByteArray(),
        maximumResponseBytes = NOTES_SETTINGS_RESPONSE_BYTES,
    )
}

fun parseNotesSettings(response: NextcloudApiResponse): NotesSettings {
    require(response.status != 400) { "This Notes version does not support settings from other apps." }
    require(response.status in 200..299) { "Notes settings failed (HTTP ${response.status})." }
    require(response.body.size <= NOTES_SETTINGS_RESPONSE_BYTES) { "Notes settings returned too much data." }
    val root = runCatching { notesSettingsJson.parseToJsonElement(response.body.decodeToString()) }
        .getOrNull() as? JsonObject ?: error("Notes settings returned invalid JSON.")
    fun text(name: String): String? = root[name]?.let { value ->
        if (value is JsonNull) null else runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
    }
    return NotesSettings(
        notesPath = text("notesPath") ?: error("Notes settings have no notes folder."),
        fileSuffix = text("fileSuffix") ?: error("Notes settings have no file suffix."),
    )
}

/** Notes folder paths are relative to the Files root; the server still sanitizes the value. */
fun normalizeNotesFolderSetting(value: String): String {
    val trimmed = value.trim().trim('/')
    require(trimmed.isNotEmpty()) { "Choose a folder for your notes." }
    return requireNotNull(canonicalRemoteFolderPath(trimmed)) { "The notes folder path is invalid." }
}

fun normalizeNotesFileSuffix(value: String, customAllowed: Boolean): String {
    val suffix = value.trim().lowercase()
    if (suffix in NOTES_STANDARD_SUFFIXES) return suffix
    require(customAllowed) { "This Notes version only supports .md and .txt files." }
    require(suffix.length in 2..NOTES_MAX_SUFFIX_LENGTH && suffix.startsWith('.')) {
        "Use a suffix that starts with a dot, such as .markdown."
    }
    require(suffix.drop(1).all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
        "The file suffix can only contain letters, digits, dots, dashes, and underscores."
    }
    return suffix
}

/** Describes server-side sanitizing so the user sees what was actually stored. */
fun describeNotesSettingsAdjustment(change: NotesSettingsChange, stored: NotesSettings): String? = buildList {
    change.notesPath?.let { requested ->
        if (requested.trim().trim('/') != stored.notesPath.trim('/')) add("Notes stored the folder as \"${stored.notesPath}\".")
    }
    change.fileSuffix?.let { requested ->
        if (requested.trim().lowercase() != stored.fileSuffix) add("Notes stored the suffix as \"${stored.fileSuffix}\".")
    }
}.takeIf(List<String>::isNotEmpty)?.joinToString(" ")

private fun parseNotesApiVersion(value: String): Pair<Int, Int>? {
    val parts = value.trim().split('.')
    if (parts.size != 2) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    return major to minor
}

internal val NOTES_STANDARD_SUFFIXES = listOf(".md", ".txt")
private val notesSettingsJson = Json { ignoreUnknownKeys = true }
private const val NOTES_SETTINGS_PATH = "/index.php/apps/notes/api/v1/settings"
private const val NOTES_SETTINGS_RESPONSE_BYTES = 64L * 1024L
private const val NOTES_CAPABILITIES_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val NOTES_MAX_SUFFIX_LENGTH = 16
