package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSyncConflictReviewTest {
    @Test
    fun `only deletion propagation is destructive`() {
        FileSyncDecisionChoice.entries.forEach { choice ->
            if (choice == FileSyncDecisionChoice.PropagateDeletion) {
                assertTrue(choice.isDestructiveSyncDecision())
            } else {
                assertFalse(choice.isDestructiveSyncDecision())
            }
        }
    }

    @Test
    fun `partial directory deletion is excluded from batch choices`() {
        val deletionChoices = setOf(
            FileSyncDecisionChoice.PropagateDeletion,
            FileSyncDecisionChoice.RestoreMissing,
            FileSyncDecisionChoice.Skip,
        )
        val fileConflict = FileSyncConflictSummary(
            workId = 1,
            relativePath = "photo.jpg",
            reason = FileSyncDecisionReason.RemoteDeletion,
            choices = deletionChoices,
            local = FileSyncConflictSideSummary(SyncEntryKind.File),
        )
        val directoryConflict = FileSyncConflictSummary(
            workId = 2,
            relativePath = "Albums",
            reason = FileSyncDecisionReason.RemoteDeletion,
            choices = deletionChoices,
            local = FileSyncConflictSideSummary(SyncEntryKind.Directory),
        )
        val partialPair = pair(listOf(fileConflict, directoryConflict), selectedPaths = listOf("Albums"))

        assertEquals(
            listOf(FileSyncDecisionChoice.RestoreMissing, FileSyncDecisionChoice.Skip),
            availableFileSyncBatchChoices(partialPair),
        )
        assertEquals(
            listOf(FileSyncDecisionChoice.RestoreMissing, FileSyncDecisionChoice.Skip),
            availableFileSyncItemChoices(partialPair, directoryConflict),
        )
        assertFailsWith<IllegalArgumentException> {
            PendingFileSyncDecision(
                partialPair,
                partialPair.conflicts,
                FileSyncDecisionChoice.PropagateDeletion,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PendingFileSyncDecision(
                partialPair,
                listOf(directoryConflict),
                FileSyncDecisionChoice.PropagateDeletion,
            )
        }
        assertTrue(
            FileSyncDecisionChoice.PropagateDeletion in
                availableFileSyncBatchChoices(pair(listOf(fileConflict, directoryConflict))),
        )
    }

    private fun pair(
        conflicts: List<FileSyncConflictSummary>,
        selectedPaths: List<String> = emptyList(),
    ) = FileSyncPairSummary(
        id = "pair-1",
        localDisplayName = "Photos",
        remoteRootPath = "Photos",
        configuration = FileSyncConfiguration(
            deviceLabel = "Phone",
            selectedPaths = selectedPaths,
        ),
        readyCount = 0,
        runningCount = 0,
        conflicts = conflicts,
        failedCount = 0,
        skippedCount = 0,
        lastScanEpochMillis = null,
    )
}
