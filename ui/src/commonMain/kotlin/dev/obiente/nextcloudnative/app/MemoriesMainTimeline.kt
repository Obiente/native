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

private const val MEMORIES_MAIN_TIMELINE_KEY = "timeline"
private const val MEMORIES_MAIN_TIMELINE_CURSOR_PREFIX = "memories-days-v1:"
private const val MEMORIES_MAIN_TIMELINE_INDEX_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L
private const val MEMORIES_MAIN_TIMELINE_PAGE_RESPONSE_LIMIT_BYTES = 8L * 1024L * 1024L
private const val SECONDS_PER_DAY = 86_400L

enum class MemoriesMainTimelineAvailability {
    Available,
    Absent,
    Incompatible,
}

enum class MemoriesMainTimelineFallbackReason {
    EndpointAbsent,
    EndpointRejected,
    InvalidResponse,
    SingleDayExceedsPageSize,
}

sealed interface MemoriesMainTimelineLoadResult<out T> {
    data class Loaded<T>(val value: T) : MemoriesMainTimelineLoadResult<T>

    data class UseFallback(
        val availability: MemoriesMainTimelineAvailability,
        val reason: MemoriesMainTimelineFallbackReason,
        val httpStatus: Int? = null,
    ) : MemoriesMainTimelineLoadResult<Nothing> {
        init {
            require(availability != MemoriesMainTimelineAvailability.Available ||
                reason == MemoriesMainTimelineFallbackReason.SingleDayExceedsPageSize) {
                "An available Memories timeline can fall back only for an explicit paging limitation."
            }
            require(httpStatus == null || httpStatus in 100..599) {
                "The Memories timeline fallback status is invalid."
            }
        }
    }
}

data class MemoriesMainTimelineDayIndex(
    val days: List<NativeMediaDay>,
) {
    init {
        require(days.size <= MAX_MEMORIES_DAYS) { "The Memories timeline day index is too large." }
        require(days.all { day -> day.id <= Long.MAX_VALUE / SECONDS_PER_DAY }) {
            "The Memories timeline day index contains an unsafe day ID."
        }
        require(days.map(NativeMediaDay::id).distinct().size == days.size) {
            "The Memories timeline day index contains duplicate IDs."
        }
        require(days.zipWithNext().all { (newer, older) -> newer.id > older.id }) {
            "The Memories timeline day index is not in deterministic newest-first order."
        }
    }

    val totalItemCount: Long
        get() = days.fold(0L) { total, day ->
            require(total <= Long.MAX_VALUE - day.itemCount.toLong()) {
                "The Memories timeline item count is too large."
            }
            total + day.itemCount
        }

    internal fun windowAfter(
        cursor: PhotoTimelineCursor?,
        maximumItems: Int,
        maximumDays: Int,
    ): MemoriesMainTimelineDayWindow {
        require(maximumItems in 1..MAX_PHOTO_TIMELINE_PAGE_SIZE) {
            "The Memories timeline page size is invalid."
        }
        require(maximumDays in 1..MAX_MEMORIES_DAY_BATCH) {
            "The Memories timeline day batch is invalid."
        }
        val afterDayId = cursor?.let(::decodeMemoriesMainTimelineCursor)
        val start = if (afterDayId == null) {
            0
        } else {
            val cursorIndex = days.indexOfFirst { day -> day.id == afterDayId }
            require(cursorIndex >= 0) {
                "The Memories timeline cursor is no longer present; refresh the timeline."
            }
            cursorIndex + 1
        }
        if (start >= days.size) return MemoriesMainTimelineDayWindow(emptyList(), null, null)

        // Empty index days do not consume the bounded request batch or become paging cursors.
        val remainingContentDays = days
            .drop(start)
            .filter { day -> day.itemCount > 0 }
        // Memories and fallback cursors cannot be mixed. Decide before loading any batch so an
        // oversized historical day cannot strand a timeline after a Memories cursor was emitted.
        val oversizedDay = remainingContentDays.firstOrNull { day -> day.itemCount > maximumItems }
        if (oversizedDay != null) {
            return MemoriesMainTimelineDayWindow(
                days = emptyList(),
                nextCursor = null,
                oversizedDay = oversizedDay,
            )
        }
        if (remainingContentDays.isEmpty()) {
            return MemoriesMainTimelineDayWindow(emptyList(), null, null)
        }

        val selected = mutableListOf<NativeMediaDay>()
        var selectedItems = 0
        for (day in remainingContentDays) {
            if (selected.size == maximumDays) break
            if (day.itemCount > maximumItems - selectedItems) break
            selected += day
            selectedItems += day.itemCount
        }
        val hasMore = selected.size < remainingContentDays.size
        return MemoriesMainTimelineDayWindow(
            days = selected,
            nextCursor = selected.lastOrNull()
                ?.takeIf { hasMore }
                ?.let { encodeMemoriesMainTimelineCursor(it.id) },
            oversizedDay = null,
        )
    }

    internal fun targetDay(dayId: Long): NativeMediaDay? =
        days.firstOrNull { day -> day.id == dayId && day.itemCount > 0 }

    internal fun advertisedItemsBefore(dayId: Long): Long? {
        var total = 0L
        days.forEach { day ->
            if (day.id == dayId) return total
            if (day.itemCount > 0) {
                if (total > Long.MAX_VALUE - day.itemCount.toLong()) return null
                total += day.itemCount.toLong()
            }
        }
        return null
    }
}

internal data class MemoriesMainTimelineDayWindow(
    val days: List<NativeMediaDay>,
    val nextCursor: PhotoTimelineCursor?,
    val oversizedDay: NativeMediaDay?,
)

data class MemoriesMainTimelineEntry(
    val timelineEntry: PhotoTimelineEntry,
    val dayId: Long,
    val rawStackFileIds: List<Long>,
) {
    init {
        require(dayId > 0L) { "The Memories timeline day ID is invalid." }
        require(rawStackFileIds.size <= MAX_RAW_STACK_ITEMS && rawStackFileIds.all { it > 0L }) {
            "The Memories timeline RAW stack is invalid."
        }
        require(rawStackFileIds.distinct().size == rawStackFileIds.size) {
            "The Memories timeline RAW stack has duplicate files."
        }
        require(timelineEntry.file.fileId !in rawStackFileIds) {
            "The Memories timeline RAW stack contains its cover."
        }
    }
}

data class MemoriesMainTimelinePage(
    val media: List<MemoriesMainTimelineEntry>,
    val nextCursor: PhotoTimelineCursor?,
) {
    init {
        require(media.size <= MAX_PHOTO_TIMELINE_PAGE_SIZE) {
            "The Memories timeline page is too large."
        }
        require(media.map { item -> item.timelineEntry.identity }.distinct().size == media.size) {
            "The Memories timeline page contains duplicate files."
        }
    }

    val entries: List<PhotoTimelineEntry>
        get() = media.map(MemoriesMainTimelineEntry::timelineEntry)

    val rawStackFileIdsByEntryIdentity: Map<String, List<Long>>
        get() = media
            .filter { item -> item.rawStackFileIds.isNotEmpty() }
            .associate { item -> item.timelineEntry.identity to item.rawStackFileIds }

    fun asPhotoTimelinePage(): PhotoTimelinePage = PhotoTimelinePage(
        entries = entries,
        nextCursor = nextCursor,
        optionalRawRemovalAuthoritative = true,
        rawObserved = media.any { item ->
            item.rawStackFileIds.isNotEmpty() || item.timelineEntry.file.isRawPhoto()
        },
        rawStackFileIdsByEntryIdentity = rawStackFileIdsByEntryIdentity,
        rawStackRelationshipsAuthoritative = true,
    )
}

data class MemoriesTimelineNavigationSnapshot(
    val sourceGeneration: Long,
    val geometry: MemoriesTimelinePlaceholderGeometry,
) {
    init {
        require(sourceGeneration > 0L) {
            "The Memories timeline navigation generation is invalid."
        }
    }
}

sealed interface MemoriesTimelineNavigationLoadResult {
    data class Loaded(
        val sourceGeneration: Long,
        val targetDayId: Long,
        val page: PhotoTimelinePage,
        val advertisedNewerItemCount: Int,
    ) : MemoriesTimelineNavigationLoadResult {
        init {
            require(sourceGeneration > 0L) {
                "The Memories timeline navigation generation is invalid."
            }
            require(targetDayId in 1..Long.MAX_VALUE / SECONDS_PER_DAY) {
                "The Memories timeline navigation day is invalid."
            }
            require(advertisedNewerItemCount >= 0) {
                "The Memories timeline navigation offset is invalid."
            }
        }
    }

    data object Stale : MemoriesTimelineNavigationLoadResult

    data class Unavailable(
        val message: String,
    ) : MemoriesTimelineNavigationLoadResult {
        init {
            require(message.isNotBlank() && message.length <= 512) {
                "The Memories timeline navigation error is invalid."
            }
        }
    }
}

class MemoriesMainTimelineHttpException(
    val status: Int,
) : IllegalStateException("Loading the Memories timeline failed (HTTP $status).")

fun memoriesMainTimelineDayIndexRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/index.php/apps/memories/api/days",
    queryParameters = mapOf("nopreload" to "1"),
    ocsApiRequest = true,
    maximumResponseBytes = MEMORIES_MAIN_TIMELINE_INDEX_RESPONSE_LIMIT_BYTES,
).requireSafe()

fun memoriesMainTimelineDaysRequest(dayIds: List<Long>): NextcloudApiRequest {
    require(dayIds.isNotEmpty() && dayIds.size <= MAX_MEMORIES_DAY_BATCH) {
        "The Memories timeline day batch is invalid."
    }
    require(dayIds.all { dayId -> dayId in 1..Long.MAX_VALUE / SECONDS_PER_DAY }) {
        "The Memories timeline day ID is invalid."
    }
    require(dayIds.distinct().size == dayIds.size) {
        "Duplicate Memories timeline day IDs are not allowed."
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/days/${dayIds.joinToString(",")}",
        ocsApiRequest = true,
        maximumResponseBytes = MEMORIES_MAIN_TIMELINE_PAGE_RESPONSE_LIMIT_BYTES,
    ).requireSafe()
}

fun parseMemoriesMainTimelineDayIndex(
    response: NextcloudApiResponse,
): MemoriesMainTimelineLoadResult<MemoriesMainTimelineDayIndex> =
    parseMemoriesMainTimelineResponse(
        response = response,
        maximumResponseBytes = MEMORIES_MAIN_TIMELINE_INDEX_RESPONSE_LIMIT_BYTES,
    ) {
        val root = memoriesMainTimelineJson.parseToJsonElement(response.body.decodeToString()) as? JsonArray
            ?: error("The Memories timeline day index is not a JSON array.")
        require(root.size <= MAX_MEMORIES_DAYS) { "The Memories timeline day index is too large." }
        val days = root.mapIndexed { index, element ->
            val item = element as? JsonObject ?: error("Memories timeline day $index is not an object.")
            val dayId = (item["dayid"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { value -> value in 1..Long.MAX_VALUE / SECONDS_PER_DAY }
                ?: error("Memories timeline day $index has no valid day ID.")
            val count = (item["count"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { value -> value >= 0 }
                ?: error("Memories timeline day $index has no valid item count.")
            NativeMediaDay(dayId, count)
        }
        require(days.map(NativeMediaDay::id).distinct().size == days.size) {
            "The Memories timeline day index contains duplicate IDs."
        }
        MemoriesMainTimelineDayIndex(days.sortedByDescending(NativeMediaDay::id))
    }

fun parseMemoriesMainTimelineDayContents(
    response: NextcloudApiResponse,
    expectedDays: List<NativeMediaDay>,
    maximumItems: Int,
): MemoriesMainTimelineLoadResult<List<MemoriesMainTimelineEntry>> {
    require(expectedDays.isNotEmpty() && expectedDays.size <= MAX_MEMORIES_DAY_BATCH) {
        "The expected Memories timeline days are invalid."
    }
    require(expectedDays.all { day -> day.id <= Long.MAX_VALUE / SECONDS_PER_DAY }) {
        "The expected Memories timeline day ID is unsafe."
    }
    require(expectedDays.map(NativeMediaDay::id).distinct().size == expectedDays.size) {
        "Duplicate expected Memories timeline day IDs are not allowed."
    }
    require(maximumItems in 1..MAX_PHOTO_TIMELINE_PAGE_SIZE) {
        "The Memories timeline page size is invalid."
    }
    val expectedDayIds = expectedDays.mapTo(linkedSetOf(), NativeMediaDay::id)
    return parseMemoriesMainTimelineResponse(
        response = response,
        maximumResponseBytes = MEMORIES_MAIN_TIMELINE_PAGE_RESPONSE_LIMIT_BYTES,
    ) {
        val items = preprocessMemoriesMainTimelineRawStacks(
            parseMemoriesMediaItemsResponse(response, expectedDayIds),
        )
        require(items.size <= maximumItems) {
            "The Memories timeline response exceeded the requested page size."
        }
        items.map(::memoriesMainTimelineEntry).sortedWith(
            compareByDescending<MemoriesMainTimelineEntry> {
                it.timelineEntry.capturedAtEpochSeconds
            }.thenBy { it.timelineEntry.identity },
        )
    }
}

class MemoriesMainTimelineReadService internal constructor(
    private val execute: suspend (NextcloudSession, NextcloudApiRequest) -> NextcloudApiResponse,
) {
    constructor(services: NextcloudPlatformServices) : this(services::executeNextcloudApi)

    suspend fun loadDayIndex(
        session: NextcloudSession,
    ): MemoriesMainTimelineLoadResult<MemoriesMainTimelineDayIndex> {
        val request = memoriesMainTimelineDayIndexRequest()
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return execute(session, request).let(::parseMemoriesMainTimelineDayIndex)
    }

    suspend fun loadPage(
        session: NextcloudSession,
        index: MemoriesMainTimelineDayIndex,
        cursor: PhotoTimelineCursor? = null,
        maximumItems: Int = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE,
        maximumDays: Int = DEFAULT_MEMORIES_DAY_BATCH,
    ): MemoriesMainTimelineLoadResult<MemoriesMainTimelinePage> {
        val window = index.windowAfter(cursor, maximumItems, maximumDays)
        window.oversizedDay?.let {
            return MemoriesMainTimelineLoadResult.UseFallback(
                availability = MemoriesMainTimelineAvailability.Available,
                reason = MemoriesMainTimelineFallbackReason.SingleDayExceedsPageSize,
            )
        }
        if (window.days.isEmpty()) {
            return MemoriesMainTimelineLoadResult.Loaded(
                MemoriesMainTimelinePage(emptyList(), null),
            )
        }
        val request = memoriesMainTimelineDaysRequest(window.days.map(NativeMediaDay::id))
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return when (
            val parsed = parseMemoriesMainTimelineDayContents(
                response = execute(session, request),
                expectedDays = window.days,
                maximumItems = maximumItems,
            )
        ) {
            is MemoriesMainTimelineLoadResult.Loaded -> MemoriesMainTimelineLoadResult.Loaded(
                MemoriesMainTimelinePage(parsed.value, window.nextCursor),
            )

            is MemoriesMainTimelineLoadResult.UseFallback -> parsed
        }
    }

    suspend fun loadTargetDay(
        session: NextcloudSession,
        index: MemoriesMainTimelineDayIndex,
        dayId: Long,
        maximumItems: Int = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE,
    ): MemoriesMainTimelineLoadResult<MemoriesMainTimelinePage> {
        require(maximumItems in 1..MAX_PHOTO_TIMELINE_PAGE_SIZE) {
            "The Memories timeline page size is invalid."
        }
        val day = index.targetDay(dayId)
            ?: return MemoriesMainTimelineLoadResult.UseFallback(
                availability = MemoriesMainTimelineAvailability.Incompatible,
                reason = MemoriesMainTimelineFallbackReason.InvalidResponse,
            )
        if (day.itemCount > maximumItems) {
            return MemoriesMainTimelineLoadResult.UseFallback(
                availability = MemoriesMainTimelineAvailability.Available,
                reason = MemoriesMainTimelineFallbackReason.SingleDayExceedsPageSize,
            )
        }
        val request = memoriesMainTimelineDaysRequest(listOf(day.id))
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return when (
            val parsed = parseMemoriesMainTimelineDayContents(
                response = execute(session, request),
                expectedDays = listOf(day),
                maximumItems = maximumItems,
            )
        ) {
            is MemoriesMainTimelineLoadResult.Loaded -> MemoriesMainTimelineLoadResult.Loaded(
                MemoriesMainTimelinePage(parsed.value, nextCursor = null),
            )

            is MemoriesMainTimelineLoadResult.UseFallback -> parsed
        }
    }
}

class MemoriesPreferredTimelineReadService(
    execute: suspend (NextcloudSession, NextcloudApiRequest) -> NextcloudApiResponse,
) {
    private val memories = MemoriesMainTimelineReadService(execute)
    private val indexCache = MemoriesMainTimelineIndexCache()

    constructor(services: NextcloudPlatformServices) : this(services::executeNextcloudApi)

    suspend fun loadPage(
        session: NextcloudSession,
        accountScope: String,
        cursor: PhotoTimelineCursor?,
        maximumItems: Int = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE,
        fallback: suspend (PhotoTimelineCursor?) -> PhotoTimelinePage,
    ): PhotoTimelinePage {
        require(accountScope.isNotBlank()) { "The Memories timeline account scope is missing." }
        if (cursor != null && !cursor.isMemoriesMainTimelineCursor()) {
            indexCache.markFallbackActive(accountScope)
            return fallback(cursor)
        }
        val indexResult = indexCache.load(
            accountScope = accountScope,
            forceRefresh = cursor == null,
        ) {
            memories.loadDayIndex(session)
        }
        val cachedIndex = when (indexResult) {
            is MemoriesMainTimelineLoadResult.Loaded -> indexResult.value
            is MemoriesMainTimelineLoadResult.UseFallback -> {
                require(!cursor.isMemoriesMainTimelineCursor()) {
                    "The Memories timeline became unavailable; refresh the timeline."
                }
                indexCache.markFallbackActive(accountScope)
                return fallback(cursor)
            }
        }
        return when (
            val page = memories.loadPage(
                session = session,
                index = cachedIndex.index,
                cursor = cursor,
                maximumItems = maximumItems,
            )
        ) {
            is MemoriesMainTimelineLoadResult.Loaded -> {
                check(
                    indexCache.markMemoriesActive(
                        accountScope = accountScope,
                        sourceGeneration = cachedIndex.sourceGeneration,
                    ),
                ) {
                    "The Memories timeline index changed while loading a page; refresh the timeline."
                }
                page.value.asPhotoTimelinePage()
            }

            is MemoriesMainTimelineLoadResult.UseFallback -> {
                require(cursor == null) {
                    "The Memories timeline paging contract changed; refresh the timeline."
                }
                indexCache.markFallbackActive(accountScope)
                fallback(null)
            }
        }
    }

    suspend fun navigationSnapshot(
        accountScope: String,
        monthResolver: PhotoTimelineMonthResolver = UtcPhotoTimelineMonthResolver,
    ): MemoriesTimelineNavigationSnapshot? {
        require(accountScope.isNotBlank()) { "The Memories timeline account scope is missing." }
        val cached = indexCache.activeMemoriesIndex(accountScope) ?: return null
        val geometry = buildMemoriesTimelinePlaceholderGeometry(
            index = cached.index,
            monthResolver = monthResolver,
        ) ?: return null
        return MemoriesTimelineNavigationSnapshot(
            sourceGeneration = cached.sourceGeneration,
            geometry = geometry,
        )
    }

    suspend fun loadNavigationTarget(
        session: NextcloudSession,
        accountScope: String,
        sourceGeneration: Long,
        targetDayId: Long,
        maximumItems: Int = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE,
    ): MemoriesTimelineNavigationLoadResult {
        require(accountScope.isNotBlank()) { "The Memories timeline account scope is missing." }
        require(sourceGeneration > 0L) {
            "The Memories timeline navigation generation is invalid."
        }
        val cached = indexCache.activeMemoriesIndex(
            accountScope = accountScope,
            sourceGeneration = sourceGeneration,
        ) ?: return MemoriesTimelineNavigationLoadResult.Stale
        val advertisedNewerItems = cached.index.advertisedItemsBefore(targetDayId)
            ?: return MemoriesTimelineNavigationLoadResult.Unavailable(
                "This date is no longer present in the photo timeline.",
            )
        if (advertisedNewerItems > Int.MAX_VALUE.toLong()) {
            return MemoriesTimelineNavigationLoadResult.Unavailable(
                "This date is beyond the supported in-memory navigation range.",
            )
        }
        val loaded = memories.loadTargetDay(
            session = session,
            index = cached.index,
            dayId = targetDayId,
            maximumItems = maximumItems,
        )
        if (
            !indexCache.matchesActiveMemoriesIndex(
                accountScope = accountScope,
                sourceGeneration = sourceGeneration,
            )
        ) {
            return MemoriesTimelineNavigationLoadResult.Stale
        }
        return when (loaded) {
            is MemoriesMainTimelineLoadResult.Loaded ->
                MemoriesTimelineNavigationLoadResult.Loaded(
                    sourceGeneration = sourceGeneration,
                    targetDayId = targetDayId,
                    page = loaded.value.asPhotoTimelinePage(),
                    advertisedNewerItemCount = advertisedNewerItems.toInt(),
                )

            is MemoriesMainTimelineLoadResult.UseFallback ->
                MemoriesTimelineNavigationLoadResult.Unavailable(
                    if (
                        loaded.reason ==
                        MemoriesMainTimelineFallbackReason.SingleDayExceedsPageSize
                    ) {
                        "This day contains too many photos for one bounded timeline window."
                    } else {
                        "Could not load photos for this date from Memories."
                    },
                )
        }
    }
}

private class MemoriesMainTimelineIndexCache {
    private val mutex = Mutex()
    private var accountScope: String? = null
    private var index: MemoriesMainTimelineDayIndex? = null
    private var sourceGeneration = 0L
    private var memoriesActive = false

    suspend fun load(
        accountScope: String,
        forceRefresh: Boolean,
        fetch: suspend () -> MemoriesMainTimelineLoadResult<MemoriesMainTimelineDayIndex>,
    ): MemoriesMainTimelineLoadResult<MemoriesMainTimelineCachedIndex> = mutex.withLock {
        if (!forceRefresh && accountScope == this.accountScope) {
            index?.let {
                return@withLock MemoriesMainTimelineLoadResult.Loaded(
                    MemoriesMainTimelineCachedIndex(it, sourceGeneration),
                )
            }
        }
        val loaded = fetch()
        when (loaded) {
            is MemoriesMainTimelineLoadResult.Loaded -> {
                sourceGeneration = nextMemoriesTimelineSourceGeneration(sourceGeneration)
                this.accountScope = accountScope
                index = loaded.value
                memoriesActive = false
                MemoriesMainTimelineLoadResult.Loaded(
                    MemoriesMainTimelineCachedIndex(loaded.value, sourceGeneration),
                )
            }

            is MemoriesMainTimelineLoadResult.UseFallback -> {
                sourceGeneration = nextMemoriesTimelineSourceGeneration(sourceGeneration)
                this.accountScope = null
                index = null
                memoriesActive = false
                loaded
            }
        }
    }

    suspend fun markMemoriesActive(
        accountScope: String,
        sourceGeneration: Long,
    ): Boolean = mutex.withLock {
        val matches =
            accountScope == this.accountScope &&
                sourceGeneration == this.sourceGeneration &&
                index != null
        if (matches) memoriesActive = true
        matches
    }

    suspend fun markFallbackActive(accountScope: String) = mutex.withLock {
        if (accountScope == this.accountScope) memoriesActive = false
    }

    suspend fun activeMemoriesIndex(
        accountScope: String,
        sourceGeneration: Long? = null,
    ): MemoriesMainTimelineCachedIndex? = mutex.withLock {
        val currentIndex = index ?: return@withLock null
        if (
            !memoriesActive ||
            accountScope != this.accountScope ||
            (sourceGeneration != null && sourceGeneration != this.sourceGeneration)
        ) {
            return@withLock null
        }
        MemoriesMainTimelineCachedIndex(currentIndex, this.sourceGeneration)
    }

    suspend fun matchesActiveMemoriesIndex(
        accountScope: String,
        sourceGeneration: Long,
    ): Boolean = mutex.withLock {
        memoriesActive &&
            index != null &&
            accountScope == this.accountScope &&
            sourceGeneration == this.sourceGeneration
    }
}

private data class MemoriesMainTimelineCachedIndex(
    val index: MemoriesMainTimelineDayIndex,
    val sourceGeneration: Long,
)

private fun nextMemoriesTimelineSourceGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L

private fun PhotoTimelineCursor?.isMemoriesMainTimelineCursor(): Boolean =
    this?.value?.startsWith(MEMORIES_MAIN_TIMELINE_CURSOR_PREFIX) == true

internal fun preprocessMemoriesMainTimelineRawStacks(
    items: List<NativeMediaItem>,
): List<NativeMediaItem> {
    if (items.isEmpty()) return emptyList()
    require(items.size <= MAX_MEDIA_ITEMS_PER_RESPONSE) {
        "The Memories timeline RAW stack input is too large."
    }

    val rawByDayAndStem = linkedMapOf<Pair<Long, String>, MutableList<NativeMediaItem>>()
    items.asSequence()
        .filter(NativeMediaItem::isMemoriesRawStackCandidate)
        .forEach { raw ->
            val stem = raw.memoriesRawStackStem() ?: return@forEach
            rawByDayAndStem.getOrPut(raw.dayId to stem, ::mutableListOf).add(raw)
        }
    if (rawByDayAndStem.isEmpty()) return items

    val stackedRawFileIds = mutableSetOf<Long>()
    val stacked = items.map { item ->
        if (item.isMemoriesRawStackCandidate()) return@map item
        val stem = item.memoriesStackStem() ?: return@map item
        val matchingKeys = buildList {
            add(item.dayId to stem)
            if ('.' in stem) add(item.dayId to stem.substringBefore('.'))
        }
        val inferredIds = matchingKeys
            .flatMap { key -> rawByDayAndStem[key].orEmpty() }
            .map(NativeMediaItem::fileId)
        val rawStackFileIds = (item.rawStackFileIds + inferredIds).distinct()
        require(rawStackFileIds.size <= MAX_RAW_STACK_ITEMS) {
            "The Memories timeline RAW stack is too large."
        }
        if (rawStackFileIds.isEmpty()) {
            item
        } else {
            stackedRawFileIds += rawStackFileIds
            item.copy(rawStackFileIds = rawStackFileIds)
        }
    }
    return stacked.filterNot { item ->
        item.isMemoriesRawStackCandidate() && item.fileId in stackedRawFileIds
    }
}

private fun memoriesMainTimelineEntry(item: NativeMediaItem): MemoriesMainTimelineEntry {
    val fallbackTimestamp = item.dayId * SECONDS_PER_DAY
    val capturedAt = item.takenAtEpochSeconds ?: fallbackTimestamp
    val file = item.toNextcloudFile(MEMORIES_MAIN_TIMELINE_KEY).copy(
        lastModified = capturedAt.toString(),
        davPathAuthoritative = false,
    )
    return MemoriesMainTimelineEntry(
        timelineEntry = PhotoTimelineEntry(file, capturedAt),
        dayId = item.dayId,
        rawStackFileIds = item.rawStackFileIds,
    )
}

private fun NativeMediaItem.isMemoriesRawStackCandidate(): Boolean =
    mimeType == "image/x-dcraw"

private fun NativeMediaItem.memoriesRawStackStem(): String? =
    memoriesStackStem()?.let { stem ->
        if (".ORIGINAL" in stem) stem.substringBefore('.') else stem
    }

private fun NativeMediaItem.memoriesStackStem(): String? =
    name.substringBeforeLast('.', missingDelimiterValue = name).takeIf(String::isNotEmpty)

private fun encodeMemoriesMainTimelineCursor(afterDayId: Long): PhotoTimelineCursor {
    require(afterDayId in 1..Long.MAX_VALUE / SECONDS_PER_DAY) {
        "The Memories timeline cursor day is invalid."
    }
    return PhotoTimelineCursor("$MEMORIES_MAIN_TIMELINE_CURSOR_PREFIX$afterDayId")
}

private fun decodeMemoriesMainTimelineCursor(cursor: PhotoTimelineCursor): Long {
    require(cursor.value.startsWith(MEMORIES_MAIN_TIMELINE_CURSOR_PREFIX)) {
        "The photo timeline cursor belongs to another source."
    }
    return cursor.value.removePrefix(MEMORIES_MAIN_TIMELINE_CURSOR_PREFIX)
        .toLongOrNull()
        ?.takeIf { value -> value in 1..Long.MAX_VALUE / SECONDS_PER_DAY }
        ?: error("The Memories timeline cursor is invalid.")
}

private inline fun <T> parseMemoriesMainTimelineResponse(
    response: NextcloudApiResponse,
    maximumResponseBytes: Long,
    parse: () -> T,
): MemoriesMainTimelineLoadResult<T> {
    when (response.status) {
        404 -> return MemoriesMainTimelineLoadResult.UseFallback(
            availability = MemoriesMainTimelineAvailability.Absent,
            reason = MemoriesMainTimelineFallbackReason.EndpointAbsent,
            httpStatus = response.status,
        )

        400, 405, 406, 415, 422, 501 -> return MemoriesMainTimelineLoadResult.UseFallback(
            availability = MemoriesMainTimelineAvailability.Incompatible,
            reason = MemoriesMainTimelineFallbackReason.EndpointRejected,
            httpStatus = response.status,
        )
    }
    if (response.status !in 200..299) throw MemoriesMainTimelineHttpException(response.status)
    if (response.body.size.toLong() > maximumResponseBytes) {
        return MemoriesMainTimelineLoadResult.UseFallback(
            availability = MemoriesMainTimelineAvailability.Incompatible,
            reason = MemoriesMainTimelineFallbackReason.InvalidResponse,
        )
    }
    return try {
        MemoriesMainTimelineLoadResult.Loaded(parse())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MemoriesMainTimelineLoadResult.UseFallback(
            availability = MemoriesMainTimelineAvailability.Incompatible,
            reason = MemoriesMainTimelineFallbackReason.InvalidResponse,
        )
    }
}

private val memoriesMainTimelineJson = Json { ignoreUnknownKeys = true }
