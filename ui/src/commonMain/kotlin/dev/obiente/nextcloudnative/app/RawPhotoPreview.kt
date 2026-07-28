package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

enum class MediaDisplayPayloadKind {
    ServerPreview,
    MemoriesRawRender,
    NativeGeneratedPreview,
    EmbeddedCameraPreview,
}

data class MediaDisplayPayload(
    val bytes: ByteArray,
    val kind: MediaDisplayPayloadKind,
)

data class DecodedMediaDisplayPayload<T>(
    val value: T,
    val payload: MediaDisplayPayload,
) {
    val bytes: ByteArray get() = payload.bytes
    val kind: MediaDisplayPayloadKind get() = payload.kind
}

data class LoadedMediaPreview<T>(
    val value: T,
    val source: MediaSourceChoice,
    val usedFallback: Boolean,
    val payloadKind: MediaDisplayPayloadKind,
)

data class FujiRafEmbeddedPreview(
    val offset: Long,
    val length: Int,
)

/**
 * Accepts the documented response contract of Memories' full-resolution decodable-image route.
 *
 * Memories returns PNG, WebP, JPEG, or GIF unchanged and converts other supported image formats,
 * including camera RAW files, to JPEG. Requiring that contract prevents an HTML login/error page
 * from reaching the image decoder even when a proxy incorrectly returns it with a successful
 * status.
 */
fun memoriesDecodableImageResponseBytes(response: NextcloudApiResponse): ByteArray {
    require(response.status in 200..299) {
        "The Memories image render failed (HTTP ${response.status})."
    }
    val contentType = response.contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
    require(contentType in MEMORIES_DECODABLE_IMAGE_CONTENT_TYPES) {
        "The Memories image render returned an unsupported content type."
    }
    require(isBoundedDisplayImagePayload(response.body, MAX_RAW_DISPLAY_PREVIEW_BYTES)) {
        "The Memories image render returned an invalid or oversized image."
    }
    return response.body
}

fun memoriesRawDisplayResponseBytes(response: NextcloudApiResponse): ByteArray =
    memoriesDecodableImageResponseBytes(response)

fun MediaDisplayPayloadKind.orientationPolicy(): EncodedImageOrientationPolicy = when (this) {
    MediaDisplayPayloadKind.ServerPreview,
    MediaDisplayPayloadKind.MemoriesRawRender,
    MediaDisplayPayloadKind.NativeGeneratedPreview,
    -> EncodedImageOrientationPolicy.PixelsAlreadyUpright
    MediaDisplayPayloadKind.EmbeddedCameraPreview -> EncodedImageOrientationPolicy.ApplyExif
}

/**
 * Validates the response identity for one HTTP byte range without relying on a permissive regex.
 *
 * A `206` status and the expected body length are not sufficient: a proxy can return a different
 * range of the same size. Unknown complete lengths (`*`) are allowed by HTTP, while a numeric
 * complete length must extend beyond the returned inclusive end.
 */
fun isExactHttpByteContentRange(
    value: String?,
    expectedStart: Long,
    expectedEndInclusive: Long,
    expectedCompleteLength: Long? = null,
): Boolean {
    if (expectedStart < 0L || expectedEndInclusive < expectedStart) return false
    if (expectedCompleteLength != null && expectedCompleteLength <= expectedEndInclusive) return false
    val contentRange = value?.trim() ?: return false
    if (
        !contentRange.regionMatches(
            thisOffset = 0,
            other = HTTP_BYTES_RANGE_PREFIX,
            otherOffset = 0,
            length = HTTP_BYTES_RANGE_PREFIX.length,
            ignoreCase = true,
        )
    ) {
        return false
    }
    val rangeAndLength = contentRange.substring(HTTP_BYTES_RANGE_PREFIX.length)
    val slash = rangeAndLength.indexOf('/')
    if (slash <= 0 || slash != rangeAndLength.lastIndexOf('/')) return false
    val range = rangeAndLength.substring(0, slash)
    val dash = range.indexOf('-')
    if (dash <= 0 || dash != range.lastIndexOf('-')) return false
    val start = range.substring(0, dash).toLongOrNull() ?: return false
    val endInclusive = range.substring(dash + 1).toLongOrNull() ?: return false
    if (start != expectedStart || endInclusive != expectedEndInclusive) return false
    val completeLength = rangeAndLength.substring(slash + 1)
    if (expectedCompleteLength != null) {
        return completeLength.toLongOrNull() == expectedCompleteLength
    }
    return completeLength == "*" ||
        completeLength.toLongOrNull()?.let { it > expectedEndInclusive } == true
}

/**
 * Reads the documented Fuji RAF embedded JPEG directory fields.
 *
 * RAF stores the JPEG offset and length as unsigned big-endian 32-bit values at 0x54 and 0x58.
 * The bounds are validated before a second range request is made, so malformed remote files
 * cannot turn a small preview read into an unbounded download.
 */
fun parseFujiRafEmbeddedPreview(
    header: ByteArray,
    fileSize: Long?,
    maximumPreviewBytes: Int = MAX_RAW_EMBEDDED_PREVIEW_BYTES,
): FujiRafEmbeddedPreview? {
    require(maximumPreviewBytes >= MIN_RAW_EMBEDDED_PREVIEW_BYTES)
    if (header.size < FUJI_RAF_DIRECTORY_END) return null
    if (!header.startsWithAscii(FUJI_RAF_SIGNATURE)) return null

    val offset = header.readUnsignedBigEndianInt(FUJI_RAF_JPEG_OFFSET_POSITION)
    val length = header.readUnsignedBigEndianInt(FUJI_RAF_JPEG_LENGTH_POSITION)
    if (offset < FUJI_RAF_DIRECTORY_END.toLong()) return null
    if (length !in MIN_RAW_EMBEDDED_PREVIEW_BYTES.toLong()..maximumPreviewBytes.toLong()) return null
    if (offset > Long.MAX_VALUE - length) return null
    val endExclusive = offset + length
    if (fileSize != null && (fileSize < 0L || endExclusive > fileSize)) return null
    return FujiRafEmbeddedPreview(offset = offset, length = length.toInt())
}

/**
 * Loads a displayable image for one file while preserving the original RAW as the action target.
 *
 * Core preview is cheapest. Images that Nextcloud cannot preview then use Memories' decodable
 * endpoint, which converts uncommon formats such as TIFF and camera RAW to a browser-decodable
 * image. Fuji RAF finally falls back to its own embedded camera JPEG through two bounded WebDAV
 * range reads. This does not require or invent a sibling JPEG file.
 */
suspend fun <T> loadMediaDisplayPayload(
    file: NextcloudFile,
    loadCorePreview: suspend () -> ByteArray,
    loadMemoriesRawRender: suspend () -> ByteArray,
    loadFileRange: suspend (offset: Long, length: Int, expectedEtag: String) -> ByteArray,
    loadNativeRender: suspend () -> ByteArray? = { null },
    decode: (MediaDisplayPayload) -> T?,
): DecodedMediaDisplayPayload<T> {
    if (file.hasPreview) {
        attemptDecodedDisplayPayload(
            kind = MediaDisplayPayloadKind.ServerPreview,
            maximumPayloadBytes = MAX_MEDIA_PREVIEW_BYTES,
            load = loadCorePreview,
            decode = decode,
        )?.let { return it }
    }

    if (file.isPhotoMedia() && file.canUseMemoriesDecodableRender()) {
        attemptDecodedDisplayPayload(
            kind = MediaDisplayPayloadKind.MemoriesRawRender,
            maximumPayloadBytes = MAX_RAW_DISPLAY_PREVIEW_BYTES,
            load = loadMemoriesRawRender,
            decode = decode,
        )?.let { return it }
    }

    if (file.isPhotoMedia()) {
        attemptDecodedDisplayPayload(
            kind = MediaDisplayPayloadKind.NativeGeneratedPreview,
            maximumPayloadBytes = MAX_RAW_DISPLAY_PREVIEW_BYTES,
            load = {
                requireNotNull(loadNativeRender()) {
                    "This platform has no native decoder for the media format."
                }
            },
            decode = decode,
        )?.let { return it }
    }

    if (file.canUseEmbeddedRafPreview() || file.canUseEmbeddedRafPreviewFromMemories()) {
        val expectedEtag = requireNotNull(file.etag)
        val header = loadFileRange(0L, FUJI_RAF_DIRECTORY_END, expectedEtag)
        val location = parseFujiRafEmbeddedPreview(header, file.size)
            ?: error("The RAF embedded preview directory is invalid.")
        val embedded = loadFileRange(location.offset, location.length, expectedEtag)
        decodeDisplayPayload(
            bytes = embedded,
            kind = MediaDisplayPayloadKind.EmbeddedCameraPreview,
            maximumPayloadBytes = MAX_RAW_EMBEDDED_PREVIEW_BYTES,
            decode = decode,
        )?.let { return it }
    }

    error(
        if (file.isRawPhoto()) {
            "No displayable RAW render or embedded preview is available."
        } else {
            "No displayable server image is available."
        },
    )
}

internal suspend fun <T> loadFirstUsableMediaPreviewSource(
    candidates: List<MediaSourceChoice>,
    load: suspend (NextcloudFile) -> DecodedMediaDisplayPayload<T>,
): LoadedMediaPreview<T>? {
    candidates.forEachIndexed { index, candidate ->
        val loaded = try {
            load(candidate.file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (loaded != null) {
            return LoadedMediaPreview(
                value = loaded.value,
                source = candidate,
                usedFallback = index > 0,
                payloadKind = loaded.kind,
            )
        }
    }
    return null
}

private suspend fun <T> attemptDecodedDisplayPayload(
    kind: MediaDisplayPayloadKind,
    maximumPayloadBytes: Int,
    load: suspend () -> ByteArray,
    decode: (MediaDisplayPayload) -> T?,
): DecodedMediaDisplayPayload<T>? = try {
    decodeDisplayPayload(load(), kind, maximumPayloadBytes, decode)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private fun <T> decodeDisplayPayload(
    bytes: ByteArray,
    kind: MediaDisplayPayloadKind,
    maximumPayloadBytes: Int,
    decode: (MediaDisplayPayload) -> T?,
): DecodedMediaDisplayPayload<T>? {
    if (!isBoundedDisplayImagePayload(bytes, maximumPayloadBytes)) return null
    val payload = MediaDisplayPayload(bytes, kind)
    return decode(payload)?.let { DecodedMediaDisplayPayload(value = it, payload = payload) }
}

private fun ByteArray.readUnsignedBigEndianInt(offset: Int): Long {
    if (offset < 0 || size - offset < Int.SIZE_BYTES) return -1L
    return (0 until Int.SIZE_BYTES).fold(0L) { value, index ->
        (value shl Byte.SIZE_BITS) or (this[offset + index].toLong() and 0xFFL)
    }
}

private fun ByteArray.startsWithAscii(expected: String): Boolean =
    size >= expected.length && expected.indices.all { index ->
        this[index].toInt() and 0xFF == expected[index].code
    }

const val MAX_RAW_DISPLAY_PREVIEW_BYTES = 64 * 1024 * 1024
const val MAX_RAW_EMBEDDED_PREVIEW_BYTES = 32 * 1024 * 1024
private const val MIN_RAW_EMBEDDED_PREVIEW_BYTES = 8
private const val FUJI_RAF_SIGNATURE = "FUJIFILMCCD-RAW "
private const val FUJI_RAF_JPEG_OFFSET_POSITION = 0x54
private const val FUJI_RAF_JPEG_LENGTH_POSITION = 0x58
private const val FUJI_RAF_DIRECTORY_END = 0x5C
private const val HTTP_BYTES_RANGE_PREFIX = "bytes "
private val MEMORIES_DECODABLE_IMAGE_CONTENT_TYPES = setOf(
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/webp",
)
