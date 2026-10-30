package dev.obiente.nextcloudnative.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.NextcloudMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Thumbnails that are on screen, by file path, so the media viewer can grow a photo out of the
 * tile that was tapped and shrink it back into its tile on close. Entries hold only layout
 * coordinates and the decoded thumbnail the tile already shows; they are never saved.
 */
@Stable
internal class MediaZoomSources {
    private val sources = mutableMapOf<String, MediaZoomSource>()

    internal fun attach(path: String, source: MediaZoomSource) {
        sources[path] = source
    }

    internal fun detach(path: String, source: MediaZoomSource) {
        if (sources[path] === source) sources.remove(path)
    }

    internal fun find(path: String): MediaZoomSource? =
        sources[path]?.takeIf { it.coordinates?.isAttached == true && it.image != null }
}

internal class MediaZoomSource {
    var coordinates: LayoutCoordinates? = null
    var image: ImageBitmap? = null
    var cropped: Boolean = true
}

internal val LocalMediaZoomSources = staticCompositionLocalOf<MediaZoomSources?> { null }

/**
 * Present inside [MediaViewerZoom]. The viewer then leaves its background to the zoom, which
 * keeps the grown thumbnail in place until the viewer reports that its own image is shown.
 */
@Stable
internal class MediaViewerZoomPresence {
    var imageShown by mutableStateOf(false)
}

internal val LocalMediaViewerZoomPresence = staticCompositionLocalOf<MediaViewerZoomPresence?> { null }

/** The viewer's backdrop, drawn by the zoom so the screen below can show through as it opens. */
internal val MediaViewerBackdrop = Color(0xFF090B0E)

/**
 * Marks a media thumbnail as the place its photo zooms from. [cropped] says whether the tile
 * crops the thumbnail to fill itself, as the square timeline grid does.
 */
@Composable
internal fun Modifier.mediaZoomSource(path: String, image: ImageBitmap, cropped: Boolean): Modifier {
    val sources = LocalMediaZoomSources.current ?: return this
    val source = remember(sources, path) { MediaZoomSource() }
    source.image = image
    source.cropped = cropped
    DisposableEffect(sources, path, source) {
        sources.attach(path, source)
        onDispose { sources.detach(path, source) }
    }
    return onGloballyPositioned { source.coordinates = it }
}

/**
 * Presents the media viewer over the screen it was opened from, which stays in place below it.
 * Opening grows the tapped thumbnail into the photo's place while the screen behind darkens, then
 * the viewer takes over; closing reverses it into the tile of the photo now shown, or fades out
 * when that tile is not on screen. Back and the viewer's own close request use [content]'s close
 * callback, which calls [onClosed] once the zoom has finished. Reduced motion opens and closes at
 * once. Input never reaches the screen below while the viewer is shown.
 */
@Composable
internal fun MediaViewerZoom(
    selectedPath: String,
    onClosed: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val sources = LocalMediaZoomSources.current
    val reducedMotion = LocalNextcloudReducedMotion.current
    val currentPath by rememberUpdatedState(selectedPath)
    val currentOnClosed by rememberUpdatedState(onClosed)
    val scope = rememberCoroutineScope()
    val presence = remember { MediaViewerZoomPresence() }
    val openedPath = remember { selectedPath }
    // The tile is looked up once, at opening; afterwards its tile may scroll or be disposed.
    var zoom by remember { mutableStateOf(sources?.find(selectedPath)?.snapshot()) }
    val progress = remember { Animatable(if (reducedMotion) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(Unit) { progress.animateTo(1f, NextcloudMotion.content(reducedMotion, NextcloudMotion.LongMillis)) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                zoom = sources?.find(currentPath)?.snapshot()
                progress.animateTo(0f, NextcloudMotion.content(reducedMotion, NextcloudMotion.LongMillis))
                currentOnClosed()
            }
        }
    }
    PlatformBackHandler(enabled = !closing, onBack = close)
    Box(
        Modifier.fillMaxSize()
            .onPlaced { container = it }
            // Taps and drags on uncovered parts of the viewer must not reach the screen below.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(MediaViewerBackdrop))
        Canvas(Modifier.fillMaxSize()) {
            val zooming = zoom ?: return@Canvas
            val p = progress.value
            // At rest the thumbnail stands in for the opened photo until the viewer's image loads.
            if (p >= 1f && (presence.imageShown || currentPath != openedPath)) return@Canvas
            val from = container?.let { zooming.boundsIn(it) } ?: return@Canvas
            val to = mediaZoomFitRect(size, zooming.image.width, zooming.image.height)
            val rect = lerp(from, to, p)
            // A cropped tile shows the middle of the photo; the crop opens up as it grows.
            val crop = if (zooming.cropped) {
                mediaZoomCoverCrop(zooming.image.width, zooming.image.height, rect.size)
            } else {
                Rect(0f, 0f, zooming.image.width.toFloat(), zooming.image.height.toFloat())
            }
            drawImage(
                image = zooming.image,
                srcOffset = IntOffset(crop.left.roundToInt(), crop.top.roundToInt()),
                srcSize = IntSize(crop.width.roundToInt().coerceAtLeast(1), crop.height.roundToInt().coerceAtLeast(1)),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
            )
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = mediaViewerContentAlpha(progress.value, zoom != null) }) {
            CompositionLocalProvider(LocalMediaViewerZoomPresence provides presence) { content(close) }
        }
    }
}

private class MediaZoomSnapshot(val coordinates: LayoutCoordinates, val image: ImageBitmap, val cropped: Boolean) {
    fun boundsIn(container: LayoutCoordinates): Rect? =
        coordinates.takeIf { it.isAttached && container.isAttached }?.let { container.localBoundingBoxOf(it, clipBounds = false) }
}

private fun MediaZoomSource.snapshot(): MediaZoomSnapshot? {
    val coordinates = coordinates ?: return null
    val image = image ?: return null
    return MediaZoomSnapshot(coordinates, image, cropped)
}

/** Where a photo of the given pixel size rests in the viewer: fitted and centered. */
internal fun mediaZoomFitRect(container: Size, imageWidth: Int, imageHeight: Int): Rect {
    if (imageWidth <= 0 || imageHeight <= 0 || container.width <= 0f || container.height <= 0f) {
        return Rect(0f, 0f, container.width, container.height)
    }
    val scale = minOf(container.width / imageWidth, container.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/** The centered part of an image that fills [target] without distortion. */
internal fun mediaZoomCoverCrop(imageWidth: Int, imageHeight: Int, target: Size): Rect {
    if (imageWidth <= 0 || imageHeight <= 0 || target.width <= 0f || target.height <= 0f) {
        return Rect(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
    }
    val targetAspect = target.width / target.height
    val imageAspect = imageWidth.toFloat() / imageHeight
    return if (imageAspect > targetAspect) {
        val width = imageHeight * targetAspect
        val left = (imageWidth - width) / 2f
        Rect(left, 0f, left + width, imageHeight.toFloat())
    } else {
        val height = imageWidth / targetAspect
        val top = (imageHeight - height) / 2f
        Rect(0f, top, imageWidth.toFloat(), top + height)
    }
}

/**
 * The viewer appears over the growing thumbnail only near the end of the zoom, so its own image
 * replaces the thumbnail where it rests, and it fades first when closing. Without a thumbnail to
 * zoom, the viewer fades in and out with the backdrop.
 */
internal fun mediaViewerContentAlpha(progress: Float, zooming: Boolean): Float =
    if (!zooming) progress else ((progress - ContentFadeStart) / (1f - ContentFadeStart)).coerceIn(0f, 1f)

private const val ContentFadeStart = 0.7f
