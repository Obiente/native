package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSyncContentIdentityTest {
    @Test
    fun `first sync and changed generations require exact content verification`() {
        val local = listOf(fileOnDevice("Notes/a.md", "local-2", 42L))
        val remote = listOf(fileOnServer("Notes/a.md", "remote-2", 42L))

        val firstSync = fileSyncContentVerificationCandidates(local, remote, emptyList())
        val changed = fileSyncContentVerificationCandidates(
            local,
            remote,
            listOf(FileSyncBaseline("Notes/a.md", SyncEntryKind.File, "local-1", "remote-1")),
        )

        assertEquals(firstSync, changed)
        assertEquals(
            FileSyncContentVerificationCandidate("Notes/a.md", "local-2", "remote-2", 42L),
            firstSync.single(),
        )
    }

    @Test
    fun `unchanged baselines and different known sizes do not read content`() {
        val unchangedLocal = fileOnDevice("Notes/a.md", "local-1", 42L)
        val unchangedRemote = fileOnServer("Notes/a.md", "remote-1", 42L)
        val digest = "sha256:" + "01".repeat(32)

        assertEquals(
            emptyList(),
            fileSyncContentVerificationCandidates(
                listOf(unchangedLocal),
                listOf(unchangedRemote),
                listOf(FileSyncBaseline("Notes/a.md", SyncEntryKind.File, "local-1", "remote-1")),
            ),
        )
        assertEquals(
            listOf(FileSyncContentVerificationCandidate("Notes/a.md", "local-1", "remote-1", 42L)),
            fileSyncContentVerificationCandidates(
                listOf(unchangedLocal),
                listOf(unchangedRemote),
                listOf(FileSyncBaseline("Notes/a.md", SyncEntryKind.File, "local-1", "remote-1")),
                requireContentBackedBaseline = true,
            ),
        )
        assertEquals(
            emptyList(),
            fileSyncContentVerificationCandidates(
                listOf(unchangedLocal),
                listOf(unchangedRemote),
                listOf(FileSyncBaseline("Notes/a.md", SyncEntryKind.File, "local-1", "remote-1", digest)),
                requireContentBackedBaseline = true,
            ),
        )
        assertEquals(
            emptyList(),
            fileSyncContentVerificationCandidates(
                listOf(LocalSyncEntry("Notes/unknown.md", SyncEntryKind.File, "local", size = null)),
                listOf(fileOnServer("Notes/unknown.md", "remote", 42L)),
                emptyList(),
            ),
        )
        assertEquals(
            emptyList(),
            fileSyncContentVerificationCandidates(
                listOf(unchangedLocal),
                listOf(unchangedRemote.copy(size = 43L)),
                emptyList(),
            ),
        )
    }

    @Test
    fun `only generation guarded verification reaches planning`() {
        val digest = "sha256:" + "ab".repeat(32)
        val local = fileOnDevice("Notes/a.md", "local-2", 42L, digest)
        val remoteHint = fileOnServer("Notes/a.md", "remote-2", 42L, digest)
        val candidate = fileSyncContentVerificationCandidates(
            listOf(local),
            listOf(remoteHint),
            emptyList(),
        ).single()

        val unverified = applyVerifiedFileSyncContent(listOf(local), listOf(remoteHint), emptyList())
        assertEquals(null, unverified.localEntries.single().contentHash)
        assertEquals(null, unverified.remoteEntries.single().contentHash)

        val verified = applyVerifiedFileSyncContent(
            listOf(local),
            listOf(remoteHint),
            listOf(VerifiedFileSyncContent(candidate, digest)),
        )
        assertEquals(digest, verified.localEntries.single().contentHash)
        assertEquals(digest, verified.remoteEntries.single().contentHash)
        assertEquals(
            emptyList(),
            planFileSync(verified.localEntries, verified.remoteEntries, emptyList(), CONFIGURATION).operations,
        )
    }

    @Test
    fun `verified local mismatch reaches planning without trusting the remote checksum hint`() {
        val localDigest = "sha256:" + "ab".repeat(32)
        val remoteHint = "sha256:" + "cd".repeat(32)
        val local = fileOnDevice("Notes/a.md", "local-1", 42L)
        val remote = fileOnServer("Notes/a.md", "remote-2", 42L, remoteHint)
        val candidate = FileSyncContentVerificationCandidate("Notes/a.md", "local-1", "remote-2", 42L)

        val verified = applyFileSyncContentVerificationResults(
            listOf(local),
            listOf(remote),
            listOf(FileSyncContentVerificationResult(candidate, localDigest, null)),
        )

        assertEquals(localDigest, verified.localEntries.single().contentHash)
        assertEquals(null, verified.remoteEntries.single().contentHash)
    }

    @Test
    fun `verification cannot be applied to a different scanned generation`() {
        val digest = "sha256:" + "cd".repeat(32)
        val candidate = FileSyncContentVerificationCandidate("Notes/a.md", "local-1", "remote-1", 42L)

        assertFailsWith<IllegalArgumentException> {
            applyVerifiedFileSyncContent(
                listOf(fileOnDevice("Notes/a.md", "local-2", 42L)),
                listOf(fileOnServer("Notes/a.md", "remote-1", 42L)),
                listOf(VerifiedFileSyncContent(candidate, digest)),
            )
        }
    }

    @Test
    fun `automatic verification is bounded per file and per scan`() {
        val candidates = listOf(
            FileSyncContentVerificationCandidate("a", "local-a", "remote-a", 40L),
            FileSyncContentVerificationCandidate("b", "local-b", "remote-b", 70L),
            FileSyncContentVerificationCandidate("c", "local-c", "remote-c", 40L),
            FileSyncContentVerificationCandidate("d", "local-d", "remote-d", null),
        )

        assertEquals(
            listOf(candidates[0], candidates[2]),
            candidates.withinFileSyncContentVerificationBudget(
                maximumFileBytes = 64L,
                maximumTotalBytes = 80L,
            ),
        )
        assertEquals(
            listOf(FileSyncContentVerificationCandidate("empty", "local", "remote", 0L)),
            listOf(FileSyncContentVerificationCandidate("empty", "local", "remote", 0L))
                .withinFileSyncContentVerificationBudget(maximumFileBytes = 0L, maximumTotalBytes = 0L),
        )
        assertEquals(
            listOf(candidates[0]),
            candidates.withinFileSyncContentVerificationBudget(
                maximumFileBytes = Long.MAX_VALUE,
                maximumTotalBytes = Long.MAX_VALUE,
                maximumCandidates = 1,
            ),
        )
        assertEquals(emptyList(), candidates.withinFileSyncContentVerificationBudget(maximumCandidates = 0))
    }

    private fun fileOnDevice(path: String, revision: String, size: Long, hash: String? = null) =
        LocalSyncEntry(path, SyncEntryKind.File, revision, size, hash)

    private fun fileOnServer(path: String, etag: String, size: Long, hash: String? = null) =
        RemoteSyncEntry(path, SyncEntryKind.File, etag, size, hash)

    private companion object {
        val CONFIGURATION = FileSyncConfiguration(deviceLabel = "Test device")
    }
}
