package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * What to draw from the newest frame: the visible region of the buffer, its upright display
 * size, and the clockwise quarter turns that make the decoded picture upright.
 */
internal data class DesktopVideoFrameGeometry(
    val crop: IntSize,
    val display: IntSize,
    val quarterTurnsClockwise: Int = 0,
) {
    init {
        require(quarterTurnsClockwise in 0..3) { "Video rotation must be 0 to 3 quarter turns." }
    }
}

/**
 * Latest decoded RV32 frame, written by the libVLC video thread and drawn by Compose.
 *
 * The decoder copies into one reused array; the UI thread turns only the newest frame into a
 * Skia image when it draws, so a slow frame never queues work behind it. [frameVersion] is read in
 * the draw phase so a new frame redraws without recomposing.
 *
 * libVLC sizes the buffer from the decoder's padded picture (a 1080-line H.264 frame decodes as
 * 1088 lines). The visible size arrives separately, and only that top-left region is drawn.
 */
internal class DesktopVideoFrameBuffer {
    private val lock = Any()
    private var width = 0
    private var height = 0
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var visibleWidth = 0
    private var visibleHeight = 0
    private var sampleAspect = 1.0
    private var quarterTurns = 0
    private var pixels: ByteArray? = null
    private var pendingFrame = false
    private var image: Image? = null
    private var closed = false

    val frameVersion = mutableLongStateOf(0L)

    /** [sourceWidth] and [sourceHeight] are the padded decoder size the buffer was scaled from. */
    fun configure(bufferWidth: Int, bufferHeight: Int, sourceWidth: Int = bufferWidth, sourceHeight: Int = bufferHeight) =
        synchronized(lock) {
            require(bufferWidth > 0 && bufferHeight > 0) { "A video frame needs a positive size." }
            width = bufferWidth
            height = bufferHeight
            this.sourceWidth = sourceWidth.takeIf { it > 0 } ?: bufferWidth
            this.sourceHeight = sourceHeight.takeIf { it > 0 } ?: bufferHeight
            pixels = ByteArray(Math.multiplyExact(Math.multiplyExact(bufferWidth, bufferHeight), RV32_BYTES_PER_PIXEL))
            pendingFrame = false
        }

    /**
     * The decoded picture size without padding, its sample (pixel) aspect ratio, and the
     * clockwise quarter turns from container orientation metadata.
     */
    fun setVisibleSize(
        width: Int,
        height: Int,
        sampleAspectRatio: Double = 1.0,
        quarterTurnsClockwise: Int = 0,
    ) = synchronized(lock) {
        if (width > 0 && height > 0) {
            visibleWidth = width
            visibleHeight = height
            sampleAspect = sampleAspectRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            quarterTurns = Math.floorMod(quarterTurnsClockwise, 4)
        }
    }

    /** Called on the decoder thread; [frame] is only valid for the duration of this call. */
    fun publish(frame: ByteBuffer) {
        synchronized(lock) {
            val target = pixels ?: return
            if (closed || frame.remaining() < target.size) return
            frame.duplicate().get(target)
            pendingFrame = true
        }
        frameVersion.longValue += 1L
    }

    /** Runs [draw] with the newest frame on the UI thread while the frame cannot be released. */
    fun <T> withLatestFrame(draw: (image: Image, geometry: DesktopVideoFrameGeometry) -> T): T? = synchronized(lock) {
        if (closed) return null
        if (pendingFrame) {
            val source = pixels ?: return null
            image?.close()
            image = Image.makeRaster(
                ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                source,
                width * RV32_BYTES_PER_PIXEL,
            )
            pendingFrame = false
        }
        val current = image ?: return null
        draw(
            current,
            desktopVideoFrameGeometry(
                buffer = IntSize(width, height),
                source = IntSize(sourceWidth, sourceHeight),
                visible = IntSize(visibleWidth, visibleHeight),
                sampleAspectRatio = sampleAspect,
                orientationTurns = quarterTurns,
            ),
        )
    }

    fun close() = synchronized(lock) {
        closed = true
        image?.close()
        image = null
        pixels = null
    }
}

/**
 * Maps the visible picture into the possibly downscaled buffer and decides who rotates it.
 *
 * [visible] is the stored (unrotated) picture size and [orientationTurns] the clockwise quarter
 * turns its container metadata asks for. libVLC 3 normally rotates callback output itself, which
 * shows as a [source] buffer that only fits the swapped size; then nothing more is turned. Only a
 * buffer that provably still holds the stored orientation is turned here, so rotation happens
 * exactly once. A half turn cannot be told apart by size and is left to libVLC. Until the
 * visible size is known the whole buffer is shown, which only differs by a few padding lines.
 */
internal fun desktopVideoFrameGeometry(
    buffer: IntSize,
    source: IntSize,
    visible: IntSize,
    sampleAspectRatio: Double,
    orientationTurns: Int = 0,
): DesktopVideoFrameGeometry {
    if (visible.width <= 0 || visible.height <= 0 || source.width <= 0 || source.height <= 0) {
        return DesktopVideoFrameGeometry(crop = buffer, display = buffer)
    }
    val sar = sampleAspectRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val storedDisplay = IntSize((visible.width * sar).roundToInt().coerceAtLeast(1), visible.height)
    val quarterTurn = Math.floorMod(orientationTurns, 2) == 1
    val fitsStored = source.width >= visible.width && source.height >= visible.height
    val fitsSwapped = source.width >= visible.height && source.height >= visible.width
    // A near-square buffer fits both ways; libVLC's default autorotation is then assumed.
    val alreadyRotated = quarterTurn && fitsSwapped
    val bufferPicture = if (alreadyRotated) IntSize(visible.height, visible.width) else visible
    val crop = IntSize(
        (bufferPicture.width.toLong() * buffer.width / source.width).toInt().coerceIn(1, buffer.width),
        (bufferPicture.height.toLong() * buffer.height / source.height).toInt().coerceIn(1, buffer.height),
    )
    val turnsHere = if (quarterTurn && !fitsSwapped && fitsStored) Math.floorMod(orientationTurns, 4) else 0
    val display = if (quarterTurn) IntSize(storedDisplay.height, storedDisplay.width) else storedDisplay
    return DesktopVideoFrameGeometry(crop = crop, display = display, quarterTurnsClockwise = turnsHere)
}

/**
 * Draws the visible region of [image] upright and letterboxed in a [boxWidth] by [boxHeight]
 * area, applying the remaining turns from [desktopVideoFrameGeometry].
 */
internal fun drawDesktopVideoFrame(
    canvas: Canvas,
    image: Image,
    geometry: DesktopVideoFrameGeometry,
    boxWidth: Float,
    boxHeight: Float,
) {
    val target = fitVideoRect(geometry.display, boxWidth, boxHeight)
    if (target.isEmpty) return
    val turns = geometry.quarterTurnsClockwise
    // The picture is laid out unrotated around the target center, then turned into place.
    val drawnWidth = if (turns % 2 == 1) target.height else target.width
    val drawnHeight = if (turns % 2 == 1) target.width else target.height
    val save = canvas.save()
    try {
        canvas.translate(target.center.x, target.center.y)
        if (turns != 0) canvas.rotate(turns * 90f)
        canvas.drawImageRect(
            image,
            SkiaRect.makeWH(geometry.crop.width.toFloat(), geometry.crop.height.toFloat()),
            SkiaRect.makeXYWH(-drawnWidth / 2f, -drawnHeight / 2f, drawnWidth, drawnHeight),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            null,
            true,
        )
    } finally {
        canvas.restoreToCount(save)
    }
}

/**
 * Bounds decoder output so a 4K or 8K source does not copy hundreds of megabytes per second
 * into the UI. libVLC scales to this size before the frame reaches the app.
 */
internal fun desktopVideoBufferSize(sourceWidth: Int, sourceHeight: Int, maximumLongSide: Int): IntSize {
    require(maximumLongSide > 0)
    if (sourceWidth <= 0 || sourceHeight <= 0) return IntSize(maximumLongSide, maximumLongSide * 9 / 16)
    val longSide = max(sourceWidth, sourceHeight)
    if (longSide <= maximumLongSide) return IntSize(sourceWidth.even(), sourceHeight.even())
    val scale = maximumLongSide.toDouble() / longSide
    return IntSize(
        (sourceWidth * scale).roundToInt().coerceAtLeast(2).even(),
        (sourceHeight * scale).roundToInt().coerceAtLeast(2).even(),
    )
}

/** Letterboxes content of [contentSize] proportions inside a box, centered. */
internal fun fitVideoRect(contentSize: IntSize, boxWidth: Float, boxHeight: Float): Rect {
    if (contentSize.width <= 0 || contentSize.height <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
        return Rect.Zero
    }
    val scale = min(boxWidth / contentSize.width, boxHeight / contentSize.height)
    val width = contentSize.width * scale
    val height = contentSize.height * scale
    val left = (boxWidth - width) / 2f
    val top = (boxHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/**
 * Sequential reader that libVLC drives through its open/read/seek callbacks.
 *
 * Every byte comes from [read], which is bound to one ETag-pinned range session; a changed
 * server file fails the read instead of mixing versions. The last read failure is kept for
 * classification after libVLC reports an error.
 */
internal class DesktopVideoRangeCursor(
    private val size: Long,
    private val read: (offset: Long, length: Int) -> ByteArray,
) {
    init {
        require(size > 0L) { "A seekable video source must have a positive size." }
    }

    @Volatile
    var position: Long = 0L
        private set

    @Volatile
    var lastFailure: Exception? = null
        private set

    fun open(): Boolean {
        position = 0L
        lastFailure = null
        return true
    }

    /** Fills up to [maximum] bytes; returns 0 at the end and -1 on a failed read. */
    fun readInto(destination: ByteArray, maximum: Int): Int {
        if (maximum <= 0) return 0
        val start = position
        if (start >= size) return 0
        val length = min(min(maximum, destination.size).toLong(), size - start).toInt()
        val bytes = try {
            read(start, length)
        } catch (failure: Exception) {
            lastFailure = failure
            return -1
        }
        if (bytes.size != length) {
            lastFailure = IllegalStateException("The video source returned an incomplete range.")
            return -1
        }
        bytes.copyInto(destination, endIndex = length)
        position = start + length
        return length
    }

    fun seek(offset: Long): Boolean {
        if (offset !in 0L..size) return false
        position = offset
        return true
    }
}

private fun Int.even(): Int = (this - this % 2).coerceAtLeast(2)

private const val RV32_BYTES_PER_PIXEL = 4
