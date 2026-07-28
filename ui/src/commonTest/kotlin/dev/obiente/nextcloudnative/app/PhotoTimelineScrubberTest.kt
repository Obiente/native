package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhotoTimelineScrubberTest {
    @Test
    fun railPositionMapsOnlyWithinTheRailAndClampsItsEdges() {
        assertEquals(
            0,
            photoTimelineSectionIndexForRailPosition(
                positionY = -12f,
                railHeight = 100f,
                thumbHeight = 16f,
                sectionCount = 5,
            ),
        )
        assertEquals(
            2,
            photoTimelineSectionIndexForRailPosition(
                positionY = 50f,
                railHeight = 100f,
                thumbHeight = 16f,
                sectionCount = 5,
            ),
        )
        assertEquals(
            4,
            photoTimelineSectionIndexForRailPosition(
                positionY = 112f,
                railHeight = 100f,
                thumbHeight = 16f,
                sectionCount = 5,
            ),
        )
    }

    @Test
    fun visibleThumbCentersMapBackToTheirEndpointSections() {
        assertEquals(
            0,
            photoTimelineSectionIndexForRailPosition(
                positionY = 8f,
                railHeight = 112f,
                thumbHeight = 16f,
                sectionCount = 13,
            ),
        )
        assertEquals(
            12,
            photoTimelineSectionIndexForRailPosition(
                positionY = 104f,
                railHeight = 112f,
                thumbHeight = 16f,
                sectionCount = 13,
            ),
        )
    }

    @Test
    fun railPositionRejectsMissingOrInvalidGeometry() {
        assertNull(
            photoTimelineSectionIndexForRailPosition(
                positionY = 20f,
                railHeight = 0f,
                thumbHeight = 16f,
                sectionCount = 5,
            ),
        )
        assertNull(
            photoTimelineSectionIndexForRailPosition(
                positionY = Float.NaN,
                railHeight = 100f,
                thumbHeight = 16f,
                sectionCount = 5,
            ),
        )
        assertNull(
            photoTimelineSectionIndexForRailPosition(
                positionY = 20f,
                railHeight = 100f,
                thumbHeight = 16f,
                sectionCount = 0,
            ),
        )
        assertNull(
            photoTimelineSectionIndexForRailPosition(
                positionY = 20f,
                railHeight = 100f,
                thumbHeight = 100f,
                sectionCount = 5,
            ),
        )
    }

    @Test
    fun railPositionKeepsASingleMonthAtItsOnlyIndex() {
        assertEquals(
            0,
            photoTimelineSectionIndexForRailPosition(
                positionY = 75f,
                railHeight = 100f,
                thumbHeight = 16f,
                sectionCount = 1,
            ),
        )
    }

    @Test
    fun arrowStepsMoveOneMonthAndStayWithinBounds() {
        assertEquals(0, photoTimelineSectionIndexAfterStep(0, sectionCount = 4, step = -1))
        assertEquals(0, photoTimelineSectionIndexAfterStep(1, sectionCount = 4, step = -1))
        assertEquals(2, photoTimelineSectionIndexAfterStep(1, sectionCount = 4, step = 1))
        assertEquals(3, photoTimelineSectionIndexAfterStep(3, sectionCount = 4, step = 1))
        assertEquals(3, photoTimelineSectionIndexAfterStep(99, sectionCount = 4, step = 0))
        assertNull(photoTimelineSectionIndexAfterStep(0, sectionCount = 0, step = 1))
    }

    @Test
    fun fullHeightRailMapsManyMonthsDeterministically() {
        assertEquals(
            0,
            photoTimelineSectionIndexForRailPosition(
                positionY = 16f,
                railHeight = 720f,
                thumbHeight = 32f,
                sectionCount = 25,
            ),
        )
        assertEquals(
            12,
            photoTimelineSectionIndexForRailPosition(
                positionY = 360f,
                railHeight = 720f,
                thumbHeight = 32f,
                sectionCount = 25,
            ),
        )
        assertEquals(
            24,
            photoTimelineSectionIndexForRailPosition(
                positionY = 704f,
                railHeight = 720f,
                thumbHeight = 32f,
                sectionCount = 25,
            ),
        )
    }

    @Test
    fun railDragMapsAcrossEveryGridItemInsteadOfSnappingToMonths() {
        val index = PhotoTimelineDateIndex(
            sections = listOf(
                PhotoTimelineMonthSection(PhotoTimelineMonth(2026, 7), 0, 5),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2026, 6), 5, 4),
            ),
            totalItemCount = 9,
        )

        assertEquals(11, photoTimelineGridItemCount(index))
        assertEquals(
            5,
            photoTimelineGridItemForRailPosition(
                positionY = 50f,
                railHeight = 100f,
                thumbHeight = 20f,
                index = index,
            ),
        )
        assertEquals(
            0,
            photoTimelineGridItemForRailPosition(
                positionY = -20f,
                railHeight = 100f,
                thumbHeight = 20f,
                index = index,
            ),
        )
        assertEquals(
            10,
            photoTimelineGridItemForRailPosition(
                positionY = 120f,
                railHeight = 100f,
                thumbHeight = 20f,
                index = index,
            ),
        )
    }

    @Test
    fun releaseSnapIsLimitedToItemsAdjacentToAMonthHeader() {
        val index = PhotoTimelineDateIndex(
            sections = listOf(
                PhotoTimelineMonthSection(PhotoTimelineMonth(2026, 7), 0, 5),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2026, 6), 5, 4),
            ),
            totalItemCount = 9,
        )

        assertEquals(0, lightlySnappedPhotoTimelineGridItem(index, 1))
        assertEquals(6, lightlySnappedPhotoTimelineGridItem(index, 5))
        assertEquals(3, lightlySnappedPhotoTimelineGridItem(index, 3))
        assertEquals(10, lightlySnappedPhotoTimelineGridItem(index, 99))
    }

    @Test
    fun dragLabelTracksTheMonthContainingTheTargetGridItem() {
        val index = PhotoTimelineDateIndex(
            sections = listOf(
                PhotoTimelineMonthSection(PhotoTimelineMonth(2026, 7), 0, 5),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2026, 6), 5, 4),
            ),
            totalItemCount = 9,
        )

        assertEquals(0, photoTimelineSectionIndexForGridItem(index, 0))
        assertEquals(0, photoTimelineSectionIndexForGridItem(index, 5))
        assertEquals(1, photoTimelineSectionIndexForGridItem(index, 6))
        assertEquals(1, photoTimelineSectionIndexForGridItem(index, 10))
    }

    @Test
    fun interactionTargetTemporarilyOwnsTheDisplayedMonth() {
        assertEquals(
            2,
            photoTimelineScrubberDisplaySectionIndex(
                activeSectionIndex = 2,
                interactionSectionIndex = null,
                sectionCount = 8,
            ),
        )
        assertEquals(
            6,
            photoTimelineScrubberDisplaySectionIndex(
                activeSectionIndex = 2,
                interactionSectionIndex = 6,
                sectionCount = 8,
            ),
        )
        assertEquals(
            7,
            photoTimelineScrubberDisplaySectionIndex(
                activeSectionIndex = 99,
                interactionSectionIndex = null,
                sectionCount = 8,
            ),
        )
        assertNull(
            photoTimelineScrubberDisplaySectionIndex(
                activeSectionIndex = 0,
                interactionSectionIndex = null,
                sectionCount = 0,
            ),
        )
    }

    @Test
    fun duplicateDragTargetsDoNotScheduleAnotherJump() {
        assertNull(
            distinctPhotoTimelineScrubberJumpTarget(
                currentSectionIndex = 4,
                requestedSectionIndex = 4,
                sectionCount = 12,
            ),
        )
        assertEquals(
            5,
            distinctPhotoTimelineScrubberJumpTarget(
                currentSectionIndex = 4,
                requestedSectionIndex = 5,
                sectionCount = 12,
            ),
        )
        assertEquals(
            11,
            distinctPhotoTimelineScrubberJumpTarget(
                currentSectionIndex = 4,
                requestedSectionIndex = 99,
                sectionCount = 12,
            ),
        )
        assertNull(
            distinctPhotoTimelineScrubberJumpTarget(
                currentSectionIndex = 0,
                requestedSectionIndex = 1,
                sectionCount = 0,
            ),
        )
    }
}
