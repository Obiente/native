package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Reviewed adapter for the Nextcloud trash bin WebDAV API.
 *
 * https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/trashbin.html
 *
 * - List: PROPFIND Depth 1 on /remote.php/dav/trashbin/{user}/trash
 * - Restore: MOVE an item to /remote.php/dav/trashbin/{user}/restore/{item}. The server
 *   restores it to its original location and picks a new name instead of overwriting.
 * - Delete permanently: DELETE the item. Empty: DELETE the trash collection.
 *
 * Availability comes from `capabilities.files.undelete`. Item addresses are taken verbatim from
 * the server's href as one encoded segment and are never built from the display name.
 */

data class NextcloudTrashItem(
    /** Encoded DAV segment of the item inside the user's trash collection. Opaque identity. */
    val key: String,
    val name: String,
    /** Path relative to the user's Files root that the item was deleted from. */
    val originalLocation: String?,
    val deletedAtEpochSeconds: Long?,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val mimeType: String?,
    val fileId: Long?,
    val deletedByDisplayName: String?,
) {
    init {
        require(isSafeEncodedFilesDavSegment(key)) { "The trash item address is invalid." }
        require(name.isSafeTrashText(MAX_TRASH_NAME_LENGTH)) { "The trash item name is invalid." }
    }

    /** Folder the item returns to, or null when the server did not report it. */
    val originalFolder: String?
        get() = originalLocation?.trim('/')?.substringBeforeLast('/', missingDelimiterValue = "")
}

sealed interface TrashbinAvailability {
    data object Unknown : TrashbinAvailability
    data class Available(val permanentDeleteAllowed: Boolean) : TrashbinAvailability
    /** The Deleted files app is disabled or this account has no trash bin. */
    data object NotEnabled : TrashbinAvailability
}

data class TrashbinListing(
    val items: List<NextcloudTrashItem>,
    /** True when the server listed more items than this client keeps in memory. */
    val truncated: Boolean,
    /** Entries the client could not read safely and therefore does not offer actions for. */
    val skippedEntries: Int,
)

internal fun trashbinCapabilitiesRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/ocs/v2.php/cloud/capabilities",
    ocsApiRequest = true,
    maximumResponseBytes = MAX_TRASH_CAPABILITIES_BYTES,
    cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
)

/** Reads `files.undelete` and the optional `files.delete_from_trash` restriction. */
internal fun parseTrashbinAvailability(response: NextcloudApiResponse): TrashbinAvailability {
    val files = (parseOcsCapabilities(response, MAX_TRASH_CAPABILITIES_BYTES)["files"] as? JsonObject)
        ?: return TrashbinAvailability.NotEnabled
    val undelete = (files["undelete"] as? JsonPrimitive)?.booleanOrNull
    if (undelete != true) return TrashbinAvailability.NotEnabled
    val deleteAllowed = (files["delete_from_trash"] as? JsonPrimitive)?.booleanOrNull ?: true
    return TrashbinAvailability.Available(permanentDeleteAllowed = deleteAllowed)
}

fun trashbinListRequest(userId: String): FilesDavRequest = FilesDavRequest(
    method = FilesDavMethod.PROPFIND,
    relativePath = trashCollectionPath(userId) + "/",
    depth = 1,
    contentType = "application/xml; charset=utf-8",
    body = TRASHBIN_PROPFIND_BODY.encodeToByteArray(),
    maximumResponseBytes = MAX_TRASH_LIST_BYTES,
)

fun trashbinRestoreRequest(userId: String, item: NextcloudTrashItem): FilesDavRequest = FilesDavRequest(
    method = FilesDavMethod.MOVE,
    relativePath = "${trashCollectionPath(userId)}/${item.key}",
    destinationRelativePath = "${trashUserRoot(userId)}/restore/${item.key}",
)

fun trashbinDeleteRequest(userId: String, item: NextcloudTrashItem): FilesDavRequest = FilesDavRequest(
    method = FilesDavMethod.DELETE,
    relativePath = "${trashCollectionPath(userId)}/${item.key}",
)

fun trashbinEmptyRequest(userId: String): FilesDavRequest = FilesDavRequest(
    method = FilesDavMethod.DELETE,
    relativePath = trashCollectionPath(userId),
)

/** Parses the listing. Unreadable entries are skipped and counted instead of failing the list. */
fun parseTrashbinListing(response: NextcloudApiResponse, userId: String): TrashbinListing {
    val records = parseFilesDavMultistatus(response, MAX_TRASH_ITEMS)
    val encodedUser = encodeFilesDavSegment(userId)
    var skipped = 0
    val items = mutableListOf<NextcloudTrashItem>()
    for (record in records) {
        val segments = filesDavHrefSegments(record.href)
        if (segments == null || segments.size < 3 || segments[0] != "trashbin" || segments[2] != "trash") {
            skipped++
            continue
        }
        if (!segments[1].equals(encodedUser, ignoreCase = true) && segments[1] != userId) {
            skipped++
            continue
        }
        if (segments.size == 3) continue
        if (segments.size != 4) {
            skipped++
            continue
        }
        val item = record.toTrashItemOrNull(segments[3])
        if (item == null) skipped++ else items += item
    }
    val unique = items.distinctBy(NextcloudTrashItem::key)
    skipped += items.size - unique.size
    val sorted = unique.sortedWith(
        compareByDescending<NextcloudTrashItem> { it.deletedAtEpochSeconds ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() },
    )
    return TrashbinListing(
        items = sorted.take(MAX_TRASH_ITEMS),
        truncated = sorted.size > MAX_TRASH_ITEMS,
        skippedEntries = skipped,
    )
}

private fun FilesDavResponseRecord.toTrashItemOrNull(key: String): NextcloudTrashItem? {
    if (!isSafeEncodedFilesDavSegment(key)) return null
    val name = text("trashbin-filename")?.takeIf { it.isSafeTrashText(MAX_TRASH_NAME_LENGTH) && '/' !in it } ?: return null
    val location = text("trashbin-original-location")
        ?.takeIf { it.isSafeTrashText(MAX_TRASH_LOCATION_LENGTH) && it.split('/').none { segment -> segment == ".." } }
    return NextcloudTrashItem(
        key = key,
        name = name,
        originalLocation = location,
        deletedAtEpochSeconds = text("trashbin-deletion-time")?.toLongOrNull()?.takeIf { it >= 0L },
        isDirectory = properties["resourcetype"]?.containsXmlElement("collection") == true,
        sizeBytes = (text("size") ?: text("getcontentlength"))?.toLongOrNull()?.takeIf { it >= 0L },
        mimeType = text("getcontenttype")?.takeIf { it.length <= 255 && it.none(Char::isISOControl) },
        fileId = text("fileid")?.toLongOrNull()?.takeIf { it > 0L },
        deletedByDisplayName = text("trashbin-deleted-by-display-name")?.takeIf { it.isSafeTrashText(MAX_TRASH_NAME_LENGTH) },
    )
}

/** Parses `ocs.data.capabilities` from a capabilities response, failing closed when malformed. */
internal fun parseOcsCapabilities(response: NextcloudApiResponse, maximumBytes: Long): JsonObject {
    if (response.body.size > maximumBytes) throw FilesDavProtocolException("The capabilities response is too large.")
    val root = try {
        kotlinx.serialization.json.Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw FilesDavProtocolException("The capabilities response is malformed.")
    val data = (root["ocs"] as? JsonObject)?.get("data")
    val capabilities = when (data) {
        is JsonObject -> data["capabilities"]
        else -> null
    }
    return when (capabilities) {
        is JsonObject -> capabilities
        is JsonArray -> if (capabilities.isEmpty()) JsonObject(emptyMap()) else null
        else -> null
    } ?: throw FilesDavProtocolException("The capabilities response has no capabilities.")
}

private fun trashUserRoot(userId: String): String {
    require(userId.isNotBlank() && userId.length <= 255 && userId.none(Char::isISOControl)) {
        "The Nextcloud user is invalid."
    }
    return "${FILES_DAV_ROOT}trashbin/${encodeFilesDavSegment(userId)}"
}

private fun trashCollectionPath(userId: String): String = "${trashUserRoot(userId)}/trash"

private fun String.isSafeTrashText(maximumLength: Int): Boolean =
    isNotBlank() && length <= maximumLength && none { it.isISOControl() }

internal const val MAX_TRASH_ITEMS = 2_000
private const val MAX_TRASH_NAME_LENGTH = 1_024
private const val MAX_TRASH_LOCATION_LENGTH = 4_096
private const val MAX_TRASH_LIST_BYTES = 8L * 1024L * 1024L
private const val MAX_TRASH_CAPABILITIES_BYTES = 2L * 1024L * 1024L

private val TRASHBIN_PROPFIND_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
      <d:prop>
        <nc:trashbin-filename/><nc:trashbin-original-location/><nc:trashbin-deletion-time/>
        <nc:trashbin-deleted-by-display-name/><d:getcontentlength/><d:getcontenttype/>
        <d:resourcetype/><oc:fileid/><oc:size/>
      </d:prop>
    </d:propfind>
""".trimIndent()
