package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSyncCenterTest {
    @Test
    fun `summary exposes actionable work counts without leaking the root grant`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "content://opaque-grant",
            remoteRootPath = "Notes",
            configuration = FileSyncConfiguration(deviceLabel = "phone"),
            workItems = listOf(
                FileSyncWorkItem(
                    id = 1,
                    relativePath = "note.md",
                    observedLocal = LocalSyncEntry("note.md", SyncEntryKind.File, "local"),
                    observedRemote = RemoteSyncEntry("note.md", SyncEntryKind.File, "remote"),
                    observedBaseline = null,
                    operation = FileSyncOperation.NeedsDecision(
                        "note.md",
                        FileSyncDecisionReason.FirstSyncCollision,
                    ),
                    state = FileSyncExecutionState.AwaitingDecision,
                    decision = FileSyncDecision(
                        FileSyncDecisionReason.FirstSyncCollision,
                        setOf(
                            FileSyncDecisionChoice.UseLocal,
                            FileSyncDecisionChoice.UseRemote,
                            FileSyncDecisionChoice.KeepBoth,
                            FileSyncDecisionChoice.Skip,
                        ),
                    ),
                ),
            ),
            nextWorkId = 2,
        )

        val summary = pair.toCenterSummary("Vault")

        assertEquals("Vault", summary.localDisplayName)
        assertEquals(1, summary.conflicts.size)
        assertEquals(0, summary.failedCount)
    }
}
