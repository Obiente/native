package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

class ExifOrientationTest {
    @Test
    fun readsLittleEndianJpegExifOrientation() {
        assertEquals(6, encodedImageOrientation(jpegWithExifOrientation(6, littleEndian = true)))
    }

    @Test
    fun readsBigEndianJpegExifOrientation() {
        assertEquals(8, encodedImageOrientation(jpegWithExifOrientation(8, littleEndian = false)))
    }

    @Test
    fun readsEveryExifOrientationInBothByteOrders() {
        for (orientation in 1..8) {
            assertEquals(
                orientation,
                encodedImageOrientation(jpegWithExifOrientation(orientation, littleEndian = true)),
            )
            assertEquals(
                orientation,
                encodedImageOrientation(jpegWithExifOrientation(orientation, littleEndian = false)),
            )
        }
    }

    @Test
    fun malformedAndOutOfRangeMetadataStayUpright() {
        assertEquals(1, encodedImageOrientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(1, encodedImageOrientation(jpegWithExifOrientation(9, littleEndian = true)))
    }

    @Test
    fun dimensionSwappingMatchesExifTransposeOrientations() {
        assertFalse(orientationSwapsDimensions(1))
        assertFalse(orientationSwapsDimensions(4))
        assertTrue(orientationSwapsDimensions(5))
        assertTrue(orientationSwapsDimensions(8))
    }

    @Test
    fun allEightOrientationsMapPixelsIncludingMirroredCases() {
        val sourceRows = listOf("ABC", "DEF")
        val expected = mapOf(
            1 to listOf("ABC", "DEF"),
            2 to listOf("CBA", "FED"),
            3 to listOf("FED", "CBA"),
            4 to listOf("DEF", "ABC"),
            5 to listOf("AD", "BE", "CF"),
            6 to listOf("DA", "EB", "FC"),
            7 to listOf("FC", "EB", "DA"),
            8 to listOf("CF", "BE", "AD"),
        )
        for (orientation in 1..8) {
            val dimensions = exifOrientedDimensions(3, 2, orientation)
            val destination = Array(dimensions.height) { CharArray(dimensions.width) { '?' } }
            sourceRows.forEachIndexed { y, row ->
                row.forEachIndexed { x, label ->
                    val mapped = exifOrientedPixel(x, y, 3, 2, orientation)
                    destination[mapped.y][mapped.x] = label
                }
            }
            assertEquals(
                expected.getValue(orientation),
                destination.map(CharArray::concatToString),
                "EXIF orientation $orientation",
            )
            val matrix = exifOrientationMatrixValues(3, 2, orientation)
            sourceRows.forEachIndexed { y, row ->
                row.indices.forEach { x ->
                    val centerX = x + 0.5f
                    val centerY = y + 0.5f
                    val mappedX = matrix[0] * centerX + matrix[1] * centerY + matrix[2]
                    val mappedY = matrix[3] * centerX + matrix[4] * centerY + matrix[5]
                    val expectedPixel = exifOrientedPixel(x, y, 3, 2, orientation)
                    assertEquals(expectedPixel.x, mappedX.toInt(), "matrix x for EXIF $orientation")
                    assertEquals(expectedPixel.y, mappedY.toInt(), "matrix y for EXIF $orientation")
                }
            }
        }
    }

    @Test
    fun desktopDecodeNormalizesExifOrientationBeforeViewerAndEditorLayout() {
        val source = BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB)
        val jpegOutput = ByteArrayOutputStream()
        assertTrue(ImageIO.write(source, "jpg", jpegOutput))
        val jpeg = jpegOutput.toByteArray()
        val metadataOnly = jpegWithExifOrientation(6, littleEndian = true)
        val encoded = jpeg.copyOfRange(0, 2) +
            metadataOnly.copyOfRange(2, metadataOnly.size - 2) +
            jpeg.copyOfRange(2, jpeg.size)
        assertEquals(6, encodedImageOrientation(encoded))
        val skiaDecoded = org.jetbrains.skia.Image.makeFromEncoded(encoded)
        assertEquals(20, skiaDecoded.width)
        assertEquals(30, skiaDecoded.height)

        val decoded = requireNotNull(decodePlatformImageSampled(encoded, 256))

        assertEquals(20, decoded.sourceWidth)
        assertEquals(30, decoded.sourceHeight)
        assertEquals(20, decoded.image.width)
        assertEquals(30, decoded.image.height)
    }

    @Test
    fun desktopDecodeAppliesEveryOrientationOnceAndCanTrustNormalizedServerPixels() {
        val jpeg = syntheticCornerJpeg()
        val source = requireNotNull(
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
        val sourcePixels = source.image.readAllPixels()

        for (orientation in 1..8) {
            val encoded = jpeg.withExifOrientation(orientation)
            val decoded = requireNotNull(
                decodePlatformImageSampled(
                    encoded,
                    256,
                    EncodedImageOrientationPolicy.ApplyExif,
                ),
            )
            val expectedDimensions = exifOrientedDimensions(source.sourceWidth, source.sourceHeight, orientation)
            assertEquals(expectedDimensions.width, decoded.sourceWidth, "width for EXIF $orientation")
            assertEquals(expectedDimensions.height, decoded.sourceHeight, "height for EXIF $orientation")
            assertEquals(expectedDimensions.width, decoded.image.width, "bitmap width for EXIF $orientation")
            assertEquals(expectedDimensions.height, decoded.image.height, "bitmap height for EXIF $orientation")
            val outputPixels = decoded.image.readAllPixels()
            sourceCoordinates.forEach { (x, y) ->
                val mapped = exifOrientedPixel(
                    x,
                    y,
                    source.sourceWidth,
                    source.sourceHeight,
                    orientation,
                )
                assertColorNear(
                    expected = sourcePixels[y * source.sourceWidth + x],
                    actual = outputPixels[mapped.y * decoded.image.width + mapped.x],
                    message = "pixel ($x,$y) for EXIF $orientation",
                )
            }
        }

        val staleExifServerPreview = jpeg.withExifOrientation(6)
        val trusted = requireNotNull(
            decodePlatformImageSampled(
                staleExifServerPreview,
                256,
                EncodedImageOrientationPolicy.PixelsAlreadyUpright,
            ),
        )
        assertEquals(40, trusted.sourceWidth)
        assertEquals(30, trusted.sourceHeight)
        assertColorNear(
            expected = sourcePixels[7 * source.sourceWidth + 10],
            actual = trusted.image.readAllPixels()[7 * trusted.image.width + 10],
            message = "already-normalized server preview",
        )
    }

    @Test
    fun desktopOrientationPreservesPartialTransparency() {
        val source = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0x40FF0000)
            setRGB(1, 0, 0x8000FF00.toInt())
            setRGB(2, 0, 0xC00000FF.toInt())
            setRGB(0, 1, 0x60FFFF00)
            setRGB(1, 1, 0xA000FFFF.toInt())
            setRGB(2, 1, 0xE0FF00FF.toInt())
        }
        val png = ByteArrayOutputStream().also { output ->
            assertTrue(ImageIO.write(source, "png", output))
        }.toByteArray()
        Image.makeFromEncoded(png).use { encoded ->
            Bitmap().use { target ->
                assertTrue(target.allocN32Pixels(2, 3, false))
                target.erase(0xFFFF00FF.toInt())
                drawDesktopExifOrientation(encoded, target, 6)
                Image.makeFromBitmap(target).use { oriented ->
                    val decoded = oriented.toComposeImageBitmap()
                    assertEquals(2, decoded.width)
                    assertEquals(3, decoded.height)
                    val output = decoded.readAllPixels()
                    repeat(source.height) { y ->
                        repeat(source.width) { x ->
                            val mapped = exifOrientedPixel(x, y, source.width, source.height, 6)
                            assertArgbNear(
                                expected = source.getRGB(x, y),
                                actual = output[mapped.y * decoded.width + mapped.x],
                                tolerance = 1,
                                message = "transparent pixel ($x,$y)",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun desktopOrientationPreservesEmbeddedColorSpace() {
        Bitmap().use { bitmap ->
            val imageInfo = ImageInfo(
                3,
                2,
                ColorType.RGBA_8888,
                ColorAlphaType.PREMUL,
                ColorSpace.displayP3,
            )
            assertTrue(bitmap.allocPixels(imageInfo))
            bitmap.erase(0xFFCC6633.toInt())
            Image.makeFromBitmap(bitmap).use { source ->
                renderDesktopExifOrientation(source, 6).use { oriented ->
                    val colorSpace = assertNotNull(oriented.colorSpace)
                    assertFalse(colorSpace.isSRGB)
                    assertEquals(ColorSpace.displayP3, colorSpace)
                }
            }
        }
    }

    private fun syntheticCornerJpeg(): ByteArray {
        val source = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        val colors = intArrayOf(0xE02020, 0x20C040, 0x3050E0, 0xE0C020)
        repeat(source.height) { y ->
            repeat(source.width) { x ->
                val quadrant = (if (y >= source.height / 2) 2 else 0) +
                    (if (x >= source.width / 2) 1 else 0)
                source.setRGB(x, y, colors[quadrant])
            }
        }
        return ByteArrayOutputStream().also { output ->
            assertTrue(ImageIO.write(source, "jpg", output))
        }.toByteArray()
    }

    private fun ByteArray.withExifOrientation(orientation: Int): ByteArray {
        val metadataOnly = jpegWithExifOrientation(orientation, littleEndian = true)
        return copyOfRange(0, 2) +
            metadataOnly.copyOfRange(2, metadataOnly.size - 2) +
            copyOfRange(2, size)
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.readAllPixels(): IntArray =
        IntArray(width * height).also { readPixels(it) }

    private fun assertColorNear(expected: Int, actual: Int, message: String) {
        fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF
        for (shift in listOf(16, 8, 0)) {
            assertTrue(
                abs(channel(expected, shift) - channel(actual, shift)) <= 20,
                "$message expected 0x${expected.toUInt().toString(16)}, " +
                    "actual 0x${actual.toUInt().toString(16)}",
            )
        }
    }

    private fun assertArgbNear(expected: Int, actual: Int, tolerance: Int, message: String) {
        for (shift in listOf(24, 16, 8, 0)) {
            val expectedChannel = (expected ushr shift) and 0xFF
            val actualChannel = (actual ushr shift) and 0xFF
            assertTrue(
                abs(expectedChannel - actualChannel) <= tolerance,
                "$message expected 0x${expected.toUInt().toString(16)}, " +
                    "actual 0x${actual.toUInt().toString(16)}",
            )
        }
    }

    private fun jpegWithExifOrientation(orientation: Int, littleEndian: Boolean): ByteArray {
        val tiff = ByteArray(26)
        if (littleEndian) {
            tiff[0] = 'I'.code.toByte()
            tiff[1] = 'I'.code.toByte()
            tiff[2] = 42
            tiff[4] = 8
            tiff[8] = 1
            tiff[10] = 0x12
            tiff[11] = 0x01
            tiff[12] = 3
            tiff[14] = 1
            tiff[18] = orientation.toByte()
        } else {
            tiff[0] = 'M'.code.toByte()
            tiff[1] = 'M'.code.toByte()
            tiff[3] = 42
            tiff[7] = 8
            tiff[9] = 1
            tiff[10] = 0x01
            tiff[11] = 0x12
            tiff[13] = 3
            tiff[17] = 1
            tiff[19] = orientation.toByte()
        }
        val payload = "Exif\u0000\u0000".encodeToByteArray() + tiff
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(),
            (length ushr 8).toByte(), length.toByte(),
        ) + payload + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
