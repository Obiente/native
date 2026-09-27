package dev.obiente.nextcloudnative

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileSyncStagingRetirementTest {
    @Test
    fun `account retirement removes only its crashed staging files`() {
        val root = Files.createTempDirectory("sync-staging").toFile()
        val removed = "a".repeat(64)
        val retained = "b".repeat(64)
        try {
            val removedRoot = androidFileSyncAccountStagingRoot(root, removed).apply { mkdirs() }
            val retainedRoot = androidFileSyncAccountStagingRoot(root, retained).apply { mkdirs() }
            val removedStage = java.io.File(removedRoot, "upload-crashed.tmp").apply { writeText("private") }
            val retainedStage = java.io.File(retainedRoot, "keep-local-running.tmp").apply { writeText("other") }

            removeAndroidFileSyncAccountStaging(root, removed)

            assertFalse(removedStage.exists())
            assertFalse(removedRoot.exists())
            assertTrue(retainedStage.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy crash files are reclaimed without entering account directories`() {
        val root = Files.createTempDirectory("sync-staging-legacy").toFile()
        try {
            val legacy = java.io.File(root, "keep-remote-crashed.tmp").apply { writeText("private") }
            val retainedRoot = androidFileSyncAccountStagingRoot(root, "c".repeat(64)).apply { mkdirs() }
            val retained = java.io.File(retainedRoot, "upload-running.tmp").apply { writeText("other") }

            removeLegacyAndroidFileSyncStaging(root)

            assertFalse(legacy.exists())
            assertTrue(retained.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
