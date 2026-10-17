package dev.obiente.nextcloudnative.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import java.io.IOException
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopVideoFramesTest {
    @Test
    fun decoderOutputKeepsAspectAndStaysBounded() {
        assertEquals(IntSize(1920, 1080), desktopVideoBufferSize(1920, 1080, 2560))
        assertEquals(IntSize(2560, 1440), desktopVideoBufferSize(3840, 2160, 2560))
        assertEquals(IntSize(1440, 2560), desktopVideoBufferSize(2160, 3840, 2560))
        assertEquals(IntSize(160, 90), desktopVideoBufferSize(161, 91, 2560), "RV32 planes stay even-sized.")
        assertEquals(IntSize(2, 2), desktopVideoBufferSize(1, 1, 2560))
        assertEquals(IntSize(2560, 1440), desktopVideoBufferSize(0, 0, 2560), "Unknown size gets a 16:9 buffer.")
    }

    @Test
    fun framesLetterboxInsideTheCanvas() {
        assertEquals(Rect(0f, 75f, 800f, 525f), fitVideoRect(IntSize(1920, 1080), 800f, 600f))
        assertEquals(Rect(231.25f, 0f, 568.75f, 600f), fitVideoRect(IntSize(1080, 1920), 800f, 600f))
        assertEquals(Rect.Zero, fitVideoRect(IntSize(0, 0), 800f, 600f))
        assertEquals(Rect.Zero, fitVideoRect(IntSize(16, 9), 0f, 600f))
    }

    @Test
    fun cursorReadsSequentiallySeeksAndRecordsFailures() {
        val source = ByteArray(10) { it.toByte() }
        val requests = mutableListOf<Pair<Long, Int>>()
        val cursor = DesktopVideoRangeCursor(source.size.toLong()) { offset, length ->
            requests += offset to length
            source.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val buffer = ByteArray(4)
        assertTrue(cursor.open())
        assertEquals(4, cursor.readInto(buffer, 4))
        assertEquals(listOf<Byte>(0, 1, 2, 3), buffer.toList())
        assertTrue(cursor.seek(8))
        assertEquals(2, cursor.readInto(buffer, 4), "The last read stops at the source size.")
        assertEquals(0, cursor.readInto(buffer, 4), "End of media.")
        assertFalse(cursor.seek(11))
        assertFalse(cursor.seek(-1))
        assertEquals(listOf(0L to 4, 8L to 2), requests)

        val failing = DesktopVideoRangeCursor(10L) { _, _ -> throw IOException("synthetic offline") }
        assertEquals(-1, failing.readInto(buffer, 4))
        assertIs<IOException>(failing.lastFailure)
        assertEquals(0L, failing.position, "A failed read does not advance.")
        assertTrue(failing.open())
        assertNull(failing.lastFailure, "Reopening clears the previous attempt.")

        val short = DesktopVideoRangeCursor(10L) { _, _ -> ByteArray(1) }
        assertEquals(-1, short.readInto(buffer, 4), "An incomplete range never reaches the decoder.")
    }

    @Test
    fun readFailuresMapToRecoverableCopy() {
        assertEquals(NativeVideoPlaybackFailure.NetworkUnavailable, desktopVideoReadFailure(IOException()))
        assertEquals(NativeVideoPlaybackFailure.Unknown, desktopVideoReadFailure(IllegalStateException()))
        assertEquals(NativeVideoPlaybackFailure.MalformedMedia, desktopVideoReadFailure(null))
    }

    @Test
    fun onlyTheNewestPublishedFrameIsDrawnAndClosingDropsIt() {
        val frames = DesktopVideoFrameBuffer()
        assertNull(frames.withLatestFrame { _, _ -> Unit }, "Nothing to draw before the first frame.")
        frames.configure(4, 2)
        frames.publish(ByteBuffer.allocateDirect(4 * 2 * 4))
        frames.publish(ByteBuffer.allocateDirect(4))
        assertEquals(1L, frames.frameVersion.longValue, "A short buffer is ignored.")

        val drawn = frames.withLatestFrame { image, geometry -> Triple(image.width, image.height, geometry) }
        assertEquals(Triple(4, 2, DesktopVideoFrameGeometry(IntSize(4, 2), IntSize(4, 2))), drawn)
        frames.setVisibleSize(3, 2, sampleAspectRatio = 2.0)
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(3, 2), display = IntSize(6, 2)),
            frames.withLatestFrame { _, geometry -> geometry },
        )

        frames.close()
        assertNull(frames.withLatestFrame { _, _ -> Unit })
        frames.publish(ByteBuffer.allocateDirect(4 * 2 * 4))
        assertEquals(1L, frames.frameVersion.longValue, "A closed buffer accepts no frames.")
    }

    @Test
    fun decoderPaddingIsCroppedEvenWhenTheBufferIsDownscaled() {
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(1920, 1080), display = IntSize(1920, 1080)),
            desktopVideoFrameGeometry(IntSize(1920, 1088), IntSize(1920, 1088), IntSize(1920, 1080), 1.0),
        )
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(2560, 1440), display = IntSize(3840, 2160)),
            desktopVideoFrameGeometry(IntSize(2560, 1451), IntSize(3840, 2176), IntSize(3840, 2160), 1.0),
        )
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(720, 480), display = IntSize(853, 480)),
            desktopVideoFrameGeometry(IntSize(720, 480), IntSize(720, 480), IntSize(720, 480), 32.0 / 27.0),
            "Anamorphic DVD-style pixels widen on display.",
        )
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(160, 96), display = IntSize(160, 96)),
            desktopVideoFrameGeometry(IntSize(160, 96), IntSize(160, 96), IntSize(0, 0), Double.NaN),
            "An unknown visible size shows the whole buffer.",
        )
    }

    @Test
    fun quarterTurnsAreAppliedExactlyOnce() {
        // libVLC rotated the output: the padded buffer only fits the swapped picture.
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(1080, 1920), display = IntSize(1080, 1920), quarterTurnsClockwise = 0),
            desktopVideoFrameGeometry(IntSize(1088, 1920), IntSize(1088, 1920), IntSize(1920, 1080), 1.0, orientationTurns = 3),
        )
        // The output still holds the stored landscape picture, so it is turned while drawing.
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(1920, 1080), display = IntSize(1080, 1920), quarterTurnsClockwise = 1),
            desktopVideoFrameGeometry(IntSize(1920, 1088), IntSize(1920, 1088), IntSize(1920, 1080), 1.0, orientationTurns = 1),
        )
        // A square picture fits both ways; libVLC's autorotation is assumed, never a second turn.
        assertEquals(
            0,
            desktopVideoFrameGeometry(IntSize(1088, 1088), IntSize(1088, 1088), IntSize(1080, 1080), 1.0, 1)
                .quarterTurnsClockwise,
        )
        // Half turns cannot be detected by size and stay with libVLC.
        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(1920, 1080), display = IntSize(1920, 1080)),
            desktopVideoFrameGeometry(IntSize(1920, 1088), IntSize(1920, 1088), IntSize(1920, 1080), 1.0, 2),
        )
    }

    @Test
    fun drawingTurnsTheStoredPictureUpright() {
        // Red left half, blue right half, stored landscape; three clockwise turns put blue on top.
        val stored = Bitmap().apply {
            allocN32Pixels(4, 2)
            erase(0xFF0000FF.toInt())
            erase(0xFFFF0000.toInt(), IRect.makeXYWH(0, 0, 2, 2))
        }
        val image = Image.makeFromBitmap(stored)
        val geometry = DesktopVideoFrameGeometry(crop = IntSize(4, 2), display = IntSize(2, 4), quarterTurnsClockwise = 3)
        Surface.makeRasterN32Premul(20, 40).use { surface ->
            drawDesktopVideoFrame(surface.canvas, image, geometry, 20f, 40f)
            Bitmap().use { drawn ->
                drawn.allocN32Pixels(20, 40)
                surface.readPixels(drawn, 0, 0)
                assertEquals(0xFF0000FF.toInt(), drawn.getColor(10, 5), "Top is blue.")
                assertEquals(0xFFFF0000.toInt(), drawn.getColor(10, 35), "Bottom is red.")
            }
        }
        image.close()
        stored.close()
    }

    @Test
    fun bundledLibVlcIsFoundOnlyWithItsLibraryAndPlugins() {
        val resources = Files.createTempDirectory("libvlc-layout")
        try {
            assertNull(bundledLibVlcLibraryDirectory(null, "Windows 11"))
            assertNull(bundledLibVlcLibraryDirectory(resources.toString(), "Windows 11"))

            val windows = Files.createDirectories(resources.resolve("libvlc"))
            Files.createFile(windows.resolve("libvlc.dll"))
            assertNull(bundledLibVlcLibraryDirectory(resources.toString(), "Windows 11"), "Plugins are required.")
            Files.createDirectories(windows.resolve("plugins"))
            assertEquals(windows, bundledLibVlcLibraryDirectory(resources.toString(), "Windows 11"))

            assertNull(bundledLibVlcLibraryDirectory(resources.toString(), "Mac OS X"))
            val mac = Files.createDirectories(windows.resolve("lib"))
            Files.createFile(mac.resolve("libvlc.dylib"))
            assertEquals(mac, bundledLibVlcLibraryDirectory(resources.toString(), "Mac OS X"))
        } finally {
            resources.toFile().deleteRecursively()
        }
    }
}
