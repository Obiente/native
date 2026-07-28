package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Collection families exposed by Memories using Photos albums and Nextcloud system tags. */
enum class NativeMediaCollectionType(val memoriesBackend: String) {
    Album("albums"),
    SystemTag("tags"),
}

data class NativeMediaCover(
    val fileId: Long,
    val etag: String?,
) {
    init {
        require(fileId > 0L) { "The media cover file ID is invalid." }
        require(etag == null || etag.isSafeMediaText(MAX_MEDIA_ETAG_LENGTH)) {
            "The media cover ETag is invalid."
        }
    }
}

/**
 * Stable, presentation-neutral collection record shared by albums and collaborative tags.
 *
 * [serverReference] remains an opaque query value. It is never interpolated into a URL path.
 */
data class NativeMediaCollection(
    val key: String,
    val type: NativeMediaCollectionType,
    val name: String,
    val serverReference: String?,
    val itemCount: Int?,
    val cover: NativeMediaCover?,
    val ownerUserId: String? = null,
    val ownerDisplayName: String? = null,
    val location: String? = null,
    val isShared: Boolean = false,
    val isHidden: Boolean = false,
    val createdAtEpochSeconds: Long? = null,
    val systemTagId: Long? = null,
    val canAssignTag: Boolean? = null,
    val tagColor: String? = null,
) {
    init {
        require(key.isSafeMediaText(MAX_MEDIA_KEY_LENGTH)) { "The media collection key is invalid." }
        require(name.isSafeMediaText(MAX_MEDIA_NAME_LENGTH)) { "The media collection name is invalid." }
        require(serverReference == null || serverReference.isSafeMediaText(MAX_MEDIA_REFERENCE_LENGTH)) {
            "The media collection reference is invalid."
        }
        require(itemCount == null || itemCount >= 0) { "The media collection count is invalid." }
        require(ownerUserId == null || ownerUserId.isSafePathIdentity()) { "The album owner is invalid." }
        require(ownerDisplayName == null || ownerDisplayName.isSafeMediaText(MAX_MEDIA_NAME_LENGTH)) {
            "The album owner display name is invalid."
        }
        require(location == null || location.isSafeMediaText(MAX_MEDIA_LOCATION_LENGTH)) {
            "The album location is invalid."
        }
        require(createdAtEpochSeconds == null || createdAtEpochSeconds >= 0L) {
            "The album creation timestamp is invalid."
        }
        require(systemTagId == null || systemTagId > 0L) { "The system tag ID is invalid." }
        require(tagColor == null || tagColor.isSafeMediaText(MAX_MEDIA_TAG_COLOR_LENGTH)) {
            "The system tag color is invalid."
        }
        require(type == NativeMediaCollectionType.Album || ownerUserId == null) {
            "Only albums can have an owner."
        }
        require(type == NativeMediaCollectionType.SystemTag || (systemTagId == null && tagColor == null)) {
            "Only system-tag collections can have tag metadata."
        }
    }

    val canBrowse: Boolean get() = serverReference != null

    fun asNextcloudCoverFileOrNull(): NextcloudFile? = cover?.let { cover ->
        NextcloudFile(
            path = "memories/collections/$key/cover/${cover.fileId}",
            name = name,
            isDirectory = false,
            mimeType = "image/*",
            size = null,
            lastModified = null,
            fileId = cover.fileId,
            hasPreview = true,
            etag = cover.etag,
        )
    }
}

data class NativeMediaCollectionCatalog(
    val albums: List<NativeMediaCollection>,
    val tags: List<NativeMediaCollection>,
    /** Non-fatal feature failures. A DAV tag catalog can still work when Memories is absent. */
    val warnings: List<String> = emptyList(),
) {
    init {
        require(albums.all { it.type == NativeMediaCollectionType.Album }) {
            "The album catalog contains another collection type."
        }
        require(tags.all { it.type == NativeMediaCollectionType.SystemTag }) {
            "The tag catalog contains another collection type."
        }
        val keys = (albums + tags).map(NativeMediaCollection::key)
        require(keys.distinct().size == keys.size) { "The media catalog contains duplicate keys." }
        require(warnings.size <= MAX_MEDIA_CATALOG_WARNINGS) { "The media catalog has too many warnings." }
    }
}

data class NativeMediaDay(
    val id: Long,
    val itemCount: Int,
) {
    init {
        require(id > 0L) { "The Memories day ID is invalid." }
        require(itemCount >= 0) { "The Memories day count is invalid." }
    }
}

data class NativeMediaDayIndex(
    val collectionKey: String,
    val days: List<NativeMediaDay>,
) {
    init {
        require(collectionKey.isSafeMediaText(MAX_MEDIA_KEY_LENGTH)) { "The collection key is invalid." }
        require(days.map(NativeMediaDay::id).distinct().size == days.size) {
            "The Memories day index contains duplicate IDs."
        }
    }

    /** Selects the next stable window. A missing cursor indicates that the index was refreshed. */
    fun pageAfter(cursor: NativeMediaDayCursor?, pageSize: Int): NativeMediaDayWindow {
        require(pageSize in 1..MAX_MEMORIES_DAY_BATCH) { "The Memories day page size is invalid." }
        val start = if (cursor == null) {
            0
        } else {
            val index = days.indexOfFirst { it.id == cursor.afterDayId }
            require(index >= 0) { "The Memories day cursor is no longer present; refresh the collection." }
            index + 1
        }
        val selected = days.drop(start).take(pageSize)
        return NativeMediaDayWindow(
            days = selected,
            nextCursor = selected.lastOrNull()
                ?.takeIf { start + selected.size < days.size }
                ?.let { NativeMediaDayCursor(it.id) },
        )
    }
}

data class NativeMediaDayCursor(val afterDayId: Long) {
    init {
        require(afterDayId > 0L) { "The Memories day cursor is invalid." }
    }
}

data class NativeMediaDayWindow(
    val days: List<NativeMediaDay>,
    val nextCursor: NativeMediaDayCursor?,
)

data class NativeMediaItem(
    val fileId: Long,
    val name: String,
    val mimeType: String?,
    val etag: String?,
    val dayId: Long,
    val width: Int?,
    val height: Int?,
    val takenAtEpochSeconds: Long?,
    val isVideo: Boolean,
    val videoDurationSeconds: Int?,
    val isFavorite: Boolean,
    val rawStackFileIds: List<Long> = emptyList(),
    val livePhoto: NextcloudLivePhotoReference? = null,
    /** Normalized recognized-face geometry when the active Memories filter supplied one. */
    val faceRectangle: NativeFaceRectangle? = null,
) {
    init {
        require(fileId > 0L) { "The media file ID is invalid." }
        require(name.isSafeMediaText(MAX_MEDIA_NAME_LENGTH)) { "The media filename is invalid." }
        require(mimeType == null || mimeType.isSafeMediaText(MAX_MEDIA_MIME_LENGTH)) {
            "The media MIME type is invalid."
        }
        require(etag == null || etag.isSafeMediaText(MAX_MEDIA_ETAG_LENGTH)) { "The media ETag is invalid." }
        require(dayId > 0L) { "The media day ID is invalid." }
        require(width == null || width > 0) { "The media width is invalid." }
        require(height == null || height > 0) { "The media height is invalid." }
        require(takenAtEpochSeconds == null || takenAtEpochSeconds >= 0L) { "The media timestamp is invalid." }
        require(videoDurationSeconds == null || videoDurationSeconds >= 0) { "The video duration is invalid." }
        require(rawStackFileIds.size <= MAX_RAW_STACK_ITEMS && rawStackFileIds.all { it > 0L }) {
            "The RAW stack is invalid."
        }
        require(rawStackFileIds.distinct().size == rawStackFileIds.size) { "The RAW stack has duplicate files." }
    }

    fun toNextcloudFile(collectionKey: String): NextcloudFile {
        require(collectionKey.isSafeMediaText(MAX_MEDIA_KEY_LENGTH)) { "The collection key is invalid." }
        return NextcloudFile(
            path = "memories/collections/$collectionKey/$dayId/$fileId",
            name = name,
            isDirectory = false,
            mimeType = mimeType,
            size = null,
            lastModified = takenAtEpochSeconds?.toString(),
            fileId = fileId,
            hasPreview = true,
            etag = etag,
            originalAccessAllowed = false,
            livePhoto = livePhoto,
        )
    }
}

data class NativeMediaCollectionPage(
    val items: List<NativeMediaItem>,
    val nextCursor: NativeMediaDayCursor?,
)

fun memoriesCollectionListRequest(
    type: NativeMediaCollectionType,
    containingFileId: Long? = null,
): NextcloudApiRequest {
    require(containingFileId == null || containingFileId > 0L) { "The containing file ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/clusters/${type.memoriesBackend}",
        queryParameters = containingFileId?.let { mapOf("fileid" to it.toString()) }.orEmpty(),
        ocsApiRequest = true,
        maximumResponseBytes = MEDIA_INDEX_RESPONSE_LIMIT_BYTES,
    ).requireSafe()
}

fun memoriesCollectionDayIndexRequest(collection: NativeMediaCollection): NextcloudApiRequest {
    val filter = collection.memoriesFilter()
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/days",
        queryParameters = mapOf(filter.first to filter.second, "nopreload" to "1"),
        ocsApiRequest = true,
        maximumResponseBytes = MEDIA_INDEX_RESPONSE_LIMIT_BYTES,
    ).requireSafe()
}

fun memoriesCollectionDaysRequest(
    collection: NativeMediaCollection,
    dayIds: List<Long>,
): NextcloudApiRequest {
    require(dayIds.isNotEmpty() && dayIds.size <= MAX_MEMORIES_DAY_BATCH && dayIds.all { it > 0L }) {
        "The Memories day batch is invalid."
    }
    require(dayIds.distinct().size == dayIds.size) { "Duplicate Memories day IDs are not allowed." }
    val filter = collection.memoriesFilter()
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/days/${dayIds.joinToString(",")}",
        queryParameters = mapOf(filter.first to filter.second),
        ocsApiRequest = true,
        maximumResponseBytes = MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES,
    ).requireSafe()
}

fun parseMemoriesCollectionListResponse(
    response: NextcloudApiResponse,
    expectedType: NativeMediaCollectionType,
): List<NativeMediaCollection> {
    val root = response.requireJsonArray("Memories ${expectedType.memoriesBackend} collection")
    require(root.size <= MAX_MEDIA_COLLECTIONS) { "The Memories collection response is too large." }
    return root.mapIndexed { index, element ->
        parseMemoriesCollection(element.requireObject("collection $index"), expectedType)
    }.distinctBy(NativeMediaCollection::key).also { collections ->
        require(collections.size == root.size) { "The Memories collection response contains duplicate records." }
    }.sortedWith(compareBy<NativeMediaCollection> { it.name.lowercase() }.thenBy(NativeMediaCollection::key))
}

fun parseMemoriesDayIndexResponse(
    response: NextcloudApiResponse,
    collection: NativeMediaCollection,
): NativeMediaDayIndex {
    val root = response.requireJsonArray("Memories day index")
    require(root.size <= MAX_MEMORIES_DAYS) { "The Memories day index is too large." }
    val days = root.mapIndexed { index, element ->
        val item = element.requireObject("day $index")
        NativeMediaDay(
            id = item.requiredPositiveLong("dayid"),
            itemCount = item.requiredNonNegativeInt("count"),
        )
    }
    require(days.map(NativeMediaDay::id).distinct().size == days.size) {
        "The Memories day index contains duplicate IDs."
    }
    return NativeMediaDayIndex(collection.key, days)
}

fun parseMemoriesDayContentsResponse(
    response: NextcloudApiResponse,
    collection: NativeMediaCollection,
    expectedDayIds: Set<Long>,
): List<NativeMediaItem> {
    require(collection.canBrowse) { "The collection has no Memories browse reference." }
    return parseMemoriesMediaItemsResponse(response, expectedDayIds)
}

/**
 * Parses the common media shape returned by Memories day endpoints.
 *
 * Albums, tags, recognized people, and future cluster backends all return this same file-oriented
 * payload. Keeping the parser independent of a specific collection lets native workflows share
 * rich media behavior without branching on an installed app ID.
 */
fun parseMemoriesMediaItemsResponse(
    response: NextcloudApiResponse,
    expectedDayIds: Set<Long>,
): List<NativeMediaItem> {
    require(expectedDayIds.isNotEmpty() && expectedDayIds.size <= MAX_MEMORIES_DAY_BATCH) {
        "The expected Memories day IDs are invalid."
    }
    val root = response.requireJsonArray("Memories day contents")
    require(root.size <= MAX_MEDIA_ITEMS_PER_RESPONSE) { "The Memories media response is too large." }
    val parsed = root.mapIndexed { index, element ->
        parseMemoriesMediaItem(element.requireObject("media item $index"), expectedDayIds)
    }
    require(parsed.map(NativeMediaItem::fileId).distinct().size == parsed.size) {
        "The Memories media response contains duplicate file IDs."
    }
    return parsed
}

/**
 * Merges DAV permissions with Memories' indexed counts and covers. Exact ID matching is preferred;
 * exact-name matching is only used when the name is unique on both sides.
 */
fun mergeSystemTagCollections(
    systemTags: List<NextcloudSystemTag>,
    memoriesTags: List<NativeMediaCollection>,
    memoriesTagBrowseAvailable: Boolean,
): List<NativeMediaCollection> {
    require(memoriesTags.all { it.type == NativeMediaCollectionType.SystemTag }) {
        "A non-tag collection was supplied to the tag merger."
    }
    val visibleDavTags = systemTags.filter(NextcloudSystemTag::userVisible).distinctBy(NextcloudSystemTag::id)
    require(visibleDavTags.size == systemTags.filter(NextcloudSystemTag::userVisible).size) {
        "The DAV tag catalog contains duplicate IDs."
    }
    val memoriesBySystemId = memoriesTags.mapNotNull { collection ->
        collection.systemTagId?.let { it to collection }
    }.groupBy({ it.first }, { it.second })
    val memoriesByName = memoriesTags.groupBy(NativeMediaCollection::name)
    val davByName = visibleDavTags.groupBy(NextcloudSystemTag::name)
    val consumed = mutableSetOf<String>()
    val merged = visibleDavTags.map { tag ->
        val byId = memoriesBySystemId[tag.id].orEmpty().singleOrNull()
        val byName = memoriesByName[tag.name].orEmpty().singleOrNull()
            ?.takeIf { davByName[tag.name].orEmpty().size == 1 }
        val indexed = byId ?: byName
        indexed?.let { consumed += it.key }
        NativeMediaCollection(
            key = "tag:${tag.id}",
            type = NativeMediaCollectionType.SystemTag,
            name = tag.name,
            // The current DAV name is authoritative because the Memories filter is name-based.
            serverReference = tag.name.takeIf { memoriesTagBrowseAvailable },
            itemCount = indexed?.itemCount,
            cover = indexed?.cover,
            systemTagId = tag.id,
            canAssignTag = tag.canAssign,
            tagColor = tag.color,
        )
    }
    val memoriesOnly = memoriesTags.filterNot { it.key in consumed }.map { collection ->
        if (memoriesTagBrowseAvailable) collection else collection.copy(serverReference = null)
    }
    return (merged + memoriesOnly).sortedWith(
        compareBy<NativeMediaCollection> { it.name.lowercase() }.thenBy(NativeMediaCollection::key),
    )
}

/** Read facade that keeps all authenticated traffic on the existing restricted transport. */
class NativeMediaCollectionReadService(
    private val services: NextcloudPlatformServices,
) {
    suspend fun loadCatalog(session: NextcloudSession): NativeMediaCollectionCatalog {
        val albumResult = runCatching {
            services.executeNextcloudApi(session, memoriesCollectionListRequest(NativeMediaCollectionType.Album))
                .let { parseMemoriesCollectionListResponse(it, NativeMediaCollectionType.Album) }
        }
        val memoriesTagResult = runCatching {
            services.executeNextcloudApi(session, memoriesCollectionListRequest(NativeMediaCollectionType.SystemTag))
                .let { parseMemoriesCollectionListResponse(it, NativeMediaCollectionType.SystemTag) }
        }
        val davTagResult = runCatching { services.listSystemTags(session) }

        if (albumResult.isFailure && memoriesTagResult.isFailure && davTagResult.isFailure) {
            error("Neither Photos/Memories collections nor system tags could be loaded.")
        }

        val memoriesTags = memoriesTagResult.getOrDefault(emptyList())
        val tags = davTagResult.fold(
            onSuccess = { davTags ->
                mergeSystemTagCollections(
                    systemTags = davTags,
                    memoriesTags = memoriesTags,
                    memoriesTagBrowseAvailable = memoriesTagResult.isSuccess,
                )
            },
            onFailure = { memoriesTags },
        )
        val warnings = buildList {
            albumResult.exceptionOrNull()?.let { add(it.safeCatalogWarning("Albums")) }
            memoriesTagResult.exceptionOrNull()?.let { add(it.safeCatalogWarning("Indexed tag counts")) }
            davTagResult.exceptionOrNull()?.let { add(it.safeCatalogWarning("Tag permissions")) }
        }
        return NativeMediaCollectionCatalog(
            albums = albumResult.getOrDefault(emptyList()),
            tags = tags,
            warnings = warnings,
        )
    }

    suspend fun loadDayIndex(
        session: NextcloudSession,
        collection: NativeMediaCollection,
    ): NativeMediaDayIndex = services.executeNextcloudApi(
        session,
        memoriesCollectionDayIndexRequest(collection),
    ).let { parseMemoriesDayIndexResponse(it, collection) }

    suspend fun loadPage(
        session: NextcloudSession,
        collection: NativeMediaCollection,
        index: NativeMediaDayIndex,
        cursor: NativeMediaDayCursor? = null,
        dayPageSize: Int = DEFAULT_MEMORIES_DAY_BATCH,
    ): NativeMediaCollectionPage {
        require(index.collectionKey == collection.key) { "The day index belongs to another collection." }
        val window = index.pageAfter(cursor, dayPageSize)
        if (window.days.isEmpty()) return NativeMediaCollectionPage(emptyList(), null)
        val dayIds = window.days.map(NativeMediaDay::id)
        val response = services.executeNextcloudApi(session, memoriesCollectionDaysRequest(collection, dayIds))
        return NativeMediaCollectionPage(
            items = parseMemoriesDayContentsResponse(response, collection, dayIds.toSet()),
            nextCursor = window.nextCursor,
        )
    }
}

private fun parseMemoriesCollection(
    item: JsonObject,
    expectedType: NativeMediaCollectionType,
): NativeMediaCollection {
    val returnedType = item.requiredText("cluster_type", MAX_MEDIA_REFERENCE_LENGTH)
    require(returnedType == expectedType.memoriesBackend) {
        "Memories returned a $returnedType cluster from the ${expectedType.memoriesBackend} endpoint."
    }
    val clusterReference = item.requiredScalarText("cluster_id", MAX_MEDIA_REFERENCE_LENGTH)
    val name = item.requiredText("name", MAX_MEDIA_NAME_LENGTH)
    val count = item.requiredNonNegativeInt("count")
    val directCover = item.optionalPositiveLong("cover")
    val coverEtag = item.optionalText("cover_etag", MAX_MEDIA_ETAG_LENGTH)

    return when (expectedType) {
        NativeMediaCollectionType.Album -> {
            val owner = item.requiredText("user", MAX_MEDIA_REFERENCE_LENGTH)
            require(owner.isSafePathIdentity()) { "The Memories album owner is invalid." }
            val fallbackCover = item.optionalPositiveLong("last_added_photo")
            val fallbackEtag = item.optionalText("last_added_photo_etag", MAX_MEDIA_ETAG_LENGTH)
            val coverId = directCover ?: fallbackCover
            NativeMediaCollection(
                key = "album:$clusterReference",
                type = expectedType,
                name = name,
                serverReference = "$owner/$name",
                itemCount = count,
                cover = coverId?.let { NativeMediaCover(it, if (it == directCover) coverEtag else fallbackEtag) },
                ownerUserId = owner,
                ownerDisplayName = item.optionalText("user_display", MAX_MEDIA_NAME_LENGTH),
                location = item.optionalText("location", MAX_MEDIA_LOCATION_LENGTH),
                isShared = item.optionalBoolean("shared") ?: false,
                isHidden = name.startsWith('.'),
                createdAtEpochSeconds = item.optionalNonNegativeLong("created"),
            )
        }

        NativeMediaCollectionType.SystemTag -> NativeMediaCollection(
            key = "memories-tag:$clusterReference",
            type = expectedType,
            name = name,
            serverReference = name,
            itemCount = count,
            cover = directCover?.let { NativeMediaCover(it, coverEtag) },
            systemTagId = item.optionalPositiveLong("id"),
        )
    }
}

private fun parseMemoriesMediaItem(
    item: JsonObject,
    expectedDayIds: Set<Long>,
): NativeMediaItem {
    val fileId = item.requiredPositiveLong("fileid")
    val dayId = item.requiredPositiveLong("dayid")
    require(dayId in expectedDayIds) { "Memories returned media from an unrequested day." }
    val mimeType = item.optionalText("mimetype", MAX_MEDIA_MIME_LENGTH)
    val name = item.optionalText("basename", MAX_MEDIA_NAME_LENGTH) ?: "Photo $fileId"
    val rawStackIds = (item["stackraw"] as? JsonArray).orEmpty().also { stack ->
        require(stack.size <= MAX_RAW_STACK_ITEMS) { "The Memories RAW stack is too large." }
    }.mapIndexed { index, raw ->
        val rawObject = raw as? JsonObject ?: error("RAW stack item $index is not an object.")
        rawObject.requiredPositiveLong("fileid")
    }.filterNot { it == fileId }
    return NativeMediaItem(
        fileId = fileId,
        name = name,
        mimeType = mimeType,
        etag = item.optionalText("etag", MAX_MEDIA_ETAG_LENGTH),
        dayId = dayId,
        width = item.optionalPositiveInt("w"),
        height = item.optionalPositiveInt("h"),
        takenAtEpochSeconds = item.optionalNonNegativeLong("epoch")
            ?: item.optionalNonNegativeLong("datetaken"),
        isVideo = item.optionalBoolean("isvideo") ?: mimeType?.startsWith("video/") == true,
        videoDurationSeconds = item.optionalNonNegativeInt("video_duration"),
        isFavorite = item.optionalBoolean("isfavorite") ?: false,
        rawStackFileIds = rawStackIds,
        livePhoto = item.optionalText("liveid", MAX_LIVE_PHOTO_TOKEN_LENGTH)
            ?.let(::NextcloudLivePhotoReference),
        faceRectangle = item.faceRectangleOrNull(),
    )
}

private fun NativeMediaCollection.memoriesFilter(): Pair<String, String> {
    val reference = requireNotNull(serverReference) { "This collection cannot be browsed through Memories." }
    return type.memoriesBackend to reference
}

private fun NextcloudApiResponse.requireJsonArray(label: String): JsonArray {
    require(status in 200..299) {
        val message = runCatching {
            (mediaCollectionsJson.parseToJsonElement(body.decodeToString()) as? JsonObject)
                ?.optionalText("message", MAX_MEDIA_ERROR_LENGTH)
        }.getOrNull()
        buildString {
            append("$label failed (HTTP $status)")
            if (message != null) append(": $message")
            append('.')
        }
    }
    return runCatching { mediaCollectionsJson.parseToJsonElement(body.decodeToString()) as? JsonArray }
        .getOrNull() ?: error("The $label response is not a JSON array.")
}

private fun JsonElement.requireObject(label: String): JsonObject = this as? JsonObject
    ?: error("The Memories $label is not an object.")

private fun JsonObject.requiredText(name: String, maximumLength: Int): String =
    optionalText(name, maximumLength) ?: error("The Memories response has no valid $name.")

private fun JsonObject.requiredScalarText(name: String, maximumLength: Int): String {
    val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()
    require(value != null && value.isSafeMediaText(maximumLength)) {
        "The Memories response has no valid $name."
    }
    return value
}

private fun JsonObject.optionalText(name: String, maximumLength: Int): String? {
    val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
    require(value.isSafeMediaText(maximumLength)) { "The Memories $name is invalid." }
    return value
}

private fun JsonObject.requiredPositiveLong(name: String): Long = optionalPositiveLong(name)
    ?: error("The Memories response has no valid $name.")

private fun JsonObject.optionalPositiveLong(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }

private fun JsonObject.optionalNonNegativeLong(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull?.also { require(it >= 0L) { "The Memories $name is invalid." } }

private fun JsonObject.requiredNonNegativeInt(name: String): Int = optionalNonNegativeInt(name)
    ?: error("The Memories response has no valid $name.")

private fun JsonObject.optionalNonNegativeInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull?.also { require(it >= 0) { "The Memories $name is invalid." } }

private fun JsonObject.optionalPositiveInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

private fun JsonObject.optionalBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.let { primitive ->
        primitive.booleanOrNull ?: primitive.intOrNull?.let { numeric ->
            require(numeric == 0 || numeric == 1) { "The Memories $name is invalid." }
            numeric == 1
        }
    }

private fun Throwable.safeCatalogWarning(feature: String): String {
    val detail = message?.trim()?.takeIf { it.isSafeMediaText(MAX_MEDIA_ERROR_LENGTH) }
    return if (detail == null) "$feature are unavailable." else "$feature: $detail"
}

private fun String.isSafeMediaText(maximumLength: Int): Boolean =
    isNotBlank() && length <= maximumLength && none(Char::isISOControl)

private fun String.isSafePathIdentity(): Boolean =
    isSafeMediaText(MAX_MEDIA_REFERENCE_LENGTH) && '/' !in this && '\\' !in this

private val mediaCollectionsJson = Json { ignoreUnknownKeys = true }

const val DEFAULT_MEMORIES_DAY_BATCH = 6
const val MAX_MEMORIES_DAY_BATCH = 8
const val MAX_MEDIA_COLLECTIONS = 10_000
const val MAX_MEMORIES_DAYS = 20_000
const val MAX_MEDIA_ITEMS_PER_RESPONSE = 10_000
const val MAX_RAW_STACK_ITEMS = 32
private const val MEDIA_INDEX_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L
private const val MAX_MEDIA_NAME_LENGTH = 1_024
private const val MAX_MEDIA_REFERENCE_LENGTH = 1_024
private const val MAX_MEDIA_KEY_LENGTH = 2_048
private const val MAX_MEDIA_LOCATION_LENGTH = 4_096
private const val MAX_MEDIA_MIME_LENGTH = 256
private const val MAX_MEDIA_ETAG_LENGTH = 1_024
private const val MAX_MEDIA_TAG_COLOR_LENGTH = 64
private const val MAX_MEDIA_ERROR_LENGTH = 512
private const val MAX_MEDIA_CATALOG_WARNINGS = 3
