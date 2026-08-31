package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextcloudSegmentedControlTest {
    @Test
    fun directionalMovementSkipsDisabledOptions() {
        assertEquals("month", target("day", NextcloudSegmentedFocusMove.Next))
        assertEquals("day", target("month", NextcloudSegmentedFocusMove.Previous))
    }

    @Test
    fun directionalMovementWrapsOnlyAcrossEnabledOptions() {
        assertEquals("day", target("agenda", NextcloudSegmentedFocusMove.Next))
        assertEquals("agenda", target("day", NextcloudSegmentedFocusMove.Previous))
    }

    @Test
    fun firstAndLastIgnoreTheCurrentOptionAndDisabledEndpoints() {
        val bounded = listOf(NextcloudSegmentedOption("start", "Disabled start", enabled = false)) + options() +
            NextcloudSegmentedOption("end", "Disabled end", enabled = false)
        assertEquals("day", nextcloudSegmentedFocusTarget(bounded, "month", NextcloudSegmentedFocusMove.First))
        assertEquals("agenda", nextcloudSegmentedFocusTarget(bounded, "month", NextcloudSegmentedFocusMove.Last))
    }

    @Test
    fun unknownRemovedOrNullCurrentOptionUsesDirectionalBoundary() {
        for (current in listOf(null, "removed-option", "disabled")) {
            assertEquals("day", target(current, NextcloudSegmentedFocusMove.Next))
            assertEquals("agenda", target(current, NextcloudSegmentedFocusMove.Previous))
        }
    }

    @Test
    fun emptyOrEntirelyDisabledOptionsHaveNoFocusTarget() {
        for (move in NextcloudSegmentedFocusMove.entries) {
            assertNull(nextcloudSegmentedFocusTarget(emptyList(), null, move))
            assertNull(nextcloudSegmentedFocusTarget(
                listOf(NextcloudSegmentedOption("disabled", "Unavailable", enabled = false)), "disabled", move,
            ))
        }
    }

    @Test
    fun oneEnabledOptionIsTheOnlyFocusTargetForEveryMove() {
        val single = listOf(NextcloudSegmentedOption("disabled", "Unavailable", enabled = false),
            NextcloudSegmentedOption("single", "Single"))
        for (move in NextcloudSegmentedFocusMove.entries) {
            assertEquals("single", nextcloudSegmentedFocusTarget(single, "single", move))
        }
    }

    @Test
    fun duplicateDisplayLabelsDoNotReplaceStableOptionIds() {
        val duplicateLabels = listOf(NextcloudSegmentedOption("personal", "Calendar"), NextcloudSegmentedOption("team", "Calendar"))
        assertEquals("team", nextcloudSegmentedFocusTarget(duplicateLabels, "personal", NextcloudSegmentedFocusMove.Next))
        assertEquals("personal", nextcloudSegmentedFocusTarget(duplicateLabels, "team", NextcloudSegmentedFocusMove.Previous))
    }

    @Test
    fun focusOrderTracksReorderedOptionsById() {
        assertEquals("day", nextcloudSegmentedFocusTarget(options().reversed(), "month", NextcloudSegmentedFocusMove.Next))
        assertEquals("agenda", nextcloudSegmentedFocusTarget(options().reversed(), "month", NextcloudSegmentedFocusMove.Previous))
    }

    private fun target(current: String?, move: NextcloudSegmentedFocusMove) =
        nextcloudSegmentedFocusTarget(options(), current, move)

    private fun options() = listOf(
        NextcloudSegmentedOption("day", "Day"),
        NextcloudSegmentedOption("disabled", "Unavailable", enabled = false),
        NextcloudSegmentedOption("month", "Month"),
        NextcloudSegmentedOption("agenda", "Agenda"),
    )
}
