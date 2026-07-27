package dev.obiente.nextcloudnative.app

const val MAXIMUM_MEDIA_SEARCH_RESULTS = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE
const val PHOTO_TIMELINE_PARTITION_PAGE_SIZE = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE / 2
const val MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST = 8
const val MAXIMUM_RAW_MEDIA_SEARCH_REQUESTS = 15
const val MAXIMUM_MEDIA_SEARCH_RESULT_PAGES = 2 + MAXIMUM_RAW_MEDIA_SEARCH_REQUESTS
private val MEDIA_SEARCH_MIME_PATTERNS = listOf("image/%", "video/%")

enum class MediaSearchDavPartition {
    ImageMime,
    VideoMime,
    Raw,
}

enum class RawMediaSearchCompatibilityPolicy {
    Fail,
    KeepAvailableResults,
}

data class MediaSearchDavRequest(
    val partition: MediaSearchDavPartition,
    val body: String,
    val userId: String,
    val maximumResults: Int,
    val rawFileNamePatterns: List<String> = emptyList(),
)

data class MediaSearchDavTransportResponse(
    val status: Int,
    val body: ByteArray,
)

data class MediaTimelineDavPage(
    val files: List<NextcloudFile>,
    val nextCursor: PhotoTimelineCursor?,
)

fun mediaSearchDavRequestBody(
    userId: String,
    maximumResults: Int = MAXIMUM_MEDIA_SEARCH_RESULTS,
    rawFileNamePatterns: List<String> = emptyList(),
    mimeTypePatterns: List<String> = MEDIA_SEARCH_MIME_PATTERNS,
    excludeCollections: Boolean = true,
    atOrBeforeEpochSeconds: Long? = null,
    firstResult: Int = 0,
): String {
    require(userId.isNotBlank())
    require(maximumResults in 1..MAXIMUM_MEDIA_SEARCH_RESULTS)
    require(firstResult >= 0)
    require(rawFileNamePatterns.size <= MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST)
    require(rawFileNamePatterns.all(::isSafeRawMediaSearchPattern))
    require(mimeTypePatterns.isNotEmpty() || rawFileNamePatterns.isNotEmpty())
    require(mimeTypePatterns.all(MEDIA_SEARCH_MIME_PATTERNS::contains))

    val filters = buildList {
        mimeTypePatterns.forEach { pattern ->
            add(
                "<d:like><d:prop><d:getcontenttype/></d:prop>" +
                    "<d:literal>$pattern</d:literal></d:like>",
            )
        }
        rawFileNamePatterns.forEach { pattern ->
            add(
                """
                    <d:like caseless="yes">
                      <d:prop><d:displayname/></d:prop><d:literal>$pattern</d:literal>
                    </d:like>
                """.trimIndent(),
            )
        }
    }
    val mediaFilters = if (filters.size == 1) {
        filters.single()
    } else {
        """
            <d:or>
              ${filters.joinToString("\n")}
            </d:or>
        """.trimIndent()
    }
    val whereFilters = buildList {
        if (excludeCollections) add("<d:not><d:is-collection/></d:not>")
        add(mediaFilters)
        atOrBeforeEpochSeconds?.let { boundary ->
            add(
                "<d:lte><d:prop><d:getlastmodified/></d:prop>" +
                    "<d:literal>${formatDavMediaSearchTimestamp(boundary)}</d:literal></d:lte>",
            )
        }
    }
    val whereFilter = when (whereFilters.size) {
        1 -> whereFilters.single()
        else -> """
            <d:and>
              ${whereFilters.joinToString("\n")}
            </d:and>
        """.trimIndent()
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"
            xmlns:sd="https://github.com/icewind1991/SearchDAV/ns">
          <d:basicsearch>
            <d:select><d:prop>
              <d:displayname/><d:resourcetype/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>
              <oc:fileid/><oc:size/><oc:permissions/><nc:has-preview/>
            </d:prop></d:select>
            <d:from><d:scope><d:href>/files/${escapeMediaSearchXml(userId)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
            <d:where>
              $whereFilter
            </d:where>
            <d:orderby>
              <d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order>
              <d:order><d:prop><oc:fileid/></d:prop><d:descending/></d:order>
            </d:orderby>
            <d:limit><d:nresults>$maximumResults</d:nresults>${if (firstResult > 0) "<sd:firstresult>$firstResult</sd:firstresult>" else ""}</d:limit>
          </d:basicsearch>
        </d:searchrequest>
    """.trimIndent()
}

/**
 * Loads one bounded timeline window.
 *
 * The first page retains RAW-aware discovery. Later pages advance the image and video MIME
 * partitions independently and never repeat optional filename-based RAW probes.
 */
suspend fun collectMediaTimelineDavPage(
    userId: String,
    cursor: PhotoTimelineCursor?,
    execute: suspend (body: String) -> MediaSearchDavTransportResponse,
    parse: (body: ByteArray) -> List<NextcloudFile>,
    shouldSearchRaw: (List<NextcloudFile>) -> Boolean,
): MediaTimelineDavPage {
    val decodedCursor = cursor?.let(::decodeMediaTimelineDavCursor)
    val mimePages: List<List<NextcloudFile>>
    val allPages: List<List<NextcloudFile>>
    if (decodedCursor == null) {
        allPages = collectMediaSearchDavPages(
            requests = mediaSearchDavRequests(
                userId = userId,
                maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
            ),
            execute = execute,
            parse = parse,
            shouldSearchRaw = shouldSearchRaw,
            rawCompatibilityPolicy = RawMediaSearchCompatibilityPolicy.KeepAvailableResults,
        )
        mimePages = allPages.take(2)
    } else {
        val requests = buildList {
            decodedCursor.image?.let { cursorPart ->
                add(mediaTimelineDavRequest(userId, MediaSearchDavPartition.ImageMime, cursorPart))
            }
            decodedCursor.video?.let { cursorPart ->
                add(mediaTimelineDavRequest(userId, MediaSearchDavPartition.VideoMime, cursorPart))
            }
        }
        val parsedByPartition = requests.associate { request ->
            val response = execute(request.body)
            if (response.status != 207) {
                error("WebDAV media search failed (HTTP ${response.status}).")
            }
            request.partition to parse(response.body)
        }
        mimePages = listOf(
            parsedByPartition[MediaSearchDavPartition.ImageMime].orEmpty(),
            parsedByPartition[MediaSearchDavPartition.VideoMime].orEmpty(),
        )
        allPages = mimePages
    }

    val next = MediaTimelineDavCursor(
        image = nextPartitionCursor(mimePages[0], decodedCursor?.image),
        video = nextPartitionCursor(mimePages[1], decodedCursor?.video),
    )
    val mergedMimeFiles = mergeMediaSearchResultPages(mimePages)
    val selectedFiles = if (allPages.size > 2) {
        val mimePaths = mergedMimeFiles.mapTo(mutableSetOf()) { file -> file.path.trim('/') }
        val rawEnrichment = mergeMediaSearchResultPages(allPages.drop(2))
            .filterNot { file -> file.path.trim('/') in mimePaths }
            .take((DEFAULT_PHOTO_TIMELINE_PAGE_SIZE - mergedMimeFiles.size).coerceAtLeast(0))
        mergedMimeFiles + rawEnrichment
    } else {
        mergedMimeFiles
    }
    return MediaTimelineDavPage(
        files = mergeMediaSearchResultPages(
            pages = listOf(selectedFiles),
            maximumResultsPerPage = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE,
        ),
        nextCursor = next.takeUnless(MediaTimelineDavCursor::isExhausted)?.encode(),
    )
}

private data class MediaTimelineDavCursor(
    val image: MediaTimelineDavCursorPart?,
    val video: MediaTimelineDavCursorPart?,
) {
    val isExhausted: Boolean
        get() = image == null && video == null

    fun encode(): PhotoTimelineCursor = PhotoTimelineCursor(
        "v2|i:${image?.encode() ?: "end"}|v:${video?.encode() ?: "end"}",
    )
}

private data class MediaTimelineDavCursorPart(
    val boundaryEpochSeconds: Long,
    val firstResult: Int,
) {
    init {
        require(firstResult > 0)
    }

    fun encode(): String = "$boundaryEpochSeconds,$firstResult"
}

private fun decodeMediaTimelineDavCursor(cursor: PhotoTimelineCursor): MediaTimelineDavCursor {
    val parts = cursor.value.split('|')
    require(parts.size == 3 && parts[0] == "v2") { "The photo timeline cursor is invalid." }
    fun parsePart(value: String, prefix: String): MediaTimelineDavCursorPart? {
        require(value.startsWith(prefix)) { "The photo timeline cursor is invalid." }
        val token = value.removePrefix(prefix)
        if (token == "end") return null
        val cursorPart = token.split(',')
        require(cursorPart.size == 2) { "The photo timeline cursor is invalid." }
        return MediaTimelineDavCursorPart(
            boundaryEpochSeconds = cursorPart[0].toLongOrNull()
                ?: error("The photo timeline cursor is invalid."),
            firstResult = cursorPart[1].toIntOrNull()
                ?: error("The photo timeline cursor is invalid."),
        )
    }
    return MediaTimelineDavCursor(
        image = parsePart(parts[1], "i:"),
        video = parsePart(parts[2], "v:"),
    )
}

private fun mediaTimelineDavRequest(
    userId: String,
    partition: MediaSearchDavPartition,
    cursor: MediaTimelineDavCursorPart,
): MediaSearchDavRequest {
    require(partition != MediaSearchDavPartition.Raw)
    val pattern = when (partition) {
        MediaSearchDavPartition.ImageMime -> "image/%"
        MediaSearchDavPartition.VideoMime -> "video/%"
        MediaSearchDavPartition.Raw -> error("RAW timeline requests are not cursor-paged.")
    }
    return MediaSearchDavRequest(
        partition = partition,
        body = mediaSearchDavRequestBody(
            userId = userId,
            mimeTypePatterns = listOf(pattern),
            excludeCollections = false,
            atOrBeforeEpochSeconds = cursor.boundaryEpochSeconds,
            firstResult = cursor.firstResult,
            maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
        ),
        userId = userId,
        maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
    )
}

private fun nextPartitionCursor(
    files: List<NextcloudFile>,
    previous: MediaTimelineDavCursorPart?,
): MediaTimelineDavCursorPart? {
    if (files.size < PHOTO_TIMELINE_PARTITION_PAGE_SIZE) return null
    val boundary = files.mapNotNull { file ->
        file.lastModified?.let(::parseDavMediaSearchTimestamp)
    }.minOrNull() ?: return null
    val filesAtBoundary = files.count { file ->
        file.lastModified?.let(::parseDavMediaSearchTimestamp) == boundary
    }
    // The next query includes the boundary timestamp, then skips only the rows already consumed
    // at that boundary. This prevents a full page of equal timestamps from hiding older rows.
    val alreadySkipped = previous
        ?.takeIf { cursorPart -> cursorPart.boundaryEpochSeconds == boundary }
        ?.firstResult
        ?: 0
    require(alreadySkipped <= Int.MAX_VALUE - filesAtBoundary) {
        "The photo timeline cursor is outside the supported range."
    }
    return MediaTimelineDavCursorPart(
        boundaryEpochSeconds = boundary,
        firstResult = alreadySkipped + filesAtBoundary,
    )
}

fun mediaSearchDavRequests(
    userId: String,
    maximumResults: Int = MAXIMUM_MEDIA_SEARCH_RESULTS,
): List<MediaSearchDavRequest> = buildList {
    add(
        MediaSearchDavRequest(
            partition = MediaSearchDavPartition.ImageMime,
            body = mediaSearchDavRequestBody(
                userId = userId,
                maximumResults = maximumResults,
                mimeTypePatterns = listOf("image/%"),
                excludeCollections = false,
            ),
            userId = userId,
            maximumResults = maximumResults,
        ),
    )
    add(
        MediaSearchDavRequest(
            partition = MediaSearchDavPartition.VideoMime,
            body = mediaSearchDavRequestBody(
                userId = userId,
                maximumResults = maximumResults,
                mimeTypePatterns = listOf("video/%"),
                excludeCollections = false,
            ),
            userId = userId,
            maximumResults = maximumResults,
        ),
    )
    rawPhotoFileNameSearchPatterns()
        .chunked(MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST)
        .forEach { rawPatterns ->
            add(
                rawMediaSearchDavRequest(userId, maximumResults, rawPatterns),
            )
        }
}

/**
 * Collects the required MIME pages before considering optional filename-based RAW enrichment.
 *
 * The caller must explicitly decide whether the parsed MIME results justify RAW discovery. This
 * keeps ordinary photo libraries from paying for unsupported RAW queries. When compatibility
 * fallback is enabled, only RAW-specific 400/422 responses and the bounded request limit keep the
 * already available MIME pages; authentication, transport, parsing, and server failures remain
 * visible.
 */
suspend fun <T> collectMediaSearchDavPages(
    requests: List<MediaSearchDavRequest>,
    execute: suspend (body: String) -> MediaSearchDavTransportResponse,
    parse: (body: ByteArray) -> List<T>,
    shouldSearchRaw: (List<T>) -> Boolean,
    rawCompatibilityPolicy: RawMediaSearchCompatibilityPolicy = RawMediaSearchCompatibilityPolicy.Fail,
): List<List<T>> {
    require(requests.getOrNull(0)?.partition == MediaSearchDavPartition.ImageMime)
    require(requests.getOrNull(1)?.partition == MediaSearchDavPartition.VideoMime)
    require(requests.size > 2)
    require(requests.drop(2).all { request -> request.partition == MediaSearchDavPartition.Raw })
    require(requests.take(2).all { request -> request.rawFileNamePatterns.isEmpty() })
    require(requests.drop(2).all { request -> request.rawFileNamePatterns.isNotEmpty() })
    val plannedRawPatterns = requests.drop(2).flatMap(MediaSearchDavRequest::rawFileNamePatterns)
    require(plannedRawPatterns == rawPhotoFileNameSearchPatterns())
    val rawContext = requests[2]
    require(requests.drop(2).all { request ->
        request.userId == rawContext.userId && request.maximumResults == rawContext.maximumResults
    })
    val pages = mutableListOf<List<T>>()
    requests.take(2).forEach { request ->
        val response = execute(request.body)
        if (response.status != 207) {
            error("WebDAV media search failed (HTTP ${response.status}).")
        }
        pages += parse(response.body)
    }
    if (!shouldSearchRaw(pages.flatten())) return pages

    var rawOffset = 0
    var rawChunkSize = MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST
    var rawRequests = 0
    while (rawOffset < plannedRawPatterns.size) {
        if (rawRequests == MAXIMUM_RAW_MEDIA_SEARCH_REQUESTS) {
            if (rawCompatibilityPolicy == RawMediaSearchCompatibilityPolicy.KeepAvailableResults) {
                return pages
            }
            error("WebDAV RAW media search exceeded the compatibility request limit.")
        }
        rawRequests += 1
        val patterns = plannedRawPatterns.drop(rawOffset).take(rawChunkSize)
        val request = rawMediaSearchDavRequest(
            rawContext.userId,
            rawContext.maximumResults,
            patterns,
        )
        val response = execute(request.body)
        when {
            response.status == 207 -> {
                pages += parse(response.body)
                rawOffset += patterns.size
            }
            isMediaSearchCompatibilityRejection(response.status) && patterns.size > 1 -> {
                rawChunkSize = (patterns.size + 1) / 2
            }
            isMediaSearchCompatibilityRejection(response.status) -> {
                if (rawCompatibilityPolicy == RawMediaSearchCompatibilityPolicy.KeepAvailableResults) {
                    return pages
                }
                error("WebDAV RAW media search is not supported by this server (HTTP ${response.status}).")
            }
            else -> error("WebDAV media search failed (HTTP ${response.status}).")
        }
    }
    return pages
}

fun isMediaSearchCompatibilityRejection(status: Int): Boolean = status == 400 || status == 422

private fun rawMediaSearchDavRequest(
    userId: String,
    maximumResults: Int,
    rawFileNamePatterns: List<String>,
): MediaSearchDavRequest {
    require(rawFileNamePatterns.isNotEmpty())
    return MediaSearchDavRequest(
        partition = MediaSearchDavPartition.Raw,
        body = mediaSearchDavRequestBody(
            userId = userId,
            maximumResults = maximumResults,
            rawFileNamePatterns = rawFileNamePatterns,
            mimeTypePatterns = emptyList(),
        ),
        userId = userId,
        maximumResults = maximumResults,
        rawFileNamePatterns = rawFileNamePatterns.toList(),
    )
}

fun mergeMediaSearchResultPages(
    pages: List<List<NextcloudFile>>,
    maximumResultsPerPage: Int = MAXIMUM_MEDIA_SEARCH_RESULTS,
): List<NextcloudFile> {
    require(maximumResultsPerPage in 1..MAX_PHOTO_TIMELINE_PAGE_SIZE)
    require(pages.size <= MAXIMUM_MEDIA_SEARCH_RESULT_PAGES)
    val boundedResultCount = maximumResultsPerPage * pages.size
    return pages.asSequence()
        .flatten()
        .filterNot(NextcloudFile::isDirectory)
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<NextcloudFile>> { indexed ->
                indexed.value.lastModified?.let(::parseDavMediaSearchTimestamp) ?: Long.MIN_VALUE
            }.thenBy(IndexedValue<NextcloudFile>::index),
        )
        .map(IndexedValue<NextcloudFile>::value)
        .distinctBy { file -> file.path.trim('/') }
        .take(boundedResultCount)
        .toList()
}

private fun isSafeRawMediaSearchPattern(pattern: String): Boolean =
    pattern.length in 3..16 &&
        pattern.startsWith("%.") &&
        pattern.drop(2).all { character -> character in 'a'..'z' || character in '0'..'9' }

internal fun parseDavMediaSearchTimestamp(value: String): Long? {
    value.trim().toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
    val parts = value.trim().split(' ').filter(String::isNotBlank)
    if (parts.size != 6 || !parts[0].endsWith(',') || parts[5] !in setOf("GMT", "UTC")) return null
    val day = parts[1].toIntOrNull() ?: return null
    val month = when (parts[2]) {
        "Jan" -> 1
        "Feb" -> 2
        "Mar" -> 3
        "Apr" -> 4
        "May" -> 5
        "Jun" -> 6
        "Jul" -> 7
        "Aug" -> 8
        "Sep" -> 9
        "Oct" -> 10
        "Nov" -> 11
        "Dec" -> 12
        else -> return null
    }
    val yearToken = parts[3]
    if (yearToken.length != 4 || !yearToken.all { character -> character in '0'..'9' }) return null
    val year = yearToken.toIntOrNull()?.takeIf { it in 1..9999 } ?: return null
    val time = parts[4].split(':')
    if (time.size != 3) return null
    val hour = time[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = time[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val second = time[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    if (day !in 1..daysInMediaSearchMonth(year, month)) return null
    return daysSinceUnixEpoch(year, month, day) * 86_400L +
        (hour * 3_600L) + (minute * 60L) + second
}

internal fun formatDavMediaSearchTimestamp(epochSeconds: Long): String {
    val epochDay = floorDivideMediaSearch(epochSeconds, 86_400L)
    val secondOfDay = epochSeconds - epochDay * 86_400L
    val (year, month, day) = mediaSearchDateFromEpochDay(epochDay)
    val hour = secondOfDay / 3_600L
    val minute = secondOfDay % 3_600L / 60L
    val second = secondOfDay % 60L
    val dayOfWeek = MEDIA_SEARCH_DAY_NAMES[
        ((epochDay + 4L) % 7L + 7L).rem(7L).toInt()
    ]
    return "$dayOfWeek, ${day.twoDigits()} ${MEDIA_SEARCH_MONTH_NAMES[month - 1]} " +
        "${year.toString().padStart(4, '0')} " +
        "${hour.twoDigits()}:${minute.twoDigits()}:${second.twoDigits()} GMT"
}

private fun mediaSearchDateFromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
    val zeroDay = epochDay + 719_468L
    val era = zeroDay / 146_097L
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra = (
        dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L
        ) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    year += if (month <= 2L) 1L else 0L
    require(year in 1L..9999L) { "The WebDAV media timestamp is outside the supported range." }
    return Triple(year.toInt(), month.toInt(), day.toInt())
}

private fun floorDivideMediaSearch(dividend: Long, divisor: Long): Long {
    val quotient = dividend / divisor
    val remainder = dividend % divisor
    return if (remainder != 0L && (dividend xor divisor) < 0L) quotient - 1L else quotient
}

private fun Number.twoDigits(): String = toLong().toString().padStart(2, '0')

private val MEDIA_SEARCH_DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MEDIA_SEARCH_MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private fun daysInMediaSearchMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun daysSinceUnixEpoch(year: Int, month: Int, day: Int): Long {
    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = adjustedYear / 400
    val yearOfEra = adjustedYear - (era * 400)
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = ((153 * adjustedMonth) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

private fun escapeMediaSearchXml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}
