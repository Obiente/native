package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAccountSyncStageRemovalTest {
    @Test
    fun removalReclaimsRecognizedCrashStagesWithoutAnotherPairRun() {
        val root = Files.createTempDirectory("synthetic-account-stages").toFile()
        try {
            val stages = root.resolve("stages").apply { mkdir() }
            val orphan = stages.resolve("nextcloud-native-upload-${UUID.randomUUID()}.tmp").apply { writeText("synthetic snapshot") }
            val unrelated = stages.resolve("user-original.txt").apply { writeText("synthetic original") }
            removeDesktopSyncAccountWithStages(DesktopFileSyncStore(root.resolve("state.db")), stages, "a".repeat(64))
            assertFalse(orphan.exists())
            assertTrue(unrelated.isFile)
        } finally { root.deleteRecursively() }
    }
}
