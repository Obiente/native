package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncInteractionRenderTest {
    @Test
    fun phonePairOpensDedicatedDetailsAndBackReturnsToListWithoutRunningWork() {
        var runRequests = 0
        var removals = 0
        nativeSceneTest(390, 844, content = {
            FileSyncWorkspace(
                snapshot = FileSyncCenterSnapshot(FileSyncCenterSupport.Available, listOf(pair())),
                loading = false, busyPairId = null,
                onAdd = {}, onRun = { runRequests++ }, onRemove = { removals++ },
                onResolve = { _, _, _ -> }, fillAvailableHeight = true,
            )
        }) {
            assertTrue(has("Add sync"))
            assertFalse(has("All syncs"))
            click("Example documents")
            assertTrue(has("All syncs"))
            assertTrue(has("Current work"))
            assertFalse(has("Add sync"))
            capture("sync-phone-detail")
            click("All syncs")
            assertTrue(has("Add sync"))
            assertFalse(has("Current work"))
            assertEquals(0, runRequests)
            assertEquals(0, removals)
        }
    }

    @Test
    fun conflictChoiceShowsConsequencesBeforeStagingAnExactGuardedReviewRequest() {
        val conflict = FileSyncConflictSummary(
            workId = 7, relativePath = "Example.txt", reason = FileSyncDecisionReason.SimultaneousEdit,
            choices = setOf(FileSyncDecisionChoice.UseLocal, FileSyncDecisionChoice.UseRemote,
                FileSyncDecisionChoice.KeepBoth, FileSyncDecisionChoice.Skip),
            local = FileSyncConflictSideSummary(SyncEntryKind.File, 1_024, 0),
            remote = FileSyncConflictSideSummary(SyncEntryKind.File, 2_048, 60_000),
        )
        val pair = pair().copy(conflicts = listOf(conflict), conflictCount = 1)
        var pending: PendingFileSyncDecision? = null
        nativeSceneTest(390, 844, content = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                FileSyncConflictBlock(pair, true,
                    onResolve = { item, choice -> pending = PendingFileSyncDecision(pair, listOf(item), choice) },
                    onResolveBatch = { _, _ -> error("Single review must not emit a batch") })
            }
        }) {
            assertTrue(has("1 conflict needs review"))
            assertFalse(has("Review this choice"))
            val options = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            assertEquals(conflict.choices.size, options.size, "Each choice must have one accessible radio target")
            assertTrue(options.all { it.boundsInRoot.height >= 48f })
            click("Keep both copies")
            assertEquals(1, nodes().count {
                it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true
            })
            assertNull(pending, "Selecting an outcome must not request a write or confirmation yet")
            assertTrue(has(FileSyncDecisionChoice.KeepBoth.decisionGuidance()))
            click("Review this choice")
            val request = assertNotNull(pending)
            assertEquals(FileSyncDecisionChoice.KeepBoth, request.choice)
            assertEquals(listOf(7L), request.conflicts.map { it.workId })
            assertEquals(pair.id, request.pair.id)
            capture("sync-conflict-choice")
        }
    }

    private fun pair() = FileSyncPairSummary(
        id = "synthetic-pair", localDisplayName = "Example documents", localRootPath = "Documents/Example",
        remoteRootPath = "Example", configuration = FileSyncConfiguration(deviceLabel = "Test device"),
        readyCount = 2, runningCount = 0, conflicts = emptyList(), failedCount = 0,
        skippedCount = 0, lastScanEpochMillis = 0,
    )
}
