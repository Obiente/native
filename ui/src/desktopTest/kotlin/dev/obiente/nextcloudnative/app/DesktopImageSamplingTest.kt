package dev.obiente.nextcloudnative.app

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopImageSamplingTest {
    @Test
    fun largeJpegIsDecodedIntoABoundedTarget() {
        val source = BufferedImage(2_048, 1_536, BufferedImage.TYPE_INT_RGB)
        val encoded = ByteArrayOutputStream().also { output ->
            assertTrue(ImageIO.write(source, "jpg", output))
        }.toByteArray()

        val decoded = assertNotNull(decodePlatformImageSampled(encoded, maximumDimension = 256))

        assertEquals(2_048, decoded.sourceWidth)
        assertEquals(1_536, decoded.sourceHeight)
        assertTrue(decoded.image.width <= 256)
        assertTrue(decoded.image.height <= 256)
    }

    @Test
    fun decodePlanRejectsUnsafeMetadataBeforePixelAllocation() {
        assertFailsWith<IllegalArgumentException> {
            desktopDecodeTargetDimensions(
                sourceWidth = 1_000_001,
                sourceHeight = 1,
                maximumDimension = 1_600,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            desktopDecodeTargetDimensions(
                sourceWidth = 8_000,
                sourceHeight = 8_000,
                maximumDimension = null,
            )
        }
    }
}
