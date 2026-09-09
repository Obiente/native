package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSyncUploadOwnershipTest {
    @Test
    fun `abandoned chunk publication retains exact recovery evidence`() {
        val contentHash = "sha256:" + "42".repeat(32)
        val uploadId = "01234567-89ab-cdef-0123-456789abcdef"
        val plan = nextcloudUploadTransferPlan(25L * 1024L * 1024L) as NextcloudUploadTransferPlan.Chunked
        val local = LocalSyncEntry(
            "archive.bin",
            SyncEntryKind.File,
            "local-revision",
            plan.sizeBytes,
            contentHash,
        )
        val work = FileSyncWorkItem(
            id = 1,
            relativePath = "archive.bin",
            observedLocal = local,
            observedRemote = RemoteSyncEntry("archive.bin", SyncEntryKind.Directory, "directory-etag"),
            observedBaseline = null,
            operation = FileSyncOperation.Upload("archive.bin", "directory-etag"),
            state = FileSyncExecutionState.Running,
            decision = FileSyncDecision(
                reason = FileSyncDecisionReason.TypeChanged,
                choices = setOf(
                    FileSyncDecisionChoice.UseLocal,
                    FileSyncDecisionChoice.UseRemote,
                    FileSyncDecisionChoice.Skip,
                ),
                state = FileSyncDecisionState.Resolved(FileSyncDecisionChoice.UseLocal),
            ),
            uploadCheckpoint = newFileSyncUploadCheckpoint(
                uploadId,
                local.revision,
                plan,
                contentHash = contentHash,
            ).copy(
                uploadedChunks = plan.chunkCount,
                commitInFlight = true,
                assembledStageEtag = "stage-etag",
            ),
        )
        val previous = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Vault",
            configuration = FileSyncConfiguration(deviceLabel = "Test device"),
            workItems = listOf(work),
            nextWorkId = 2,
        )

        val cleanup = retainFileSyncUploadOwnership(previous, currentWork = emptyList()).single()

        assertTrue(cleanup.publicationInFlight)
        assertEquals(plan.sizeBytes, cleanup.expectedStageSizeBytes)
        assertEquals(contentHash, cleanup.expectedStageContentHash)
        val restored = decodeFileSyncCoordinatorSnapshot(
            encodeFileSyncCoordinatorSnapshot(FileSyncCoordinatorState(listOf(previous))),
        )
        assertEquals(contentHash, restored.pairs.single().workItems.single().uploadCheckpoint?.contentHash)
    }
}
