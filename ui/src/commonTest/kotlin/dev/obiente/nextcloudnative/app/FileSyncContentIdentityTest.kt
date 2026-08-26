package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
    fun `cached verification is discarded when either generation or size changes`() {
        val digest = "sha256:" + "cd".repeat(32)
        val results = listOf(
            verificationResult("current", "local-1", "remote-1", 42L, digest),
            verificationResult("local-changed", "local-1", "remote-1", 42L, digest),
            verificationResult("remote-changed", "local-1", "remote-1", 42L, digest),
            verificationResult("size-changed", "local-1", "remote-1", 42L, digest),
        )
        val local = listOf(
            fileOnDevice("current", "local-1", 42L),
            fileOnDevice("local-changed", "local-2", 42L),
            fileOnDevice("remote-changed", "local-1", 42L),
            fileOnDevice("size-changed", "local-1", 43L),
        )
        val remote = listOf(
            fileOnServer("current", "remote-1", 42L),
            fileOnServer("local-changed", "remote-1", 42L),
            fileOnServer("remote-changed", "remote-2", 42L),
            fileOnServer("size-changed", "remote-1", 43L),
        )

        assertEquals(
            listOf(results.first()),
            currentFileSyncContentVerificationResults(local, remote, results),
        )
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

        val nineGiB = FileSyncContentVerificationCandidate(
            "large-video.mkv",
            "local-large",
            "remote-large",
            9L * 1024L * 1024L * 1024L,
        )
        assertEquals(emptyList(), listOf(nineGiB).withinFileSyncContentVerificationBudget())
    }

    @Test
    fun `large identity checks advance in fair bounded slices without becoming conflicts`() {
        val large = FileSyncContentVerificationCandidate(
            "large-video.mkv",
            "local-large",
            "remote-large",
            9L * 1024L * 1024L * 1024L,
        )
        val small = FileSyncContentVerificationCandidate("notes.txt", "local-small", "remote-small", 12L)
        val progress = FileSyncContentVerificationProgress(
            large,
            verifiedBytes = 8L * 1024L * 1024L,
            aggregateHash = "sha256:" + "01".repeat(32),
        )

        val slices = planFileSyncContentVerificationSlices(
            candidates = listOf(large, small),
            progress = listOf(progress),
            maximumSliceBytes = 1024,
            maximumTotalBytes = 2048L,
            maximumSlices = 2,
        )

        assertEquals(listOf("notes.txt", "large-video.mkv"), slices.map { it.candidate.relativePath })
        assertEquals(listOf(0L, progress.verifiedBytes), slices.map(FileSyncContentVerificationSlice::offset))
        assertEquals(12, slices.first().length)

        val largeBytes = requireNotNull(large.expectedSizeBytes)
        val pending = markPendingFileSyncContentVerification(
            FileSyncContentIdentitySnapshot(
                localEntries = listOf(fileOnDevice(large.relativePath, large.localRevision, largeBytes)),
                remoteEntries = listOf(fileOnServer(large.relativePath, large.remoteEtag, largeBytes)),
            ),
            listOf(large),
        )
        val operation = planFileSync(pending.localEntries, pending.remoteEntries, emptyList(), CONFIGURATION)
            .operations.single()
        assertIs<FileSyncOperation.Skipped>(operation)
    }

    private fun fileOnDevice(path: String, revision: String, size: Long, hash: String? = null) =
        LocalSyncEntry(path, SyncEntryKind.File, revision, size, hash)

    private fun fileOnServer(path: String, etag: String, size: Long, hash: String? = null) =
        RemoteSyncEntry(path, SyncEntryKind.File, etag, size, hash)

    private fun verificationResult(
        path: String,
        localRevision: String,
        remoteEtag: String,
        size: Long,
        digest: String,
    ) = FileSyncContentVerificationResult(
        FileSyncContentVerificationCandidate(path, localRevision, remoteEtag, size),
        digest,
        matchingContentHash = null,
    )

    private companion object {
        val CONFIGURATION = FileSyncConfiguration(deviceLabel = "Test device")
    }
}
