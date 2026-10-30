package dev.obiente.nextcloudnative.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaViewerZoomGeometryTest {
    @Test
    fun aPhotoRestsFittedAndCenteredInTheViewer() {
        assertEquals(Rect(0f, 175f, 400f, 625f), mediaZoomFitRect(Size(400f, 800f), 4000, 4500))
        assertEquals(Rect(0f, 287.5f, 400f, 512.5f), mediaZoomFitRect(Size(400f, 800f), 1600, 900))
        assertEquals(Rect(0f, 0f, 400f, 800f), mediaZoomFitRect(Size(400f, 800f), 0, 900), "An empty image falls back to the viewer")
    }

    @Test
    fun aCroppedTileShowsTheMiddleOfThePhoto() {
        assertEquals(Rect(350f, 0f, 1250f, 900f), mediaZoomCoverCrop(1600, 900, Size(100f, 100f)))
        assertEquals(Rect(0f, 350f, 900f, 1250f), mediaZoomCoverCrop(900, 1600, Size(100f, 100f)))
        assertEquals(Rect(0f, 0f, 1600f, 900f), mediaZoomCoverCrop(1600, 900, Size(160f, 90f)), "At its own shape nothing is cropped")
    }

    @Test
    fun theViewerReplacesTheZoomedThumbnailOnlyNearTheEnd() {
        assertEquals(0f, mediaViewerContentAlpha(0.5f, zooming = true))
        assertEquals(0f, mediaViewerContentAlpha(0.7f, zooming = true))
        assertEquals(1f, mediaViewerContentAlpha(1f, zooming = true))
        assertEquals(0.5f, mediaViewerContentAlpha(0.5f, zooming = false), "Without a tile the viewer fades with the backdrop")
    }
}
