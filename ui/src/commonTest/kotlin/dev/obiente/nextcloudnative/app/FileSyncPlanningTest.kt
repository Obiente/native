package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FileSyncPlanningTest {
    private val config = FileSyncConfiguration(deviceLabel = "Test phone")

    @Test
    fun unchangedEntriesProduceNoWork() {
        val plan = planFileSync(
            localEntries = listOf(local("notes/today.md", "local-1")),
            remoteEntries = listOf(remote("notes/today.md", "remote-1")),
            baselines = listOf(baseline("notes/today.md", "local-1", "remote-1")),
            configuration = config,
        )

        assertEquals(emptyList(), plan.operations)
    }

    @Test
    fun oneSidedChangesUseGuardedUploadAndDownload() {
        val localChange = planFileSync(
            listOf(local("vault/a.md", "local-2")),
            listOf(remote("vault/a.md", "remote-1")),
            listOf(baseline("vault/a.md", "local-1", "remote-1")),
            config,
        ).operations.single()
        assertEquals("remote-1", assertIs<FileSyncOperation.Upload>(localChange).expectedRemoteEtag)

        val remoteChange = planFileSync(
            listOf(local("vault/a.md", "local-1")),
            listOf(remote("vault/a.md", "remote-2")),
            listOf(baseline("vault/a.md", "local-1", "remote-1")),
            config,
        ).operations.single()
        assertEquals("local-1", assertIs<FileSyncOperation.Download>(remoteChange).expectedLocalRevision)
    }

    @Test
    fun simultaneousEditsNeverGuessByDefault() {
        val operation = planFileSync(
            listOf(local("vault/a.md", "local-2")),
            listOf(remote("vault/a.md", "remote-2")),
            listOf(baseline("vault/a.md", "local-1", "remote-1")),
            config,
        ).operations.single()

        assertEquals(
            FileSyncDecisionReason.SimultaneousEdit,
            assertIs<FileSyncOperation.NeedsDecision>(operation).reason,
        )
    }

    @Test
    fun firstSyncCollisionIsExplicitEvenWhenNamesAndSizesMatch() {
        val operation = planFileSync(
            listOf(local("vault/a.md", "local", size = 42)),
            listOf(remote("vault/a.md", "remote", size = 42)),
            emptyList(),
            config,
        ).operations.single()

        assertEquals(
            FileSyncDecisionReason.FirstSyncCollision,
            assertIs<FileSyncOperation.NeedsDecision>(operation).reason,
        )
    }

    @Test
    fun identicalStrongContentHashesNeedNoConflictOrTransfer() {
        val digest = "sha256:" + "a1".repeat(32)
        val firstSync = planFileSync(
            listOf(local("vault/a.md", "local", size = 42, contentHash = digest)),
            listOf(remote("vault/a.md", "remote", size = 42, contentHash = digest)),
            emptyList(),
            config,
        )
        val simultaneousMetadataChange = planFileSync(
            listOf(local("vault/a.md", "local-2", contentHash = digest)),
            listOf(remote("vault/a.md", "remote-2", contentHash = digest)),
            listOf(baseline("vault/a.md", "local-1", "remote-1")),
            config,
        )

        assertEquals(emptyList(), firstSync.operations)
        assertEquals(emptyList(), simultaneousMetadataChange.operations)
        assertEquals(digest, normalizeSyncSha256("SHA-256:" + "A1".repeat(32)))
        assertEquals(null, normalizeSyncSha256("md5:" + "a1".repeat(16)))
    }

    @Test
    fun keepBothProducesStableSiblingNamesWithoutOverwritingEitherRevision() {
        val operation = planFileSync(
            listOf(local("vault/daily.note.md", "local-2")),
            listOf(remote("vault/daily.note.md", "remote-2")),
            listOf(baseline("vault/daily.note.md", "local-1", "remote-1")),
            config.copy(conflictPolicy = FileSyncConflictPolicy.KeepBoth),
        ).operations.single()

        val keepBoth = assertIs<FileSyncOperation.KeepBoth>(operation)
        assertEquals("vault/daily.note (conflict-test-phone-local).md", keepBoth.localConflictPath)
        assertEquals("vault/daily.note (conflict-server).md", keepBoth.remoteConflictPath)
    }

    @Test
    fun deletionsRequireAChoiceUntilPropagationIsExplicitlyEnabled() {
        val ask = planFileSync(
            emptyList(),
            listOf(remote("vault/a.md", "remote-1")),
            listOf(baseline("vault/a.md", "local-1", "remote-1")),
            config,
        ).operations.single()
        assertEquals(FileSyncDecisionReason.LocalDeletion, assertIs<FileSyncOperation.NeedsDecision>(ask).reason)

        val propagate = planFileSync(
            emptyList(),
            listOf(remote("vault/a.md", "remote-1")),
            listOf(baseline("vault/a.md", "local-1", "remote-1")),
            config.copy(deletionPolicy = FileSyncDeletionPolicy.Propagate),
        ).operations.single()
        assertEquals("remote-1", assertIs<FileSyncOperation.DeleteRemote>(propagate).expectedRemoteEtag)
    }

    @Test
    fun directoryDeletionsUseTheSameExplicitPolicyAsFiles() {
        val operation = planFileSync(
            localEntries = emptyList(),
            remoteEntries = listOf(
                RemoteSyncEntry("vault/archive", SyncEntryKind.Directory, "remote-dir"),
            ),
            baselines = listOf(
                FileSyncBaseline("vault/archive", SyncEntryKind.Directory, "local-dir", "remote-dir"),
            ),
            configuration = config.copy(deletionPolicy = FileSyncDeletionPolicy.Propagate),
        ).operations.single()

        assertEquals("remote-dir", assertIs<FileSyncOperation.DeleteRemote>(operation).expectedRemoteEtag)
    }

    @Test
    fun unsafeOrDuplicatePathsAreRejectedBeforePlanning() {
        assertFailsWith<IllegalArgumentException> { local("../secret", "1") }
        assertFailsWith<IllegalArgumentException> {
            planFileSync(
                listOf(local("vault/a.md", "1"), local("vault/a.md", "2")),
                emptyList(),
                emptyList(),
                config,
            )
        }
    }

    private fun local(
        path: String,
        revision: String,
        size: Long? = null,
        contentHash: String? = null,
    ) = LocalSyncEntry(path, SyncEntryKind.File, revision, size, contentHash)

    private fun remote(
        path: String,
        etag: String,
        size: Long? = null,
        contentHash: String? = null,
    ) = RemoteSyncEntry(path, SyncEntryKind.File, etag, size, contentHash)

    private fun baseline(path: String, localRevision: String?, remoteEtag: String?) =
        FileSyncBaseline(path, SyncEntryKind.File, localRevision, remoteEtag)
}
