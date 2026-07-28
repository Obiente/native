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
}
