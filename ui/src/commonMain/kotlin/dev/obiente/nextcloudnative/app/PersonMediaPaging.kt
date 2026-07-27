package dev.obiente.nextcloudnative.app

/**
 * Stable day index for one Memories people cluster.
 *
 * The person reference is retained so a cursor or index can never accidentally be reused for
 * another person after navigation or a refresh.
 */
data class PersonMediaDayIndex(
    val person: PersonMediaReference,
    val days: List<MemoriesPersonDay>,
) {
    init {
        require(days.map(MemoriesPersonDay::dayId).distinct().size == days.size) {
            "The person media day index contains duplicate IDs."
        }
    }

    fun pageAfter(
        cursor: NativeMediaDayCursor?,
        pageSize: Int,
    ): PersonMediaDayWindow {
        require(pageSize in 1..MAX_PERSON_MEDIA_REQUEST_DAY_BATCH) {
            "The person media page size is invalid."
        }
        val start = if (cursor == null) {
            0
        } else {
            val index = days.indexOfFirst { it.dayId == cursor.afterDayId }
            require(index >= 0) { "The person media cursor is no longer present; refresh this person." }
            index + 1
        }
        val selected = days.drop(start).take(pageSize)
        return PersonMediaDayWindow(
            days = selected,
            nextCursor = selected.lastOrNull()
                ?.takeIf { start + selected.size < days.size }
                ?.let { NativeMediaDayCursor(it.dayId) },
        )
    }
}

data class PersonMediaDayWindow(
    val days: List<MemoriesPersonDay>,
    val nextCursor: NativeMediaDayCursor?,
)

data class PersonMediaPage(
    val items: List<NativeMediaItem>,
    val nextCursor: NativeMediaDayCursor?,
)

/**
 * Shape-driven person gallery reader shared by Android and desktop.
 *
 * It uses the same typed Memories day/media primitives as albums and tags. The backend value is
 * capability data in [PersonMediaReference], not a UI or installed-app branch.
 */
class NextcloudPersonMediaReadService internal constructor(
    private val execute: suspend (NextcloudSession, NextcloudApiRequest) -> NextcloudApiResponse,
) {
    constructor(services: NextcloudPlatformServices) : this(services::executeNextcloudApi)

    suspend fun loadDayIndex(
        session: NextcloudSession,
        person: PersonMediaReference,
    ): PersonMediaDayIndex {
        val request = memoriesPersonDayIndexRequest(person, includeFaceRectangle = true)
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return PersonMediaDayIndex(
            person = person,
            days = parseMemoriesPersonDayIndex(execute(session, request)),
        )
    }

    suspend fun loadPage(
        session: NextcloudSession,
        person: PersonMediaReference,
        index: PersonMediaDayIndex,
        cursor: NativeMediaDayCursor? = null,
        dayPageSize: Int = DEFAULT_MEMORIES_DAY_BATCH,
    ): PersonMediaPage {
        require(index.person == person) { "The person media day index belongs to another person." }
        val window = index.pageAfter(cursor, dayPageSize)
        if (window.days.isEmpty()) return PersonMediaPage(emptyList(), null)
        val dayIds = window.days.map(MemoriesPersonDay::dayId)
        val request = memoriesPersonDaysRequest(person, dayIds, includeFaceRectangle = true)
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return PersonMediaPage(
            items = parseMemoriesMediaItemsResponse(execute(session, request), dayIds.toSet()),
            nextCursor = window.nextCursor,
        )
    }
}

fun NativeMediaItem.toPersonMediaFile(person: PersonMediaReference): NextcloudFile = NextcloudFile(
    path = "memories/people/${person.backend.apiValue}/${person.clusterId}/$dayId/$fileId",
    name = name,
    isDirectory = false,
    mimeType = mimeType,
    size = null,
    lastModified = takenAtEpochSeconds?.toString(),
    fileId = fileId,
    hasPreview = true,
    etag = etag,
    originalAccessAllowed = false,
    davPathAuthoritative = false,
)

/**
 * Builds a preview-only file for the legacy platform person-media readers.
 *
 * The generated path is a stable UI identity, not a path in the authenticated Files DAV tree.
 * Original reads must first resolve [fileId] to an authoritative Files record.
 */
fun syntheticMemoriesPersonFile(
    personId: String,
    fileId: Long,
    name: String,
    mimeType: String?,
    lastModified: String?,
    etag: String?,
): NextcloudFile = NextcloudFile(
    path = "memories/people/$personId/$fileId",
    name = name,
    isDirectory = false,
    mimeType = mimeType,
    size = null,
    lastModified = lastModified,
    fileId = fileId,
    hasPreview = true,
    etag = etag,
    originalAccessAllowed = false,
    davPathAuthoritative = false,
)
