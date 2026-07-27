package dev.obiente.nextcloudnative.app

const val MAXIMUM_MEDIA_SEARCH_RESULTS = 80
const val MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST = 8
private val MEDIA_SEARCH_MIME_PATTERNS = listOf("image/%", "video/%")

enum class MediaSearchDavPartition {
    ImageMime,
    VideoMime,
    Raw,
}

data class MediaSearchDavRequest(
    val partition: MediaSearchDavPartition,
    val body: String,
)

data class MediaSearchDavTransportResponse(
    val status: Int,
    val body: ByteArray,
)

fun mediaSearchDavRequestBody(
    userId: String,
    maximumResults: Int = MAXIMUM_MEDIA_SEARCH_RESULTS,
    rawFileNamePatterns: List<String> = emptyList(),
    mimeTypePatterns: List<String> = MEDIA_SEARCH_MIME_PATTERNS,
    excludeCollections: Boolean = true,
): String {
    require(userId.isNotBlank())
    require(maximumResults in 1..MAXIMUM_MEDIA_SEARCH_RESULTS)
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
    val whereFilter = if (excludeCollections) {
        """
            <d:and>
              <d:not><d:is-collection/></d:not>
              $mediaFilters
            </d:and>
        """.trimIndent()
    } else {
        mediaFilters
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:basicsearch>
            <d:select><d:prop>
              <d:displayname/><d:resourcetype/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>
              <oc:fileid/><oc:size/><oc:permissions/><nc:has-preview/>
            </d:prop></d:select>
            <d:from><d:scope><d:href>/files/${escapeMediaSearchXml(userId)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
            <d:where>
              $whereFilter
            </d:where>
            <d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>
            <d:limit><d:nresults>$maximumResults</d:nresults></d:limit>
          </d:basicsearch>
        </d:searchrequest>
    """.trimIndent()
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
        ),
    )
    rawPhotoFileNameSearchPatterns()
        .chunked(MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST)
        .forEach { rawPatterns ->
            add(
                MediaSearchDavRequest(
                    partition = MediaSearchDavPartition.Raw,
                    body = mediaSearchDavRequestBody(
                        userId = userId,
                        maximumResults = maximumResults,
                        rawFileNamePatterns = rawPatterns,
                        mimeTypePatterns = emptyList(),
                    ),
                ),
            )
        }
}

suspend fun <T> collectMediaSearchDavPages(
    requests: List<MediaSearchDavRequest>,
    execute: suspend (body: String) -> MediaSearchDavTransportResponse,
    parse: (body: ByteArray) -> List<T>,
): List<List<T>> {
    require(requests.getOrNull(0)?.partition == MediaSearchDavPartition.ImageMime)
    require(requests.getOrNull(1)?.partition == MediaSearchDavPartition.VideoMime)
    require(requests.drop(2).all { request -> request.partition == MediaSearchDavPartition.Raw })
    val pages = mutableListOf<List<T>>()
    for (request in requests) {
        val response = execute(request.body)
        when {
            response.status == 207 -> pages += parse(response.body)
            request.partition == MediaSearchDavPartition.Raw &&
                isMediaSearchCompatibilityRejection(response.status) -> break
            else -> error("WebDAV media search failed (HTTP ${response.status}).")
        }
    }
    return pages
}

fun isMediaSearchCompatibilityRejection(status: Int): Boolean = status == 400 || status == 422

fun mergeMediaSearchResultPages(
    pages: List<List<NextcloudFile>>,
    maximumResults: Int = MAXIMUM_MEDIA_SEARCH_RESULTS,
): List<NextcloudFile> {
    require(maximumResults in 1..MAXIMUM_MEDIA_SEARCH_RESULTS)
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
        .take(maximumResults)
        .toList()
}

private fun isSafeRawMediaSearchPattern(pattern: String): Boolean =
    pattern.length in 3..16 &&
        pattern.startsWith("%.") &&
        pattern.drop(2).all { character -> character in 'a'..'z' || character in '0'..'9' }

private fun parseDavMediaSearchTimestamp(value: String): Long? {
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
    val year = parts[3].toIntOrNull()?.takeIf { it in 1970..9999 } ?: return null
    val time = parts[4].split(':')
    if (time.size != 3) return null
    val hour = time[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = time[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val second = time[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    if (day !in 1..daysInMediaSearchMonth(year, month)) return null
    return daysSinceUnixEpoch(year, month, day) * 86_400L +
        (hour * 3_600L) + (minute * 60L) + second
}

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
