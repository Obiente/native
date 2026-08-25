package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileSyncContentEvidenceTest {
    @Test
    fun identityReadBudgetIsSharedAndBounded() {
        val budget = AndroidFileSyncContentReadBudget(
            maximumFileBytes = 64L,
            maximumTotalBytes = 80L,
        )

        assertTrue(budget.reserve(40L))
        assertFalse(budget.reserve(70L))
        assertTrue(budget.reserve(40L))
        assertFalse(budget.reserve(1L))
        assertTrue(budget.reserve(0L))
        assertFalse(budget.reserve(null))
        assertEquals(0L, budget.remainingBytes)
    }

    @Test
    fun hashlessBaselineCannotAuthorizeRemoteDeletion() {
        val localEntry = LocalSyncEntry(
            relativePath = "large.bin",
            kind = SyncEntryKind.File,
            revision = "local-1",
            size = 65L * 1024L * 1024L,
        )

        val verified = verifyAndroidRemoteDeletionContent(
            localEntries = listOf(localEntry),
            remoteEntries = emptyList(),
            baselines = listOf(
                FileSyncBaseline("large.bin", SyncEntryKind.File, "local-1", "remote-1", contentHash = null),
            ),
            local = NoReadLocalTree,
            budget = AndroidFileSyncContentReadBudget(),
        ).single()

        assertTrue(verified.contentIdentityUnverified)
        assertEquals(null, verified.contentHash)
    }

    @Test
    fun unreadableContentRetainsRemoteDeletionForReview() {
        val localEntry = LocalSyncEntry(
            relativePath = "note.txt",
            kind = SyncEntryKind.File,
            revision = "local-1",
            size = 4L,
        )

        val verified = verifyAndroidRemoteDeletionContent(
            localEntries = listOf(localEntry),
            remoteEntries = emptyList(),
            baselines = listOf(
                FileSyncBaseline(
                    "note.txt",
                    SyncEntryKind.File,
                    "local-1",
                    "remote-1",
                    contentHash = "sha256:${"0".repeat(64)}",
                ),
            ),
            local = UnreadableLocalTree,
            budget = AndroidFileSyncContentReadBudget(),
        ).single()

        assertTrue(verified.contentIdentityUnverified)
        assertEquals(null, verified.contentHash)
    }

    private object NoReadLocalTree : AndroidFileSyncLocalTree {
        override fun scan(
            includes: (relativePath: String, kind: SyncEntryKind) -> Boolean,
        ): List<AndroidLocalSyncDocument> = emptyList()

        override fun contentHash(
            path: String,
            expectedLocalRevision: String,
            expectedBytes: Long,
            maximumBytes: Long,
        ): String? = error("Hashless deletion evidence must not read content")

        override fun stageForUpload(path: String, destination: File, maximumBytes: Long): LocalSyncEntry =
            error("Not used")

        override fun createDirectory(path: String, expectedLocalRevision: String?) = error("Not used")

        override fun writeFile(path: String, source: File, expectedLocalRevision: String?) = error("Not used")

        override fun delete(path: String, expectedLocalRevision: String) = error("Not used")

        override fun resolve(path: String): AndroidLocalSyncDocument? = error("Not used")
    }

    private object UnreadableLocalTree : AndroidFileSyncLocalTree by NoReadLocalTree {
        override fun contentHash(
            path: String,
            expectedLocalRevision: String,
            expectedBytes: Long,
            maximumBytes: Long,
        ): String? = null
    }
}
