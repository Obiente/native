package dev.obiente.nextcloudnative.app

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
