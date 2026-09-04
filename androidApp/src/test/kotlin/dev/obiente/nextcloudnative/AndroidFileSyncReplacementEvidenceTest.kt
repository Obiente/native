package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncDecisionReason
import dev.obiente.nextcloudnative.app.FileSyncDeletionPolicy
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.planFileSync
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `unreadable replacement content becomes unavailable evidence`() {
        val budget = AndroidFileSyncContentReadBudget(maximumFileBytes = 8L, maximumTotalBytes = 8L)

        val hash = readAndroidSafReplacementContentWithinBudget("offline.bin", 4L, budget) {
            throw IOException("provider item is offline")
        }

        assertNull(hash)
        assertEquals(4L, budget.remainingBytes)
    }

    @Test
    fun `replacement content read still propagates cancellation`() {
        val budget = AndroidFileSyncContentReadBudget(maximumFileBytes = 8L, maximumTotalBytes = 8L)

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            readAndroidSafReplacementContentWithinBudget("cancelled.bin", 4L, budget) {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `replacement hash stops after the first byte beyond the reserved size`() {
        val input = ByteArrayInputStream(ByteArray(1_024) { 1 })

        assertFailsWith<IllegalArgumentException> {
            hashAndroidSafReplacementContent(input, expectedBytes = 4L, shouldContinue = { true })
        }

        assertEquals(1_019, input.available())
    }

    @Test
    fun `oversized folder evidence does not start an incomplete content read`() {
        val budget = AndroidFileSyncContentReadBudget(maximumFileBytes = 8L, maximumTotalBytes = 8L)

        val reserved = budget.reserveCompleteReplacementContent(listOf(4L, 4L, 4L))

        assertFalse(reserved)
        assertEquals(8L, budget.remainingBytes)
    }

    @Test
    fun `directory without complete evidence becomes an explicit preservation decision`() {
        val local = LocalSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.Directory,
            revision = "weak-directory-revision",
        ).withUnavailableAndroidSafReplacementIdentity()
        val remote = RemoteSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.File,
            etag = "remote-1",
            size = 4L,
        )

        val operation = planFileSync(
            localEntries = listOf(local),
            remoteEntries = listOf(remote),
            baselines = emptyList(),
            configuration = FileSyncConfiguration(deviceLabel = "Test device"),
        ).operations.single()

        assertTrue(local.replacementContentIdentityUnavailable)
        assertEquals(
            FileSyncDecisionReason.UnverifiedLocalContent,
            (operation as FileSyncOperation.NeedsDecision).reason,
        )
    }

    @Test
    fun `partial sync view disables destructive directory evidence`() {
        val directory = LocalSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.Directory,
            revision = "weak-directory-revision",
        )
        val file = LocalSyncEntry(
            relativePath = "Archive/visible.bin",
            kind = SyncEntryKind.File,
            revision = "file-revision",
            size = 1L,
        )

        assertEquals(
            setOf("Archive"),
            unavailableAndroidSafDirectoryReplacementPaths(
                localEntries = listOf(directory, file),
                protectedPaths = setOf("Archive", "Archive/visible.bin"),
                configuration = FileSyncConfiguration(
                    deviceLabel = "Test device",
                    ignoredPatterns = listOf("Archive/private/**"),
                ),
            ),
        )
    }

    @Test
    fun `replacement content identity survives a recovery rename and detects an edit`() {
        val before = listOf(
            evidence("Archive", SyncEntryKind.Directory, contentHash = null),
            evidence("Archive/item.bin", SyncEntryKind.File, contentHash = hash('0')),
        )
        val renamed = listOf(
            evidence("recovery-name", SyncEntryKind.Directory, contentHash = null),
            evidence("recovery-name/item.bin", SyncEntryKind.File, contentHash = hash('0')),
        )
        val edited = renamed.map { item ->
            if (item.entry.kind == SyncEntryKind.File) item.copy(contentHash = hash('1')) else item
        }

        assertEquals(
            androidSafReplacementContentIdentity(before),
            androidSafReplacementContentIdentity(renamed),
        )
        assertNotEquals(
            androidSafReplacementContentIdentity(before),
            androidSafReplacementContentIdentity(edited),
        )
    }

    @Test
    fun `directory authentication preserves its provider revision for remote deletion planning`() {
        val folder = evidence("Archive", SyncEntryKind.Directory, contentHash = null).copy(
            entry = LocalSyncEntry(
                relativePath = "Archive",
                kind = SyncEntryKind.Directory,
                revision = "saf-directory-1",
            ),
        )
        val child = evidence("Archive/item.bin", SyncEntryKind.File, contentHash = hash('0'))
        val strengthened = folder.entry.withAndroidSafReplacementAuthentication(listOf(folder, child))
        val baseline = FileSyncBaseline(
            relativePath = "Archive",
            kind = SyncEntryKind.Directory,
            localRevision = "saf-directory-1",
            remoteEtag = "remote-directory-1",
        )

        val operation = planFileSync(
            localEntries = listOf(strengthened),
            remoteEntries = emptyList(),
            baselines = listOf(baseline),
            configuration = FileSyncConfiguration(
                deviceLabel = "Test device",
                deletionPolicy = FileSyncDeletionPolicy.Propagate,
            ),
        ).operations.single()

        assertEquals("saf-directory-1", strengthened.revision)
        assertTrue(strengthened.replacementAuthentication?.startsWith("saf-tree-sha256:") == true)
        assertEquals(
            FileSyncOperation.DeleteLocal("Archive", expectedLocalRevision = "saf-directory-1"),
            operation,
        )
        requireExpectedAndroidSafReplacement(strengthened, listOf(folder, child))
        assertFailsWith<IllegalArgumentException> {
            requireExpectedAndroidSafReplacement(
                strengthened,
                listOf(folder, child.copy(contentHash = hash('1'))),
            )
        }
    }

    @Test
    fun `directory listing observes cancellation before and after provider work`() {
        var listingCalls = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            listAndroidSafReplacementChildrenAfterCancellationCheck(
                shouldContinue = { false },
                listChildren = {
                    listingCalls += 1
                    emptyList<Int>()
                },
            )
        }
        assertEquals(0, listingCalls)

        var checks = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            listAndroidSafReplacementChildrenAfterCancellationCheck(
                shouldContinue = { ++checks == 1 },
                listChildren = {
                    listingCalls += 1
                    emptyList<Int>()
                },
            )
        }
        assertEquals(1, listingCalls)
        assertEquals(2, checks)
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
