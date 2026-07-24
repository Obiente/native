package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

enum class SystemTagAccess {
    Public,
    Restricted,
    Invisible,
}

/** A server-wide Nextcloud system tag and its effective permission for the current account. */
data class NextcloudSystemTag(
    val id: Long,
    val name: String,
    val userVisible: Boolean,
    val userAssignable: Boolean,
    val canAssign: Boolean,
    val color: String? = null,
    val etag: String? = null,
) {
    init {
        require(id > 0L) { "The system tag ID is invalid." }
        require(name.isNotBlank() && name.none(Char::isISOControl)) { "The system tag name is invalid." }
        require(color == null || color.length <= MAX_SYSTEM_TAG_COLOR_LENGTH) { "The system tag color is invalid." }
        require(etag == null || ('\r' !in etag && '\n' !in etag)) { "The system tag ETag is invalid." }
    }

    val access: SystemTagAccess
        get() = when {
            !userVisible -> SystemTagAccess.Invisible
            userAssignable -> SystemTagAccess.Public
            else -> SystemTagAccess.Restricted
        }
}

/**
 * Transport-neutral PROPFIND request for the official system-tags DAV collection.
 *
 * Authentication is intentionally absent. A platform transport must attach the active account and
 * keep this request on the connected Nextcloud origin.
 */
data class SystemTagsDavDiscoveryRequest(
    val method: String,
    val relativePath: String,
    val depth: Int,
    val contentType: String,
    val body: ByteArray,
)

fun systemTagsDavDiscoveryRequest(): SystemTagsDavDiscoveryRequest = SystemTagsDavDiscoveryRequest(
    method = "PROPFIND",
    relativePath = "/remote.php/dav/systemtags",
    depth = 1,
    contentType = "application/xml; charset=utf-8",
    body = SYSTEM_TAGS_PROPFIND_BODY.encodeToByteArray(),
)

data class MemoriesPhotoTagNames(
    val fileId: Long,
    val etag: String?,
    val names: List<String>,
) {
    init {
        require(fileId > 0L) { "The photo file ID is invalid." }
        require(etag == null || ('\r' !in etag && '\n' !in etag)) { "The photo ETag is invalid." }
        require(names.all { it.isNotBlank() && it.none(Char::isISOControl) }) {
            "The photo tag names are invalid."
        }
        require(names.distinct().size == names.size) { "The photo tag names contain duplicates." }
    }
}

data class ResolvedMemoriesPhotoTags(
    val availableTags: List<NextcloudSystemTag>,
    val currentTags: List<NextcloudSystemTag>,
    val unresolvedNames: List<String>,
    val ambiguousNames: Set<String>,
) {
    val currentTagIds: Set<Long> get() = currentTags.mapTo(linkedSetOf(), NextcloudSystemTag::id)
}

/**
 * Resolves Memories' name-only photo tags against the DAV tag catalog without guessing.
 * Duplicate DAV display names remain disabled and unresolved so saving cannot remove or replace
 * an assignment whose numeric ID is unknowable from the Memories response.
 */
fun resolveMemoriesPhotoTags(
    availableTags: List<NextcloudSystemTag>,
    currentNames: List<String>,
): ResolvedMemoriesPhotoTags {
    val uniqueById = availableTags.uniqueBySystemTagId().values.toList()
    val byName = uniqueById.groupBy(NextcloudSystemTag::name)
    val current = mutableListOf<NextcloudSystemTag>()
    val unresolved = mutableListOf<String>()
    val ambiguous = mutableSetOf<String>()
    currentNames.distinct().forEach { name ->
        val matches = byName[name].orEmpty()
        when (matches.size) {
            1 -> current += matches.single()
            0 -> unresolved += name
            else -> {
                unresolved += name
                ambiguous += name
            }
        }
    }
    return ResolvedMemoriesPhotoTags(
        availableTags = uniqueById.sortedWith(
            compareBy<NextcloudSystemTag> { it.name.lowercase() }.thenBy(NextcloudSystemTag::id),
        ),
        currentTags = current,
        unresolvedNames = unresolved.sortedBy(String::lowercase),
        ambiguousNames = ambiguous,
    )
}

/** Requests only basic image information plus the current visible system-tag names. */
fun memoriesPhotoTagNamesRequest(fileId: Long): NextcloudApiRequest {
    require(fileId > 0L) { "The photo file ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/image/info/$fileId",
        queryParameters = mapOf("basic" to "1", "tags" to "1"),
        // Memories' browser-facing route retains Nextcloud's CSRF middleware even for GET.
        // Authenticated native clients use the standard OCS API marker instead of a web-session token.
        ocsApiRequest = true,
    ).requireSafe()
}

fun parseMemoriesPhotoTagNamesResponse(
    response: NextcloudApiResponse,
    expectedFileId: Long,
): MemoriesPhotoTagNames {
    require(expectedFileId > 0L) { "The expected photo file ID is invalid." }
    require(response.status in 200..299) {
        val serverMessage = runCatching {
            (systemTagsJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject)
                ?.get("message")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.getOrNull()
        buildString {
            append("Memories rejected the photo tag read (HTTP ${response.status})")
            if (serverMessage != null) append(": $serverMessage")
            append('.')
        }
    }
    val root = runCatching {
        systemTagsJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    }.getOrNull() ?: error("The Memories photo tag response is not a JSON object.")
    val fileId = (root["fileid"] as? JsonPrimitive)?.longOrNull
        ?: error("The Memories photo tag response has no file ID.")
    require(fileId == expectedFileId) { "Memories returned tags for a different photo." }
    val tagNames = when (val tags = root["tags"]) {
        is JsonArray -> tags.map(::parsePhotoTagName)
        is JsonObject -> tags.values.map(::parsePhotoTagName)
        else -> error("The Memories photo tag response has no tag-name collection.")
    }.distinct().sortedBy { it.lowercase() }
    val etag = (root["etag"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    return MemoriesPhotoTagNames(fileId = fileId, etag = etag, names = tagNames)
}

/** Raw properties from one successful DAV response element. */
data class SystemTagDavRecord(
    val href: String,
    val id: String?,
    val displayName: String?,
    val userVisible: String?,
    val userAssignable: String?,
    val canAssign: String?,
    val color: String? = null,
    val etag: String? = null,
)

/**
 * Converts a DAV response element into a tag. The collection response itself has no tag ID and is
 * ignored; malformed child responses fail instead of silently widening permissions.
 */
fun SystemTagDavRecord.toNextcloudSystemTagOrNull(): NextcloudSystemTag? {
    if (id == null && displayName == null) return null
    val numericId = id?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        ?: error("The system tag response has an invalid ID.")
    val name = displayName?.trim()?.takeIf(String::isNotEmpty)
        ?: error("The system tag response has no display name.")
    return NextcloudSystemTag(
        id = numericId,
        name = name,
        userVisible = parseDavBoolean("user-visible", userVisible),
        userAssignable = parseDavBoolean("user-assignable", userAssignable),
        canAssign = parseDavBoolean("can-assign", canAssign),
        color = color?.trim()?.takeIf(String::isNotEmpty),
        etag = etag?.trim()?.removeSurrounding("\"")?.takeIf(String::isNotEmpty),
    )
}

data class SystemTagsDavDiscoveryResponse(
    val tags: List<NextcloudSystemTag>,
) {
    init {
        require(tags.map(NextcloudSystemTag::id).distinct().size == tags.size) {
            "The system tag response contains duplicate IDs."
        }
    }
}

fun normalizeSystemTagsDavResponse(records: List<SystemTagDavRecord>): SystemTagsDavDiscoveryResponse =
    SystemTagsDavDiscoveryResponse(
        tags = records.mapNotNull(SystemTagDavRecord::toNextcloudSystemTagOrNull)
            .sortedWith(compareBy<NextcloudSystemTag> { it.name.lowercase() }.thenBy(NextcloudSystemTag::id)),
    )

@Serializable
data class MemoriesTagPatchPayload(
    val add: List<Long>,
    val remove: List<Long>,
) {
    init {
        require(add.all { it > 0L } && remove.all { it > 0L }) { "System tag IDs must be positive." }
        require(add.distinct().size == add.size && remove.distinct().size == remove.size) {
            "System tag changes cannot contain duplicates."
        }
        require(add.toSet().intersect(remove.toSet()).isEmpty()) {
            "A system tag cannot be added and removed in the same request."
        }
        require(add.isNotEmpty() || remove.isNotEmpty()) { "The system tag selection has not changed." }
    }
}

data class MemoriesTagUpdatePlan(
    val fileId: Long,
    val payload: MemoriesTagPatchPayload,
) {
    init {
        require(fileId > 0L) { "The file ID is invalid." }
    }

    fun toNextcloudApiRequest(): NextcloudApiRequest = NextcloudApiRequest(
        method = NextcloudApiMethod.PATCH,
        relativePath = "/index.php/apps/memories/api/tags/set/$fileId",
        contentType = "application/json",
        body = systemTagsJson.encodeToString(payload).encodeToByteArray(),
        ocsApiRequest = true,
    ).requireSafe()
}

/**
 * Computes a minimal tag diff and rejects changes to tags the current account cannot assign.
 * Unchanged restricted tags are preserved without requiring assignment permission.
 */
fun planMemoriesTagUpdate(
    fileId: Long,
    currentTags: List<NextcloudSystemTag>,
    selectedTags: List<NextcloudSystemTag>,
): MemoriesTagUpdatePlan {
    require(fileId > 0L) { "The file ID is invalid." }
    val currentById = currentTags.uniqueBySystemTagId()
    val selectedById = selectedTags.uniqueBySystemTagId()
    val added = selectedById.keys - currentById.keys
    val removed = currentById.keys - selectedById.keys
    val changedTags = added.map(selectedById::getValue) + removed.map(currentById::getValue)
    val blocked = changedTags.filterNot(NextcloudSystemTag::canAssign)
    require(blocked.isEmpty()) {
        "The current account cannot change: ${blocked.joinToString { it.name }}."
    }
    return MemoriesTagUpdatePlan(
        fileId = fileId,
        payload = MemoriesTagPatchPayload(add = added.sorted(), remove = removed.sorted()),
    )
}

/** Memories returns an empty JSON array when a tag patch is accepted. */
data class MemoriesTagUpdateResponse(val status: Int)

fun parseMemoriesTagUpdateResponse(response: NextcloudApiResponse): MemoriesTagUpdateResponse {
    require(response.status in 200..299) { "Memories rejected the tag update (HTTP ${response.status})." }
    val document = runCatching { systemTagsJson.parseToJsonElement(response.body.decodeToString()) }.getOrNull()
        ?: error("The Memories tag response is not valid JSON.")
    require(document is JsonArray && document.isEmpty()) { "The Memories tag response has an unknown shape." }
    return MemoriesTagUpdateResponse(response.status)
}

private fun List<NextcloudSystemTag>.uniqueBySystemTagId(): Map<Long, NextcloudSystemTag> {
    val result = associateBy(NextcloudSystemTag::id)
    require(result.size == size) { "The system tag selection contains duplicate IDs." }
    return result
}

private fun parseDavBoolean(property: String, value: String?): Boolean = when (value?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> error("The system tag response has an invalid $property value.")
}

private fun parsePhotoTagName(value: kotlinx.serialization.json.JsonElement): String {
    val name = (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        ?: error("The Memories photo tag response contains an invalid tag name.")
    require(name.none(Char::isISOControl)) { "The Memories photo tag response contains an invalid tag name." }
    return name
}

private val systemTagsJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private const val MAX_SYSTEM_TAG_COLOR_LENGTH = 64

private val SYSTEM_TAGS_PROPFIND_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
      <d:prop>
        <d:resourcetype />
        <d:getetag />
        <oc:id />
        <oc:display-name />
        <oc:user-visible />
        <oc:user-assignable />
        <oc:can-assign />
        <nc:color />
      </d:prop>
    </d:propfind>
""".trimIndent()
