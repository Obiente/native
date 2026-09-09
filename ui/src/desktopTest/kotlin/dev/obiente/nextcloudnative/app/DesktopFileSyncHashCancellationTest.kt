package dev.obiente.nextcloudnative.app

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopFileSyncHashCancellationTest {
    @Test
    fun `local scan stops between content hash chunks`() {
        val root = createTempDirectory("desktop-sync-hash-cancellation")
        try {
            root.resolve("large.bin").writeBytes(ByteArray(128 * 1_024))
            var continuationChecks = 0

            assertFailsWith<DesktopFileSyncScanStoppedException> {
                DesktopFileSyncLocalTree(root.toFile()).scan(
                    shouldContinue = { ++continuationChecks <= 5 },
                )
            }

            assertTrue(continuationChecks > 5)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
