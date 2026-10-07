package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeLayoutHierarchySceneTest {
    @Test
    fun budgetSummaryAndCategoryKeepAmountsAndCarryoverVisibleAtLargeText() {
        val category = NativeBudgetCategoryProgress("food", "Food", 100.0, 90.0, 10.0,
            120.0, -20.0, 120.0, "overbudget", null)
        val plan = NativeBudgetPlanPresentation("2026-09-01", "2026-09-30", 100.0, 120.0,
            -20.0, "Over budget", listOf(category))
        nativeSceneTest(390, 844, fontScale = 1.5f, content = {
            CompositionLocalProvider(LocalNativeFinanceCurrency provides "EUR") { GenericBudgetPlanDashboard(plan) }
        }) {
            assertTrue(has("Budget progress"))
            showAndAssertVisible("120% used")
            showAndAssertVisible("Food")
            showAndAssertVisible("Includes EUR 10.00 carryover")
        }
    }

    @Test
    fun playlistPlayAllKeepsTheExactRecordOrder() {
        val resource = ResourceSpec("tracks", "Tracks", Confidence.verified)
        val records = listOf("First", "Second").mapIndexed { index, name -> NativeRecord(
            "track-$index", mapOf("title" to name, "artist" to "Synthetic artist", "tracknumber" to "${index + 1}",
                "fileid" to "${index + 1}", "mimetype" to "audio/mpeg")) }
        var played: List<NativeRecord>? = null
        var selected: NativeRecord? = null
        nativeSceneTest(390, 844, content = {
            NativePlaylistTrackCollection(NativePlaylistTracks("Synthetic playlist", resource, records), null,
                NativeAudioRecordPlayer { _, queue, track, _ -> played = queue; selected = track }, null)
        }) {
            assertTrue(has("2 tracks"))
            showAndAssertVisible("Play all")
            click("Play all")
            assertEquals(records, played)
            assertEquals(records.first(), selected)
        }
    }
    @Test
    fun transferSummaryOpensFailuresWithoutClearingHistory() {
        var section: MediaTransferSection? = null
        var clears = 0
        val state = mediaTransferCenterState(MediaBackupLedgerSummary(3, 1, 2, 4),
            MediaTransferSection.Pending, MediaBackupLedgerPage(emptyList(), null), false)
        nativeSceneTest(390, 844, fontScale = 1.5f, content = {
            MediaTransferCenterScreen(state = state, loading = false, busyLocalKey = null,
                clearingCompleted = false, statusMessage = null, statusMessageIsError = false,
                onBack = {}, onSelectSection = { section = it }, onLoadNewer = {}, onLoadOlder = {},
                onRetry = {}, onAction = { _, _ -> }, onClearCompleted = { clears++ })
        }) {
            showAndAssertVisible("Review failed uploads")
            click("Review failed uploads")
            assertEquals(MediaTransferSection.Failed, section)
            assertEquals(0, clears)
        }
    }

    private suspend fun NativeSceneTestDriver.showAndAssertVisible(label: String) {
        repeat(12) {
            val target = node(label)
            if (target != null && target.boundsInRoot.top >= 0f && target.boundsInRoot.bottom <= 844f) return
            val scroll = nodes().firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
            assertNotNull(scroll, "No scroll surface for $label")(0f, 160f)
            settle()
        }
        val bounds = assertNotNull(node(label), "Missing $label").boundsInRoot
        assertTrue(bounds.top >= 0f && bounds.bottom <= 844f, "$label must be within the viewport")
    }

}
