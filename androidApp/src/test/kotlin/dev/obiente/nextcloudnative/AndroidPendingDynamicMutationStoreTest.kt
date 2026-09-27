package dev.obiente.nextcloudnative

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AndroidPendingDynamicMutationStoreTest {
    @Test
    fun `fallback syncs a complete destination before clearing staging`() {
        val root = createTempDirectory("android-pending-mutation").toFile()
        try {
            val temporary = root.resolve("mutation.json.part").apply {
                writeText("durable mutation identity")
            }
            val target = root.resolve("mutation.json").apply {
                writeText("stale")
            }

            var synced = false
            copyAndSyncAndroidPendingMutation(temporary, target) { directory ->
                assertEquals(root, directory)
                assertEquals("durable mutation identity", target.readText())
                synced = true
            }
            kotlin.test.assertTrue(synced)

            assertEquals("durable mutation identity", target.readText())
            assertFalse(temporary.exists())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun `directory sync failure prevents publication from authorizing a request`() {
        val root = createTempDirectory("android-pending-fence").toFile()
        try {
            for (fallback in listOf(false, true)) {
                val temporary = root.resolve("marker.part").apply { writeText("identity") }
                val target = root.resolve("marker.json")
                val failure = java.io.IOException("synthetic directory sync failure")
                val observed = kotlin.test.assertFailsWith<java.io.IOException> {
                    if (fallback) copyAndSyncAndroidPendingMutation(temporary, target) { throw failure }
                    else publishAndroidPendingMutation(temporary, target) { throw failure }
                }
                kotlin.test.assertSame(failure, observed)
                assertEquals("identity", target.readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
