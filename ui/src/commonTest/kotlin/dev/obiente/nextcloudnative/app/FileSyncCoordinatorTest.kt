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
    fun `changed destination replaces a stale resolved decision`() {
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
    fun `use local follows a newer same-kind source while destination stays unchanged`() {
        var state = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(local("note.md", "l1")),
            listOf(remote("note.md", "r1")),
            10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.UseLocal)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("note.md", "l2")),
            listOf(remote("note.md", "r1")),
            20,
        )

        val rebound = state.pair().workItems.single()
        assertEquals(workId, rebound.id)
        assertEquals(FileSyncExecutionState.Ready, rebound.state)
        assertEquals("l2", rebound.observedLocal?.revision)
        assertEquals("r1", assertIs<FileSyncOperation.Upload>(rebound.operation).expectedRemoteEtag)
        assertEquals(
            FileSyncDecisionState.Resolved(FileSyncDecisionChoice.UseLocal),
            rebound.decision?.state,
        )
    }

    @Test
    fun `use remote follows a newer same-kind source while destination stays unchanged`() {
        var state = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(local("note.md", "l1")),
            listOf(remote("note.md", "r1")),
            10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.UseRemote)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("note.md", "l1")),
            listOf(remote("note.md", "r2")),
            20,
        )

        val rebound = state.pair().workItems.single()
        assertEquals(workId, rebound.id)
        assertEquals(FileSyncExecutionState.Ready, rebound.state)
        assertEquals("r2", rebound.observedRemote?.etag)
        assertEquals("l1", assertIs<FileSyncOperation.Download>(rebound.operation).expectedLocalRevision)
    }

    @Test
    fun `display timestamps refresh without replacing a resolved decision`() {
        var state = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(LocalSyncEntry("note.md", SyncEntryKind.File, "l1")),
            listOf(RemoteSyncEntry("note.md", SyncEntryKind.File, "r1")),
            10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.UseLocal)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(LocalSyncEntry("note.md", SyncEntryKind.File, "l1", modifiedEpochMillis = 1_000L)),
            listOf(RemoteSyncEntry("note.md", SyncEntryKind.File, "r1", modifiedEpochMillis = 2_000L)),
            20,
        )

        val retained = state.pair().workItems.single()
        assertEquals(workId, retained.id)
        assertEquals(FileSyncExecutionState.Ready, retained.state)
        assertEquals(1_000L, retained.observedLocal?.modifiedEpochMillis)
        assertEquals(2_000L, retained.observedRemote?.modifiedEpochMillis)
        assertEquals(
            FileSyncDecisionState.Resolved(FileSyncDecisionChoice.UseLocal),
            retained.decision?.state,
        )
    }

    @Test
    fun `display timestamps do not reset exhausted retry state`() {
        val localWithoutTimestamp = LocalSyncEntry("note.md", SyncEntryKind.File, "l1")
        val exhausted = FileSyncWorkItem(
            id = 1L,
            relativePath = "note.md",
            observedLocal = localWithoutTimestamp,
            observedRemote = null,
            observedBaseline = null,
            operation = FileSyncOperation.Upload("note.md", expectedRemoteEtag = null),
            state = FileSyncExecutionState.Failed,
            attemptCount = MAX_FILE_SYNC_ATTEMPTS,
            lastAttemptEpochMillis = 9L,
            failureMessage = "Automatic retries exhausted",
        )

        val scanned = scanFileSyncPair(
            state(workItems = listOf(exhausted), nextWorkId = 2L),
            PAIR_ID,
            listOf(localWithoutTimestamp.copy(modifiedEpochMillis = 1_000L)),
            remoteEntries = emptyList(),
            nowEpochMillis = 10L,
        ).pair().workItems.single()

        assertEquals(exhausted.id, scanned.id)
        assertEquals(FileSyncExecutionState.Failed, scanned.state)
        assertEquals(MAX_FILE_SYNC_ATTEMPTS, scanned.attemptCount)
        assertEquals(1_000L, scanned.observedLocal?.modifiedEpochMillis)
    }

    @Test
    fun `restore local follows the latest surviving remote source`() {
        var state = scanFileSyncPair(
            state(baselines = listOf(baseline("note.md", "l1", "r1"))),
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = listOf(remote("note.md", "r1")),
            nowEpochMillis = 10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.RestoreMissing)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = listOf(remote("note.md", "r2")),
            nowEpochMillis = 20,
        )

        val rebound = state.pair().workItems.single()
        assertEquals(workId, rebound.id)
        assertEquals("r2", rebound.observedRemote?.etag)
        assertNull(assertIs<FileSyncOperation.Download>(rebound.operation).expectedLocalRevision)
    }

    @Test
    fun `restore remote follows the latest surviving local source`() {
        var state = scanFileSyncPair(
            state(baselines = listOf(baseline("note.md", "l1", "r1"))),
            PAIR_ID,
            localEntries = listOf(local("note.md", "l1")),
            remoteEntries = emptyList(),
            nowEpochMillis = 10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.RestoreMissing)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = listOf(local("note.md", "l2")),
            remoteEntries = emptyList(),
            nowEpochMillis = 20,
        )

        val rebound = state.pair().workItems.single()
        assertEquals(workId, rebound.id)
        assertEquals("l2", rebound.observedLocal?.revision)
        assertNull(assertIs<FileSyncOperation.Upload>(rebound.operation).expectedRemoteEtag)
    }

    @Test
    fun `restore local follows the latest surviving remote directory`() {
        val baseline = FileSyncBaseline("Archive", SyncEntryKind.Directory, "local-dir", "remote-dir-1")
        var state = scanFileSyncPair(
            state(baselines = listOf(baseline)),
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = listOf(RemoteSyncEntry("Archive", SyncEntryKind.Directory, "remote-dir-1")),
            nowEpochMillis = 10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.RestoreMissing)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = listOf(RemoteSyncEntry("Archive", SyncEntryKind.Directory, "remote-dir-2")),
            nowEpochMillis = 20,
        )

        val rebound = state.pair().workItems.single()
        assertEquals(workId, rebound.id)
        assertEquals("remote-dir-2", rebound.observedRemote?.etag)
        assertNull(assertIs<FileSyncOperation.Download>(rebound.operation).expectedLocalRevision)
    }

    @Test
    fun `restore remote follows the latest surviving local directory`() {
        val baseline = FileSyncBaseline("Archive", SyncEntryKind.Directory, "local-dir-1", "remote-dir")
        var state = scanFileSyncPair(
            state(baselines = listOf(baseline)),
            PAIR_ID,
            localEntries = listOf(LocalSyncEntry("Archive", SyncEntryKind.Directory, "local-dir-1")),
            remoteEntries = emptyList(),
            nowEpochMillis = 10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.RestoreMissing)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            localEntries = listOf(LocalSyncEntry("Archive", SyncEntryKind.Directory, "local-dir-2")),
            remoteEntries = emptyList(),
            nowEpochMillis = 20,
        )

        val rebound = state.pair().workItems.single()
        assertEquals(workId, rebound.id)
        assertEquals("local-dir-2", rebound.observedLocal?.revision)
        assertNull(assertIs<FileSyncOperation.Upload>(rebound.operation).expectedRemoteEtag)
    }

    @Test
    fun `keep both requires renewed review when either source changes`() {
        var state = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(local("note.md", "l1")),
            listOf(remote("note.md", "r1")),
            10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.KeepBoth)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(local("note.md", "l2")),
            listOf(remote("note.md", "r1")),
            20,
        )

        val replacement = state.pair().workItems.single()
        assertTrue(replacement.id > workId)
        assertEquals(FileSyncExecutionState.AwaitingDecision, replacement.state)
        assertEquals(FileSyncDecisionState.Pending, replacement.decision?.state)
    }

    @Test
    fun `directional choice requires renewed review when selected source changes type`() {
        var state = scanFileSyncPair(
            state(),
            PAIR_ID,
            listOf(local("note.md", "l1")),
            listOf(remote("note.md", "r1")),
            10,
        )
        val workId = state.pair().workItems.single().id
        state = resolveFileSyncDecision(state, PAIR_ID, workId, FileSyncDecisionChoice.UseLocal)

        state = scanFileSyncPair(
            state,
            PAIR_ID,
            listOf(LocalSyncEntry("note.md", SyncEntryKind.Directory, "local-directory")),
            listOf(remote("note.md", "r1")),
            20,
        )

        val replacement = state.pair().workItems.single()
        assertTrue(replacement.id > workId)
        assertEquals(FileSyncExecutionState.AwaitingDecision, replacement.state)
        assertEquals(FileSyncDecisionReason.TypeChanged, replacement.decision?.reason)
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
    fun `resolved deletion decisions retain child before parent execution order`() {
        val parent = FileSyncBaseline("Photos", SyncEntryKind.Directory, "local-dir", "remote-dir")
        val child = baseline("Photos/image.jpg", "local-file", "remote-file")
        val remoteEntries = listOf(
            RemoteSyncEntry(parent.relativePath, SyncEntryKind.Directory, "remote-dir"),
            remote(child.relativePath, "remote-file"),
        )
        var coordinator = scanFileSyncPair(
            state(baselines = listOf(parent, child)),
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = remoteEntries,
            nowEpochMillis = 10L,
        )
        coordinator.pair().workItems.forEach { work ->
            coordinator = resolveFileSyncDecision(
                coordinator,
                PAIR_ID,
                work.id,
                FileSyncDecisionChoice.PropagateDeletion,
            )
        }

        val rescanned = scanFileSyncPair(
            coordinator,
            PAIR_ID,
            localEntries = emptyList(),
            remoteEntries = remoteEntries,
            nowEpochMillis = 20L,
        ).pair()

        assertEquals(listOf(child.relativePath, parent.relativePath), rescanned.workItems.map { it.relativePath })
        assertTrue(rescanned.workItems.all { it.operation is FileSyncOperation.DeleteRemote })
    }

    @Test
    fun `explicit recovery resets only failures that exhausted automatic retries`() {
        var coordinator = scanFileSyncPair(
            state(),
            PAIR_ID,
            localEntries = listOf(local("retry.txt", "local-v1")),
            remoteEntries = emptyList(),
            nowEpochMillis = 10,
        )
        val workId = coordinator.pair().workItems.single().id
        repeat(MAX_FILE_SYNC_ATTEMPTS) { attempt ->
            coordinator = claimNextFileSyncOperation(coordinator, PAIR_ID, attempt.toLong()).state
            coordinator = failFileSyncOperation(coordinator, PAIR_ID, workId, "Temporary failure")
            if (attempt + 1 < MAX_FILE_SYNC_ATTEMPTS) {
                coordinator = retryFileSyncOperation(coordinator, PAIR_ID, workId)
            }
        }

        val reset = resetExhaustedFileSyncOperations(coordinator, PAIR_ID).pair().workItems.single()

        assertEquals(FileSyncExecutionState.Ready, reset.state)
        assertEquals(0, reset.attemptCount)
        assertNull(reset.failureMessage)
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

    @Test
    fun `the complete supported tree is planned in one priority ordered pass`() {
        val entries = (0 until MAX_FILE_SYNC_ENTRIES).map { index ->
            local("Archive/file-${index.toString().padStart(5, '0')}.jpg", "local-$index")
        }

        val scanned = scanFileSyncPair(
            state(),
            PAIR_ID,
            entries,
            emptyList(),
            nowEpochMillis = 10L,
        ).pair()

        assertEquals(MAX_FILE_SYNC_ENTRIES, scanned.workItems.size)
        assertEquals("Archive/file-00000.jpg", scanned.workItems.first().relativePath)
        assertEquals("Archive/file-99999.jpg", scanned.workItems.last().relativePath)
        assertEquals(100_001L, scanned.nextWorkId)
    }

    @Test
    fun `non executable work cannot crowd transfers out of a reserved desktop batch`() {
        val excludedLocal = (0 until 10_000).map { index ->
            local("Local/file-${index.toString().padStart(5, '0')}.jpg", "local-$index")
        }
        val configuration = FileSyncConfiguration(
            direction = FileSyncDirection.DownloadOnly,
            deviceLabel = "Workstation",
        )

        val scanned = scanFileSyncPair(
            state(configuration = configuration),
            PAIR_ID,
            localEntries = excludedLocal,
            remoteEntries = listOf(remote("Remote/download.jpg", "remote-download")),
            nowEpochMillis = 10L,
            maximumWorkItems = 20_000,
            reservedNonExecutableWorkItems = 10_000,
        ).pair()

        assertEquals(10_001, scanned.workItems.size)
        assertEquals(10_000, scanned.workItems.count { it.state == FileSyncExecutionState.Skipped })
        assertIs<FileSyncOperation.Download>(
            scanned.workItems.single { it.state == FileSyncExecutionState.Ready }.operation,
        )
    }

    @Test
    fun `exhausted failures cannot crowd transfers out of a reserved desktop batch`() {
        val exhaustedLocal = (0 until 10_000).map { index ->
            local("Archive/file-${index.toString().padStart(5, '0')}.jpg", "local-$index")
        }
        val exhaustedWork = exhaustedLocal.mapIndexed { index, entry ->
            FileSyncWorkItem(
                id = index + 1L,
                relativePath = entry.relativePath,
                observedLocal = entry,
                observedRemote = null,
                observedBaseline = null,
                operation = FileSyncOperation.Upload(entry.relativePath, expectedRemoteEtag = null),
                state = FileSyncExecutionState.Failed,
                attemptCount = MAX_FILE_SYNC_ATTEMPTS,
                lastAttemptEpochMillis = 9L,
                failureMessage = "Automatic retries exhausted",
            )
        }
        val initial = state(
            workItems = exhaustedWork,
            nextWorkId = 10_001L,
        )

        val scanned = scanFileSyncPair(
            initial,
            PAIR_ID,
            localEntries = exhaustedLocal,
            remoteEntries = listOf(remote("Remote/download.jpg", "remote-download")),
            nowEpochMillis = 10L,
            maximumWorkItems = 20_000,
            reservedNonExecutableWorkItems = 10_000,
        ).pair()

        assertEquals(10_001, scanned.workItems.size)
        assertEquals(
            10_000,
            scanned.workItems.count {
                it.state == FileSyncExecutionState.Failed && it.attemptCount == MAX_FILE_SYNC_ATTEMPTS
            },
        )
        assertIs<FileSyncOperation.Download>(
            scanned.workItems.single { it.state == FileSyncExecutionState.Ready }.operation,
        )
    }

    @Test
    fun `new conflicts displace retained skips in a reserved desktop batch`() {
        val skippedLocal = (0 until 10_000).map { index ->
            local("Local/file-${index.toString().padStart(5, '0')}.jpg", "local-$index")
        }
        val configuration = FileSyncConfiguration(
            direction = FileSyncDirection.DownloadOnly,
            deviceLabel = "Workstation",
        )
        val initial = scanFileSyncPair(
            state(configuration = configuration),
            PAIR_ID,
            localEntries = skippedLocal,
            remoteEntries = emptyList(),
            nowEpochMillis = 9L,
            maximumWorkItems = 20_000,
            reservedNonExecutableWorkItems = 10_000,
        )
        val collisionLocal = local("Remote/collision.jpg", "local-collision")

        val scanned = scanFileSyncPair(
            initial,
            PAIR_ID,
            localEntries = skippedLocal + collisionLocal,
            remoteEntries = listOf(
                RemoteSyncEntry(collisionLocal.relativePath, SyncEntryKind.Directory, "remote-collision"),
            ),
            nowEpochMillis = 10L,
            maximumWorkItems = 20_000,
            reservedNonExecutableWorkItems = 10_000,
        ).pair()

        assertEquals(10_000, scanned.workItems.size)
        assertEquals(9_999, scanned.workItems.count { it.state == FileSyncExecutionState.Skipped })
        assertIs<FileSyncOperation.NeedsDecision>(
            scanned.workItems.single { it.state == FileSyncExecutionState.AwaitingDecision }.operation,
        )
    }

    private fun state(
        baselines: List<FileSyncBaseline> = emptyList(),
        configuration: FileSyncConfiguration = FileSyncConfiguration(deviceLabel = "Test phone"),
        workItems: List<FileSyncWorkItem> = emptyList(),
        nextWorkId: Long = 1L,
    ) = FileSyncCoordinatorState(
        pairs = listOf(
            FileSyncPair(
                id = PAIR_ID,
                accountId = "account-a",
                localRootId = "android-tree:primary-notes",
                remoteRootPath = "Notes",
                configuration = configuration,
                baselines = baselines,
                workItems = workItems,
                nextWorkId = nextWorkId,
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
