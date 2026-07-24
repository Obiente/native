package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

actual fun decodePlatformImage(bytes: ByteArray): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

actual fun decodePlatformImageSampled(bytes: ByteArray, maximumDimension: Int): PlatformDecodedImage? = runCatching {
    require(maximumDimension > 0)
    val source = Image.makeFromEncoded(bytes)
    val sourceWidth = source.width
    val sourceHeight = source.height
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
