package dev.obiente.nextcloudnative

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidIncomingShareStagingStorageTest {
    @Test
    fun scheduledExpiryRemovesOnlyAbandonedStaging() {
        val root = Files.createTempDirectory("incoming-share-staging-expiry").toFile()
        try {
            val abandoned = root.resolve("abandoned").apply { mkdirs() }
            val marker = createIncomingShareStagingMarker(abandoned, ".staging")
            marker.setLastModified(1_000L)
            assertFalse(
                removeExpiredAbandonedIncomingShareStagingDirectory(
                    abandoned,
                    ".staging",
                    retentionMillis = 1_000L,
                    nowMillis = 1_999L,
                ),
            )
            assertTrue(
                removeExpiredAbandonedIncomingShareStagingDirectory(
                    abandoned,
                    ".staging",
                    retentionMillis = 1_000L,
                    nowMillis = 2_000L,
                ),
            )

            val durable = root.resolve("durable").apply { mkdirs() }
            createIncomingShareStagingMarker(durable, ".staging").setLastModified(1_000L)
            durable.resolve("request.json").writeText("{}")
            assertFalse(
                removeExpiredAbandonedIncomingShareStagingDirectory(
                    durable,
                    ".staging",
                    retentionMillis = 1_000L,
                    nowMillis = 3_000L,
                ),
            )
            assertTrue(durable.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
