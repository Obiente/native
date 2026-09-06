package dev.obiente.nextcloudnative.app

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAccountMemoryRetirementTest {
    @Test
    fun `committed credential removal retires memory before journal commit can fail`() = runBlocking {
        val events = mutableListOf<String>()

        val removed = removeDesktopAccountBeforeSyncPairCleanup(
            accountId = "a".repeat(64),
            accountStorageKey = "b".repeat(64),
            prepareCleanup = { _, _, _, _ -> events += "prepare" },
            commitCleanup = { events += "commit"; throw IOException("disk full") },
            clearCleanup = { events += "clear" },
            accountOwnership = { DesktopAccountOwnership.Absent },
            removeCredential = { events += "credential"; true },
            removeSyncPairs = { events += "cleanup" },
            retireCommittedAccount = { events += "retire" },
            recordCleanupFailure = { events += "failure" },
        )

        assertTrue(removed)
        assertEquals(listOf("prepare", "credential", "retire", "commit", "failure"), events)
    }

    @Test
    fun `post-commit credential throw retires but confirmed presence does not`() = runBlocking {
        suspend fun attempt(ownership: DesktopAccountOwnership): Boolean {
            var retired = false
            runCatching {
                removeDesktopAccountBeforeSyncPairCleanup(
                    accountId = "c".repeat(64),
                    prepareCleanup = { _, _, _, _ -> },
                    commitCleanup = {},
                    clearCleanup = {},
                    accountOwnership = { ownership },
                    removeCredential = { throw IOException("credential result lost") },
                    removeSyncPairs = {},
                    retireCommittedAccount = { retired = true },
                    recordCleanupFailure = {},
                )
            }
            return retired
        }

        assertTrue(attempt(DesktopAccountOwnership.Absent))
        assertFalse(attempt(DesktopAccountOwnership.Present))
    }
}
