package dev.obiente.nextcloudnative.app

/** People backends exposed by Memories' typed cluster and day APIs. */
enum class NextcloudPeopleBackend(val apiValue: String) {
    Recognize("recognize"),
    FaceRecognition("facerecognition"),
    ;

    companion object {
        fun fromApiValue(value: String): NextcloudPeopleBackend? = entries.firstOrNull { it.apiValue == value }
    }
}

data class PeopleSearchQuery(
    val backend: NextcloudPeopleBackend = NextcloudPeopleBackend.Recognize,
    val text: String = "",
)

/** A Memories cluster-cover object. For Recognize, [objectId] is a face-detection ID. */
data class PersonCoverReference(
    val objectId: Long,
    val sourceEtag: String?,
) {
    init {
        require(objectId >= 0L) { "The person cover object ID is invalid." }
    }
}

fun NextcloudPerson.coverReferenceOrNull(): PersonCoverReference? = coverFileId?.let {
    PersonCoverReference(objectId = it, sourceEtag = coverEtag)
}

/**
 * Stable input for Memories person-media reads.
 *
 * [lookupName] is either the server-provided cluster name or its numeric cluster ID. The owner and
 * lookup name are kept as query values, never interpolated into an API path.
 */
data class PersonMediaReference(
    val backend: NextcloudPeopleBackend,
    val clusterId: Long,
    val ownerUserId: String,
    val lookupName: String,
) {
    init {
        require(clusterId >= 0L) { "The person cluster ID is invalid." }
        require(ownerUserId.isNotBlank() && '/' !in ownerUserId) { "The person owner is invalid." }
        require(lookupName.isNotBlank() && '/' !in lookupName) { "The person lookup name is invalid." }
    }
}

fun NextcloudPerson.toMediaReference(): PersonMediaReference = PersonMediaReference(
    backend = requireNotNull(NextcloudPeopleBackend.fromApiValue(backend)) {
        "Unsupported people backend: $backend"
    },
    clusterId = id,
    ownerUserId = userId,
    lookupName = queryName,
)

/**
 * Read-only facade over platform people support. It intentionally exposes no rename, merge,
 * assignment, visibility, cover-selection, or deletion operation.
 */
class NextcloudPeopleReadService internal constructor(
    private val loadPeople: suspend (NextcloudSession, NextcloudPeopleBackend) -> List<NextcloudPerson>,
    private val loadMedia: suspend (NextcloudSession, NextcloudPerson) -> List<NextcloudFile>,
) {
    constructor(services: NextcloudPlatformServices) : this(
        loadPeople = { session, backend -> services.listPeople(session, backend.apiValue) },
        loadMedia = services::listPersonMedia,
    )

    suspend fun searchPeople(session: NextcloudSession, query: PeopleSearchQuery): List<NextcloudPerson> {
        val needle = query.text.trim()
        return loadPeople(session, query.backend)
            .asSequence()
            .filter { NextcloudPeopleBackend.fromApiValue(it.backend) == query.backend }
            .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
            .toList()
            .let(::sortNextcloudPeopleForDisplay)
    }

    suspend fun listPersonMedia(session: NextcloudSession, person: NextcloudPerson): List<NextcloudFile> {
        person.toMediaReference()
        return loadMedia(session, person)
    }
}

/** Named clusters are actionable and recognizable, so they lead the gallery before unnamed work. */
fun sortNextcloudPeopleForDisplay(people: List<NextcloudPerson>): List<NextcloudPerson> = people.sortedWith(
    compareByDescending<NextcloudPerson> { it.hasAssignedPersonName() }
        .thenBy { person -> person.name.lowercase().takeIf { person.hasAssignedPersonName() }.orEmpty() }
        .thenByDescending(NextcloudPerson::count)
        .thenBy(NextcloudPerson::id),
)

fun NextcloudPerson.hasAssignedPersonName(): Boolean =
    queryName.isNotBlank() && queryName != id.toString() && !name.equals("Unnamed person", ignoreCase = true)

/** Plan the bounded, same-origin Memories request used to enumerate people. */
fun memoriesPeopleListRequest(
    backend: NextcloudPeopleBackend,
    containingFileId: Long? = null,
): NextcloudApiRequest {
    require(containingFileId == null || containingFileId > 0L) { "The containing file ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/clusters/${backend.apiValue}",
        queryParameters = containingFileId?.let { mapOf("fileid" to it.toString()) }.orEmpty(),
        ocsApiRequest = true,
    ).requireSafe()
}

/**
 * Plan the first read in a person-media query. `nopreload=1` keeps this response to day buckets;
 * callers then fetch only visible day IDs with [memoriesPersonDaysRequest].
 */
fun memoriesPersonDayIndexRequest(
    person: PersonMediaReference,
    includeFaceRectangle: Boolean = true,
): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/index.php/apps/memories/api/days",
    queryParameters = buildMap {
        put(person.backend.apiValue, "${person.ownerUserId}/${person.lookupName}")
        put("nopreload", "1")
        if (includeFaceRectangle) put("facerect", "1")
    },
    ocsApiRequest = true,
    maximumResponseBytes = PERSON_MEDIA_INDEX_RESPONSE_LIMIT_BYTES,
).requireSafe()

/** Plan a read of one or more day buckets selected from the person day index. */
fun memoriesPersonDaysRequest(
    person: PersonMediaReference,
    dayIds: List<Long>,
    includeFaceRectangle: Boolean = true,
): NextcloudApiRequest {
    require(dayIds.isNotEmpty() && dayIds.size <= MAX_PERSON_MEDIA_REQUEST_DAY_BATCH && dayIds.all { it > 0L }) {
        "The person media day batch is invalid."
    }
    require(dayIds.distinct().size == dayIds.size) { "Duplicate day IDs are not allowed." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/days/${dayIds.joinToString(",")}",
        queryParameters = buildMap {
            put(person.backend.apiValue, "${person.ownerUserId}/${person.lookupName}")
            if (includeFaceRectangle) put("facerect", "1")
        },
        ocsApiRequest = true,
        // The recognized-face parser has the same 4 MiB ceiling. Keeping the transport bound
        // aligned avoids downloading a response that the UI must reject afterwards.
        maximumResponseBytes = PERSON_MEDIA_PAGE_RESPONSE_LIMIT_BYTES,
    ).requireSafe()
}

const val MAX_PERSON_MEDIA_REQUEST_DAY_BATCH = 32
private const val PERSON_MEDIA_INDEX_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L
private const val PERSON_MEDIA_PAGE_RESPONSE_LIMIT_BYTES = 4L * 1024L * 1024L
