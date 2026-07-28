package dev.obiente.nextcloudnative.app

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

internal data class BoundedImageDecodePlan(
    val sampleSize: Int,
    val targetWidth: Int,
    val targetHeight: Int,
) {
    val targetPixels: Long
        get() = targetWidth.toLong() * targetHeight.toLong()
}

/**
 * Plans a decode whose final bitmap is bounded by both its longest edge and its pixel allocation.
 *
 * The sample size limits the decoder's intermediate allocation. The exact target dimensions then
 * remove the remaining power-of-two overshoot before the bitmap can enter a Compose scene.
 */
internal fun boundedImageDecodePlan(
    sourceWidth: Int,
    sourceHeight: Int,
    maximumDimension: Int,
    maximumPixels: Long = DEFAULT_MAXIMUM_DECODED_IMAGE_PIXELS,
): BoundedImageDecodePlan {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(maximumDimension > 0)
    require(maximumPixels > 0L)

    val dimensionScale = max(
        sourceWidth.toDouble() / maximumDimension.toDouble(),
        sourceHeight.toDouble() / maximumDimension.toDouble(),
    )
    val pixelScale = sqrt(
        sourceWidth.toDouble() * sourceHeight.toDouble() / maximumPixels.toDouble(),
    )
    val exactScale = max(1.0, max(dimensionScale, pixelScale))
    val targetWidth = floor(sourceWidth / exactScale).toInt().coerceIn(1, sourceWidth)
    val targetHeight = floor(sourceHeight / exactScale).toInt().coerceIn(1, sourceHeight)

    var sampleSize = 1
    while (
        sampleSize <= Int.MAX_VALUE / 2 &&
        ceil(sourceWidth.toDouble() / (sampleSize.toLong() * 2L).toDouble()).toInt() >= targetWidth &&
        ceil(sourceHeight.toDouble() / (sampleSize.toLong() * 2L).toDouble()).toInt() >= targetHeight
    ) {
        sampleSize *= 2
    }
    return BoundedImageDecodePlan(
        sampleSize = sampleSize,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
    )
}

internal const val DEFAULT_MAXIMUM_DECODED_IMAGE_PIXELS = 8_000_000L
