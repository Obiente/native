package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncCoordinatorTest {
    @Test
    fun `conflict decision becomes guarded command and baseline advances only after verification`() {
        val baseline = baseline("Vault/today.md", "local-1", "remote-1")
        var state = state(baselines = listOf(baseline))

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("Vault/today.md", "local-2")),
            listOf(remote("Vault/today.md", "remote-2")),
            nowEpochMillis = 10,
        )
        val pending = state.pair().workItems.single()
        assertEquals(FileSyncExecutionState.AwaitingDecision, pending.state)
        assertEquals(FileSyncDecisionState.Pending, pending.decision?.state)
        assertTrue(FileSyncDecisionChoice.UseLocal in requireNotNull(pending.decision).choices)

        state = resolveFileSyncDecision(
            state,
            PAIR_ID,
            pending.id,
            FileSyncDecisionChoice.UseLocal,
        )
        val resolved = state.pair().workItems.single()
        assertEquals(
            FileSyncDecisionState.Resolved(FileSyncDecisionChoice.UseLocal),
            resolved.decision?.state,
        )
        assertEquals("remote-2", assertIs<FileSyncOperation.Upload>(resolved.operation).expectedRemoteEtag)
        assertEquals(listOf(baseline), state.pair().baselines)

        val claimed = claimNextFileSyncOperation(state, PAIR_ID, nowEpochMillis = 20)
        state = claimed.state
        assertIs<FileSyncOperation.Upload>(requireNotNull(claimed.command).operation)
        assertEquals(FileSyncExecutionState.Running, state.pair().workItems.single().state)
        assertEquals(listOf(baseline), state.pair().baselines)

        val verified = baseline("Vault/today.md", "local-2", "remote-3")
        state = completeFileSyncOperation(
            state,
            PAIR_ID,
            pending.id,
            FileSyncExecutionSuccess(synchronizedBaselines = listOf(verified)),
        )

        assertEquals(listOf(verified), state.pair().baselines)
        assertEquals(emptyList(), state.pair().workItems)
    }

    @Test
    fun `changed observations replace a stale resolved decision`() {
        var state = state(baselines = listOf(baseline("note.md", "l1", "r1")))
        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("note.md", "l2")),
            listOf(remote("note.md", "r2")),
            10,
        )
        val staleId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, staleId, FileSyncDecisionChoice.UseRemote)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("note.md", "l3")),
            listOf(remote("note.md", "r2")),
            20,
        )

        val replacement = state.pair().workItems.single()
        assertTrue(replacement.id > staleId)
        assertEquals(FileSyncExecutionState.AwaitingDecision, replacement.state)
        assertEquals(FileSyncDecisionState.Pending, replacement.decision?.state)
    }

    @Test
    fun `verified identical content establishes and refreshes baseline without work`() {
        val digest = "sha256:" + "0f".repeat(32)
        var state = state()
        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("Vault/today.md", "local-1", digest)),
            listOf(remote("Vault/today.md", "remote-1", digest)),
            10,
        )
        assertEquals(emptyList(), state.pair().workItems)
        assertEquals(
            listOf(baseline("Vault/today.md", "local-1", "remote-1")),
            state.pair().baselines,
        )

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("Vault/today.md", "local-2", digest)),
            listOf(remote("Vault/today.md", "remote-2", digest)),
            20,
        )
        assertEquals(emptyList(), state.pair().workItems)
        assertEquals(
            listOf(baseline("Vault/today.md", "local-2", "remote-2")),
            state.pair().baselines,
        )
    }

    @Test
    fun `deletion decisions failure and retry remain explicit`() {
        var state = state(baselines = listOf(baseline("removed.md", "l1", "r1")))
        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = listOf(remote("removed.md", "r1")),
            nowEpochMillis = 10,
        )
        val workId = state.pair().workItems.single().id
        val decision = requireNotNull(state.pair().workItems.single().decision)
        assertEquals(
            setOf(
                FileSyncDecisionChoice.PropagateDeletion,
                FileSyncDecisionChoice.RestoreMissing,
                FileSyncDecisionChoice.Skip,
            ),
            decision.choices,
        )

        state = resolveFileSyncDecision(
            state,
            PAIR_ID,
            workId,
            FileSyncDecisionChoice.PropagateDeletion,
        )
        val claim = claimNextFileSyncOperation(state, PAIR_ID, 20)
        assertEquals("r1", assertIs<FileSyncOperation.DeleteRemote>(claim.command?.operation).expectedRemoteEtag)
        state = failFileSyncOperation(claim.state, PAIR_ID, workId, "Network unavailable")
        assertEquals(FileSyncExecutionState.Failed, state.pair().workItems.single().state)
        assertNull(claimNextFileSyncOperation(state, PAIR_ID, 30).command)

        state = retryFileSyncOperation(state, PAIR_ID, workId)
        assertEquals(FileSyncExecutionState.Ready, state.pair().workItems.single().state)
        state = claimNextFileSyncOperation(state, PAIR_ID, 40).state
        state = completeFileSyncOperation(
            state,
            PAIR_ID,
            workId,
            FileSyncExecutionSuccess(removedRelativePaths = listOf("removed.md")),
        )
        assertEquals(emptyList(), state.pair().baselines)
    }

    @Test
    fun `selective directory deletion choice remains non destructive`() {
        var state = state(
            baselines = listOf(
                FileSyncBaseline("Photos", SyncEntryKind.Directory, "local-dir", "remote-dir"),
            ),
            configuration = FileSyncConfiguration(
                deviceLabel = "Workstation",
                selectedPaths = listOf("Photos/Shared"),
            ),
        )
        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = listOf(RemoteSyncEntry("Photos", SyncEntryKind.Directory, "remote-dir")),
            nowEpochMillis = 10,
        )
        val workId = state.pair().workItems.single().id

        state = resolveFileSyncDecision(
            state,
            PAIR_ID,
            workId,
            FileSyncDecisionChoice.PropagateDeletion,
        )

        assertIs<FileSyncOperation.Skipped>(state.pair().workItems.single().operation)
        assertNull(claimNextFileSyncOperation(state, PAIR_ID, 20).command)
    }

    @Test
    fun `keep both requires verified convergence for every generated path`() {
        var state = state(baselines = listOf(baseline("daily.note.md", "l1", "r1")))
        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("daily.note.md", "l2")),
            listOf(remote("daily.note.md", "r2")),
            10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.KeepBoth)
        val operation = assertIs<FileSyncOperation.KeepBoth>(state.pair().workItems.single().operation)
        state = claimNextFileSyncOperation(state, PAIR_ID, 20).state

        assertFailsWith<IllegalArgumentException> {
            completeFileSyncOperation(
                state,
                PAIR_ID,
                workId,
                FileSyncExecutionSuccess(
                    synchronizedBaselines = listOf(baseline("daily.note.md", "l3", "r3")),
                ),
            )
        }

        state = completeFileSyncOperation(
            state,
            PAIR_ID,
            workId,
            FileSyncExecutionSuccess(
                synchronizedBaselines = listOf(
                    baseline("daily.note.md", "l3", "r3"),
                    baseline(operation.localConflictPath, "ll", "lr"),
                    baseline(operation.remoteConflictPath, "rl", "rr"),
                ),
            ),
        )
        assertEquals(
            setOf("daily.note.md", operation.localConflictPath, operation.remoteConflictPath),
            state.pair().baselines.mapTo(linkedSetOf(), FileSyncBaseline::relativePath),
        )
    }

    @Test
    fun `running work blocks rescans and only one command can be claimed`() {
        var state = state()
        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("a.md", "a"), local("b.md", "b")),
            emptyList(),
            10,
        )
        val claim = claimNextFileSyncOperation(state, PAIR_ID, 20)
        assertEquals("a.md", claim.command?.operation?.relativePath)
        assertFailsWith<IllegalArgumentException> {
            claimNextFileSyncOperation(claim.state, PAIR_ID, 21)
        }
        assertFailsWith<IllegalArgumentException> {
            scanFileSyncPair(
                claim.state,
                PAIR_ID,
                listOf(local("a.md", "a"), local("b.md", "b")),
                emptyList(),
                30,
            )
        }
    }

    @Test
    fun `selective and ignored paths cannot become deletion work`() {
        val configuration = FileSyncConfiguration(
            deviceLabel = "Test phone",
            selectedPaths = listOf("Photos/Keep"),
            ignoredPatterns = listOf("*.tmp"),
        )
        var state = state(
            baselines = listOf(
                baseline("Photos/Keep/a.raf", "l1", "r1"),
                baseline("Photos/Other/b.raf", "l1", "r1"),
                baseline("Photos/Keep/incomplete.tmp", "l1", "r1"),
            ),
            configuration = configuration,
        )

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = listOf(local("Photos/Keep/a.raf", "l2")),
            remoteEntries = listOf(
                remote("Photos/Keep/a.raf", "r1"),
                remote("Photos/Other/b.raf", "r1"),
                remote("Photos/Keep/incomplete.tmp", "r1"),
            ),
            nowEpochMillis = 10,
        )

        assertEquals(listOf("Photos/Keep/a.raf"), state.pair().workItems.map { it.relativePath })
        assertEquals(
            setOf("Photos/Keep/a.raf", "Photos/Other/b.raf", "Photos/Keep/incomplete.tmp"),
            state.pair().baselines.mapTo(linkedSetOf(), FileSyncBaseline::relativePath),
        )
    }

    @Test
    fun `directories are created first then raw files outrank jpeg files across folders`() {
        val configuration = FileSyncConfiguration(
            deviceLabel = "Test phone",
            priorityRules = listOf(
                FileSyncPriorityRule("**/*.raf"),
                FileSyncPriorityRule("**/*.jpg"),
            ),
        )
        var state = state(configuration = configuration)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = listOf(
                LocalSyncEntry("Shoot", SyncEntryKind.Directory, "dir"),
                LocalSyncEntry("Other", SyncEntryKind.Directory, "other-dir"),
                local("Shoot/export.jpg", "jpg"),
                local("Other/negative.raf", "raf"),
                local("Shoot/sidecar.xmp", "xmp"),
            ),
            remoteEntries = emptyList(),
            nowEpochMillis = 10,
        )

        assertEquals(
            listOf("Other", "Shoot", "Other/negative.raf", "Shoot/export.jpg", "Shoot/sidecar.xmp"),
            state.pair().workItems.map(FileSyncWorkItem::relativePath),
        )
    }

    @Test
    fun `pair and failure fields are bounded before persistence`() {
        assertFailsWith<IllegalArgumentException> {
            FileSyncPair(
                id = "x".repeat(MAX_FILE_SYNC_ID_LENGTH + 1),
                accountId = "account-a",
                localRootId = "root",
                remoteRootPath = "Notes",
                configuration = FileSyncConfiguration(deviceLabel = "Phone"),
            )
        }

        var state = state()
        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("note.md", "l1")),
            emptyList(),
            10,
        )
        val claim = claimNextFileSyncOperation(state, PAIR_ID, 20)
        assertFailsWith<IllegalArgumentException> {
            failFileSyncOperation(
                claim.state,
                PAIR_ID,
                requireNotNull(claim.command).workId,
                "x".repeat(MAX_FILE_SYNC_FAILURE_LENGTH + 1),
            )
        }
    }

    private fun state(
        baselines: List<FileSyncBaseline> = emptyList(),
        configuration: FileSyncConfiguration = FileSyncConfiguration(deviceLabel = "Test phone"),
    ) = FileSyncCoordinatorState(
        pairs = listOf(
            FileSyncPair(
                id = PAIR_ID,
                accountId = "account-a",
                localRootId = "android-tree:primary-notes",
                remoteRootPath = "Notes",
                configuration = configuration,
                baselines = baselines,
            ),
        ),
    )

    private fun FileSyncCoordinatorState.pair(): FileSyncPair = pairs.single()

    private fun local(path: String, revision: String, contentHash: String? = null) =
        LocalSyncEntry(path, SyncEntryKind.File, revision, contentHash = contentHash)

    private fun remote(path: String, etag: String, contentHash: String? = null) =
        RemoteSyncEntry(path, SyncEntryKind.File, etag, contentHash = contentHash)

    private fun baseline(path: String, localRevision: String, remoteEtag: String) =
        FileSyncBaseline(path, SyncEntryKind.File, localRevision, remoteEtag)

    private companion object {
        const val PAIR_ID = "obsidian-notes"
    }
}
