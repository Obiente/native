package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFileSyncDiagnosticsTest {
    @Test
    fun `count and byte diagnostics use stable coarse buckets`() {
        assertEquals("0", desktopFileSyncCountBucket(0))
        assertEquals("10k-99k", desktopFileSyncCountBucket(42_000))
        assertEquals("100k+", desktopFileSyncCountBucket(100_001))
        assertEquals("under-1mib", desktopFileSyncByteBucket(512L * 1_024L))
        assertEquals("10gib-99gib", desktopFileSyncByteBucket(17L * 1_024L * 1_024L * 1_024L))
    }

}
