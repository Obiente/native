package dev.obiente.nextcloudnative.app

const val DEFAULT_PHOTO_TIMELINE_PAGE_SIZE = 200
const val MAX_PHOTO_TIMELINE_PAGE_SIZE = 500
const val DEFAULT_PHOTO_TIMELINE_RETAINED_ITEMS = 20_000
const val MAX_PHOTO_TIMELINE_RETAINED_ITEMS = 50_000
private const val MAX_PHOTO_TIMELINE_REVALIDATION_CATCH_UP_PAGES = 256

data class PhotoTimelineCursor(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= 2_048) {
            "The photo timeline cursor is invalid."
        }
    }
}

data class PhotoTimelineEntry(
    val file: NextcloudFile,
    val capturedAtEpochSeconds: Long,
) {
    init {
        require(!file.isDirectory) { "A photo timeline entry must reference a file." }
        require(file.path.trim('/').isNotBlank()) { "A photo timeline entry must have a stable path." }
    }

    val identity: String
        get() = file.fileId?.takeIf { it > 0L }?.let { "file:$it" }
            ?: "path:${file.path.trim('/')}"
}

fun NextcloudFile.toPhotoTimelineEntryOrNull(): PhotoTimelineEntry? {
    if (isDirectory) return null
    val timestamp = lastModified?.let(::parseDavMediaSearchTimestamp) ?: return null
    return PhotoTimelineEntry(this, timestamp)
}

data class PhotoTimelinePage(
    val entries: List<PhotoTimelineEntry>,
    val nextCursor: PhotoTimelineCursor?,
    val optionalRawRemovalAuthoritative: Boolean = true,
    val rawObserved: Boolean = entries.any { entry -> entry.file.isRawPhoto() },
) {
    init {
        require(entries.size <= MAX_PHOTO_TIMELINE_PAGE_SIZE) {
            "The photo timeline page is too large."
        }
    }
}

enum class PhotoTimelineLoadKind {
    Refresh,
    RevalidateNewest,
    NextPage,
}

class PhotoTimelineLoadToken internal constructor(
    val generation: Long,
    val kind: PhotoTimelineLoadKind,
    val cursor: PhotoTimelineCursor?,
    val pageSize: Int,
)

data class PhotoTimelineLoadStart(
    val state: PhotoTimelineState,
    val token: PhotoTimelineLoadToken?,
)

/**
 * Platform-neutral, bounded timeline state.
 *
 * Platforms load one page for the returned token, then pass the same token to [accept] or [fail].
 * A refresh or cancellation advances [generation], so a late result cannot replace newer state.
 * The retained limit is an in-memory safety boundary. When the window is full, accepting an older
 * page evicts the same number of newest entries while preserving the opaque cursor. Paging can
 * therefore reach the complete server history with bounded memory, and refresh returns to newest.
 * If newest-page revalidation changes paging identities or timestamps, paging temporarily replays
 * from the new first-page cursor with a strict request bound until it reaches the cached tail.
 */
data class PhotoTimelineState(
    val entries: List<PhotoTimelineEntry> = emptyList(),
    val nextCursor: PhotoTimelineCursor? = null,
    val loading: PhotoTimelineLoadToken? = null,
    val error: String? = null,
    val failedLoadKind: PhotoTimelineLoadKind? = null,
    val generation: Long = 0L,
    val pageSize: Int = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE,
    val retentionLimit: Int = DEFAULT_PHOTO_TIMELINE_RETAINED_ITEMS,
    val discardedNewerEntries: Int = 0,
    val loadedOlderPages: Boolean = false,
    val revalidationCursorCatchUpPagesRemaining: Int = 0,
    val revalidationCursorCatchUpTailIdentity: String? = null,
    val rawEverObserved: Boolean = entries.any { entry -> entry.file.isRawPhoto() },
) {
    init {
        require(pageSize in 1..MAX_PHOTO_TIMELINE_PAGE_SIZE) {
            "The photo timeline page size is invalid."
        }
        require(retentionLimit in 1..MAX_PHOTO_TIMELINE_RETAINED_ITEMS) {
            "The photo timeline retention limit is invalid."
        }
        require(entries.size <= retentionLimit) {
            "The photo timeline contains more entries than its retention limit."
        }
        require(discardedNewerEntries >= 0) {
            "The discarded photo timeline count is invalid."
        }
        require(
            revalidationCursorCatchUpPagesRemaining in
                0..MAX_PHOTO_TIMELINE_REVALIDATION_CATCH_UP_PAGES,
        ) {
            "The photo timeline revalidation catch-up count is invalid."
        }
        require(
            (revalidationCursorCatchUpPagesRemaining > 0) ==
                (revalidationCursorCatchUpTailIdentity != null),
        ) {
            "The photo timeline revalidation catch-up target is invalid."
        }
        require(entries.map(PhotoTimelineEntry::identity).distinct().size == entries.size) {
            "The photo timeline contains duplicate media identities."
        }
        require(entries.zipWithNext().all { (newer, older) ->
            newer.capturedAtEpochSeconds >= older.capturedAtEpochSeconds
        }) {
            "The photo timeline entries are not ordered newest first."
        }
    }

    val canLoadNextPage: Boolean
        get() = loading == null && nextCursor != null

    val canPrefetchNextPage: Boolean
        get() = canLoadNextPage && error == null

    val hasDiscardedNewerEntries: Boolean
        get() = discardedNewerEntries > 0

    val revalidationCursorCatchUp: Boolean
        get() = revalidationCursorCatchUpPagesRemaining > 0

    fun beginRefresh(): PhotoTimelineLoadStart {
        val nextGeneration = generation + 1L
        val token = PhotoTimelineLoadToken(
            generation = nextGeneration,
            kind = PhotoTimelineLoadKind.Refresh,
            cursor = null,
            pageSize = pageSize,
        )
        return PhotoTimelineLoadStart(
            state = copy(
                loading = token,
                error = null,
                failedLoadKind = null,
                generation = nextGeneration,
            ),
            token = token,
        )
    }

    fun beginNextPage(): PhotoTimelineLoadStart {
        if (!canLoadNextPage) return PhotoTimelineLoadStart(this, null)
        val token = PhotoTimelineLoadToken(
            generation = generation,
            kind = PhotoTimelineLoadKind.NextPage,
            cursor = nextCursor,
            pageSize = pageSize,
        )
        return PhotoTimelineLoadStart(
            copy(loading = token, error = null, failedLoadKind = null),
            token,
        )
    }

    fun beginNewestRevalidation(): PhotoTimelineLoadStart {
        if (loading != null || hasDiscardedNewerEntries || revalidationCursorCatchUp) {
            return PhotoTimelineLoadStart(this, null)
        }
        val nextGeneration = generation + 1L
        val token = PhotoTimelineLoadToken(
            generation = nextGeneration,
            kind = PhotoTimelineLoadKind.RevalidateNewest,
            cursor = null,
            pageSize = pageSize,
        )
        return PhotoTimelineLoadStart(
            state = copy(
                loading = token,
                error = null,
                failedLoadKind = null,
                generation = nextGeneration,
            ),
            token = token,
        )
    }

    fun accept(
        token: PhotoTimelineLoadToken,
        page: PhotoTimelinePage,
    ): PhotoTimelineState {
        if (token != loading || token.generation != generation) return this
        require(page.entries.size <= token.pageSize) {
            "The photo timeline response exceeded the requested page size."
        }
        val source = when (token.kind) {
            PhotoTimelineLoadKind.Refresh -> emptyList()
            PhotoTimelineLoadKind.RevalidateNewest ->
                reconcileNewestPhotoTimelinePage(entries, page)
            PhotoTimelineLoadKind.NextPage -> entries
        }
        val merged = when (token.kind) {
            PhotoTimelineLoadKind.RevalidateNewest -> source
            else -> mergePhotoTimelineEntries(source, page.entries)
        }
        val overflow = (merged.size - retentionLimit).coerceAtLeast(0)
        val retained = when {
            overflow == 0 -> merged
            token.kind == PhotoTimelineLoadKind.NextPage -> merged.drop(overflow)
            else -> merged.take(retentionLimit)
        }
        val retainedSamePagingKeys = retained.size == entries.size &&
            retained.zip(entries).all { (next, current) ->
                next.identity == current.identity &&
                    next.capturedAtEpochSeconds == current.capturedAtEpochSeconds
            }
        val cursorRepeated = page.nextCursor != null && page.nextCursor == token.cursor
        val catchUpTailReached =
            token.kind == PhotoTimelineLoadKind.NextPage &&
                revalidationCursorCatchUpTailIdentity?.let { tailIdentity ->
                    page.entries.any { entry -> entry.identity == tailIdentity }
                } == true
        val catchUpRequestLimitReached =
            token.kind == PhotoTimelineLoadKind.NextPage &&
                revalidationCursorCatchUp &&
                !catchUpTailReached &&
                page.nextCursor != null &&
                revalidationCursorCatchUpPagesRemaining == 1
        val stalled = token.kind == PhotoTimelineLoadKind.NextPage &&
            (
                cursorRepeated ||
                    catchUpRequestLimitReached ||
                    (
                        !revalidationCursorCatchUp &&
                            (
                                page.entries.none { incoming ->
                                    entries.none { existing -> existing.identity == incoming.identity }
                                } ||
                                    retainedSamePagingKeys
                                )
                        )
                )
        val cachedTailIdentity = entries.lastOrNull()?.identity
        val revalidationRestartsFromFreshCursor =
            token.kind == PhotoTimelineLoadKind.RevalidateNewest &&
                loadedOlderPages &&
                page.nextCursor != null
        val revalidationNeedsCursorCatchUp =
            revalidationRestartsFromFreshCursor &&
                cachedTailIdentity != null &&
                page.entries.none { entry -> entry.identity == cachedTailIdentity }
        val acceptedCursor = when {
            stalled -> null
            revalidationRestartsFromFreshCursor -> page.nextCursor
            else -> page.nextCursor
        }
        return copy(
            entries = retained,
            nextCursor = acceptedCursor,
            loading = null,
            error = when {
                catchUpRequestLimitReached ->
                    "The photo timeline revalidation exceeded its paging limit."
                stalled -> "The server repeated the same photo timeline page."
                else -> null
            },
            failedLoadKind = when {
                catchUpRequestLimitReached || stalled -> token.kind
                else -> null
            },
            discardedNewerEntries = when {
                token.kind == PhotoTimelineLoadKind.Refresh -> 0
                stalled || token.kind == PhotoTimelineLoadKind.RevalidateNewest ->
                    discardedNewerEntries
                else -> (discardedNewerEntries.toLong() + overflow)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            },
            loadedOlderPages = when (token.kind) {
                PhotoTimelineLoadKind.Refresh -> false
                PhotoTimelineLoadKind.RevalidateNewest ->
                    loadedOlderPages && page.nextCursor != null
                PhotoTimelineLoadKind.NextPage -> loadedOlderPages || !stalled
            },
            revalidationCursorCatchUpPagesRemaining = when (token.kind) {
                PhotoTimelineLoadKind.Refresh -> 0
                PhotoTimelineLoadKind.RevalidateNewest ->
                    if (revalidationNeedsCursorCatchUp) {
                        MAX_PHOTO_TIMELINE_REVALIDATION_CATCH_UP_PAGES
                    } else {
                        0
                    }
                PhotoTimelineLoadKind.NextPage ->
                    if (
                        revalidationCursorCatchUp &&
                        !stalled &&
                        !catchUpTailReached &&
                        page.nextCursor != null
                    ) {
                        revalidationCursorCatchUpPagesRemaining - 1
                    } else {
                        0
                    }
            },
            revalidationCursorCatchUpTailIdentity = when (token.kind) {
                PhotoTimelineLoadKind.Refresh -> null
                PhotoTimelineLoadKind.RevalidateNewest ->
                    if (revalidationNeedsCursorCatchUp) cachedTailIdentity else null
                PhotoTimelineLoadKind.NextPage ->
                    if (
                        revalidationCursorCatchUp &&
                        !stalled &&
                        !catchUpTailReached &&
                        page.nextCursor != null
                    ) {
                        revalidationCursorCatchUpTailIdentity
                    } else {
                        null
                    }
            },
            rawEverObserved = rawEverObserved || page.rawObserved,
        )
    }

    fun fail(
        token: PhotoTimelineLoadToken,
        message: String,
    ): PhotoTimelineState {
        if (token != loading || token.generation != generation) return this
        return copy(
            loading = null,
            error = message.trim().take(512).ifBlank { "Could not load the photo timeline." },
            failedLoadKind = token.kind,
        )
    }

    fun cancelPendingLoad(): PhotoTimelineState = if (loading == null) {
        this
    } else {
        copy(
            loading = null,
            error = null,
            failedLoadKind = null,
            generation = generation + 1L,
        )
    }

    fun cancel(token: PhotoTimelineLoadToken): PhotoTimelineState =
        if (token != loading || token.generation != generation) {
            this
        } else {
            cancelPendingLoad()
        }
}

private fun reconcileNewestPhotoTimelinePage(
    existing: List<PhotoTimelineEntry>,
    newestPage: PhotoTimelinePage,
): List<PhotoTimelineEntry> {
    val retainWithoutAuthoritativeRawRemoval: (PhotoTimelineEntry) -> Boolean = { entry ->
        !newestPage.optionalRawRemovalAuthoritative && entry.file.isRawPhoto()
    }
    if (newestPage.nextCursor == null) {
        return mergePhotoTimelineEntries(
            existing = existing.filter(retainWithoutAuthoritativeRawRemoval),
            incoming = newestPage.entries,
        )
    }
    val incomingIdentities = newestPage.entries
        .mapTo(mutableSetOf(), PhotoTimelineEntry::identity)
    val oldestIncomingTimestamp = newestPage.entries
        .minOfOrNull(PhotoTimelineEntry::capturedAtEpochSeconds)
        ?: return existing
    val retainedCached = existing.filter { entry ->
        entry.identity in incomingIdentities ||
            entry.capturedAtEpochSeconds <= oldestIncomingTimestamp ||
            retainWithoutAuthoritativeRawRemoval(entry)
    }
    return mergePhotoTimelineEntries(retainedCached, newestPage.entries)
}

fun mergePhotoTimelineEntries(
    existing: List<PhotoTimelineEntry>,
    incoming: List<PhotoTimelineEntry>,
): List<PhotoTimelineEntry> {
    val byIdentity = LinkedHashMap<String, PhotoTimelineEntry>(existing.size + incoming.size)
    existing.forEach { entry -> byIdentity[entry.identity] = entry }
    incoming.forEach { entry -> byIdentity[entry.identity] = entry }
    return byIdentity.values.sortedWith(
        compareByDescending<PhotoTimelineEntry>(PhotoTimelineEntry::capturedAtEpochSeconds)
            .thenBy(PhotoTimelineEntry::identity),
    )
}

/**
 * Builds rendered media stacks without allowing folder grouping to change timeline chronology.
 */
data class PhotoTimelineStackEntry(
    val stack: MediaStack,
    val capturedAtEpochSeconds: Long,
) {
    val timelineEntry: PhotoTimelineEntry
        get() = PhotoTimelineEntry(stack.cover, capturedAtEpochSeconds)
}

fun buildPhotoTimelineStackEntries(
    entries: List<PhotoTimelineEntry>,
): List<PhotoTimelineStackEntry> {
    val timestamps = entries.associate { entry ->
        entry.identity to entry.capturedAtEpochSeconds
    }
    return stackMediaFiles(entries.map(PhotoTimelineEntry::file))
        .mapNotNull { stack ->
            stack.members
                .mapNotNull { member -> timestamps[member.toPhotoTimelineEntryIdentity()] }
                .maxOrNull()
                ?.let { capturedAt -> PhotoTimelineStackEntry(stack, capturedAt) }
        }
        .sortedWith(
            compareByDescending<PhotoTimelineStackEntry>(
                PhotoTimelineStackEntry::capturedAtEpochSeconds,
            ).thenBy { entry -> entry.stack.id },
        )
}

private fun NextcloudFile.toPhotoTimelineEntryIdentity(): String =
    fileId?.takeIf { it > 0L }?.let { "file:$it" }
        ?: "path:${path.trim('/')}"

data class PhotoTimelineMonth(
    val year: Int,
    val month: Int,
) : Comparable<PhotoTimelineMonth> {
    init {
        require(year in 1..9999) { "The photo timeline year is invalid." }
        require(month in 1..12) { "The photo timeline month is invalid." }
    }

    val label: String
        get() = "${MONTH_NAMES[month - 1]} $year"

    override fun compareTo(other: PhotoTimelineMonth): Int =
        compareValuesBy(this, other, PhotoTimelineMonth::year, PhotoTimelineMonth::month)
}

data class PhotoTimelineMonthSection(
    val month: PhotoTimelineMonth,
    val firstItemIndex: Int,
    val itemCount: Int,
)

data class PhotoTimelineDateIndex(
    val sections: List<PhotoTimelineMonthSection>,
    val totalItemCount: Int,
) {
    init {
        require(totalItemCount >= 0)
        require(sections.sumOf(PhotoTimelineMonthSection::itemCount) == totalItemCount)
        require(sections.map(PhotoTimelineMonthSection::month).distinct().size == sections.size)
        require(sections.zipWithNext().all { (first, second) ->
            first.firstItemIndex + first.itemCount == second.firstItemIndex &&
                first.month > second.month
        })
    }

    fun itemIndexFor(month: PhotoTimelineMonth): Int? =
        sections.firstOrNull { section -> section.month == month }?.firstItemIndex

    fun sectionAtFraction(fraction: Float): PhotoTimelineMonthSection? {
        if (sections.isEmpty()) return null
        val clamped = fraction.coerceIn(0f, 1f)
        val index = (clamped * (sections.size - 1)).toInt()
        return sections[index]
    }

    fun fractionFor(month: PhotoTimelineMonth): Float? {
        val index = sections.indexOfFirst { section -> section.month == month }
        if (index < 0) return null
        return if (sections.size == 1) 0f else index.toFloat() / (sections.size - 1)
    }
}

/**
 * Maps Compose grid positions back to bounded timeline stack positions.
 *
 * Each month contributes one full-width header before its media items, so grid and timeline
 * indices are not interchangeable. Backup-status refreshes use this mapping to query only media
 * that is actually visible instead of re-reading the complete retained timeline.
 */
fun photoTimelineStackIndicesForGridItems(
    dateIndex: PhotoTimelineDateIndex,
    gridItemIndices: Collection<Int>,
): List<Int> {
    if (gridItemIndices.isEmpty() || dateIndex.sections.isEmpty()) return emptyList()
    return gridItemIndices.asSequence()
        .mapNotNull { gridIndex ->
            if (gridIndex < 0) return@mapNotNull null
            dateIndex.sections.withIndex().firstNotNullOfOrNull { (sectionIndex, section) ->
                val firstGridItem = section.firstItemIndex + sectionIndex + 1
                val offset = gridIndex - firstGridItem
                if (offset in 0 until section.itemCount) {
                    section.firstItemIndex + offset
                } else {
                    null
                }
            }
        }
        .distinct()
        .sorted()
        .toList()
}

fun interface PhotoTimelineMonthResolver {
    fun resolve(epochSeconds: Long): PhotoTimelineMonth
}

/**
 * Fixed offsets are deterministic for tests and UTC servers. Android and desktop can provide a
 * resolver backed by the account's real time zone so daylight-saving transitions remain correct.
 */
class FixedOffsetPhotoTimelineMonthResolver(
    offsetMinutes: Int,
) : PhotoTimelineMonthResolver {
    private val offsetSeconds = offsetMinutes.toLong() * 60L

    init {
        require(offsetMinutes in -18 * 60..18 * 60) {
            "The photo timeline UTC offset is invalid."
        }
    }

    override fun resolve(epochSeconds: Long): PhotoTimelineMonth {
        val localSeconds = safeAdd(epochSeconds, offsetSeconds)
        val localDays = floorDiv(localSeconds, SECONDS_PER_DAY)
        return monthFromUnixEpochDay(localDays)
    }
}

val UtcPhotoTimelineMonthResolver: PhotoTimelineMonthResolver =
    FixedOffsetPhotoTimelineMonthResolver(0)

internal expect fun platformLocalPhotoTimelineMonthResolver(): PhotoTimelineMonthResolver

fun buildPhotoTimelineDateIndex(
    entries: List<PhotoTimelineEntry>,
    monthResolver: PhotoTimelineMonthResolver = UtcPhotoTimelineMonthResolver,
): PhotoTimelineDateIndex {
    if (entries.isEmpty()) return PhotoTimelineDateIndex(emptyList(), 0)
    require(entries.zipWithNext().all { (newer, older) ->
        newer.capturedAtEpochSeconds >= older.capturedAtEpochSeconds
    }) {
        "The photo timeline date index requires newest-first entries."
    }
    val sections = mutableListOf<PhotoTimelineMonthSection>()
    entries.forEachIndexed { index, entry ->
        val month = monthResolver.resolve(entry.capturedAtEpochSeconds)
        val last = sections.lastOrNull()
        if (last?.month == month) {
            sections[sections.lastIndex] = last.copy(itemCount = last.itemCount + 1)
        } else {
            sections += PhotoTimelineMonthSection(
                month = month,
                firstItemIndex = index,
                itemCount = 1,
            )
        }
    }
    return PhotoTimelineDateIndex(sections, entries.size)
}

private fun monthFromUnixEpochDay(epochDay: Long): PhotoTimelineMonth {
    val zeroDay = safeAdd(epochDay, 719_468L)
    val era = floorDiv(zeroDay, 146_097L)
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra = (
        dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L
        ) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    year += if (month <= 2L) 1L else 0L
    require(year in 1L..9999L) { "The photo timeline date is outside the supported range." }
    return PhotoTimelineMonth(year.toInt(), month.toInt())
}

private fun floorDiv(dividend: Long, divisor: Long): Long {
    val quotient = dividend / divisor
    val remainder = dividend % divisor
    return if (remainder != 0L && (dividend xor divisor) < 0L) quotient - 1L else quotient
}

private fun safeAdd(value: Long, delta: Long): Long = when {
    delta > 0L && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
    delta < 0L && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
    else -> value + delta
}

private const val SECONDS_PER_DAY = 86_400L
private val MONTH_NAMES = listOf(
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)
