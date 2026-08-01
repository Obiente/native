package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFileSyncEngineTest {
    @Test
    fun `stale owned stages are reclaimed without touching lookalikes`() {
        val root = Files.createTempDirectory("desktop-sync-stage-recovery-").toFile()
        try {
            val stale = root.resolve("nextcloud-native-download-${UUID.randomUUID()}.tmp").apply {
                writeText("partial download")
            }
            val unknownPrefix = root.resolve("nextcloud-native-preview-${UUID.randomUUID()}.tmp").apply {
                writeText("keep")
            }
            val invalidToken = root.resolve("nextcloud-native-download-not-a-uuid.tmp").apply {
                writeText("keep")
            }
            val ownedDirectory = root.resolve("nextcloud-native-download-${UUID.randomUUID()}.tmp").apply {
                mkdir()
            }

            assertEquals(1, reclaimDesktopFileSyncStages(root))

            assertFalse(stale.exists())
            assertTrue(unknownPrefix.isFile)
            assertTrue(invalidToken.isFile)
            assertTrue(ownedDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
