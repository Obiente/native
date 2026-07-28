package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageDecodeBoundsTest {
    @Test
    fun largePortraitIsBoundedBeforeItCanReachCompose() {
        val plan = boundedImageDecodePlan(
            sourceWidth = 5_152,
            sourceHeight = 7_728,
            maximumDimension = 4_096,
        )

        assertEquals(2, plan.sampleSize)
        assertTrue(plan.targetWidth <= 4_096)
        assertTrue(plan.targetHeight <= 4_096)
        assertTrue(plan.targetPixels <= DEFAULT_MAXIMUM_DECODED_IMAGE_PIXELS)
    }

    @Test
    fun previewDecodeRemovesPowerOfTwoOvershoot() {
        val plan = boundedImageDecodePlan(
            sourceWidth = 5_152,
            sourceHeight = 7_728,
            maximumDimension = 1_600,
        )

        assertEquals(4, plan.sampleSize)
        assertEquals(1_066, plan.targetWidth)
        assertEquals(1_600, plan.targetHeight)
        assertTrue(plan.targetPixels <= 1_600L * 1_600L)
    }

    @Test
    fun smallImageRemainsAtItsOriginalDimensions() {
        val plan = boundedImageDecodePlan(
            sourceWidth = 640,
            sourceHeight = 480,
            maximumDimension = 1_600,
        )

        assertEquals(1, plan.sampleSize)
        assertEquals(640, plan.targetWidth)
        assertEquals(480, plan.targetHeight)
    }

    @Test
    fun maximumIntegerDimensionsDoNotRelyOnSampleSizeOverflow() {
        val plan = boundedImageDecodePlan(
            sourceWidth = Int.MAX_VALUE,
            sourceHeight = Int.MAX_VALUE,
            maximumDimension = 1,
            maximumPixels = 1,
        )

        assertEquals(1 shl 30, plan.sampleSize)
        assertEquals(1, plan.targetWidth)
        assertEquals(1, plan.targetHeight)
    }
}
