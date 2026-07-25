package dev.obiente.nextcloudnative

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.EncodedImageOrientationPolicy
import dev.obiente.nextcloudnative.app.decodePlatformImageSampled
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExifOrientationInstrumentedTest {
    @Test
    fun bitmapMatrixNormalizesAllExifOrientationsExactlyOnce() {
        val jpeg = syntheticCornerJpeg()
        val raw = requireNotNull(
            decodePlatformImageSampled(
                jpeg,
                256,
                EncodedImageOrientationPolicy.PixelsAlreadyUpright,
            ),
        )
        val sourceCoordinates = listOf(
            10 to 7,
            30 to 7,
            10 to 22,
            30 to 22,
        )
        val sourcePixels = raw.image.readAllPixels()

        for (orientation in 1..8) {
            val decoded = requireNotNull(
                decodePlatformImageSampled(
                    jpeg.withExifOrientation(orientation),
                    256,
                    EncodedImageOrientationPolicy.ApplyExif,
                ),
            )
            val expectedWidth = if (orientation in 5..8) raw.sourceHeight else raw.sourceWidth
            val expectedHeight = if (orientation in 5..8) raw.sourceWidth else raw.sourceHeight
            assertEquals("source width for EXIF $orientation", expectedWidth, decoded.sourceWidth)
            assertEquals("source height for EXIF $orientation", expectedHeight, decoded.sourceHeight)
            assertEquals("bitmap width for EXIF $orientation", expectedWidth, decoded.image.width)
            assertEquals("bitmap height for EXIF $orientation", expectedHeight, decoded.image.height)
            val outputPixels = decoded.image.readAllPixels()
            sourceCoordinates.forEach { (x, y) ->
                val (destinationX, destinationY) = expectedDestination(
                    x,
                    y,
                    raw.sourceWidth,
                    raw.sourceHeight,
                    orientation,
                )
                assertColorNear(
                    expected = sourcePixels[y * raw.sourceWidth + x],
                    actual = outputPixels[destinationY * decoded.image.width + destinationX],
                    message = "pixel ($x,$y) for EXIF $orientation",
                )
            }
        }

        val trustedServerPreview = requireNotNull(
            decodePlatformImageSampled(
                jpeg.withExifOrientation(6),
                256,
                EncodedImageOrientationPolicy.PixelsAlreadyUpright,
            ),
        )
        assertEquals(40, trustedServerPreview.image.width)
        assertEquals(30, trustedServerPreview.image.height)
        assertColorNear(
            expected = sourcePixels[7 * raw.sourceWidth + 10],
            actual = trustedServerPreview.image.readAllPixels()[7 * trustedServerPreview.image.width + 10],
            message = "already-normalized server preview",
        )
    }

    private fun syntheticCornerJpeg(): ByteArray {
        val source = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(
            0xFFE02020.toInt(),
            0xFF20C040.toInt(),
            0xFF3050E0.toInt(),
            0xFFE0C020.toInt(),
        )
        repeat(source.height) { y ->
            repeat(source.width) { x ->
                val quadrant = (if (y >= source.height / 2) 2 else 0) +
                    (if (x >= source.width / 2) 1 else 0)
                source.setPixel(x, y, colors[quadrant])
            }
        }
        return ByteArrayOutputStream().use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, output))
            source.recycle()
            output.toByteArray()
        }
    }

    private fun ByteArray.withExifOrientation(orientation: Int): ByteArray {
        require(orientation in 1..8)
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0,
            8, 0, 0, 0,
            1, 0,
            0x12, 0x01,
            3, 0,
            1, 0, 0, 0,
            orientation.toByte(), 0, 0, 0,
            0, 0, 0, 0,
        )
        val payload = "Exif\u0000\u0000".encodeToByteArray() + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(),
            0xE1.toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + payload
        return copyOfRange(0, 2) + app1 + copyOfRange(2, size)
    }

    private fun ImageBitmap.readAllPixels(): IntArray =
        IntArray(width * height).also { readPixels(it) }

    private fun expectedDestination(
        sourceX: Int,
        sourceY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        orientation: Int,
    ): Pair<Int, Int> = when (orientation) {
        1 -> sourceX to sourceY
        2 -> sourceWidth - 1 - sourceX to sourceY
        3 -> sourceWidth - 1 - sourceX to sourceHeight - 1 - sourceY
        4 -> sourceX to sourceHeight - 1 - sourceY
        5 -> sourceY to sourceX
        6 -> sourceHeight - 1 - sourceY to sourceX
        7 -> sourceHeight - 1 - sourceY to sourceWidth - 1 - sourceX
        8 -> sourceY to sourceWidth - 1 - sourceX
        else -> error("Unsupported EXIF orientation.")
    }

    private fun assertColorNear(expected: Int, actual: Int, message: String) {
        fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF
        for (shift in listOf(16, 8, 0)) {
            assertTrue(
                "$message expected 0x${expected.toUInt().toString(16)}, " +
                    "actual 0x${actual.toUInt().toString(16)}",
                abs(channel(expected, shift) - channel(actual, shift)) <= 24,
            )
        }
    }
}
