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

            copyAndSyncAndroidPendingMutation(temporary, target)

            assertEquals("durable mutation identity", target.readText())
            assertFalse(temporary.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
