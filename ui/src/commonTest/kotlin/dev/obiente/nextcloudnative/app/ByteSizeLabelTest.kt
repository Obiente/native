package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteSizeLabelTest {
    @Test
    fun smallCountsStayInBytes() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1023 B", formatByteSize(1_023))
    }

    @Test
    fun wholeValuesDropTheDecimal() {
        assertEquals("1 KiB", formatByteSize(1_024))
        assertEquals("4 KiB", formatByteSize(4_096))
        assertEquals("8 MiB", formatByteSize(8L * 1_024L * 1_024L))
    }

    @Test
    fun fractionalValuesKeepOneRoundedDecimal() {
        assertEquals("1.5 KiB", formatByteSize(1_536))
        assertEquals("12.5 MiB", formatByteSize(13_107_200))
        assertEquals("1.5 GiB", formatByteSize(1_610_612_736))
    }

    @Test
    fun valuesThatRoundToTheNextUnitArePromoted() {
        assertEquals("1 MiB", formatByteSize(1_048_575))
        assertEquals("1 GiB", formatByteSize(1_073_741_823))
    }

    @Test
    fun largestUnitAbsorbsOversizedCounts() {
        assertEquals("8388608 TiB", formatByteSize(Long.MAX_VALUE))
    }

    @Test
    fun missingOrNegativeSizesReadAsUnknown() {
        assertEquals("Unknown size", formatByteSize(-1))
        assertEquals("Unknown size", formatOptionalByteSize(null))
        assertEquals("2 KiB", formatOptionalByteSize(2_048))
    }
}
