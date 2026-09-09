package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmSupportAccountStorageCleanupTest {
    @Test
    fun `removal purges only descriptors and archive proven to belong to the account`() {
        val root = Files.createTempDirectory("support-retirement").toFile()
        val removed = "a".repeat(64)
        val retained = "b".repeat(64)
        var syncs = 0
        try {
            val archive = root.resolve("support-00000000-0000-0000-0000-000000000001.zip")
                .apply { writeText("private") }
            root.resolve("pending.json").writeText(
                """{"originAccountIdentity":"$removed","archiveName":"${archive.name}"}""",
            )
            val removedCompleted = root.resolve("completed-00000000-0000-0000-0000-000000000002.json")
                .apply { writeText("""{"originAccountIdentity":"$removed"}""") }
            val retainedCompleted = root.resolve("completed-00000000-0000-0000-0000-000000000003.json")
                .apply { writeText("""{"originAccountIdentity":"$retained"}""") }

            JvmSupportAccountStorageCleanup(root, { syncs += 1 }).removeAccount(removed, archive)

            assertFalse(root.resolve("pending.json").exists())
            assertFalse(archive.exists())
            assertFalse(removedCompleted.exists())
            assertTrue(retainedCompleted.isFile)
            assertEquals(1, syncs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unreadable ownership fails closed without deleting the descriptor`() {
        val root = Files.createTempDirectory("support-retirement-invalid").toFile()
        val descriptor = root.resolve("pending.json").apply { writeText("not-json") }
        try {
            assertFails {
                JvmSupportAccountStorageCleanup(root, {}).removeAccount("a".repeat(64), null)
            }
            assertTrue(descriptor.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `archive deletion failure preserves the descriptor for cleanup retry`() {
        val root = Files.createTempDirectory("support-retirement-delete-failure").toFile()
        val account = "c".repeat(64)
        val archive = root.resolve("support-00000000-0000-0000-0000-000000000004.zip")
            .apply { writeText("private") }
        val descriptor = root.resolve("pending.json").apply {
            writeText("""{"originAccountIdentity":"$account","archiveName":"${archive.name}"}""")
        }
        try {
            assertFails {
                JvmSupportAccountStorageCleanup(root, {}, deleteFile = { false })
                    .removeAccount(account, archive)
            }
            assertTrue(descriptor.isFile)
            assertTrue(archive.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
