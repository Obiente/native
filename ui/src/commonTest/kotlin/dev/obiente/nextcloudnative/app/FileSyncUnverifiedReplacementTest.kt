package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSyncUnverifiedReplacementTest {
    @Test
    fun `replacement authentication survives coordinator persistence`() {
        val authentication = "saf-tree-sha256:${"a".repeat(64)}"
        val local = LocalSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.Directory,
            revision = "saf-directory-1",
            replacementAuthentication = authentication,
        )
        val remote = RemoteSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.File,
            etag = "remote-1",
            size = 1L,
        )
        val scanned = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(local),
            listOf(remote),
            nowEpochMillis = 1L,
        )

        val restored = decodeFileSyncCoordinatorSnapshot(encodeFileSyncCoordinatorSnapshot(scanned))

        assertEquals(authentication, restored.pair().workItems.single().observedLocal?.replacementAuthentication)
        assertEquals("saf-directory-1", restored.pair().workItems.single().observedLocal?.revision)

        val file = local.copy(
            relativePath = "Archive.bin",
            kind = SyncEntryKind.File,
            revision = "saf-file-1",
            size = 4L,
            replacementAuthentication = "sha256:${"b".repeat(64)}",
        )
        val fileRemote = remote.copy(relativePath = "Archive.bin")
        val fileRestored = decodeFileSyncCoordinatorSnapshot(
            encodeFileSyncCoordinatorSnapshot(
                scanFileSyncPair(state(), PAIR_ID, listOf(file), listOf(fileRemote), nowEpochMillis = 2L),
            ),
        )
        assertEquals(
            file.replacementAuthentication,
            fileRestored.pair().workItems.single().observedLocal?.replacementAuthentication,
        )
    }

    @Test
    fun `changed replacement authentication requires renewed review`() {
        val firstAuthentication = "sha256:" + "ab".repeat(32)
        val changedAuthentication = "sha256:" + "cd".repeat(32)
        val local = LocalSyncEntry(
            relativePath = "note.md",
            kind = SyncEntryKind.File,
            revision = "weak-local-revision",
            size = 42L,
            replacementAuthentication = firstAuthentication,
        )
        val remote = RemoteSyncEntry("note.md", SyncEntryKind.File, "remote-1", size = 43L)
        var state = scanFileSyncPair(state(), PAIR_ID, listOf(local), listOf(remote), 10L)
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.UseRemote)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local.copy(replacementAuthentication = changedAuthentication)),
            listOf(remote),
            20L,
        )

        val replacement = state.pair().workItems.single()
        assertTrue(replacement.id > workId)
        assertEquals(FileSyncExecutionState.AwaitingDecision, replacement.state)
        assertEquals(FileSyncDecisionState.Pending, replacement.decision?.state)
        assertEquals(changedAuthentication, replacement.observedLocal?.replacementAuthentication)
    }

    @Test
    fun `unverified replacement offers only choices that preserve the device copy`() {
        val local = LocalSyncEntry(
            relativePath = "large.bin",
            kind = SyncEntryKind.File,
            revision = "local-1",
            size = 65L * 1024L * 1024L,
            contentIdentityUnverified = true,
            replacementContentIdentityUnavailable = true,
        )
        val remote = RemoteSyncEntry(
            relativePath = "large.bin",
            kind = SyncEntryKind.File,
            etag = "remote-1",
            size = 66L * 1024L * 1024L,
        )

        val bidirectional = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(local),
            listOf(remote),
            nowEpochMillis = 1L,
        )
        val pending = bidirectional.pair().workItems.single()
        assertEquals(FileSyncDecisionReason.UnverifiedLocalContent, pending.decision?.reason)
        assertEquals(
            setOf(FileSyncDecisionChoice.UseLocal, FileSyncDecisionChoice.Skip),
            pending.decision?.choices,
        )
        val restored = decodeFileSyncCoordinatorSnapshot(encodeFileSyncCoordinatorSnapshot(bidirectional))
        assertTrue(restored.pair().workItems.single().observedLocal?.replacementContentIdentityUnavailable == true)
        assertEquals(pending.decision, restored.pair().workItems.single().decision)
        val resolved = resolveFileSyncDecision(
            bidirectional,
            PAIR_ID,
            pending.id,
            FileSyncDecisionChoice.UseLocal,
        )
        assertIs<FileSyncOperation.Upload>(resolved.pair().workItems.single().operation)

        val downloadOnly = scanFileSyncPair(
            state(FileSyncConfiguration(direction = FileSyncDirection.DownloadOnly, deviceLabel = "Test device")),
            PAIR_ID,
            listOf(local),
            listOf(remote),
            nowEpochMillis = 1L,
        )
        assertEquals(
            setOf(FileSyncDecisionChoice.Skip),
            downloadOnly.pair().workItems.single().decision?.choices,
        )
    }

    @Test
    fun `unverified directory replacement offers no destructive device choice`() {
        val local = LocalSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.Directory,
            revision = "weak-directory-revision",
            replacementContentIdentityUnavailable = true,
        )
        val remote = RemoteSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.File,
            etag = "remote-1",
            size = 4L,
        )

        val bidirectional = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(local),
            listOf(remote),
            nowEpochMillis = 1L,
        )
        assertEquals(
            setOf(FileSyncDecisionChoice.UseLocal, FileSyncDecisionChoice.Skip),
            bidirectional.pair().workItems.single().decision?.choices,
        )

        val downloadOnly = scanFileSyncPair(
            state(FileSyncConfiguration(direction = FileSyncDirection.DownloadOnly, deviceLabel = "Test device")),
            PAIR_ID,
            listOf(local),
            listOf(remote),
            nowEpochMillis = 1L,
        )
        assertEquals(
            setOf(FileSyncDecisionChoice.Skip),
            downloadOnly.pair().workItems.single().decision?.choices,
        )
    }

    private fun state(
        configuration: FileSyncConfiguration = FileSyncConfiguration(deviceLabel = "Test device"),
    ) = FileSyncCoordinatorState(
        pairs = listOf(
            FileSyncPair(
                id = PAIR_ID,
                accountId = "account-a",
                localRootId = "android-tree:primary-notes",
                remoteRootPath = "Notes",
                configuration = configuration,
            ),
        ),
    )

    private fun FileSyncCoordinatorState.pair(): FileSyncPair = pairs.single()

    private companion object {
        const val PAIR_ID = "notes"
    }
}
