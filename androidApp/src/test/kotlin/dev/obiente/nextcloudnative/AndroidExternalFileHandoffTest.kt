package dev.obiente.nextcloudnative

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.obiente.nextcloudnative.app.ExternalFileHandoffAction

class AndroidExternalFileHandoffTest {
    @Test
    fun `share and open intents have distinct least privilege payload plans`() {
        val share = androidExternalFileIntentPlan(ExternalFileHandoffAction.Share)
        val open = androidExternalFileIntentPlan(ExternalFileHandoffAction.OpenWith)

        assertEquals("android.intent.action.SEND", share.action)
        assertEquals("Share file", share.chooserTitle)
        assertTrue(share.attachStream)
        assertEquals("android.intent.action.VIEW", open.action)
        assertEquals("Open file with", open.chooserTitle)
        assertFalse(open.attachStream)
    }

    @Test
    fun `cache pruning removes expired handoffs but preserves recent ones`() {
        val root = Files.createTempDirectory("nextcloud-handoff-test-").toFile()
        try {
            val old = root.resolve("old").apply { mkdir() }
            old.resolve("payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val recent = root.resolve("recent").apply { mkdir() }
            recent.resolve("payload.bin").writeBytes(byteArrayOf(4, 5, 6))
            val now = 2L * 24L * 60L * 60L * 1000L
            old.setLastModified(1L)
            recent.setLastModified(now)

            pruneExternalShareCache(root, requiredBytes = 1L, nowMillis = now)

            assertFalse(old.exists())
            assertTrue(recent.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cache pruning rejects a non-directory root`() {
        val root = Files.createTempFile("nextcloud-handoff-test-", ".tmp").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                pruneExternalShareCache(root, requiredBytes = 1L)
            }
        } finally {
            root.delete()
        }
    }
}
