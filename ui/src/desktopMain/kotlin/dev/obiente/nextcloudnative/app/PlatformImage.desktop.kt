package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33

actual fun decodePlatformImage(
    bytes: ByteArray,
    orientationPolicy: EncodedImageOrientationPolicy,
): ImageBitmap? = runCatching {
    val decoded = decodeDesktopImage(bytes, orientationPolicy, maximumDimension = null)
    try {
        decoded.image.toComposeImageBitmap()
    } catch (failure: Throwable) {
        decoded.image.close()
        throw failure
    }
}.getOrNull()

actual fun decodePlatformImageSampled(
    bytes: ByteArray,
    maximumDimension: Int,
    orientationPolicy: EncodedImageOrientationPolicy,
): PlatformDecodedImage? = runCatching {
    require(maximumDimension > 0)
    val decoded = decodeDesktopImage(bytes, orientationPolicy, maximumDimension)
    val source = decoded.image
    val sourceWidth = decoded.width
    val sourceHeight = decoded.height
    try {
        try {
            PlatformDecodedImage(
                image = source.toComposeImageBitmap(),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
        } catch (failure: Throwable) {
            source.close()
            throw failure
        }
    } catch (failure: Throwable) {
        if (!source.isClosed) {
            source.close()
        }
        throw failure
    }
}.getOrNull()

private data class DesktopDecodedImage(
    val image: Image,
    val width: Int,
    val height: Int,
)

/**
 * Codec.readPixels returns encoded pixel storage without applying EncodedOrigin. This lets a
 * trusted server preview opt out of stale EXIF while originals use Skia's complete 1-8 transform.
 */
private fun decodeDesktopImage(
    bytes: ByteArray,
    orientationPolicy: EncodedImageOrientationPolicy,
    maximumDimension: Int?,
): DesktopDecodedImage {
    return Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            val sourceWidth = codec.width
            val sourceHeight = codec.height
            val target = desktopDecodeTargetDimensions(sourceWidth, sourceHeight, maximumDimension)
            Bitmap().use { rawBitmap ->
                val targetInfo = codec.imageInfo.withWidthHeight(target.width, target.height)
                check(rawBitmap.allocPixels(targetInfo)) {
                    "Could not allocate a bounded desktop image."
                }
                codec.readPixels(rawBitmap)
                val rawImage = Image.makeFromBitmap(rawBitmap)
                val orientation = when (orientationPolicy) {
                    EncodedImageOrientationPolicy.ApplyExif -> codec.encodedOrigin.exifOrientation()
                    EncodedImageOrientationPolicy.PixelsAlreadyUpright -> 1
                }
                if (orientation == 1) {
                    return@use DesktopDecodedImage(rawImage, sourceWidth, sourceHeight)
                }
                rawImage.use { source ->
                    val dimensions = exifOrientedDimensions(sourceWidth, sourceHeight, orientation)
                    DesktopDecodedImage(
                        image = renderDesktopExifOrientation(source, orientation),
                        width = dimensions.width,
                        height = dimensions.height,
                    )
                }
            }
        }
    }
}

internal data class DesktopDecodeDimensions(
    val width: Int,
    val height: Int,
)

internal fun desktopDecodeTargetDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    maximumDimension: Int?,
): DesktopDecodeDimensions {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(sourceWidth <= MAX_DESKTOP_ENCODED_DIMENSION && sourceHeight <= MAX_DESKTOP_ENCODED_DIMENSION) {
        "The encoded image dimensions are unsafe."
    }
    if (maximumDimension == null) {
        require(sourceWidth.toLong() * sourceHeight <= MAX_DESKTOP_UNSAMPLED_PIXELS) {
            "The encoded image is too large for an unsampled desktop decode."
        }
        return DesktopDecodeDimensions(sourceWidth, sourceHeight)
    }
    require(maximumDimension in 1..MAX_DESKTOP_SAMPLED_DIMENSION)
    var divisor = 1
    val sourceMaximum = maxOf(sourceWidth, sourceHeight)
    while ((sourceMaximum + divisor - 1) / divisor > maximumDimension) {
        divisor = Math.multiplyExact(divisor, 2)
    }
    return DesktopDecodeDimensions(
        width = ((sourceWidth + divisor - 1) / divisor).coerceAtLeast(1),
        height = ((sourceHeight + divisor - 1) / divisor).coerceAtLeast(1),
    )
}

internal fun renderDesktopExifOrientation(source: Image, orientation: Int): Image {
    val dimensions = exifOrientedDimensions(source.width, source.height, orientation)
    return Bitmap().use { target ->
        check(
            target.allocPixels(
                source.imageInfo.withWidthHeight(dimensions.width, dimensions.height),
            ),
        ) {
            "Could not allocate an EXIF-normalized image."
        }
        drawDesktopExifOrientation(source, target, orientation)
        Image.makeFromBitmap(target)
    }
}

internal fun drawDesktopExifOrientation(
    source: Image,
    target: Bitmap,
    orientation: Int,
) {
    val dimensions = exifOrientedDimensions(source.width, source.height, orientation)
    require(target.width == dimensions.width && target.height == dimensions.height)
    target.erase(0x00000000)
    Canvas(target).use { canvas ->
        canvas.concat(
            Matrix33(
                *exifOrientationMatrixValues(
                    source.width,
                    source.height,
                    orientation,
                ),
            ),
        )
        canvas.drawImage(source, 0f, 0f)
    }
}

private fun EncodedOrigin.exifOrientation(): Int = when (this) {
    EncodedOrigin.UNUSED,
    EncodedOrigin.TOP_LEFT,
    -> 1
    EncodedOrigin.TOP_RIGHT -> 2
    EncodedOrigin.BOTTOM_RIGHT -> 3
    EncodedOrigin.BOTTOM_LEFT -> 4
    EncodedOrigin.LEFT_TOP -> 5
    EncodedOrigin.RIGHT_TOP -> 6
    EncodedOrigin.RIGHT_BOTTOM -> 7
    EncodedOrigin.LEFT_BOTTOM -> 8
}

private const val MAX_DESKTOP_ENCODED_DIMENSION = 1_000_000
private const val MAX_DESKTOP_UNSAMPLED_PIXELS = 32_000_000L
private const val MAX_DESKTOP_SAMPLED_DIMENSION = 8_192
