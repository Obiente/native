package dev.obiente.nextcloudnative.app

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue

/**
 * Plays synthetic 1.2 second 160x90 H.264/AAC clips through real libVLC and the range-callback
 * path.
 *
 * It runs only where libVLC is installed or bundled. The deterministic contract is covered by
 * [DesktopVideoFramesTest] and [DesktopVideoSurfaceSceneTest]; this proves the native wiring.
 */
class DesktopLibVlcPlaybackTest {
    private val runtime = DesktopLibVlcRuntime(enabled = true)

    @Test
    fun aRangeSourceClipDecodesFramesAndEnds() = runBlocking<Unit> {
        val geometry = play("/video/synthetic-test-pattern.mp4")

        assertEquals(
            DesktopVideoFrameGeometry(crop = IntSize(160, 90), display = IntSize(160, 90)),
            geometry,
            "Decoder padding below the 90 visible lines is not drawn.",
        )
    }

    /**
     * The clip stores red on the left and blue on the right with a 90 degree counterclockwise
     * display rotation, so upright it is portrait with blue on top (as ffmpeg renders it).
     */
    @Test
    fun rotationMetadataIsAppliedOnceAndUpright() = runBlocking<Unit> {
        var top = 0
        var bottom = 0
        val geometry = play("/video/synthetic-red-left-rotated-ccw.mp4") { image, geometry ->
            Surface.makeRasterN32Premul(96, 160).use { surface ->
                drawDesktopVideoFrame(surface.canvas, image, geometry, 96f, 160f)
                Bitmap().use { bitmap ->
                    bitmap.allocN32Pixels(96, 160)
                    surface.readPixels(bitmap, 0, 0)
                    top = bitmap.getColor(48, 20)
                    bottom = bitmap.getColor(48, 140)
                }
            }
        }

        assertEquals(IntSize(96, 160), geometry.display, "A quarter-turn clip displays as portrait.")
        assertTrue(Color.getB(top) > 200 && Color.getR(top) < 60, "Top is blue: ${top.toUInt().toString(16)}")
        assertTrue(Color.getR(bottom) > 200 && Color.getB(bottom) < 60, "Bottom is red: ${bottom.toUInt().toString(16)}")
    }

    private suspend fun play(
        resource: String,
        inspect: (Image, DesktopVideoFrameGeometry) -> Unit = { _, _ -> },
    ): DesktopVideoFrameGeometry {
        assumeTrue("libVLC is not available on this machine.", runtime.isAvailable)
        val clip = assertNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }
        val reads = mutableListOf<Long>()
        var closed = false
        val source = NativeVideoRangeSource(
            size = clip.size.toLong(),
            readBlock = { offset, length ->
                synchronized(reads) { reads += offset }
                clip.copyOfRange(offset.toInt(), offset.toInt() + length)
            },
            closeBlock = { closed = true },
        )
        val engine = DesktopVlcVideoEngine(source, runtime)
        try {
            engine.start()
            val outcome = withTimeout(20_000L) { engine.outcomes.first() }

            assertEquals(DesktopVideoOutcome.Ended, outcome)
            assertTrue(engine.frames.frameVersion.longValue > 0L, "libVLC delivered decoded frames.")
            val duration = assertNotNull(engine.state.value.durationMillis)
            assertTrue(duration in 1_000L..1_500L, "Reported duration $duration ms.")
            assertTrue(synchronized(reads) { reads.isNotEmpty() && reads.all { it in clip.indices } })
            return assertNotNull(
                engine.frames.withLatestFrame { image, geometry -> inspect(image, geometry); geometry },
            )
        } finally {
            engine.release()
            source.close()
            assertTrue(closed)
        }
    }
}
