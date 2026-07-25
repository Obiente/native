package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class MemoriesPersonDay(
    val dayId: Long,
    val itemCount: Int?,
)

/**
 * A display-safe face rectangle in normalized source-image coordinates.
 *
 * Memories already returns normalized values. Slight detector overflow is clipped so an overlay
 * can never draw outside the photo or produce a negative size.
 */
data class NativeFaceRectangle(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(x in 0f..1f && y in 0f..1f)
        require(width > 0f && height > 0f)
        require(x + width <= 1.0001f && y + height <= 1.0001f)
    }
}

/**
 * Complete geometry required to place a normalized face outline over a source image.
 *
 * Keeping this as one value prevents callers from switching presentation modes when only a
 * rectangle or only source dimensions are available.
 */
data class NativeFaceOutlineGeometry(
    val rectangle: NativeFaceRectangle,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
    }
}

internal fun nativeFaceOutlineGeometryOrNull(
    rectangle: NativeFaceRectangle?,
    sourceWidth: Int?,
    sourceHeight: Int?,
): NativeFaceOutlineGeometry? {
    if (rectangle == null || sourceWidth == null || sourceHeight == null) return null
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    return NativeFaceOutlineGeometry(rectangle, sourceWidth, sourceHeight)
}

internal fun NativeMediaItem.faceOutlineGeometryOrNull(): NativeFaceOutlineGeometry? =
    nativeFaceOutlineGeometryOrNull(faceRectangle, width, height)

/**
 * One actionable Recognize detection. [file] identifies the source photo, while [detectionId]
 * identifies only the face assignment. They must never be treated as interchangeable IDs.
 */
data class RecognizedFaceMedia(
    val detectionId: Long,
    val file: NextcloudFile,
    val sourceWidth: Int?,
    val sourceHeight: Int?,
    val rectangle: NativeFaceRectangle?,
) {
    init {
        require(detectionId > 0L)
        require(!file.isDirectory && file.fileId != null)
        require((sourceWidth == null) == (sourceHeight == null))
        require(sourceWidth == null || (sourceWidth > 0 && requireNotNull(sourceHeight) > 0))
    }

    fun toFaceReference(person: PersonMediaReference): RecognizeFaceReference =
        RecognizeFaceReference(
            person = person,
            detectionId = detectionId,
            sourceFileName = file.name,
        )
}

fun parseMemoriesPersonDayIndex(response: NextcloudApiResponse): List<MemoriesPersonDay> {
    val values = response.requireMemoriesArray("person day index", MAX_PERSON_DAY_INDEX_ITEMS)
    return values.mapIndexed { index, element ->
        val item = element as? JsonObject ?: error("Person day $index is not an object.")
        val dayId = item.positiveLong("dayid") ?: error("Person day $index has no valid day ID.")
        val count = item.nonNegativeInt("count")
            ?: item.nonNegativeInt("photos")
            ?: item.nonNegativeInt("items")
        MemoriesPersonDay(dayId = dayId, itemCount = count)
    }.distinctBy(MemoriesPersonDay::dayId)
}

fun parseMemoriesRecognizedFaces(
    response: NextcloudApiResponse,
    person: PersonMediaReference,
): List<RecognizedFaceMedia> {
    require(person.backend == NextcloudPeopleBackend.Recognize) {
        "Direct face actions are only supported for the Recognize backend."
    }
    val values = response.requireMemoriesArray("recognized face media", MAX_RECOGNIZED_FACE_ITEMS)
    val faces = values.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: error("Recognized face item $index is not an object.")
        val detectionId = item.positiveLong("faceid") ?: return@mapIndexedNotNull null
        val fileId = item.positiveLong("fileid") ?: return@mapIndexedNotNull null
        val basename = item.safeFaceFilename() ?: return@mapIndexedNotNull null
        val width = item.positiveInt("w")
        val height = item.positiveInt("h")
        val dimensions = if (width != null && height != null) width to height else null
        RecognizedFaceMedia(
            detectionId = detectionId,
            file = NextcloudFile(
                path = "memories/people/${person.clusterId}/faces/$detectionId",
                name = basename,
                isDirectory = false,
                mimeType = item.safeText("mimetype", MAX_FACE_MIME_LENGTH),
                size = null,
                lastModified = item.positiveLong("epoch")?.toString(),
                fileId = fileId,
                hasPreview = true,
                etag = item.safeText("etag", MAX_FACE_ETAG_LENGTH),
            ),
            sourceWidth = dimensions?.first,
            sourceHeight = dimensions?.second,
            rectangle = item.faceRectangleOrNull(),
        )
    }
    require(faces.map(RecognizedFaceMedia::detectionId).distinct().size == faces.size) {
        "The Memories response contains duplicate face detections."
    }
    return faces
}

fun planRemoveRecognizedFace(
    media: RecognizedFaceMedia,
    person: PersonMediaReference,
    personDisplayName: String,
    support: PeopleActionSupport,
): PeopleActionPlan = planRemoveFace(
    face = media.toFaceReference(person),
    personDisplayName = personDisplayName,
    support = support,
)

data class RecognizedFacePage(
    val faces: List<RecognizedFaceMedia>,
    val nextCursor: NativeMediaDayCursor?,
)

/**
 * Resolves a UI selection by the Recognize detection ID, never by source file ID or list position.
 */
fun recognizedFaceByDetectionId(
    faces: List<RecognizedFaceMedia>,
    detectionId: Long,
): RecognizedFaceMedia {
    require(detectionId > 0L) { "The selected face detection ID is invalid." }
    require(faces.map(RecognizedFaceMedia::detectionId).distinct().size == faces.size) {
        "The face selection contains duplicate detection IDs."
    }
    return faces.singleOrNull { it.detectionId == detectionId }
        ?: error("The selected face is no longer present; choose it again.")
}

/**
 * Bounded read-only loader for the first visible person-day window. It requests face rectangles so
 * the UI can show which face will be removed instead of presenting an ambiguous whole-photo tile.
 */
class RecognizedFaceReadService internal constructor(
    private val execute: suspend (NextcloudSession, NextcloudApiRequest) -> NextcloudApiResponse,
) {
    constructor(services: NextcloudPlatformServices) : this(services::executeNextcloudApi)

    suspend fun loadDayIndex(
        session: NextcloudSession,
        person: PersonMediaReference,
    ): PersonMediaDayIndex {
        require(person.backend == NextcloudPeopleBackend.Recognize) {
            "Direct face actions are only supported for the Recognize backend."
        }
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
    ): RecognizedFacePage {
        require(index.person == person) { "The recognized face day index belongs to another person." }
        val window = index.pageAfter(cursor, dayPageSize)
        if (window.days.isEmpty()) return RecognizedFacePage(emptyList(), null)
        val request = memoriesPersonDaysRequest(
            person = person,
            dayIds = window.days.map(MemoriesPersonDay::dayId),
            includeFaceRectangle = true,
        )
        require(request.method == NextcloudApiMethod.GET && request.body == null)
        return RecognizedFacePage(
            faces = parseMemoriesRecognizedFaces(execute(session, request), person),
            nextCursor = window.nextCursor,
        )
    }

    suspend fun loadInitialFaces(
        session: NextcloudSession,
        person: PersonMediaReference,
        maximumDays: Int = DEFAULT_PERSON_FACE_DAY_WINDOW,
    ): List<RecognizedFaceMedia> {
        require(maximumDays in 1..MAX_PERSON_FACE_DAY_WINDOW)
        val index = loadDayIndex(session, person)
        return loadPage(
            session = session,
            person = person,
            index = index,
            dayPageSize = maximumDays,
        ).faces
    }

    /**
     * Loads a complete, bounded face inventory before a merge is allowed to start.
     *
     * Merge cannot safely use the initial visible-day window because omitted detections would be
     * left behind. Day reads stay chunked so neither URLs nor individual responses grow without a
     * bound. No mutation is planned when the configured face ceiling would be exceeded.
     */
    suspend fun loadCompleteFacesForMerge(
        session: NextcloudSession,
        person: PersonMediaReference,
        maximumFaces: Int = MAX_COMPLETE_MERGE_FACE_ITEMS,
    ): List<RecognizedFaceMedia> = loadCompleteFaces(
        session = session,
        person = person,
        maximumFaces = maximumFaces,
        unavailablePurpose = "a safe native merge",
    )

    suspend fun loadCompleteFacesForReconciliation(
        session: NextcloudSession,
        person: PersonMediaReference,
        maximumFaces: Int = MAX_COMPLETE_MERGE_FACE_ITEMS,
    ): List<RecognizedFaceMedia> = loadCompleteFaces(
        session = session,
        person = person,
        maximumFaces = maximumFaces,
        unavailablePurpose = "exact face-removal verification",
    )

    private suspend fun loadCompleteFaces(
        session: NextcloudSession,
        person: PersonMediaReference,
        maximumFaces: Int,
        unavailablePurpose: String,
    ): List<RecognizedFaceMedia> {
        require(maximumFaces in 1..MAX_COMPLETE_MERGE_FACE_ITEMS)
        val dayResponse = execute(session, memoriesPersonDayIndexRequest(person, includeFaceRectangle = true))
        val days = parseMemoriesPersonDayIndex(dayResponse)
        val advertisedTotal = days.sumOf { day -> day.itemCount?.toLong() ?: 0L }
        require(advertisedTotal <= maximumFaces.toLong()) {
            "This person has more than $maximumFaces face assignments, so $unavailablePurpose is unavailable."
        }
        if (days.isEmpty()) return emptyList()

        val faces = buildList {
            days.map(MemoriesPersonDay::dayId)
                .chunked(MAX_PERSON_FACE_DAY_WINDOW)
                .forEach { dayIds ->
                    val request = memoriesPersonDaysRequest(person, dayIds, includeFaceRectangle = true)
                    require(request.method == NextcloudApiMethod.GET && request.body == null)
                    addAll(parseMemoriesRecognizedFaces(execute(session, request), person))
                    require(size <= maximumFaces) {
                        "This person has more than $maximumFaces face assignments, so $unavailablePurpose is unavailable."
                    }
                }
        }
        require(faces.map(RecognizedFaceMedia::detectionId).distinct().size == faces.size) {
            "The complete face inventory contains duplicate detections."
        }
        return faces
    }
}

private fun NextcloudApiResponse.requireMemoriesArray(label: String, maximumItems: Int): JsonArray {
    require(status in 200..299) { "Loading $label failed (HTTP $status)." }
    require(body.size <= MAX_MEMORIES_FACE_RESPONSE_BYTES) { "The $label response is too large." }
    val root = runCatching { faceJson.parseToJsonElement(body.decodeToString()) }.getOrNull()
        ?: error("The $label response is not valid JSON.")
    val values = root as? JsonArray ?: error("The $label response is not an array.")
    require(values.size <= maximumItems) { "The $label response contains too many items." }
    return values
}

private fun JsonObject.safeFaceFilename(): String? =
    safeText("basename", MAX_FACE_FILENAME_LENGTH)?.takeIf { name ->
        name != "." && name != ".." && '/' !in name && '\\' !in name
    }

private fun JsonObject.safeText(key: String, maximumLength: Int): String? {
    val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        ?: return null
    return value.takeIf { it.length <= maximumLength && it.none(Char::isISOControl) }
}

private fun JsonObject.positiveLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }

private fun JsonObject.positiveInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

private fun JsonObject.nonNegativeInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

/**
 * Parses the common normalized face rectangle returned by Memories day payloads.
 *
 * This stays shared between the read-only person gallery and the exact face-removal picker so
 * both surfaces clip detector overflow identically.
 */
internal fun JsonObject.faceRectangleOrNull(): NativeFaceRectangle? {
    val rectangle = this["facerect"] as? JsonObject ?: return null
    val rawX = rectangle.finiteDouble("x") ?: return null
    val rawY = rectangle.finiteDouble("y") ?: return null
    val rawWidth = rectangle.finiteDouble("w") ?: return null
    val rawHeight = rectangle.finiteDouble("h") ?: return null
    if (rawWidth <= 0.0 || rawHeight <= 0.0) return null
    val left = rawX.coerceIn(0.0, 1.0)
    val top = rawY.coerceIn(0.0, 1.0)
    val right = (rawX + rawWidth).coerceIn(0.0, 1.0)
    val bottom = (rawY + rawHeight).coerceIn(0.0, 1.0)
    if (right <= left || bottom <= top) return null
    return NativeFaceRectangle(
        x = left.toFloat(),
        y = top.toFloat(),
        width = (right - left).toFloat(),
        height = (bottom - top).toFloat(),
    )
}

private fun JsonObject.finiteDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)

private val faceJson = Json { ignoreUnknownKeys = true }

private const val DEFAULT_PERSON_FACE_DAY_WINDOW = 12
private const val MAX_PERSON_FACE_DAY_WINDOW = 32
private const val MAX_COMPLETE_MERGE_FACE_ITEMS = 20_000
private const val MAX_PERSON_DAY_INDEX_ITEMS = 20_000
private const val MAX_RECOGNIZED_FACE_ITEMS = 5_000
private const val MAX_MEMORIES_FACE_RESPONSE_BYTES = 4 * 1024 * 1024
private const val MAX_FACE_FILENAME_LENGTH = 1_024
private const val MAX_FACE_MIME_LENGTH = 256
private const val MAX_FACE_ETAG_LENGTH = 1_024
