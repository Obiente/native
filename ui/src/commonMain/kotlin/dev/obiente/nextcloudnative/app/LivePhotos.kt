package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
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
private const val MAX_MEMORIES_DESCRIBE_BYTES = 64L * 1_024L

/**
 * Evidence that the connected Memories app exposes the Live Photo route family used here.
 *
 * The public describe endpoint first shipped in Memories 5.2.0. The route family is verified
 * against upstream Memories source through 8.1.0. Versions outside that audited range remain
 * disabled until their route contract is reviewed.
 */
sealed interface MemoriesLivePhotoCapability {
    data object NotAdvertised : MemoriesLivePhotoCapability

    data object Unverified : MemoriesLivePhotoCapability

    data class UnsupportedVersion(
        val installedVersion: String,
    ) : MemoriesLivePhotoCapability

    data class CompatibleVersion(
        val installedVersion: String,
    ) : MemoriesLivePhotoCapability

    /** A validated `liveid` returned by a Memories media response. */
    data object ObservedReference : MemoriesLivePhotoCapability
}

private data class MemoriesVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<MemoriesVersion> {
    override fun compareTo(other: MemoriesVersion): Int =
        compareValuesBy(this, other, MemoriesVersion::major, MemoriesVersion::minor, MemoriesVersion::patch)
}

private val minimumDescribedLivePhotoVersion = MemoriesVersion(5, 2, 0)
private val maximumAuditedLivePhotoVersion = MemoriesVersion(8, 1, 0)

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
    capability: MemoriesLivePhotoCapability,
    nativePlaybackAvailable: Boolean,
): Boolean =
    capability.supportsLivePhotoRoutes() &&
        nativePlaybackAvailable &&
        canResolveMemoriesLivePhoto()

internal fun NextcloudFile.effectiveLivePhotoCapability(
    describedCapability: MemoriesLivePhotoCapability,
): MemoriesLivePhotoCapability =
    if (livePhoto != null) MemoriesLivePhotoCapability.ObservedReference else describedCapability

internal fun memoriesDescribeRequest(): NextcloudApiRequest =
    NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/describe",
        maximumResponseBytes = MAX_MEMORIES_DESCRIBE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    ).requireSafe()

internal fun parseMemoriesLivePhotoCapability(
    response: NextcloudApiResponse,
): MemoriesLivePhotoCapability {
    if (response.status !in 200..299) return MemoriesLivePhotoCapability.Unverified
    val root = runCatching {
        livePhotoJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    }.getOrNull() ?: return MemoriesLivePhotoCapability.Unverified
    val installedVersion = (root["version"] as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf(String::isSafeMemoriesVersionText)
        ?: return MemoriesLivePhotoCapability.Unverified
    val parsed = installedVersion.parseMemoriesVersion()
        ?: return MemoriesLivePhotoCapability.Unverified
    return if (parsed in minimumDescribedLivePhotoVersion..maximumAuditedLivePhotoVersion) {
        MemoriesLivePhotoCapability.CompatibleVersion(installedVersion)
    } else {
        MemoriesLivePhotoCapability.UnsupportedVersion(installedVersion)
    }
}

suspend fun discoverMemoriesLivePhotoCapability(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): MemoriesLivePhotoCapability =
    try {
        parseMemoriesLivePhotoCapability(
            services.executeNextcloudApi(session, memoriesDescribeRequest()),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MemoriesLivePhotoCapability.Unverified
    }

internal suspend fun <T> livePhotoLookupOrNull(
    lookup: suspend () -> T,
): T? =
    try {
        lookup()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

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

private fun MemoriesLivePhotoCapability.supportsLivePhotoRoutes(): Boolean =
    this is MemoriesLivePhotoCapability.CompatibleVersion ||
        this is MemoriesLivePhotoCapability.ObservedReference

private fun String.isSafeMemoriesVersionText(): Boolean =
    isNotBlank() &&
        length <= 64 &&
        none { character -> character.isISOControl() || character == '\u007f' }

private fun String.parseMemoriesVersion(): MemoriesVersion? {
    val match = Regex("""^([0-9]+)\.([0-9]+)\.([0-9]+)$""").matchEntire(this) ?: return null
    return MemoriesVersion(
        major = match.groupValues[1].toIntOrNull() ?: return null,
        minor = match.groupValues[2].toIntOrNull() ?: return null,
        patch = match.groupValues[3].toIntOrNull() ?: return null,
    )
}

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
