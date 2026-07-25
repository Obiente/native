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
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

actual fun decodePlatformImage(
    bytes: ByteArray,
    orientationPolicy: EncodedImageOrientationPolicy,
): ImageBitmap? = runCatching {
    decodeDesktopImage(bytes, orientationPolicy).image.toComposeImageBitmap()
}.getOrNull()

actual fun decodePlatformImageSampled(
    bytes: ByteArray,
    maximumDimension: Int,
    orientationPolicy: EncodedImageOrientationPolicy,
): PlatformDecodedImage? = runCatching {
    require(maximumDimension > 0)
    val decoded = decodeDesktopImage(bytes, orientationPolicy)
    val source = decoded.image
    val sourceWidth = decoded.width
    val sourceHeight = decoded.height
    val sampled = if (maxOf(sourceWidth, sourceHeight) <= maximumDimension) {
        source
    } else {
        val scale = maximumDimension.toFloat() / maxOf(sourceWidth, sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val target = Bitmap()
        check(target.allocN32Pixels(width, height, false)) { "Could not allocate a display-safe image." }
        val targetPixels = checkNotNull(target.peekPixels()) { "Could not access display-safe image pixels." }
        check(source.scalePixels(targetPixels, SamplingMode.MITCHELL, true)) {
            "Could not create a display-safe image."
        }
        Image.makeFromBitmap(target)
    }
    PlatformDecodedImage(
        image = sampled.toComposeImageBitmap(),
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
    )
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
): DesktopDecodedImage {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    val rawBitmap = codec.readPixels()
    val rawImage = Image.makeFromBitmap(rawBitmap)
    val orientation = when (orientationPolicy) {
        EncodedImageOrientationPolicy.ApplyExif -> codec.encodedOrigin.exifOrientation()
        EncodedImageOrientationPolicy.PixelsAlreadyUpright -> 1
    }
    if (orientation == 1) {
        return DesktopDecodedImage(rawImage, rawImage.width, rawImage.height)
    }
    val dimensions = exifOrientedDimensions(rawImage.width, rawImage.height, orientation)
    val target = Bitmap()
    check(target.allocN32Pixels(dimensions.width, dimensions.height, false)) {
        "Could not allocate an EXIF-normalized image."
    }
    Canvas(target).apply {
        concat(Matrix33(*exifOrientationMatrixValues(rawImage.width, rawImage.height, orientation)))
        drawImage(rawImage, 0f, 0f)
    }
    return DesktopDecodedImage(
        image = Image.makeFromBitmap(target),
        width = dimensions.width,
        height = dimensions.height,
    )
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
