package dev.obiente.nextcloudnative.app

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
    val exactTargetWidth = floor(sourceWidth / exactScale).toInt().coerceIn(1, sourceWidth)
    val exactTargetHeight = floor(sourceHeight / exactScale).toInt().coerceIn(1, sourceHeight)

    var sampleSize = 1
    while (sampleSize <= Int.MAX_VALUE / 2) {
        val sampledWidth = sampledDimension(sourceWidth, sampleSize)
        val sampledHeight = sampledDimension(sourceHeight, sampleSize)
        if (
            sampledWidth <= maximumDimension &&
            sampledHeight <= maximumDimension &&
            sampledWidth.toLong() * sampledHeight.toLong() <= maximumPixels
        ) {
            break
        }
        sampleSize *= 2
    }
    val sampledWidth = sampledDimension(sourceWidth, sampleSize)
    val sampledHeight = sampledDimension(sourceHeight, sampleSize)
    return BoundedImageDecodePlan(
        sampleSize = sampleSize,
        targetWidth = minOf(exactTargetWidth, sampledWidth),
        targetHeight = minOf(exactTargetHeight, sampledHeight),
    )
}

private fun sampledDimension(sourceDimension: Int, sampleSize: Int): Int {
    val sampled = (sourceDimension.toLong() + sampleSize.toLong() - 1L) /
        sampleSize.toLong()
    return sampled
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()
}

internal const val DEFAULT_MAXIMUM_DECODED_IMAGE_PIXELS = 8_000_000L
