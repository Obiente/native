package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationCandidate
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationResult
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileSyncContentEvidenceTest {
    @Test
    fun unreadableCandidatesDoNotStarveLaterVerificationResults() {
        val candidates = listOf(
            FileSyncContentVerificationCandidate("a", "local-a", "remote-a", 40L),
            FileSyncContentVerificationCandidate("b", "local-b", "remote-b", 40L),
            FileSyncContentVerificationCandidate("c", "local-c", "remote-c", 40L),
        )

        val results = verifyAndroidFileSyncCandidates(candidates, maximumResults = 1) { candidate ->
            candidate.takeIf { it.relativePath == "c" }?.let {
                FileSyncContentVerificationResult(it, "sha256:${"0".repeat(64)}", null)
            }
        }

        assertEquals(listOf("c"), results.map { it.candidate.relativePath })
    }

    @Test
    fun identityReadBudgetIsSharedAndBounded() {
        val budget = AndroidFileSyncContentReadBudget(
            maximumFileBytes = 64L,
            maximumTotalBytes = 80L,
        )

        assertTrue(budget.reserve(40L))
        assertFalse(budget.reserve(70L))
        budget.refund(40L)
        assertEquals(80L, budget.remainingBytes)
        assertTrue(budget.reserve(40L))
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
            direction = FileSyncDirection.Bidirectional,
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
            direction = FileSyncDirection.Bidirectional,
            local = UnreadableLocalTree,
            budget = AndroidFileSyncContentReadBudget(),
        ).single()

        assertTrue(verified.contentIdentityUnverified)
        assertEquals(null, verified.contentHash)
    }

    @Test
    fun zeroByteReadFailureDoesNotStarveLaterDeletionEvidence() {
        val localEntries = listOf("a.txt", "b.txt").map { path ->
            LocalSyncEntry(path, SyncEntryKind.File, "local-$path", size = 4L)
        }
        val baselines = localEntries.map { entry ->
            FileSyncBaseline(
                entry.relativePath,
                SyncEntryKind.File,
                entry.revision,
                "remote-${entry.relativePath}",
                contentHash = "sha256:${"0".repeat(64)}",
            )
        }
        val budget = AndroidFileSyncContentReadBudget(maximumFileBytes = 4L, maximumTotalBytes = 4L)

        val verified = verifyAndroidRemoteDeletionContent(
            localEntries = localEntries,
            remoteEntries = emptyList(),
            baselines = baselines,
            direction = FileSyncDirection.Bidirectional,
            local = ZeroReadThenReadableLocalTree,
            budget = budget,
        )

        assertTrue(verified[0].contentIdentityUnverified)
        assertEquals("sha256:${"1".repeat(64)}", verified[1].contentHash)
        assertEquals(0L, budget.remainingBytes)
    }

    @Test
    fun uploadOnlyRemoteDeletionNeverReadsLocalContentEvidence() {
        val localEntry = LocalSyncEntry(
            relativePath = "camera.jpg",
            kind = SyncEntryKind.File,
            revision = "local-1",
            size = 32L,
        )

        val entries = verifyAndroidRemoteDeletionContent(
            localEntries = listOf(localEntry),
            remoteEntries = emptyList(),
            baselines = listOf(
                FileSyncBaseline(
                    "camera.jpg",
                    SyncEntryKind.File,
                    "local-1",
                    "remote-1",
                    contentHash = "sha256:${"0".repeat(64)}",
                ),
            ),
            direction = FileSyncDirection.UploadOnly,
            local = NoReadLocalTree,
            budget = AndroidFileSyncContentReadBudget(),
        )

        assertEquals(listOf(localEntry), entries)
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

        override fun contentHashRead(
            path: String,
            expectedLocalRevision: String,
            expectedBytes: Long,
            maximumBytes: Long,
        ): AndroidFileSyncContentHashRead = AndroidFileSyncContentHashRead(null, expectedBytes)
    }

    private object ZeroReadThenReadableLocalTree : AndroidFileSyncLocalTree by NoReadLocalTree {
        override fun contentHashRead(
            path: String,
            expectedLocalRevision: String,
            expectedBytes: Long,
            maximumBytes: Long,
        ): AndroidFileSyncContentHashRead = if (path == "a.txt") {
            AndroidFileSyncContentHashRead(null, 0L)
        } else {
            AndroidFileSyncContentHashRead("sha256:${"1".repeat(64)}", expectedBytes)
        }
    }
}
