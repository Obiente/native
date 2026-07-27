package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal const val MAX_LIVE_PHOTO_TOKEN_LENGTH = 1_024
private const val MAX_LIVE_PHOTO_LOOKUP_BYTES = 256L * 1_024L
private const val MAX_LIVE_PHOTO_DAY_BYTES = 16L * 1_024L * 1_024L
private const val MAX_LIVE_PHOTO_DAY_ITEMS = 10_000
private const val MAX_LIVE_PHOTO_ETAG_LENGTH = 1_024

internal fun String.isSafeLivePhotoToken(): Boolean =
    isNotEmpty() &&
        length <= MAX_LIVE_PHOTO_TOKEN_LENGTH &&
        none { character -> character.isISOControl() || character == '\u007f' }

data class MemoriesLivePhotoSource(
    val fileId: Long,
    val reference: NextcloudLivePhotoReference,
    val etag: String?,
) {
    init {
        require(fileId > 0L) { "The Live Photo file ID is invalid." }
        require(etag == null || etag.isSafeLivePhotoEtag()) { "The Live Photo ETag is invalid." }
    }
}

internal data class LivePhotoDiscoveryIdentity(
    val path: String,
    val fileId: Long?,
    val etag: String?,
    val size: Long?,
    val lastModified: String?,
    val mimeType: String?,
    val serverReference: String?,
)

internal fun NextcloudFile.livePhotoDiscoveryIdentity(): LivePhotoDiscoveryIdentity =
    LivePhotoDiscoveryIdentity(
        path = path,
        fileId = fileId,
        etag = etag,
        size = size,
        lastModified = lastModified,
        mimeType = mimeType,
        serverReference = livePhoto?.serverToken,
    )

internal fun NextcloudFile.shouldDiscoverMemoriesLivePhoto(
    memoriesAvailable: Boolean,
    nativePlaybackAvailable: Boolean,
): Boolean =
    memoriesAvailable &&
        nativePlaybackAvailable &&
        canResolveMemoriesLivePhoto()

fun memoriesLivePhotoInfoRequest(fileId: Long): NextcloudApiRequest {
    require(fileId > 0L) { "The Live Photo file ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/image/info/$fileId",
        queryParameters = mapOf("basic" to "1"),
        ocsApiRequest = true,
        maximumResponseBytes = MAX_LIVE_PHOTO_LOOKUP_BYTES,
    ).requireSafe()
}

fun parseMemoriesLivePhotoDayId(
    response: NextcloudApiResponse,
    expectedFileId: Long,
): Long {
    require(expectedFileId > 0L) { "The expected Live Photo file ID is invalid." }
    require(response.status in 200..299) {
        "Memories could not inspect this photo (HTTP ${response.status})."
    }
    val root = response.livePhotoJsonObject("Memories photo information")
    require(root.positiveLong("fileid") == expectedFileId) {
        "Memories returned information for another photo."
    }
    return root.positiveLong("dayid")
}

fun memoriesLivePhotoDayRequest(dayId: Long): NextcloudApiRequest {
    require(dayId > 0L) { "The Live Photo day ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/days/$dayId",
        ocsApiRequest = true,
        maximumResponseBytes = MAX_LIVE_PHOTO_DAY_BYTES,
    ).requireSafe()
}

fun parseMemoriesLivePhotoSource(
    response: NextcloudApiResponse,
    expectedFileId: Long,
    expectedDayId: Long,
): MemoriesLivePhotoSource? {
    require(expectedFileId > 0L) { "The expected Live Photo file ID is invalid." }
    require(expectedDayId > 0L) { "The expected Live Photo day ID is invalid." }
    require(response.status in 200..299) {
        "Memories could not inspect this photo's day (HTTP ${response.status})."
    }
    val items = response.livePhotoJsonArray("Memories photo day")
    require(items.size <= MAX_LIVE_PHOTO_DAY_ITEMS) {
        "The Memories photo day contains too many items."
    }
    val matches = items.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject
            ?: error("Memories photo day item $index is not an object.")
        val fileId = item.positiveLong("fileid")
        val dayId = item.positiveLong("dayid")
        require(dayId == expectedDayId) { "Memories returned media from another day." }
        if (fileId == expectedFileId) item else null
    }
    require(matches.size <= 1) { "Memories returned duplicate photo records." }
    val item = matches.singleOrNull() ?: return null
    val token = item.optionalText("liveid") ?: return null
    require(token.isSafeLivePhotoToken()) { "Memories returned an invalid Live Photo reference." }
    val etag = item.optionalText("etag")
    require(etag == null || etag.isSafeLivePhotoEtag()) {
        "Memories returned an invalid Live Photo ETag."
    }
    return MemoriesLivePhotoSource(
        fileId = expectedFileId,
        reference = NextcloudLivePhotoReference(token),
        etag = etag,
    )
}

suspend fun resolveMemoriesLivePhotoSource(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    file: NextcloudFile,
): MemoriesLivePhotoSource? {
    val fileId = file.fileId ?: return null
    file.livePhoto?.let { reference ->
        return MemoriesLivePhotoSource(fileId, reference, file.etag)
    }
    if (!file.canResolveMemoriesLivePhoto()) return null
    val infoResponse = services.executeNextcloudApi(session, memoriesLivePhotoInfoRequest(fileId))
    val dayId = withContext(Dispatchers.Default) {
        parseMemoriesLivePhotoDayId(
            response = infoResponse,
            expectedFileId = fileId,
        )
    }
    val dayResponse = services.executeNextcloudApi(session, memoriesLivePhotoDayRequest(dayId))
    return withContext(Dispatchers.Default) {
        parseMemoriesLivePhotoSource(
            response = dayResponse,
            expectedFileId = fileId,
            expectedDayId = dayId,
        )
    }
}

fun memoriesLivePhotoVideoRequest(source: MemoriesLivePhotoSource): NextcloudApiRequest =
    NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/video/livephoto/${source.fileId}",
        queryParameters = buildMap {
            put("liveid", source.reference.serverToken)
            source.etag?.let { put("etag", it) }
        },
        ocsApiRequest = true,
        maximumResponseBytes = 1L,
    ).requireSafe()

internal fun NextcloudFile.canResolveMemoriesLivePhoto(): Boolean {
    if (
        isDirectory ||
        fileId == null ||
        mediaAssetFormat() !in setOf(MediaAssetFormat.Jpeg, MediaAssetFormat.Image)
    ) {
        return false
    }
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("jpg", "jpeg", "heic", "heif", "avif")
}

private fun String.isSafeLivePhotoEtag(): Boolean =
    isNotBlank() &&
        length <= MAX_LIVE_PHOTO_ETAG_LENGTH &&
        none { character -> character.isISOControl() || character == '\u007f' }

private val livePhotoJson = Json { ignoreUnknownKeys = true }

private fun NextcloudApiResponse.livePhotoJsonObject(label: String): JsonObject =
    runCatching { livePhotoJson.parseToJsonElement(body.decodeToString()) as? JsonObject }
        .getOrNull() ?: error("The $label response is not a JSON object.")

private fun NextcloudApiResponse.livePhotoJsonArray(label: String): JsonArray =
    runCatching { livePhotoJson.parseToJsonElement(body.decodeToString()) as? JsonArray }
        .getOrNull() ?: error("The $label response is not a JSON array.")

private fun JsonObject.positiveLong(key: String): Long =
    (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
        ?: error("Memories returned an invalid $key value.")

private fun JsonObject.optionalText(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
