package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val MAXIMUM_MEDIA_SEARCH_RESULTS = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE
const val PHOTO_TIMELINE_PARTITION_PAGE_SIZE = DEFAULT_PHOTO_TIMELINE_PAGE_SIZE
const val MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST = 8
const val MAXIMUM_RAW_MEDIA_SEARCH_REQUESTS = 15
const val MAXIMUM_MEDIA_SEARCH_RESULT_PAGES = 2 + MAXIMUM_RAW_MEDIA_SEARCH_REQUESTS
private val MEDIA_SEARCH_MIME_PATTERNS = listOf("image/%", "video/%")
private const val DEFAULT_MEDIA_TIMELINE_CARRYOVER_ACCOUNT_LIMIT = 4
private const val DEFAULT_MEDIA_TIMELINE_CARRYOVER_CURSOR_LIMIT = 4

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
    val optionalRawRemovalAuthoritative: Boolean,
    val rawObserved: Boolean,
)

/**
 * Retains at most one already-fetched server page per active SearchDAV partition.
 *
 * The store is deliberately runtime-only. Its compact cursor remains sufficient for a stateless
 * retry when the process, account LRU, or refresh generation has discarded buffered records.
 */
class MediaTimelineDavCarryoverStore(
    private val maximumAccountScopes: Int = DEFAULT_MEDIA_TIMELINE_CARRYOVER_ACCOUNT_LIMIT,
    private val maximumCursorsPerAccount: Int = DEFAULT_MEDIA_TIMELINE_CARRYOVER_CURSOR_LIMIT,
) {
    private data class AccountState(
        val generation: Long,
        val continuations: LinkedHashMap<String, MediaTimelineDavCarryover>,
    )

    private val mutex = Mutex()
    private val accounts = linkedMapOf<String, AccountState>()
    private var nextGeneration = 0L

    init {
        require(maximumAccountScopes > 0)
        require(maximumCursorsPerAccount > 0)
    }

    internal suspend fun beginAccountGeneration(accountScope: String): Long =
        mutex.withLock {
            requireMediaTimelineAccountScope(accountScope)
            nextGeneration = if (nextGeneration == Long.MAX_VALUE) 1L else nextGeneration + 1L
            accounts.remove(accountScope)
            accounts[accountScope] = AccountState(nextGeneration, linkedMapOf())
            while (accounts.size > maximumAccountScopes) {
                accounts.remove(accounts.keys.first())
            }
            nextGeneration
        }

    internal suspend fun take(
        accountScope: String,
        generation: Long,
        cursor: PhotoTimelineCursor,
    ): MediaTimelineDavCarryover? = mutex.withLock {
        requireMediaTimelineAccountScope(accountScope)
        val account = accounts[accountScope]?.takeIf { it.generation == generation }
            ?: return@withLock null
        val continuation = account.continuations.remove(cursor.value)
        accounts.remove(accountScope)
        accounts[accountScope] = account
        continuation
    }

    internal suspend fun put(
        accountScope: String,
        generation: Long,
        cursor: PhotoTimelineCursor,
        carryover: MediaTimelineDavCarryover,
    ) {
        mutex.withLock {
            requireMediaTimelineAccountScope(accountScope)
            val account = accounts[accountScope]?.takeIf { it.generation == generation }
                ?: return@withLock
            account.continuations.remove(cursor.value)
            account.continuations[cursor.value] = carryover
            while (account.continuations.size > maximumCursorsPerAccount) {
                account.continuations.remove(account.continuations.keys.first())
            }
            accounts.remove(accountScope)
            accounts[accountScope] = account
        }
    }
}

private fun requireMediaTimelineAccountScope(accountScope: String) {
    require(
        accountScope.isNotBlank() &&
            accountScope.length <= 256 &&
            accountScope.none(Char::isISOControl),
    ) {
        "The photo timeline carryover scope is invalid."
    }
}

fun mediaSearchDavRequestBody(
    userId: String,
    maximumResults: Int = MAXIMUM_MEDIA_SEARCH_RESULTS,
    rawFileNamePatterns: List<String> = emptyList(),
    mimeTypePatterns: List<String> = MEDIA_SEARCH_MIME_PATTERNS,
    excludeCollections: Boolean = true,
    atOrBeforeEpochSeconds: Long? = null,
    firstResult: Int = 0,
    strictlyBeforeEpochSeconds: Long? = null,
    strictlyBeforeFileId: Long? = null,
): String {
    require(userId.isNotBlank())
    require(maximumResults in 1..MAXIMUM_MEDIA_SEARCH_RESULTS)
    require(firstResult >= 0)
    require((strictlyBeforeEpochSeconds == null) == (strictlyBeforeFileId == null))
    require(strictlyBeforeEpochSeconds == null || atOrBeforeEpochSeconds == null)
    require(strictlyBeforeEpochSeconds == null || firstResult == 0)
    require(strictlyBeforeFileId == null || strictlyBeforeFileId >= 0L)
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
        if (strictlyBeforeEpochSeconds != null && strictlyBeforeFileId != null) {
            val timestamp = formatDavMediaSearchTimestamp(strictlyBeforeEpochSeconds)
            add(
                """
                    <d:or>
                      <d:lt><d:prop><d:getlastmodified/></d:prop><d:literal>$timestamp</d:literal></d:lt>
                      <d:and>
                        <d:eq><d:prop><d:getlastmodified/></d:prop><d:literal>$timestamp</d:literal></d:eq>
                        <d:lt><d:prop><oc:fileid/></d:prop><d:literal>$strictlyBeforeFileId</d:literal></d:lt>
                      </d:and>
                    </d:or>
                """.trimIndent(),
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
 * The first page retains RAW-aware discovery. When MIME results or account-scoped cached state
 * prove that the library contains RAW media, each successful filename partition receives its own
 * cursor and can advance on later pages. Libraries without observed RAW media never issue those
 * optional filename searches.
 */
suspend fun collectMediaTimelineDavPage(
    userId: String,
    cursor: PhotoTimelineCursor?,
    execute: suspend (body: String) -> MediaSearchDavTransportResponse,
    parse: (body: ByteArray) -> List<NextcloudFile>,
    shouldSearchRaw: (List<NextcloudFile>) -> Boolean,
    carryoverStore: MediaTimelineDavCarryoverStore? = null,
    carryoverAccountScope: String? = null,
): MediaTimelineDavPage {
    require((carryoverStore == null) == (carryoverAccountScope == null)) {
        "The photo timeline carryover scope is invalid."
    }
    carryoverAccountScope?.let(::requireMediaTimelineAccountScope)
    val decodedCursor = cursor?.let(::decodeMediaTimelineDavCursor)
    val runtimeGeneration = when {
        carryoverStore == null -> null
        cursor == null -> carryoverStore.beginAccountGeneration(requireNotNull(carryoverAccountScope))
        else -> decodedCursor?.runtimeGeneration
    }
    val runtimeCarryover = if (
        carryoverStore != null &&
        cursor != null &&
        runtimeGeneration != null
    ) {
        carryoverStore.take(
            accountScope = requireNotNull(carryoverAccountScope),
            generation = runtimeGeneration,
            cursor = cursor,
        )
    } else {
        null
    }
    val partitionPages = mutableListOf<MediaTimelinePartitionPage>()

    suspend fun loadMime(
        partition: MediaSearchDavPartition,
        logicalPrevious: MediaTimelineDavCursorPart?,
    ) {
        if (decodedCursor != null && logicalPrevious == null) return
        val key = MediaTimelinePartitionKey.Mime(partition)
        val retained = runtimeCarryover?.partitions?.get(key)
        if (retained != null) {
            partitionPages += MediaTimelinePartitionPage(
                key = key,
                files = retained.files,
                logicalPrevious = requireNotNull(logicalPrevious),
                remoteCursorAfterFetched = retained.remoteCursorAfterFetched,
            )
            return
        }
        val queryCursor = logicalPrevious
        val request = if (decodedCursor == null) {
            mediaSearchDavRequests(
                userId = userId,
                maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
            ).first { candidate -> candidate.partition == partition }
        } else {
            mediaTimelineDavRequest(userId, partition, requireNotNull(queryCursor))
        }
        var effectivePrevious = queryCursor
        var response = execute(request.body)
        if (
            queryCursor?.keysetBoundary != null &&
            isMediaSearchCompatibilityRejection(response.status)
        ) {
            effectivePrevious = queryCursor.asLegacyFallback()
            response = execute(
                mediaTimelineDavRequest(
                    userId = userId,
                    partition = partition,
                    cursor = effectivePrevious,
                ).body,
            )
        }
        if (response.status != 207) {
            error("WebDAV media search failed (HTTP ${response.status}).")
        }
        val files = orderedTimelinePartition(parse(response.body))
        partitionPages += MediaTimelinePartitionPage(
            key = key,
            files = files,
            logicalPrevious = effectivePrevious,
            remoteCursorAfterFetched = advancedPartitionCursor(
                consumedFiles = files,
                previous = effectivePrevious,
                hasMore = files.size >= PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
            ),
        )
    }

    loadMime(MediaSearchDavPartition.ImageMime, decodedCursor?.image)
    loadMime(MediaSearchDavPartition.VideoMime, decodedCursor?.video)

    var rawObserved = decodedCursor?.raw?.isNotEmpty() == true
    if (decodedCursor == null) {
        val mimeFiles = partitionPages.flatMap(MediaTimelinePartitionPage::files)
        if (shouldSearchRaw(mimeFiles)) {
            rawObserved = true
            partitionPages += collectInitialRawTimelinePartitions(
                userId = userId,
                execute = execute,
                parse = parse,
            )
        }
    } else {
        decodedCursor.raw.forEach { raw ->
            val key = MediaTimelinePartitionKey.Raw(raw.patternIndexes)
            val retained = runtimeCarryover?.partitions?.get(key)
            if (retained != null) {
                partitionPages += MediaTimelinePartitionPage(
                    key = key,
                    files = retained.files,
                    logicalPrevious = raw.cursor,
                    remoteCursorAfterFetched = retained.remoteCursorAfterFetched,
                )
                return@forEach
            }
            val patterns = raw.patternIndexes.map { index ->
                rawPhotoFileNameSearchPatterns()[index]
            }
            var effectivePrevious = raw.cursor
            var response = execute(
                mediaTimelineRawDavRequest(userId, patterns, effectivePrevious).body,
            )
            if (
                effectivePrevious.keysetBoundary != null &&
                isMediaSearchCompatibilityRejection(response.status)
            ) {
                effectivePrevious = effectivePrevious.asLegacyFallback()
                response = execute(
                    mediaTimelineRawDavRequest(userId, patterns, effectivePrevious).body,
                )
            }
            when {
                response.status == 207 -> {
                    val files = orderedTimelinePartition(parse(response.body))
                    partitionPages += MediaTimelinePartitionPage(
                        key = key,
                        files = files,
                        logicalPrevious = effectivePrevious,
                        remoteCursorAfterFetched = advancedPartitionCursor(
                            consumedFiles = files,
                            previous = effectivePrevious,
                            hasMore = files.size >= PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
                        ),
                    )
                }
                isMediaSearchCompatibilityRejection(response.status) -> Unit
                else -> error("WebDAV media search failed (HTTP ${response.status}).")
            }
        }
    }

    val merged = mergeMediaTimelinePartitionPages(
        pages = partitionPages,
        runtimeGeneration = runtimeGeneration,
    )
    val coveredRawPatternIndexes = partitionPages
        .mapNotNull { page -> page.key as? MediaTimelinePartitionKey.Raw }
        .flatMap { raw -> raw.patternIndexes }
        .toSet()
    val nextCursor = merged.nextCursor
        .takeUnless(MediaTimelineDavCursor::isExhausted)
        ?.encode()
    if (
        carryoverStore != null &&
        runtimeGeneration != null &&
        nextCursor != null &&
        merged.carryover.partitions.isNotEmpty()
    ) {
        carryoverStore.put(
            accountScope = requireNotNull(carryoverAccountScope),
            generation = runtimeGeneration,
            cursor = nextCursor,
            carryover = merged.carryover,
        )
    }
    return MediaTimelineDavPage(
        files = merged.files,
        nextCursor = nextCursor,
        optionalRawRemovalAuthoritative =
            coveredRawPatternIndexes.size == rawPhotoFileNameSearchPatterns().size,
        rawObserved = rawObserved,
    )
}

private data class MediaTimelineDavCursor(
    val image: MediaTimelineDavCursorPart?,
    val video: MediaTimelineDavCursorPart?,
    val raw: List<MediaTimelineDavRawCursor> = emptyList(),
    val runtimeGeneration: Long? = null,
) {
    val isExhausted: Boolean
        get() = image == null && video == null && raw.isEmpty()

    fun encode(): PhotoTimelineCursor {
        val partitions = "i:${image?.encodeV4() ?: "end"}|v:${video?.encodeV4() ?: "end"}|r:" +
            raw.joinToString(";") { cursor ->
                "${cursor.patternMask.toString(16)}@${cursor.cursor.encodeV4()}"
            }
        return PhotoTimelineCursor(
            runtimeGeneration?.let { generation ->
                "v4c|g:${generation.toString(16)}|$partitions"
            } ?: "v4|$partitions",
        )
    }
}

internal data class MediaTimelineDavKeysetBoundary(
    val epochSeconds: Long,
    val fileId: Long,
) {
    init {
        require(fileId >= 0L)
    }
}

internal data class MediaTimelineDavCursorPart(
    val boundaryEpochSeconds: Long?,
    val firstResult: Int,
    private val keyset: MediaTimelineDavKeysetBoundary? = null,
) {
    init {
        require(firstResult >= 0)
        require(boundaryEpochSeconds != null || firstResult == 0)
    }

    internal val keysetBoundary: Pair<Long, Long>?
        get() = keyset?.let { boundary -> boundary.epochSeconds to boundary.fileId }

    fun encodeV4(): String = keyset?.let { boundary ->
        "k,${boundary.epochSeconds},${boundary.fileId}," +
            "${boundaryEpochSeconds ?: "start"},$firstResult"
    } ?: "o,${boundaryEpochSeconds ?: "start"},$firstResult"

    fun asLegacyFallback(): MediaTimelineDavCursorPart = copy(keyset = null)
}

private fun decodeMediaTimelineDavCursor(cursor: PhotoTimelineCursor): MediaTimelineDavCursor {
    val parts = cursor.value.split('|')
    val version = parts.firstOrNull()
    require(
        (version == "v2" && parts.size == 3) ||
            (version == "v3" && parts.size == 4) ||
            (version == "v3c" && parts.size == 5) ||
            (version == "v4" && parts.size == 4) ||
            (version == "v4c" && parts.size == 5),
    ) {
        "The photo timeline cursor is invalid."
    }
    fun parsePart(value: String, prefix: String): MediaTimelineDavCursorPart? {
        require(value.startsWith(prefix)) { "The photo timeline cursor is invalid." }
        val token = value.removePrefix(prefix)
        if (token == "end") return null
        if (version == "v4" || version == "v4c") {
            val cursorPart = token.split(',')
            return when (cursorPart.firstOrNull()) {
                "o" -> {
                    require(cursorPart.size == 3) { "The photo timeline cursor is invalid." }
                    MediaTimelineDavCursorPart(
                        boundaryEpochSeconds = parseMediaTimelineCursorEpoch(cursorPart[1]),
                        firstResult = cursorPart[2].toIntOrNull()
                            ?: error("The photo timeline cursor is invalid."),
                    )
                }
                "k" -> {
                    require(cursorPart.size == 5) { "The photo timeline cursor is invalid." }
                    val keysetEpoch = cursorPart[1].toLongOrNull()
                        ?: error("The photo timeline cursor is invalid.")
                    val keysetFileId = cursorPart[2].toLongOrNull()
                        ?: error("The photo timeline cursor is invalid.")
                    val fallbackEpoch = parseMediaTimelineCursorEpoch(cursorPart[3])
                    require(fallbackEpoch == keysetEpoch) {
                        "The photo timeline cursor is invalid."
                    }
                    MediaTimelineDavCursorPart(
                        boundaryEpochSeconds = fallbackEpoch,
                        firstResult = cursorPart[4].toIntOrNull()
                            ?: error("The photo timeline cursor is invalid."),
                        keyset = MediaTimelineDavKeysetBoundary(keysetEpoch, keysetFileId),
                    )
                }
                else -> error("The photo timeline cursor is invalid.")
            }
        }
        val cursorPart = token.split(',')
        require(cursorPart.size == 2) { "The photo timeline cursor is invalid." }
        return MediaTimelineDavCursorPart(
            boundaryEpochSeconds = parseMediaTimelineCursorEpoch(cursorPart[0]),
            firstResult = cursorPart[1].toIntOrNull()
                ?: error("The photo timeline cursor is invalid."),
        )
    }
    val hasRuntimeGeneration = version == "v3c" || version == "v4c"
    val partitionOffset = if (hasRuntimeGeneration) 1 else 0
    val runtimeGeneration = if (hasRuntimeGeneration) {
        require(parts[1].startsWith("g:")) { "The photo timeline cursor is invalid." }
        parts[1].removePrefix("g:").toLongOrNull(16)
            ?.takeIf { it > 0L }
            ?: error("The photo timeline cursor is invalid.")
    } else {
        null
    }
    val raw = if (version != "v2") {
        val rawPart = parts[3 + partitionOffset]
        require(rawPart.startsWith("r:")) {
            "The photo timeline cursor is invalid."
        }
        rawPart.removePrefix("r:").takeIf(String::isNotBlank)
            ?.split(';')
            .orEmpty()
            .map { encoded ->
                val split = encoded.split('@', limit = 2)
                require(split.size == 2) { "The photo timeline cursor is invalid." }
                val mask = split[0].toIntOrNull(16)
                    ?: error("The photo timeline cursor is invalid.")
                MediaTimelineDavRawCursor(
                    patternIndexes = rawPatternIndexes(mask),
                    cursor = requireNotNull(parsePart("r:${split[1]}", "r:")),
                )
            }
    } else {
        emptyList()
    }
    return MediaTimelineDavCursor(
        image = parsePart(parts[1 + partitionOffset], "i:"),
        video = parsePart(parts[2 + partitionOffset], "v:"),
        raw = raw,
        runtimeGeneration = runtimeGeneration,
    )
}

private fun parseMediaTimelineCursorEpoch(value: String): Long? =
    value.takeUnless { it == "start" }?.toLongOrNull()
        ?: if (value == "start") null else error("The photo timeline cursor is invalid.")

private fun mediaTimelineDavRequest(
    userId: String,
    partition: MediaSearchDavPartition,
    cursor: MediaTimelineDavCursorPart,
): MediaSearchDavRequest {
    require(partition != MediaSearchDavPartition.Raw)
    val pattern = when (partition) {
        MediaSearchDavPartition.ImageMime -> "image/%"
        MediaSearchDavPartition.VideoMime -> "video/%"
        MediaSearchDavPartition.Raw -> error("RAW timeline pages use filename partition requests.")
    }
    return MediaSearchDavRequest(
        partition = partition,
        body = mediaSearchDavRequestBody(
            userId = userId,
            mimeTypePatterns = listOf(pattern),
            excludeCollections = false,
            atOrBeforeEpochSeconds =
                if (cursor.keysetBoundary == null) cursor.boundaryEpochSeconds else null,
            firstResult = if (cursor.keysetBoundary == null) cursor.firstResult else 0,
            strictlyBeforeEpochSeconds = cursor.keysetBoundary?.first,
            strictlyBeforeFileId = cursor.keysetBoundary?.second,
            maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
        ),
        userId = userId,
        maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
    )
}

private fun mediaTimelineRawDavRequest(
    userId: String,
    patterns: List<String>,
    cursor: MediaTimelineDavCursorPart,
): MediaSearchDavRequest = MediaSearchDavRequest(
    partition = MediaSearchDavPartition.Raw,
    body = mediaSearchDavRequestBody(
        userId = userId,
        maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
        rawFileNamePatterns = patterns,
        mimeTypePatterns = emptyList(),
        atOrBeforeEpochSeconds =
            if (cursor.keysetBoundary == null) cursor.boundaryEpochSeconds else null,
        firstResult = if (cursor.keysetBoundary == null) cursor.firstResult else 0,
        strictlyBeforeEpochSeconds = cursor.keysetBoundary?.first,
        strictlyBeforeFileId = cursor.keysetBoundary?.second,
    ),
    userId = userId,
    maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
    rawFileNamePatterns = patterns,
)

private fun advancedPartitionCursor(
    consumedFiles: List<NextcloudFile>,
    previous: MediaTimelineDavCursorPart?,
    hasMore: Boolean,
): MediaTimelineDavCursorPart? {
    if (!hasMore) return null
    if (consumedFiles.isEmpty()) return previous ?: MediaTimelineDavCursorPart(null, 0)
    val boundary = consumedFiles.mapNotNull { file ->
        file.lastModified?.let(::parseDavMediaSearchTimestamp)
    }.minOrNull() ?: return null
    val filesAtBoundary = consumedFiles.count { file ->
        file.lastModified?.let(::parseDavMediaSearchTimestamp) == boundary
    }
    // Keep the timestamp offset as a compatibility fallback even when the next request can use
    // the stable timestamp and file-ID keyset.
    val alreadySkipped = previous
        ?.takeIf { cursorPart -> cursorPart.boundaryEpochSeconds == boundary }
        ?.firstResult
        ?: 0
    require(alreadySkipped <= Int.MAX_VALUE - filesAtBoundary) {
        "The photo timeline cursor is outside the supported range."
    }
    val keysetBoundary = consumedFiles.lastOrNull()?.let { file ->
        val epochSeconds = file.lastModified?.let(::parseDavMediaSearchTimestamp)
        val fileId = file.fileId
        val mayUseKeyset = previous == null || previous.keysetBoundary != null
        if (!mayUseKeyset || epochSeconds == null || fileId == null || fileId < 0L) {
            null
        } else {
            MediaTimelineDavKeysetBoundary(epochSeconds, fileId)
        }
    }
    return MediaTimelineDavCursorPart(
        boundaryEpochSeconds = boundary,
        firstResult = alreadySkipped + filesAtBoundary,
        keyset = keysetBoundary,
    )
}

internal sealed interface MediaTimelinePartitionKey {
    data class Mime(val partition: MediaSearchDavPartition) : MediaTimelinePartitionKey
    data class Raw(val patternIndexes: List<Int>) : MediaTimelinePartitionKey
}

private data class MediaTimelinePartitionPage(
    val key: MediaTimelinePartitionKey,
    val files: List<NextcloudFile>,
    val logicalPrevious: MediaTimelineDavCursorPart?,
    val remoteCursorAfterFetched: MediaTimelineDavCursorPart?,
)

internal data class MediaTimelinePartitionCarryover(
    val files: List<NextcloudFile>,
    val remoteCursorAfterFetched: MediaTimelineDavCursorPart?,
) {
    init {
        require(files.isNotEmpty())
        require(files.size <= PHOTO_TIMELINE_PARTITION_PAGE_SIZE)
    }
}

internal data class MediaTimelineDavCarryover(
    val partitions: Map<MediaTimelinePartitionKey, MediaTimelinePartitionCarryover>,
) {
    init {
        require(partitions.size <= MAXIMUM_MEDIA_SEARCH_RESULT_PAGES)
        require(
            partitions.values.sumOf { it.files.size } <=
                MAXIMUM_MEDIA_SEARCH_RESULT_PAGES * PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
        )
    }
}

private data class MediaTimelineDavRawCursor(
    val patternIndexes: List<Int>,
    val cursor: MediaTimelineDavCursorPart,
) {
    init {
        require(patternIndexes.isNotEmpty())
        require(patternIndexes.size <= MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST)
        require(patternIndexes.distinct().size == patternIndexes.size)
        require(patternIndexes.all { it in rawPhotoFileNameSearchPatterns().indices })
    }

    val patternMask: Int
        get() = patternIndexes.fold(0) { mask, index -> mask or (1 shl index) }
}

private data class MergedMediaTimelinePartitions(
    val files: List<NextcloudFile>,
    val nextCursor: MediaTimelineDavCursor,
    val carryover: MediaTimelineDavCarryover,
)

private fun mergeMediaTimelinePartitionPages(
    pages: List<MediaTimelinePartitionPage>,
    runtimeGeneration: Long?,
): MergedMediaTimelinePartitions {
    val candidates = pages.flatMapIndexed { partitionIndex, page ->
        page.files.mapIndexed { fileIndex, file ->
            Triple(partitionIndex, fileIndex, file)
        }
    }.sortedWith(
        compareByDescending<Triple<Int, Int, NextcloudFile>> { candidate ->
            candidate.third.lastModified?.let(::parseDavMediaSearchTimestamp) ?: Long.MIN_VALUE
        }.thenBy { candidate -> candidate.first }
            .thenBy { candidate -> candidate.second },
    )
    val consumed = IntArray(pages.size)
    val selected = mutableListOf<NextcloudFile>()
    val selectedPaths = mutableSetOf<String>()
    candidates.forEach { (partitionIndex, fileIndex, file) ->
        if (selected.size >= DEFAULT_PHOTO_TIMELINE_PAGE_SIZE) return@forEach
        consumed[partitionIndex] = maxOf(consumed[partitionIndex], fileIndex + 1)
        if (selectedPaths.add(file.path.trim('/'))) selected += file
    }
    val carryover = linkedMapOf<MediaTimelinePartitionKey, MediaTimelinePartitionCarryover>()
    val nextParts = pages.mapIndexedNotNull { index, page ->
        val consumedCount = consumed[index]
        val unconsumedFiles = page.files.drop(consumedCount)
        val mayHaveAnotherServerPage =
            unconsumedFiles.isNotEmpty() || page.remoteCursorAfterFetched != null
        val cursor = if (unconsumedFiles.isEmpty()) {
            page.remoteCursorAfterFetched
        } else {
            advancedPartitionCursor(
                consumedFiles = page.files.take(consumedCount),
                previous = page.logicalPrevious,
                hasMore = mayHaveAnotherServerPage,
            )
        } ?: return@mapIndexedNotNull null
        if (unconsumedFiles.isNotEmpty()) {
            carryover[page.key] = MediaTimelinePartitionCarryover(
                files = unconsumedFiles,
                remoteCursorAfterFetched = page.remoteCursorAfterFetched,
            )
        }
        page.key to cursor
    }
    return MergedMediaTimelinePartitions(
        files = selected,
        nextCursor = MediaTimelineDavCursor(
            image = nextParts.firstOrNull { (key, _) ->
                key == MediaTimelinePartitionKey.Mime(MediaSearchDavPartition.ImageMime)
            }?.second,
            video = nextParts.firstOrNull { (key, _) ->
                key == MediaTimelinePartitionKey.Mime(MediaSearchDavPartition.VideoMime)
            }?.second,
            raw = nextParts.mapNotNull { (key, cursor) ->
                (key as? MediaTimelinePartitionKey.Raw)?.let { raw ->
                    MediaTimelineDavRawCursor(raw.patternIndexes, cursor)
                }
            },
            runtimeGeneration = runtimeGeneration,
        ),
        carryover = MediaTimelineDavCarryover(carryover),
    )
}

private suspend fun collectInitialRawTimelinePartitions(
    userId: String,
    execute: suspend (body: String) -> MediaSearchDavTransportResponse,
    parse: (body: ByteArray) -> List<NextcloudFile>,
): List<MediaTimelinePartitionPage> {
    val patterns = rawPhotoFileNameSearchPatterns()
    val pages = mutableListOf<MediaTimelinePartitionPage>()
    var offset = 0
    var chunkSize = MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST
    var requests = 0
    while (offset < patterns.size && requests < MAXIMUM_RAW_MEDIA_SEARCH_REQUESTS) {
        requests += 1
        val indexes = patterns.indices.drop(offset).take(chunkSize)
        val selectedPatterns = indexes.map(patterns::get)
        val request = rawMediaSearchDavRequest(
            userId = userId,
            maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
            rawFileNamePatterns = selectedPatterns,
        )
        val response = execute(request.body)
        when {
            response.status == 207 -> {
                val files = orderedTimelinePartition(parse(response.body))
                pages += MediaTimelinePartitionPage(
                    key = MediaTimelinePartitionKey.Raw(indexes),
                    files = files,
                    logicalPrevious = null,
                    remoteCursorAfterFetched = advancedPartitionCursor(
                        consumedFiles = files,
                        previous = null,
                        hasMore = files.size >= PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
                    ),
                )
                offset += indexes.size
            }
            isMediaSearchCompatibilityRejection(response.status) && indexes.size > 1 -> {
                chunkSize = (indexes.size + 1) / 2
            }
            isMediaSearchCompatibilityRejection(response.status) -> return pages
            else -> error("WebDAV media search failed (HTTP ${response.status}).")
        }
    }
    return pages
}

private fun orderedTimelinePartition(files: List<NextcloudFile>): List<NextcloudFile> =
    mergeMediaSearchResultPages(
        pages = listOf(files),
        maximumResultsPerPage = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
    )

private fun rawPatternIndexes(mask: Int): List<Int> {
    require(mask > 0) { "The photo timeline cursor is invalid." }
    val indexes = rawPhotoFileNameSearchPatterns().indices.filter { index ->
        mask and (1 shl index) != 0
    }
    require(indexes.isNotEmpty() && indexes.size <= MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST) {
        "The photo timeline cursor is invalid."
    }
    require(indexes.fold(0) { value, index -> value or (1 shl index) } == mask) {
        "The photo timeline cursor is invalid."
    }
    return indexes
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
