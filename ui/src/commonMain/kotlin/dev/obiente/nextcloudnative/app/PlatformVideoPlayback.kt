package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal expect val platformNativeVideoPlaybackAvailable: Boolean

/**
 * True while the platform is still finding its player. The viewer then offers neither in-app
 * nor external playback, so one tap cannot start both once discovery finishes.
 */
internal expect val platformNativeVideoPlaybackDiscoveryPending: Boolean

/** True when [PlatformNativeVideoPlayer] reads every source through a [NativeVideoRangeSource]. */
internal expect val platformNativeVideoPlaybackUsesRangeSource: Boolean

/** True when a separate software player can retry after the default player fails. */
internal expect val platformVideoCompatibilityPlaybackAvailable: Boolean

internal sealed interface NativeVideoPlaybackSource {
    data class DavFile(val file: NextcloudFile) : NativeVideoPlaybackSource

    data class MemoriesLivePhoto(val source: MemoriesLivePhotoSource) : NativeVideoPlaybackSource
}

internal class NativeVideoRangeSource(
    val size: Long,
    private val readBlock: suspend (offset: Long, length: Int) -> ByteArray,
    private val closeBlock: () -> Unit = {},
) : AutoCloseable {
    init {
        require(size > 0L) { "A seekable video source must have a positive size." }
    }

    suspend fun read(offset: Long, length: Int): ByteArray = readBlock(offset, length)

    override fun close() = closeBlock()
}

internal sealed interface NativeVideoCompatibilityRangePlan {
    val file: NextcloudFile

    data class WholeFile(
        override val file: NextcloudFile,
    ) : NativeVideoCompatibilityRangePlan

    data class EmbeddedTrailer(
        override val file: NextcloudFile,
        val offset: Long,
    ) : NativeVideoCompatibilityRangePlan {
        init {
            require(offset > 0L) { "A Live Photo trailer offset must be positive." }
            require(file.size?.let { offset < it } == true) {
                "A Live Photo trailer offset must be inside the source file."
            }
        }
    }
}

internal fun NextcloudFile.wholeFileVideoCompatibilityPlanOrNull(
    userId: String,
): NativeVideoCompatibilityRangePlan.WholeFile? =
    takeIf {
        canUsePlatformNativeVideoPlayback(userId, nativePlaybackAvailable = true) &&
            hasSafeSeekableVideoGeneration()
    }?.let { file -> NativeVideoCompatibilityRangePlan.WholeFile(file) }

internal fun NextcloudFile.embeddedLivePhotoCompatibilityPlanOrNull(
    source: MemoriesLivePhotoSource,
    userId: String,
): NativeVideoCompatibilityRangePlan.EmbeddedTrailer? {
    if (source.fileId != fileId || !hasAuthoritativeMediaDavAccess(userId)) return null
    if (!hasSafeSeekableVideoGeneration()) return null
    val match = LIVE_PHOTO_TRAILER_OFFSET_PATTERN.matchEntire(source.reference.serverToken)
        ?: return null
    val offset = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val sourceSize = size?.takeIf { it > 0L } ?: return null
    if (offset >= sourceSize) return null
    return NativeVideoCompatibilityRangePlan.EmbeddedTrailer(this, offset)
}

internal fun NativeVideoCompatibilityRangePlan.openRangeSource(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
): NativeVideoRangeSource {
    val sourceSize = requireNotNull(file.size?.takeIf { it > 0L }) {
        "A seekable video source must have a positive size."
    }
    val expectedEtag = requireSafeFileRangeEtag(requireNotNull(file.etag))
    require(file.hasAuthoritativeMediaDavAccess(userId)) {
        "A seekable video source requires authoritative file access."
    }
    requireSafeFilePath(file.path, allowRoot = false)
    val rangeSession = services.openFileRangeSession(
        session = session,
        userId = userId,
        path = file.path,
        size = sourceSize,
        expectedEtag = expectedEtag,
    )
    val sourceOffset = when (this) {
        is NativeVideoCompatibilityRangePlan.WholeFile -> 0L
        is NativeVideoCompatibilityRangePlan.EmbeddedTrailer -> offset
    }
    val exposedSize = sourceSize - sourceOffset
    return NativeVideoRangeSource(
        size = exposedSize,
        readBlock = { offset, length ->
            rangeSession.read(sourceReadOffset(offset, length), length)
        },
        closeBlock = rangeSession::close,
    )
}

internal fun NativeVideoCompatibilityRangePlan.sourceReadOffset(
    exposedOffset: Long,
    length: Int,
): Long {
    require(exposedOffset >= 0L && length > 0) { "The video range is invalid." }
    val sourceSize = requireNotNull(file.size?.takeIf { it > 0L })
    val sourceOffset = when (this) {
        is NativeVideoCompatibilityRangePlan.WholeFile -> 0L
        is NativeVideoCompatibilityRangePlan.EmbeddedTrailer -> offset
    }
    val exposedSize = sourceSize - sourceOffset
    val endExclusive = Math.addExact(exposedOffset, length.toLong())
    require(endExclusive <= exposedSize) { "The video range exceeds the source size." }
    return Math.addExact(sourceOffset, exposedOffset)
}

private fun NextcloudFile.hasSafeSeekableVideoGeneration(): Boolean =
    size?.let { it > 0L } == true &&
        etag?.let { runCatching { requireSafeFileRangeEtag(it) }.isSuccess } == true &&
        runCatching { requireSafeFilePath(path, allowRoot = false) }.isSuccess

private val LIVE_PHOTO_TRAILER_OFFSET_PATTERN =
    Regex("""self__traileroffset=([0-9]+)""")

internal class NativeVideoRangeCache(
    private val source: NativeVideoRangeSource,
    private val readAheadBytes: Int = DEFAULT_VIDEO_READ_AHEAD_BYTES,
    private val maximumCachedRanges: Int = DEFAULT_VIDEO_CACHED_RANGE_COUNT,
) {
    private val cachedRanges = mutableListOf<CachedVideoRange>()

    init {
        require(readAheadBytes > 0) { "Video read-ahead must be positive." }
        require(maximumCachedRanges > 0) { "At least one video range must be cached." }
    }

    suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0L) { "The video range offset must not be negative." }
        require(length > 0) { "The video range length must be positive." }
        val endExclusive = Math.addExact(offset, length.toLong())
        require(endExclusive <= source.size) { "The video range exceeds the source size." }

        val cachedIndex = cachedRanges.indexOfFirst { range ->
            offset >= range.offset && endExclusive <= range.endExclusive
        }
        if (cachedIndex >= 0) {
            val range = cachedRanges.removeAt(cachedIndex)
            cachedRanges.add(0, range)
            val start = (offset - range.offset).toInt()
            return range.bytes.copyOfRange(start, start + length)
        }

        val remaining = source.size - offset
        val fetchLength = minOf(
            remaining,
            maxOf(length, readAheadBytes).toLong(),
        ).toInt()
        val fetched = source.read(offset, fetchLength)
        require(fetched.size == fetchLength) {
            "The seekable video source returned an incomplete range."
        }
        cachedRanges.add(0, CachedVideoRange(offset, fetched))
        while (cachedRanges.size > maximumCachedRanges) {
            cachedRanges.removeAt(cachedRanges.lastIndex)
        }
        return fetched.copyOfRange(0, length)
    }
}

private data class CachedVideoRange(
    val offset: Long,
    val bytes: ByteArray,
) {
    val endExclusive: Long = offset + bytes.size
}

private const val DEFAULT_VIDEO_READ_AHEAD_BYTES = 1_024 * 1_024
private const val DEFAULT_VIDEO_CACHED_RANGE_COUNT = 2

/**
 * Request headers for streaming [this] source. A DAV file is pinned to its selected ETag, so a
 * seek or reconnect after the server file was replaced fails with 412 (reported as a changed
 * source) instead of mixing ranges from two versions.
 */
internal fun NativeVideoPlaybackSource.authenticatedRequestProperties(
    authorization: String,
): Map<String, String> = buildMap {
    put("Authorization", authorization)
    when (val source = this@authenticatedRequestProperties) {
        is NativeVideoPlaybackSource.MemoriesLivePhoto -> put("OCS-APIRequest", "true")
        is NativeVideoPlaybackSource.DavFile -> source.file.etag
            ?.let { etag -> runCatching { requireSafeFileRangeEtag(etag) }.getOrNull() }
            ?.let { pinned -> put("If-Match", pinned) }
    }
}

internal fun NativeVideoPlaybackSource.restoresStillAfterPlaybackEnds(): Boolean =
    this is NativeVideoPlaybackSource.MemoriesLivePhoto

internal data class NativeVideoFormatSummary(
    val mimeType: String?,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
)

internal sealed interface NativeVideoPlaybackFailure {
    val format: NativeVideoFormatSummary?

    data class FormatExceedsCapabilities(
        override val format: NativeVideoFormatSummary?,
    ) : NativeVideoPlaybackFailure

    data class FormatUnsupported(
        override val format: NativeVideoFormatSummary?,
    ) : NativeVideoPlaybackFailure

    data class DecoderInitializationFailed(
        override val format: NativeVideoFormatSummary?,
    ) : NativeVideoPlaybackFailure

    data class DecodeFailed(
        override val format: NativeVideoFormatSummary?,
    ) : NativeVideoPlaybackFailure

    /** The player cannot read the file's container, though another player may. */
    data object ContainerUnsupported : NativeVideoPlaybackFailure {
        override val format: NativeVideoFormatSummary? = null
    }

    data object NetworkUnavailable : NativeVideoPlaybackFailure {
        override val format: NativeVideoFormatSummary? = null
    }

    data object AccessDenied : NativeVideoPlaybackFailure {
        override val format: NativeVideoFormatSummary? = null
    }

    data object SourceChanged : NativeVideoPlaybackFailure {
        override val format: NativeVideoFormatSummary? = null
    }

    data object MalformedMedia : NativeVideoPlaybackFailure {
        override val format: NativeVideoFormatSummary? = null
    }

    data object Unknown : NativeVideoPlaybackFailure {
        override val format: NativeVideoFormatSummary? = null
    }
}

internal fun NativeVideoPlaybackFailure.canUseSoftwareFallback(): Boolean = when (this) {
    is NativeVideoPlaybackFailure.FormatExceedsCapabilities,
    is NativeVideoPlaybackFailure.FormatUnsupported,
    is NativeVideoPlaybackFailure.DecoderInitializationFailed,
    is NativeVideoPlaybackFailure.DecodeFailed,
    NativeVideoPlaybackFailure.ContainerUnsupported,
    -> true
    NativeVideoPlaybackFailure.NetworkUnavailable,
    NativeVideoPlaybackFailure.AccessDenied,
    NativeVideoPlaybackFailure.SourceChanged,
    NativeVideoPlaybackFailure.MalformedMedia,
    NativeVideoPlaybackFailure.Unknown,
    -> false
}

internal data class NativeVideoFailureActionPolicy(
    val showCompatibilityAction: Boolean,
    val showExternalAction: Boolean,
)

internal fun nativeVideoFailureActionPolicy(
    failure: NativeVideoPlaybackFailure,
    compatibilityAvailable: Boolean,
    motionOnly: Boolean,
): NativeVideoFailureActionPolicy = NativeVideoFailureActionPolicy(
    showCompatibilityAction = compatibilityAvailable && failure.canUseSoftwareFallback(),
    // A Live Photo motion failure does not make its already-visible still an external handoff.
    showExternalAction = !motionOnly,
)

internal fun nativeVideoPlaybackFailureForHttpStatus(status: Int): NativeVideoPlaybackFailure =
    when (status) {
        401, 403 -> NativeVideoPlaybackFailure.AccessDenied
        404, 410, 412, 416 -> NativeVideoPlaybackFailure.SourceChanged
        408, 425, 429,
        in 500..599,
        -> NativeVideoPlaybackFailure.NetworkUnavailable
        else -> NativeVideoPlaybackFailure.Unknown
    }

internal fun NativeVideoPlaybackFailure.userTitle(): String = when (this) {
    is NativeVideoPlaybackFailure.FormatExceedsCapabilities,
    is NativeVideoPlaybackFailure.FormatUnsupported,
    -> "This video cannot play with the device decoder"
    is NativeVideoPlaybackFailure.DecoderInitializationFailed,
    is NativeVideoPlaybackFailure.DecodeFailed,
    -> "Video playback stopped"
    NativeVideoPlaybackFailure.ContainerUnsupported -> "This video's file format is not supported"
    NativeVideoPlaybackFailure.NetworkUnavailable -> "The video is temporarily unavailable"
    NativeVideoPlaybackFailure.AccessDenied -> "This video cannot be accessed"
    NativeVideoPlaybackFailure.SourceChanged -> "The video changed on the server"
    NativeVideoPlaybackFailure.MalformedMedia -> "This video file is damaged or incomplete"
    NativeVideoPlaybackFailure.Unknown -> "The video could not be played"
}

internal fun NativeVideoPlaybackFailure.userDetail(): String = when (this) {
    is NativeVideoPlaybackFailure.FormatExceedsCapabilities ->
        "Its ${format.safeDescription()} stream exceeds this device's decoder capabilities. " +
            "The original file is unchanged."
    is NativeVideoPlaybackFailure.FormatUnsupported ->
        "This device has no decoder for the ${format.safeDescription()} stream. " +
            "The original file is unchanged."
    is NativeVideoPlaybackFailure.DecoderInitializationFailed ->
        "The device could not start a compatible video decoder."
    is NativeVideoPlaybackFailure.DecodeFailed ->
        "The decoder could not finish reading this ${format.safeDescription()} stream."
    NativeVideoPlaybackFailure.ContainerUnsupported ->
        "The built-in player cannot read this file's container. The original file is unchanged."
    NativeVideoPlaybackFailure.NetworkUnavailable ->
        "Check the connection to Nextcloud, then try again."
    NativeVideoPlaybackFailure.AccessDenied ->
        "The server did not allow this media read."
    NativeVideoPlaybackFailure.SourceChanged ->
        "Close and reopen the video to load its current server version."
    NativeVideoPlaybackFailure.MalformedMedia ->
        "The original is preserved. Another media app may still be able to inspect it."
    NativeVideoPlaybackFailure.Unknown ->
        "The original is preserved. Another media app may support this format."
}

private fun NativeVideoFormatSummary?.safeDescription(): String {
    if (this == null) return "selected video"
    val formatName = when (mimeType?.lowercase()) {
        "video/hevc" -> "HEVC"
        "video/avc" -> "AVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        else -> mimeType?.substringAfter('/')?.uppercase() ?: "video"
    }
    val dimensions = if (width != null && height != null && width > 0 && height > 0) {
        ", $width x $height"
    } else {
        ""
    }
    return "$formatName$dimensions"
}

/**
 * What the operating system's media controls (notification, lock screen, headset and media keys)
 * show and do for the playing video. Previous and next move through the viewer's own sequence and
 * are null when there is no neighbor or navigation is blocked.
 */
internal class NativeVideoSystemMedia(
    val title: String = "",
    val onPrevious: (() -> Unit)? = null,
    val onNext: (() -> Unit)? = null,
)

/**
 * Plays [source] inside the viewer canvas and starts as soon as it is ready.
 *
 * [onControlsVisibilityChanged] reports when the platform transport controls appear or auto-hide,
 * so the viewer chrome can follow them during immersive playback. [systemMedia] publishes the
 * video to the operating system's media controls where the platform supports them.
 */
@Composable
internal expect fun PlatformNativeVideoPlayer(
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    rangeSource: NativeVideoRangeSource?,
    compatibilityPlaybackRequested: Boolean,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    modifier: Modifier = Modifier,
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
    systemMedia: NativeVideoSystemMedia = NativeVideoSystemMedia(),
)

internal fun NextcloudFile.canUsePlatformNativeVideoPlayback(
    userId: String,
    nativePlaybackAvailable: Boolean,
): Boolean =
    nativePlaybackAvailable &&
        mediaAssetFormat() == MediaAssetFormat.Video &&
        hasAuthoritativeMediaDavAccess(userId)

internal fun NextcloudFile.nativeVideoPlaybackSource(
    userId: String,
    nativePlaybackAvailable: Boolean,
    livePhotoSource: MemoriesLivePhotoSource?,
    motionPlaying: Boolean,
): NativeVideoPlaybackSource? {
    if (!nativePlaybackAvailable) return null
    if (motionPlaying && livePhotoSource != null && livePhotoSource.fileId == fileId) {
        return NativeVideoPlaybackSource.MemoriesLivePhoto(livePhotoSource)
    }
    return takeIf { canUsePlatformNativeVideoPlayback(userId, nativePlaybackAvailable) }
        ?.let { NativeVideoPlaybackSource.DavFile(it) }
}

