package dev.obiente.nextcloudnative.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaViewportTransformTest {
    private val viewport = Size(800f, 600f)
    private val landscape = Size(1600f, 1200f)

    @Test
    fun zoomPreservesThePointUnderThePointer() {
        val next = MediaViewportTransform().transform(2f, Offset.Zero, Offset(600f, 400f), viewport, landscape)
        assertEquals(2f, next.zoom)
        assertEquals(Offset(-200f, -100f), next.offset)
        val imagePoint = (Offset(600f, 400f) - Offset(400f, 300f) - next.offset) / next.zoom
        assertEquals(Offset(200f, 100f), imagePoint)
    }

    @Test
    fun zoomAndPanNeverLoseTheImageOutsideTheViewport() {
        val next = MediaViewportTransform(2f, Offset(10000f, -10000f)).bounded(viewport, landscape)
        assertEquals(Offset(400f, -300f), next.offset)
        assertEquals(MediaViewportTransform(), MediaViewportTransform(1f, next.offset).bounded(viewport, landscape))
        assertEquals(5f, MediaViewportTransform(100f).bounded(viewport, landscape).zoom)
    }

    @Test
    fun letterboxedPortraitDoesNotPanAlongAnAxisSmallerThanTheWindow() {
        val portrait = Size(600f, 1200f)
        assertEquals(Offset(0f, 300f), MediaViewportTransform(2f, Offset(300f, 400f)).bounded(viewport, portrait).offset)
    }

    @Test
    fun resizeReclampsTheTransformAndEmptyGeometryIsSafe() {
        val transform = MediaViewportTransform(2f, Offset(400f, 300f))
        assertEquals(Offset(150f, 0f), transform.bounded(Size(300f, 800f), landscape).offset)
        assertEquals(Offset.Zero, transform.bounded(Size.Zero, landscape).offset)
        assertEquals(MediaViewportTransform(), MediaViewportTransform(Float.NaN).bounded(viewport, landscape))
    }

    @Test
    fun repeatedWheelDeltasAreBoundedAndZoomOutReturnsToFit() {
        var transform = MediaViewportTransform()
        repeat(100) { transform = transform.transform(mediaWheelZoomFactor(-1f), Offset.Zero, Offset(400f, 300f), viewport, landscape) }
        assertEquals(5f, transform.zoom)
        repeat(100) { transform = transform.transform(mediaWheelZoomFactor(1f), Offset.Zero, Offset(400f, 300f), viewport, landscape) }
        assertEquals(MediaViewportTransform(), transform)
        assertEquals(1f, mediaWheelZoomFactor(Float.NaN))
        assertTrue(mediaWheelZoomFactor(-0.1f) > 1f)
    }

    @Test
    fun malformedGestureDoesNotCorruptCurrentState() {
        val transform = MediaViewportTransform(2f)
        assertEquals(transform, transform.transform(Float.NaN, Offset.Zero, Offset.Zero, viewport, landscape))
        assertEquals(transform, transform.transform(-1f, Offset.Zero, Offset.Zero, viewport, landscape))
    }

    @Test
    fun actualSizeUsesImagePixelsInsteadOfFitPercentage() {
        assertEquals(2f, mediaActualSizeZoom(viewport, landscape))
        assertEquals(50, mediaPixelScalePercent(1f, viewport, landscape))
        assertEquals(100, mediaPixelScalePercent(2f, viewport, landscape))
        val large = Size(16000f, 12000f)
        assertEquals(20f, MediaViewportTransform(mediaActualSizeZoom(viewport, large)).bounded(viewport, large).zoom)
    }

    @Test
    fun smallImageCanReturnFromFitToActualSizeWithoutPanning() {
        val small = Size(400f, 300f)
        assertEquals(0.5f, mediaActualSizeZoom(viewport, small))
        assertEquals(MediaViewportTransform(0.5f), MediaViewportTransform(0.5f, Offset(100f, 100f)).bounded(viewport, small))
        assertEquals(100, mediaPixelScalePercent(0.5f, viewport, small))
        assertEquals(1f, mediaActualSizeZoom(Size.Zero, small))
    }

    @Test
    fun actualSizeUsesOnePhysicalPixelAcrossAspectRatiosAndWindowSizes() {
        for (window in listOf(Size(390f, 844f), Size(1280f, 720f), Size(1920f, 1080f))) {
            for (image in listOf(Size(401f, 307f), Size(200f, 600f), Size(16000f, 12000f))) {
                val fitScale = minOf(window.width / image.width, window.height / image.height)
                val transform = MediaViewportTransform(mediaActualSizeZoom(window, image)).bounded(window, image)
                assertEquals(1f, fitScale * transform.zoom, 0.0001f)
                assertEquals(100, mediaPixelScalePercent(transform.zoom, window, image))
                assertEquals(Offset.Zero, transform.offset)
            }
        }
    }
}
