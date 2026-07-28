package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val MEMORIES_FOLDER_LIST_RESPONSE_LIMIT_BYTES = 512L * 1024L
private const val MEMORIES_FOLDER_DAY_INDEX_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L
private const val MAX_MEMORIES_FOLDER_PATH_BYTES = 4_096
private const val MAX_MEMORIES_FOLDER_SEGMENT_BYTES = 255
private const val MAX_MEMORIES_FOLDER_NAME_BYTES = 255
private const val MAX_MEMORIES_DIRECT_CHILDREN = 2_048
private const val MAX_MEMORIES_FOLDER_PREVIEWS = 4
private const val MAX_MEMORIES_FOLDER_DAYS = 20_000
private const val MEMORIES_FOLDER_INVENTORY_CURSOR_PREFIX = "memories-folder-days-v1:"
private const val MEMORIES_FOLDER_DAY_CONTENTS_RESPONSE_LIMIT_BYTES = 8L * 1024L * 1024L

@JvmInline
value class MemoriesFolderPath private constructor(
    val value: String,
) {
    companion object {
        fun of(path: String): MemoriesFolderPath =
            MemoriesFolderPath(normalizeMemoriesFolderPath(path))
    }

    internal fun child(name: String): MemoriesFolderPath {
        requireMemoriesFolderName(name)
        return of(if (value == "/") "/$name" else "$value/$name")
    }
}

data class MemoriesDirectChildFolder(
    val fileId: Long,
    val name: String,
    val path: MemoriesFolderPath,
    val previewFileIds: List<Long>,
) {
    init {
        require(fileId > 0L) { "The Memories folder file ID is invalid." }
        requireMemoriesFolderName(name)
        require(path.value.substringAfterLast('/') == name) {
            "The Memories folder name does not match its path."
        }
        require(previewFileIds.size <= MAX_MEMORIES_FOLDER_PREVIEWS) {
            "The Memories folder has too many previews."
        }
        require(previewFileIds.all { fileId -> fileId > 0L }) {
            "The Memories folder preview file ID is invalid."
        }
        require(previewFileIds.distinct().size == previewFileIds.size) {
            "The Memories folder has duplicate previews."
        }
    }
}

data class MemoriesDirectChildFolders(
    val parent: MemoriesFolderPath,
    val folders: List<MemoriesDirectChildFolder>,
) {
    init {
        require(folders.size <= MAX_MEMORIES_DIRECT_CHILDREN) {
            "The Memories direct-child folder response is too large."
        }
        require(folders.map(MemoriesDirectChildFolder::fileId).distinct().size == folders.size) {
            "The Memories direct-child folder response has duplicate file IDs."
        }
        require(folders.map { folder -> folder.name }.distinct().size == folders.size) {
            "The Memories direct-child folder response has duplicate names."
        }
        require(folders.all { folder ->
            folder.path == parent.child(folder.name)
        }) {
            "The Memories folder response contains a non-child path."
        }
    }
}

data class MemoriesFolderDayIndex(
    val folder: MemoriesFolderPath,
    val recursive: Boolean,
    val days: List<NativeMediaDay>,
) {
    init {
        require(days.size <= MAX_MEMORIES_FOLDER_DAYS) {
            "The Memories folder day index is too large."
        }
        require(days.map(NativeMediaDay::id).distinct().size == days.size) {
            "The Memories folder day index has duplicate IDs."
        }
        require(days.zipWithNext().all { (newer, older) -> newer.id > older.id }) {
            "The Memories folder day index is not in deterministic newest-first order."
        }
    }

    val totalItemCount: Long
        get() = days.fold(0L) { total, day ->
            require(total <= Long.MAX_VALUE - day.itemCount.toLong()) {
                "The Memories folder item count is too large."
            }
            total + day.itemCount
        }
}

enum class MemoriesFolderBrowseAvailability {
    Absent,
    Incompatible,
}

enum class MemoriesFolderBrowseFallbackReason {
    EndpointAbsent,
    EndpointRejected,
    InvalidResponse,
}

sealed interface MemoriesFolderBrowseLoadResult<out T> {
    data class Loaded<T>(
        val value: T,
    ) : MemoriesFolderBrowseLoadResult<T>

    data class UseFallback(
        val availability: MemoriesFolderBrowseAvailability,
        val reason: MemoriesFolderBrowseFallbackReason,
        val httpStatus: Int? = null,
    ) : MemoriesFolderBrowseLoadResult<Nothing> {
        init {
            require(httpStatus == null || httpStatus in 100..599) {
                "The Memories folder fallback status is invalid."
            }
        }
    }
}

class MemoriesFolderBrowseHttpException(
    val status: Int,
) : IllegalStateException("Loading Memories folders failed (HTTP $status).")

fun memoriesDirectChildFoldersRequest(
    folder: MemoriesFolderPath,
): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/index.php/apps/memories/api/folders/sub",
    queryParameters = mapOf("folder" to folder.value),
    ocsApiRequest = true,
    maximumResponseBytes = MEMORIES_FOLDER_LIST_RESPONSE_LIMIT_BYTES,
).requireSafe()

fun memoriesFolderDayIndexRequest(
    folder: MemoriesFolderPath,
    recursive: Boolean,
): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/index.php/apps/memories/api/days",
    queryParameters = mapOf(
        "folder" to folder.value,
        "recursive" to if (recursive) "1" else "0",
        "nopreload" to "1",
    ),
    ocsApiRequest = true,
    maximumResponseBytes = MEMORIES_FOLDER_DAY_INDEX_RESPONSE_LIMIT_BYTES,
).requireSafe()

fun parseMemoriesDirectChildFoldersResponse(
    response: NextcloudApiResponse,
    parent: MemoriesFolderPath,
): MemoriesFolderBrowseLoadResult<MemoriesDirectChildFolders> =
    parseMemoriesFolderBrowseResponse(
        response = response,
        maximumResponseBytes = MEMORIES_FOLDER_LIST_RESPONSE_LIMIT_BYTES,
    ) {
        val root = memoriesFolderJson.parseToJsonElement(response.body.decodeToString()) as? JsonArray
            ?: error("The Memories direct-child folder response is not a JSON array.")
        require(root.size <= MAX_MEMORIES_DIRECT_CHILDREN) {
            "The Memories direct-child folder response is too large."
        }
        val folders = root.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: error("Memories folder $index is not an object.")
            val fileId = (item["fileid"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { value -> value > 0L }
                ?: error("Memories folder $index has no valid file ID.")
            val name = (item["name"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?: error("Memories folder $index has no valid name.")
            requireMemoriesFolderName(name)
            val previews = when (val previewsElement = item["previews"]) {
                null -> JsonArray(emptyList())
                is JsonArray -> previewsElement
                else -> error("Memories folder $index previews are not an array.")
            }
            require(previews.size <= MAX_MEMORIES_FOLDER_PREVIEWS) {
                "Memories folder $index has too many previews."
            }
            val previewFileIds = previews.mapIndexed { previewIndex, preview ->
                val previewObject = preview as? JsonObject
                    ?: error("Memories folder $index preview $previewIndex is not an object.")
                (previewObject["fileid"] as? JsonPrimitive)?.longOrNull
                    ?.takeIf { value -> value > 0L }
                    ?: error("Memories folder $index preview $previewIndex has no valid file ID.")
            }
            MemoriesDirectChildFolder(
                fileId = fileId,
                name = name,
                path = parent.child(name),
                previewFileIds = previewFileIds,
            )
        }.sortedWith { left, right ->
            compareMemoriesFolderNames(left.name, right.name)
                .takeIf { comparison -> comparison != 0 }
                ?: left.fileId.compareTo(right.fileId)
        }
        MemoriesDirectChildFolders(parent, folders)
    }

fun parseMemoriesFolderDayIndexResponse(
    response: NextcloudApiResponse,
    folder: MemoriesFolderPath,
    recursive: Boolean,
): MemoriesFolderBrowseLoadResult<MemoriesFolderDayIndex> =
    parseMemoriesFolderBrowseResponse(
        response = response,
        maximumResponseBytes = MEMORIES_FOLDER_DAY_INDEX_RESPONSE_LIMIT_BYTES,
    ) {
        val root = memoriesFolderJson.parseToJsonElement(response.body.decodeToString()) as? JsonArray
            ?: error("The Memories folder day index is not a JSON array.")
        require(root.size <= MAX_MEMORIES_FOLDER_DAYS) {
            "The Memories folder day index is too large."
        }
        val days = root.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: error("Memories folder day $index is not an object.")
            val dayId = (item["dayid"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { value -> value > 0L }
                ?: error("Memories folder day $index has no valid day ID.")
            val count = (item["count"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { value -> value >= 0 }
                ?: error("Memories folder day $index has no valid item count.")
            NativeMediaDay(dayId, count)
        }.sortedByDescending(NativeMediaDay::id)
        MemoriesFolderDayIndex(folder, recursive, days)
    }

class MemoriesFolderBrowseReadService internal constructor(
    private val execute: suspend (NextcloudSession, NextcloudApiRequest) -> NextcloudApiResponse,
) {
    constructor(services: NextcloudPlatformServices) : this(services::executeNextcloudApi)

    suspend fun loadDirectChildren(
        session: NextcloudSession,
        folder: MemoriesFolderPath,
    ): MemoriesFolderBrowseLoadResult<MemoriesDirectChildFolders> {
        val request = memoriesDirectChildFoldersRequest(folder)
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return parseMemoriesDirectChildFoldersResponse(execute(session, request), folder)
    }

    suspend fun loadDayIndex(
        session: NextcloudSession,
        folder: MemoriesFolderPath,
        recursive: Boolean,
    ): MemoriesFolderBrowseLoadResult<MemoriesFolderDayIndex> {
        val request = memoriesFolderDayIndexRequest(folder, recursive)
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return parseMemoriesFolderDayIndexResponse(execute(session, request), folder, recursive)
    }
}

fun memoriesFolderDayContentsRequest(
    folder: MemoriesFolderPath,
    recursive: Boolean,
    dayIds: List<Long>,
): NextcloudApiRequest {
    require(dayIds.isNotEmpty() && dayIds.size <= MAX_MEMORIES_DAY_BATCH) {
        "The Memories folder day batch is invalid."
    }
    require(dayIds.all { dayId -> dayId > 0L } && dayIds.distinct().size == dayIds.size) {
        "The Memories folder day IDs are invalid."
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/days/${dayIds.joinToString(",")}",
        queryParameters = mapOf(
            "folder" to folder.value,
            "recursive" to if (recursive) "1" else "0",
        ),
        ocsApiRequest = true,
        maximumResponseBytes = MEMORIES_FOLDER_DAY_CONTENTS_RESPONSE_LIMIT_BYTES,
    ).requireSafe()
}

/**
 * Adapts Memories' folder-specific APIs to the existing bounded folder inventory pager.
 *
 * Direct child folders remain explicit directory records. Media returned by Memories has a file
 * ID but no authoritative DAV path, so its local path is presentation-only and must never be used
 * for a server mutation or original download.
 */
class MemoriesPreferredFolderInventoryReadService(
    execute: suspend (NextcloudSession, NextcloudApiRequest) -> NextcloudApiResponse,
) {
    private val memories = MemoriesFolderBrowseReadService(execute)
    private val executeRequest = execute
    private val cache = MemoriesFolderInventoryCache()

    constructor(services: NextcloudPlatformServices) : this(services::executeNextcloudApi)

    suspend fun loadPage(
        session: NextcloudSession,
        accountScope: String,
        selectedFolderPath: String,
        scope: PhotoFolderBrowseScope,
        cursor: PhotoFolderInventoryCursor?,
        fallback: suspend (PhotoFolderInventoryCursor?) -> PhotoFolderInventoryPage,
    ): PhotoFolderInventoryPage {
        require(accountScope.isNotBlank()) { "The Memories folder account scope is missing." }
        val folder = MemoriesFolderPath.of(
            selectedFolderPath.takeIf(String::isNotEmpty) ?: "/",
        )
        val recursive = scope == PhotoFolderBrowseScope.RecursiveMedia
        val key = MemoriesFolderInventoryCacheKey(accountScope, folder, scope)
        val cached = cache.load(
            key = key,
            forceRefresh = cursor == null,
            fetch = fetch@{
                val folders = if (scope.showsDirectChildren()) {
                    when (val result = memories.loadDirectChildren(session, folder)) {
                        is MemoriesFolderBrowseLoadResult.Loaded -> result.value
                        is MemoriesFolderBrowseLoadResult.UseFallback -> return@fetch null
                    }
                } else {
                    MemoriesDirectChildFolders(folder, emptyList())
                }
                val index = if (scope == PhotoFolderBrowseScope.FoldersOnly) {
                    MemoriesFolderDayIndex(folder, recursive = false, days = emptyList())
                } else {
                    when (val result = memories.loadDayIndex(session, folder, recursive)) {
                        is MemoriesFolderBrowseLoadResult.Loaded -> result.value
                        is MemoriesFolderBrowseLoadResult.UseFallback -> return@fetch null
                    }
                }
                MemoriesFolderInventoryCacheValue(folders, index)
            },
        ) ?: run {
            require(!cursor.isMemoriesFolderInventoryCursor()) {
                "Memories folder browsing became unavailable; refresh this folder."
            }
            return fallback(cursor)
        }

        val afterDayId = cursor?.let(::decodeMemoriesFolderInventoryCursor)
        val window = cached.index.dayWindowAfter(afterDayId)
        val media = if (window.days.isEmpty()) {
            emptyList()
        } else {
            val request = memoriesFolderDayContentsRequest(
                folder = folder,
                recursive = cached.index.recursive,
                dayIds = window.days.map(NativeMediaDay::id),
            )
            val response = executeRequest(session, request)
            val parsed = parseMemoriesFolderBrowseResponse(
                response = response,
                maximumResponseBytes = MEMORIES_FOLDER_DAY_CONTENTS_RESPONSE_LIMIT_BYTES,
            ) {
                preprocessMemoriesMainTimelineRawStacks(
                    parseMemoriesMediaItemsResponse(
                        response = response,
                        expectedDayIds = window.days.mapTo(linkedSetOf(), NativeMediaDay::id),
                    ),
                )
            }
            when (parsed) {
                is MemoriesFolderBrowseLoadResult.Loaded -> parsed.value
                is MemoriesFolderBrowseLoadResult.UseFallback -> {
                    require(cursor == null) {
                        "The Memories folder paging contract changed; refresh this folder."
                    }
                    return fallback(null)
                }
            }
        }
        val records = buildList {
            if (cursor == null) {
                addAll(cached.folders.folders.map(MemoriesDirectChildFolder::toInventoryRecord))
            }
            addAll(media.map { item -> item.toFolderInventoryRecord(folder, recursive) })
        }
        return PhotoFolderInventoryPage(
            records = records,
            nextCursor = window.nextDayId?.let(::encodeMemoriesFolderInventoryCursor),
            rawObserved = media.any { item ->
                item.rawStackFileIds.isNotEmpty() || item.mimeType == "image/x-dcraw"
            },
        )
    }
}

private data class MemoriesFolderInventoryCacheKey(
    val accountScope: String,
    val folder: MemoriesFolderPath,
    val scope: PhotoFolderBrowseScope,
)

private data class MemoriesFolderInventoryCacheValue(
    val folders: MemoriesDirectChildFolders,
    val index: MemoriesFolderDayIndex,
)

private class MemoriesFolderInventoryCache {
    private val mutex = Mutex()
    private var key: MemoriesFolderInventoryCacheKey? = null
    private var value: MemoriesFolderInventoryCacheValue? = null

    suspend fun load(
        key: MemoriesFolderInventoryCacheKey,
        forceRefresh: Boolean,
        fetch: suspend () -> MemoriesFolderInventoryCacheValue?,
    ): MemoriesFolderInventoryCacheValue? = mutex.withLock {
        if (!forceRefresh && key == this.key) value?.let { return@withLock it }
        fetch().also { loaded ->
            this.key = key.takeIf { loaded != null }
            value = loaded
        }
    }
}

private data class MemoriesFolderDayWindow(
    val days: List<NativeMediaDay>,
    val nextDayId: Long?,
)

private fun MemoriesFolderDayIndex.dayWindowAfter(
    afterDayId: Long?,
): MemoriesFolderDayWindow {
    val start = if (afterDayId == null) {
        0
    } else {
        val index = days.indexOfFirst { day -> day.id == afterDayId }
        require(index >= 0) { "The Memories folder cursor is no longer present; refresh this folder." }
        index + 1
    }
    if (start >= days.size) return MemoriesFolderDayWindow(emptyList(), null)
    val selected = mutableListOf<NativeMediaDay>()
    var itemCount = 0
    for (day in days.drop(start)) {
        if (selected.size == MAX_MEMORIES_DAY_BATCH) break
        require(day.itemCount <= MAX_MEDIA_ITEMS_PER_RESPONSE) {
            "A single Memories folder day exceeds the safe media response limit."
        }
        if (itemCount > MAX_MEDIA_ITEMS_PER_RESPONSE - day.itemCount) break
        selected += day
        itemCount += day.itemCount
    }
    require(selected.isNotEmpty()) { "The Memories folder day window is empty." }
    val hasMore = start + selected.size < days.size
    return MemoriesFolderDayWindow(
        days = selected,
        nextDayId = selected.last().id.takeIf { hasMore },
    )
}

private fun encodeMemoriesFolderInventoryCursor(dayId: Long): PhotoFolderInventoryCursor {
    require(dayId > 0L) { "The Memories folder cursor day is invalid." }
    return PhotoFolderInventoryCursor("$MEMORIES_FOLDER_INVENTORY_CURSOR_PREFIX$dayId")
}

private fun decodeMemoriesFolderInventoryCursor(cursor: PhotoFolderInventoryCursor): Long {
    require(cursor.isMemoriesFolderInventoryCursor()) {
        "The folder cursor belongs to another paging source."
    }
    return cursor.value.removePrefix(MEMORIES_FOLDER_INVENTORY_CURSOR_PREFIX)
        .toLongOrNull()
        ?.takeIf { dayId -> dayId > 0L }
        ?: error("The Memories folder cursor is invalid.")
}

private fun PhotoFolderInventoryCursor?.isMemoriesFolderInventoryCursor(): Boolean =
    this?.value?.startsWith(MEMORIES_FOLDER_INVENTORY_CURSOR_PREFIX) == true

private fun PhotoFolderBrowseScope.showsDirectChildren(): Boolean =
    this == PhotoFolderBrowseScope.FoldersOnly ||
        this == PhotoFolderBrowseScope.DirectMediaAndSubfolders

private fun MemoriesDirectChildFolder.toInventoryRecord(): NextcloudFile = NextcloudFile(
    path = path.value.trim('/'),
    name = name,
    isDirectory = true,
    mimeType = "httpd/unix-directory",
    size = null,
    lastModified = null,
    fileId = fileId,
    hasPreview = previewFileIds.isNotEmpty(),
    etag = null,
    originalAccessAllowed = false,
    davPathAuthoritative = false,
    directoryPreviewFileIds = previewFileIds,
)

private fun NativeMediaItem.toFolderInventoryRecord(
    folder: MemoriesFolderPath,
    recursive: Boolean,
): NextcloudFile {
    require('/' !in name && '\\' !in name) {
        "The Memories folder media filename is invalid."
    }
    val parent = folder.value.trim('/')
    val presentationName = if (recursive) "$fileId-$name" else name
    val path = listOf(parent, presentationName)
        .filter(String::isNotEmpty)
        .joinToString("/")
    return toNextcloudFile("folder").copy(
        path = path,
        name = name,
        davPathAuthoritative = false,
        originalAccessAllowed = false,
    )
}

private fun normalizeMemoriesFolderPath(path: String): String {
    require(path.isNotEmpty()) { "The Memories folder path is empty." }
    require(path.none { character ->
        character == '\\' || character == '\u0000' || character.code in 0x01..0x1F ||
            character.code == 0x7F
    }) {
        "The Memories folder path contains an invalid character."
    }
    val segments = path
        .replace(Regex("/+"), "/")
        .trim('/')
        .takeIf(String::isNotEmpty)
        ?.split('/')
        .orEmpty()
    require(segments.none { segment -> segment == "." || segment == ".." }) {
        "The Memories folder path cannot traverse folders."
    }
    require(segments.all { segment ->
        segment.isNotEmpty() && segment.encodeToByteArray().size <= MAX_MEMORIES_FOLDER_SEGMENT_BYTES
    }) {
        "The Memories folder path contains an invalid segment."
    }
    val normalized = if (segments.isEmpty()) "/" else segments.joinToString(prefix = "/", separator = "/")
    require(normalized.encodeToByteArray().size <= MAX_MEMORIES_FOLDER_PATH_BYTES) {
        "The Memories folder path is too long."
    }
    return normalized
}

private fun requireMemoriesFolderName(name: String) {
    require(
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            name.none { character ->
                character == '/' || character == '\\' || character == '\u0000' ||
                    character.code in 0x01..0x1F || character.code == 0x7F
            } &&
            name.encodeToByteArray().size <= MAX_MEMORIES_FOLDER_NAME_BYTES,
    ) {
        "The Memories folder name is invalid."
    }
}

private fun compareMemoriesFolderNames(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    var exactCaseTieBreak = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftCharacter = left[leftIndex]
        val rightCharacter = right[rightIndex]
        if (leftCharacter.isDigit() && rightCharacter.isDigit()) {
            val leftEnd = left.indexAfterDigitRun(leftIndex)
            val rightEnd = right.indexAfterDigitRun(rightIndex)
            val leftSignificant = left.indexAfterLeadingZeroes(leftIndex, leftEnd)
            val rightSignificant = right.indexAfterLeadingZeroes(rightIndex, rightEnd)
            val significantLengthComparison =
                (leftEnd - leftSignificant).compareTo(rightEnd - rightSignificant)
            if (significantLengthComparison != 0) return significantLengthComparison
            for (offset in 0 until leftEnd - leftSignificant) {
                val digitComparison =
                    left[leftSignificant + offset].compareTo(right[rightSignificant + offset])
                if (digitComparison != 0) return digitComparison
            }
            val runLengthComparison =
                (leftEnd - leftIndex).compareTo(rightEnd - rightIndex)
            if (runLengthComparison != 0) return runLengthComparison
            leftIndex = leftEnd
            rightIndex = rightEnd
            continue
        }
        val foldedComparison = leftCharacter.lowercaseChar().compareTo(rightCharacter.lowercaseChar())
        if (foldedComparison != 0) return foldedComparison
        if (exactCaseTieBreak == 0) {
            exactCaseTieBreak = leftCharacter.compareTo(rightCharacter)
        }
        leftIndex += 1
        rightIndex += 1
    }
    return left.length.compareTo(right.length)
        .takeIf { comparison -> comparison != 0 }
        ?: exactCaseTieBreak
}

private fun String.indexAfterDigitRun(start: Int): Int {
    var index = start
    while (index < length && this[index].isDigit()) index += 1
    return index
}

private fun String.indexAfterLeadingZeroes(start: Int, end: Int): Int {
    var index = start
    while (index < end - 1 && this[index] == '0') index += 1
    return index
}

private inline fun <T> parseMemoriesFolderBrowseResponse(
    response: NextcloudApiResponse,
    maximumResponseBytes: Long,
    parse: () -> T,
): MemoriesFolderBrowseLoadResult<T> {
    when (response.status) {
        404 -> return MemoriesFolderBrowseLoadResult.UseFallback(
            availability = MemoriesFolderBrowseAvailability.Absent,
            reason = MemoriesFolderBrowseFallbackReason.EndpointAbsent,
            httpStatus = response.status,
        )

        400, 405, 406, 415, 422, 501 -> return MemoriesFolderBrowseLoadResult.UseFallback(
            availability = MemoriesFolderBrowseAvailability.Incompatible,
            reason = MemoriesFolderBrowseFallbackReason.EndpointRejected,
            httpStatus = response.status,
        )
    }
    if (response.status !in 200..299) throw MemoriesFolderBrowseHttpException(response.status)
    if (response.body.size.toLong() > maximumResponseBytes) {
        return MemoriesFolderBrowseLoadResult.UseFallback(
            availability = MemoriesFolderBrowseAvailability.Incompatible,
            reason = MemoriesFolderBrowseFallbackReason.InvalidResponse,
        )
    }
    return try {
        MemoriesFolderBrowseLoadResult.Loaded(parse())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MemoriesFolderBrowseLoadResult.UseFallback(
            availability = MemoriesFolderBrowseAvailability.Incompatible,
            reason = MemoriesFolderBrowseFallbackReason.InvalidResponse,
        )
    }
}

private val memoriesFolderJson = Json { ignoreUnknownKeys = true }
