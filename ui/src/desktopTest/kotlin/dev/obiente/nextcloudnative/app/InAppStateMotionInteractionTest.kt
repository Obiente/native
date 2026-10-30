package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.NextcloudDisclosureContent
import dev.obiente.nextcloudnative.app.design.NextcloudRefreshBar
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption
import dev.obiente.nextcloudnative.app.design.NextcloudSkeletonLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InAppStateMotionInteractionTest {
    @Test
    fun loadingSkeletonAnnouncesOneBusyIndicatorNamedByItsMessage() {
        nativeSceneTest(400, 700, content = {
            LoadingMessage("Loading conversations...", NextcloudSkeletonLayout.List)
        }) {
            val busy = nodes().filter {
                it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
            }
            assertEquals(1, busy.size, "Placeholder shapes collapse into one busy indicator")
            assertTrue(busy.single().config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Loading conversations...") == true)
            assertTrue(has("Loading conversations..."), "The message also stays visible as a caption")
        }
    }

    @Test
    fun errorStateIsCenteredAndOffersItsRetry() {
        var retries = 0
        nativeSceneTest(400, 700, content = {
            ErrorMessage("Could not load Talk conversations.") { retries++ }
        }) {
            val title = assertNotNull(node("Could not load Talk conversations."))
            assertTrue(title.boundsInRoot.center.x in 150f..250f, "The failure explains itself in the middle of the workspace")
            click("Try again")
            assertEquals(1, retries)
        }
    }

    @Test
    fun emptyStateNamesWhatIsMissing() {
        nativeSceneTest(400, 700, content = { EmptyMessage("No Talk conversations yet.") }) {
            assertTrue(has("No Talk conversations yet."))
        }
    }

    @Test
    fun refreshBarAndDisclosureOpenAndCloseInBothMotionModes() {
        for (reduced in listOf(false, true)) {
            val open = mutableStateOf(false)
            nativeSceneTest(400, 700, content = {
                CompositionLocalProvider(LocalNextcloudReducedMotion provides reduced) {
                    Column {
                        NextcloudRefreshBar(open.value)
                        NextcloudDisclosureContent(open.value) { Text("Secondary details") }
                    }
                }
            }) {
                assertFalse(has("Secondary details"))
                assertFalse(hasProgress())
                open.value = true
                settleUntil { has("Secondary details") && hasProgress() }
                assertTrue(has("Secondary details"))
                assertTrue(hasProgress())
                open.value = false
                settleUntil { !has("Secondary details") && !hasProgress() }
                assertFalse(has("Secondary details"), "Collapsed details leave the semantics tree after folding")
                assertFalse(hasProgress())
            }
        }
    }

    @Test
    fun segmentedSelectionMovesWithoutChangingSelectionSemantics() {
        val selected = mutableStateOf("month")
        nativeSceneTest(500, 300, content = {
            NextcloudSegmentedControl(
                options = listOf(
                    NextcloudSegmentedOption("month", "Month"),
                    NextcloudSegmentedOption("week", "Week"),
                    NextcloudSegmentedOption("agenda", "Agenda"),
                ),
                selectedId = selected.value,
                onSelected = { selected.value = it },
            )
        }) {
            assertEquals(listOf("Month"), selectedLabels())
            click("Agenda")
            assertEquals("agenda", selected.value)
            settle()
            assertEquals(listOf("Agenda"), selectedLabels())
        }
    }

    private fun NativeSceneTestDriver.hasProgress(): Boolean = nodes().any {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
    }

    private fun NativeSceneTestDriver.selectedLabels(): List<String> = nodes()
        .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    private fun assertNotNull(value: androidx.compose.ui.semantics.SemanticsNode?) =
        kotlin.test.assertNotNull(value, "Missing node")
}
