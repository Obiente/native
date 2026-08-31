package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.max

/** Image-space interaction policy shared by touch, pointer, buttons, and keyboard. */
@Immutable
internal data class MediaViewportTransform(
    val zoom: Float = 1f,
    val offset: Offset = Offset.Zero,
) {
    fun bounded(viewport: Size, image: Size): MediaViewportTransform {
        val scale = if (zoom.isFinite()) zoom.coerceIn(mediaMinimumZoom(viewport, image), mediaMaximumZoom(viewport, image)) else 1f
        if (scale == 1f || !viewport.usable() || !image.usable()) return MediaViewportTransform(scale)
        val fit = min(viewport.width / image.width, viewport.height / image.height)
        val limitX = ((image.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val limitY = ((image.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
        return MediaViewportTransform(scale, Offset(
            offset.x.takeIf(Float::isFinite)?.coerceIn(-limitX, limitX) ?: 0f,
            offset.y.takeIf(Float::isFinite)?.coerceIn(-limitY, limitY) ?: 0f,
        ))
    }

    fun transform(
        factor: Float,
        pan: Offset,
        anchor: Offset,
        viewport: Size,
        image: Size,
    ): MediaViewportTransform {
        val current = bounded(viewport, image)
        if (!factor.isFinite() || factor <= 0f) return current
        val nextZoom = (current.zoom * factor).coerceIn(mediaMinimumZoom(viewport, image), mediaMaximumZoom(viewport, image))
        val relativeAnchor = anchor - Offset(viewport.width / 2f, viewport.height / 2f)
        val nextOffset = relativeAnchor - (relativeAnchor - current.offset) * (nextZoom / current.zoom) + pan
        return MediaViewportTransform(nextZoom, nextOffset).bounded(viewport, image)
    }
}

internal fun mediaWheelZoomFactor(delta: Float): Float =
    if (delta.isFinite()) exp(-delta.coerceIn(-10f, 10f) * 0.12f) else 1f

/** One image pixel per viewport pixel, including images smaller than the viewport. */
internal fun mediaActualSizeZoom(viewport: Size, image: Size): Float =
    if (viewport.usable() && image.usable()) max(image.width / viewport.width, image.height / viewport.height) else 1f

internal fun mediaMinimumZoom(viewport: Size, image: Size): Float = min(1f, mediaActualSizeZoom(viewport, image))

internal fun mediaMaximumZoom(viewport: Size, image: Size): Float = max(5f, mediaActualSizeZoom(viewport, image))

internal fun mediaPixelScalePercent(zoom: Float, viewport: Size, image: Size): Int =
    (zoom / mediaActualSizeZoom(viewport, image) * 100f).toInt().coerceAtLeast(1)

private fun Size.usable(): Boolean = width.isFinite() && height.isFinite() && width > 0f && height > 0f
