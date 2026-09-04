package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncDecisionReason
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.planFileSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AndroidFileSyncReplacementEvidenceTest {
    @Test
    fun `deletion revalidation detects a descendant edit after the authenticated snapshot`() {
        val folder = evidence("Archive", SyncEntryKind.Directory, contentHash = null)
        val child = evidence("Archive/item.bin", SyncEntryKind.File, contentHash = hash('0'))
        val authenticated = listOf(folder, child)

        requireUnchangedAndroidSafReplacement(authenticated, authenticated)

        assertFailsWith<IllegalArgumentException> {
            requireUnchangedAndroidSafReplacement(
                authenticated,
                listOf(folder, child.copy(contentHash = hash('1'))),
            )
        }
    }

    @Test
    fun `keep both receives scan time replacement content identity`() {
        val protected = androidFileSyncProtectedReplacementPaths(
            operations = listOf(
                FileSyncOperation.KeepBoth(
                    relativePath = "Archive.bin",
                    localConflictPath = "Archive (local).bin",
                    remoteConflictPath = "Archive (server).bin",
                ),
            ),
            localPaths = setOf("Archive.bin"),
        )

        assertEquals(setOf("Archive.bin"), protected)
    }

    @Test
    fun `replacement evidence shares the bounded scan content budget`() {
        val reads = mutableListOf<String>()
        val budget = AndroidFileSyncContentReadBudget(maximumFileBytes = 8L, maximumTotalBytes = 8L)

        val hashes = listOf("a.bin", "b.bin", "c.bin").map { path ->
            readAndroidSafReplacementContentWithinBudget(path, 4L, budget) {
                reads += it
                hash(it.first())
            }
        }

        assertEquals(listOf("a.bin", "b.bin"), reads)
        assertEquals(0L, budget.remainingBytes)
        assertEquals(listOf(hash('a'), hash('b'), null), hashes)
    }

    @Test
    fun `oversized folder evidence does not start an incomplete content read`() {
        val budget = AndroidFileSyncContentReadBudget(maximumFileBytes = 8L, maximumTotalBytes = 8L)

        val reserved = budget.reserveCompleteReplacementContent(listOf(4L, 4L, 4L))

        assertFalse(reserved)
        assertEquals(8L, budget.remainingBytes)
    }

    @Test
    fun `oversized replacement becomes an explicit preservation decision`() {
        val budget = AndroidFileSyncContentReadBudget(maximumFileBytes = Long.MAX_VALUE, maximumTotalBytes = 64L)
        val contentHash = readAndroidSafReplacementContentWithinBudget("Archive.bin", 65L, budget) {
            error("An oversized replacement must not be read during the bounded scan")
        }
        assertNull(contentHash)
        assertEquals(64L, budget.remainingBytes)
        val local = LocalSyncEntry(
            relativePath = "Archive.bin",
            kind = SyncEntryKind.File,
            revision = "local-1",
            size = 65L * 1024L * 1024L,
        ).withAndroidSafReplacementContentHash(contentHash)
        val remote = RemoteSyncEntry(
            relativePath = "Archive.bin",
            kind = SyncEntryKind.File,
            etag = "remote-1",
            size = 66L * 1024L * 1024L,
        )

        val operation = planFileSync(
            localEntries = listOf(local),
            remoteEntries = listOf(remote),
            baselines = emptyList(),
            configuration = FileSyncConfiguration(deviceLabel = "Test device"),
        ).operations.single()

        assertEquals(
            FileSyncDecisionReason.UnverifiedLocalContent,
            (operation as FileSyncOperation.NeedsDecision).reason,
        )
    }

    private fun evidence(
        path: String,
        kind: SyncEntryKind,
        contentHash: String?,
    ): AndroidSafReplacementEvidence = AndroidSafReplacementEvidence(
        entry = LocalSyncEntry(
            relativePath = path,
            kind = kind,
            revision = "revision-$path",
            size = if (kind == SyncEntryKind.File) 1L else null,
        ),
        documentIdentity = "content://provider/$path",
        displayName = path.substringAfterLast('/'),
        contentHash = contentHash,
    )

    private fun hash(character: Char): String = "sha256:${character.toString().repeat(64)}"
}
