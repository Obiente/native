package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudConditionalRead
import dev.obiente.nextcloudnative.app.NextcloudDocumentCreatorCapability
import dev.obiente.nextcloudnative.app.NextcloudDocumentEditingCapabilities
import dev.obiente.nextcloudnative.app.NextcloudDocumentEditorCapability
import dev.obiente.nextcloudnative.app.NextcloudDocumentEditSession
import dev.obiente.nextcloudnative.app.NextcloudDocumentEditSessionRequest
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val ANDROID_DIRECT_EDITING_INFO_RELATIVE_PATH =
    "/ocs/v2.php/apps/files/api/v1/directEditing?format=json"

internal const val ANDROID_NEXTCLOUD_CAPABILITIES_RELATIVE_PATH =
    "/ocs/v1.php/cloud/capabilities?format=json"

internal const val ANDROID_DIRECT_EDITING_OPEN_RELATIVE_PATH =
    "/ocs/v2.php/apps/files/api/v1/directEditing/open?format=json"

private const val MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES = 512L * 1024L
private const val MAX_DOCUMENT_EDIT_SESSION_RESPONSE_BYTES = 64L * 1024L
private val TRUSTED_ANDROID_DIRECT_EDITING_EDITOR_IDS = setOf("richdocuments", "whiteboard")

internal data class AndroidDocumentEditingHttpRequest(
    val method: String,
    val relativePath: String,
    val body: String? = null,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxResponseBytes: Long,
)

internal data class AndroidDocumentEditingHttpResponse(
    val status: Int,
    val body: String,
    val etag: String?,
    val location: String?,
)

internal class AndroidDocumentEditingTransport(
    private val execute: (NextcloudSession, AndroidDocumentEditingHttpRequest) -> AndroidDocumentEditingHttpResponse,
) {
    suspend fun loadCapabilities(
        session: NextcloudSession,
        expectedEtag: String?,
        cachedCapabilities: NextcloudDocumentEditingCapabilities?,
    ): NextcloudConditionalRead<NextcloudDocumentEditingCapabilities> = withContext(Dispatchers.IO) {
        val conditionalInventory = execute(
            session,
            AndroidDocumentEditingHttpRequest(
                method = "GET",
                relativePath = ANDROID_DIRECT_EDITING_INFO_RELATIVE_PATH,
                headers = androidDocumentEditingConditionalHeaders(expectedEtag),
                maxResponseBytes = MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES,
            ),
        )
        val inventory = if (conditionalInventory.status == 304 && cachedCapabilities == null) {
            execute(
                session,
                AndroidDocumentEditingHttpRequest(
                    method = "GET",
                    relativePath = ANDROID_DIRECT_EDITING_INFO_RELATIVE_PATH,
                    maxResponseBytes = MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES,
                ),
            )
        } else {
            conditionalInventory.takeUnless { response -> response.status == 304 }
        }
        if (inventory != null) {
            check(inventory.status in 200..299 && inventory.location == null) {
                "Loading document editing capabilities failed (HTTP ${inventory.status})."
            }
        }
        val capabilitiesResponse = execute(
            session,
            AndroidDocumentEditingHttpRequest(
                method = "GET",
                relativePath = ANDROID_NEXTCLOUD_CAPABILITIES_RELATIVE_PATH,
                maxResponseBytes = MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES,
            ),
        )
        check(capabilitiesResponse.status in 200..299 && capabilitiesResponse.location == null) {
            "Loading direct-editing support failed (HTTP ${capabilitiesResponse.status})."
        }
        val supportsFileId = parseAndroidDirectEditingSupportsFileId(capabilitiesResponse.body)
        val combined = inventory?.let { response ->
            parseAndroidDocumentEditingCapabilities(response.body, supportsFileId)
        } ?: requireNotNull(cachedCapabilities).copy(supportsFileId = supportsFileId)
        NextcloudConditionalRead.Modified(combined, inventory?.etag ?: expectedEtag)
    }

    suspend fun beginSession(
        session: NextcloudSession,
        request: NextcloudDocumentEditSessionRequest,
    ): NextcloudDocumentEditSession = withContext(Dispatchers.IO) {
        val response = execute(
            session,
            AndroidDocumentEditingHttpRequest(
                method = "POST",
                relativePath = ANDROID_DIRECT_EDITING_OPEN_RELATIVE_PATH,
                body = androidDirectEditingOpenForm(request),
                contentType = "application/x-www-form-urlencoded",
                maxResponseBytes = MAX_DOCUMENT_EDIT_SESSION_RESPONSE_BYTES,
            ),
        )
        check(response.status in 200..299 && response.location == null) {
            "Starting the Office edit session failed (HTTP ${response.status})."
        }
        val candidate = JSONObject(response.body)
            .getJSONObject("ocs")
            .getJSONObject("data")
            .getString("url")
        NextcloudDocumentEditSession(
            validatedAndroidDirectEditingHandoffUrl(session.serverUrl, candidate),
        )
    }
}

internal fun androidDocumentEditingConditionalHeaders(expectedEtag: String?): Map<String, String> =
    expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-None-Match" to it) }.orEmpty()

internal fun parseAndroidDocumentEditingCapabilities(
    body: String,
    supportsFileId: Boolean,
): NextcloudDocumentEditingCapabilities {
    val data = JSONObject(body).getJSONObject("ocs").getJSONObject("data")
    val editorObject = data.optJSONObject("editors") ?: JSONObject()
    val creatorObject = data.optJSONObject("creators") ?: JSONObject()
    val editors = editorObject.keys().asSequence().mapNotNull { key ->
        val item = editorObject.optJSONObject(key) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        id to NextcloudDocumentEditorCapability(
            id = id,
            displayName = item.optString("name").ifBlank { id },
            mimeTypes = item.optJSONArray("mimetypes").toAndroidStringSet(),
            optionalMimeTypes = item.optJSONArray("optionalMimetypes").toAndroidStringSet(),
            secure = item.optBoolean("secure", false),
        )
    }.toMap()
    val creators = creatorObject.keys().asSequence().mapNotNull { key ->
        val item = creatorObject.optJSONObject(key) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val editorId = item.optString("editor").takeIf(String::isNotBlank) ?: return@mapNotNull null
        id to NextcloudDocumentCreatorCapability(
            id = id,
            editorId = editorId,
            displayName = item.optString("name").ifBlank { id },
            extension = item.optString("extension"),
            templates = item.optBoolean("templates", false),
            mimeType = item.optString("mimetype").takeIf(String::isNotBlank)
                ?: item.optJSONArray("mimetypes")?.optString(0)?.takeIf(String::isNotBlank),
        )
    }.toMap()
    return NextcloudDocumentEditingCapabilities(editors, creators, supportsFileId)
}

internal fun parseAndroidDirectEditingSupportsFileId(body: String): Boolean =
    JSONObject(body)
        .getJSONObject("ocs")
        .getJSONObject("data")
        .getJSONObject("capabilities")
        .optJSONObject("files")
        ?.optJSONObject("directEditing")
        ?.optBoolean("supportsFileId", false)
        ?: false

internal fun androidDirectEditingOpenForm(request: NextcloudDocumentEditSessionRequest): String {
    require(request.path.isSafeAndroidDocumentLookupPath()) { "The document path is unsafe." }
    require(request.fileId >= 0L) { "The document ID is invalid." }
    require(request.editorId in TRUSTED_ANDROID_DIRECT_EDITING_EDITOR_IDS) {
        "The document editor is not trusted."
    }
    require(request.expectedEtag.isNotBlank()) { "The document version is missing." }
    return listOf(
        "path" to request.path,
        "editorId" to request.editorId,
        "fileId" to request.fileId.toString(),
    ).joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}

internal fun validatedAndroidDirectEditingHandoffUrl(serverUrl: String, candidate: String): String {
    require(candidate.isNotBlank() && candidate.none(Char::isISOControl)) {
        "Nextcloud returned an invalid direct-editing handoff."
    }
    val server = URI(serverUrl.trimEnd('/') + "/")
    require(server.scheme.equals("https", ignoreCase = true) && !server.host.isNullOrBlank()) {
        "The Nextcloud account origin is invalid."
    }
    val resolved = server.resolve(candidate)
    require(
        resolved.scheme.equals(server.scheme, ignoreCase = true) &&
            resolved.host.equals(server.host, ignoreCase = true) &&
            resolved.effectiveAndroidDirectEditingPort() == server.effectiveAndroidDirectEditingPort() &&
            resolved.userInfo == null &&
            resolved.rawQuery == null &&
            resolved.rawFragment == null,
    ) {
        "Nextcloud returned a cross-origin direct-editing handoff."
    }
    val basePath = server.rawPath.trimEnd('/')
    val routePrefix = listOf(
        "$basePath/apps/files/directEditing/",
        "$basePath/index.php/apps/files/directEditing/",
    ).firstOrNull { prefix -> resolved.rawPath.startsWith(prefix) }.orEmpty()
    val token = resolved.rawPath.removePrefix(routePrefix)
    require(
        routePrefix.isNotEmpty() &&
            token.isNotBlank() &&
            '/' !in token &&
            '\\' !in token &&
            !token.contains("%2e", ignoreCase = true) &&
            !token.contains("%2f", ignoreCase = true) &&
            !token.contains("%5c", ignoreCase = true),
    ) {
        "Nextcloud returned an unexpected direct-editing handoff route."
    }
    return resolved.toASCIIString()
}

private fun String.isSafeAndroidDocumentLookupPath(): Boolean =
    this == "/" ||
        (
            isNotBlank() &&
                length <= 4_096 &&
                !startsWith('/') &&
                none(Char::isISOControl) &&
                split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
            )

private fun URI.effectiveAndroidDirectEditingPort(): Int = if (port >= 0) port else when {
    scheme.equals("https", ignoreCase = true) -> 443
    scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun JSONArray?.toAndroidStringSet(): Set<String> = buildSet {
    val source = this@toAndroidStringSet ?: return@buildSet
    for (index in 0 until source.length()) {
        source.optString(index).trim().lowercase().takeIf(String::isNotBlank)?.let(::add)
    }
}
