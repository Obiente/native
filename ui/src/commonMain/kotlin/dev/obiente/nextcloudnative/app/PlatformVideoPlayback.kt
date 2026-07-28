package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal expect val platformNativeVideoPlaybackAvailable: Boolean

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

internal fun NativeVideoPlaybackSource.authenticatedRequestProperties(
    authorization: String,
): Map<String, String> = buildMap {
    put("Authorization", authorization)
    if (this@authenticatedRequestProperties is NativeVideoPlaybackSource.MemoriesLivePhoto) {
        put("OCS-APIRequest", "true")
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
    -> true
    NativeVideoPlaybackFailure.NetworkUnavailable,
    NativeVideoPlaybackFailure.AccessDenied,
    NativeVideoPlaybackFailure.SourceChanged,
    NativeVideoPlaybackFailure.MalformedMedia,
    NativeVideoPlaybackFailure.Unknown,
    -> false
}

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
)
