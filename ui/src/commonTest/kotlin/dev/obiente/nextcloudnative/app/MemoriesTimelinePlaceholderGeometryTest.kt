package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoriesTimelinePlaceholderGeometryTest {
    @Test
    fun completeIndexCreatesDeterministicMonthAndPlaceholderGeometry() {
        val geometry = requireNotNull(
            buildMemoriesTimelinePlaceholderGeometry(
                MemoriesMainTimelineDayIndex(
                    listOf(
                        NativeMediaDay(60L, 2),
                        NativeMediaDay(59L, 3),
                        NativeMediaDay(31L, 4),
                        NativeMediaDay(30L, 1),
                    ),
                ),
            ),
        )

        assertEquals(10L, geometry.totalAdvertisedItemCount)
        assertEquals(13, geometry.totalGridItemCount)
        assertEquals(
            listOf(
                PhotoTimelineMonth(1970, 3),
                PhotoTimelineMonth(1970, 2),
                PhotoTimelineMonth(1970, 1),
            ),
            geometry.months.map(MemoriesTimelinePlaceholderMonth::month),
        )
        assertEquals(listOf(0, 6, 11), geometry.months.map { it.monthHeaderGridItemIndex })
        assertEquals(listOf(1, 3, 7, 12), geometry.days.map { it.firstGridItemIndex })
        assertEquals(listOf(2, 5, 10, 12), geometry.days.map { it.lastGridItemIndex })
    }

    @Test
    fun scrubberFractionsUseAdvertisedCountsRatherThanEqualMonthSpacing() {
        val geometry = geometry()

        assertEquals(PhotoTimelineMonth(1970, 3), geometry.monthAtFraction(0.49f)?.month)
        assertEquals(PhotoTimelineMonth(1970, 2), geometry.monthAtFraction(0.50f)?.month)
        assertEquals(PhotoTimelineMonth(1970, 1), geometry.monthAtFraction(0.90f)?.month)
        assertEquals(PhotoTimelineMonth(1970, 1), geometry.monthAtFraction(1f)?.month)
        assertNull(geometry.monthAtFraction(Float.NaN))
        assertEquals(0f, geometry.fractionFor(PhotoTimelineMonth(1970, 3)))
        assertEquals(0.5f, geometry.fractionFor(PhotoTimelineMonth(1970, 2)))
        assertEquals(0.9f, geometry.fractionFor(PhotoTimelineMonth(1970, 1)))
    }

    @Test
    fun visiblePlaceholderRangeMapsBackToDirectDayRequests() {
        val geometry = geometry()

        assertEquals(listOf(60L, 59L), geometry.dayIdsIntersectingGridItems(1, 3))
        assertEquals(listOf(31L), geometry.dayIdsIntersectingGridItems(6, 8))
        assertEquals(listOf(30L), geometry.dayIdsIntersectingGridItems(11, 20))
        assertEquals(emptyList(), geometry.dayIdsIntersectingGridItems(-1, 4))
        assertEquals(emptyList(), geometry.dayIdsIntersectingGridItems(99, 120))
        assertEquals(31L, geometry.firstDayIdFor(PhotoTimelineMonth(1970, 2)))
        assertNull(geometry.firstDayIdFor(PhotoTimelineMonth(1969, 12)))
    }

    @Test
    fun zeroCountDaysDoNotCreateUnreachableMonths() {
        val geometry = requireNotNull(
            buildMemoriesTimelinePlaceholderGeometry(
                MemoriesMainTimelineDayIndex(
                    listOf(
                        NativeMediaDay(60L, 2),
                        NativeMediaDay(31L, 0),
                        NativeMediaDay(30L, 1),
                    ),
                ),
            ),
        )

        assertEquals(listOf(60L, 30L), geometry.days.map(MemoriesTimelinePlaceholderDay::dayId))
        assertEquals(
            listOf(PhotoTimelineMonth(1970, 3), PhotoTimelineMonth(1970, 1)),
            geometry.months.map(MemoriesTimelinePlaceholderMonth::month),
        )
    }

    @Test
    fun geometryRefusesAGridThatComposeCannotIndex() {
        val geometry = buildMemoriesTimelinePlaceholderGeometry(
            MemoriesMainTimelineDayIndex(
                listOf(
                    NativeMediaDay(60L, Int.MAX_VALUE),
                    NativeMediaDay(59L, 1),
                ),
            ),
        )

        assertNull(geometry)
    }

    private fun geometry(): MemoriesTimelinePlaceholderGeometry = requireNotNull(
        buildMemoriesTimelinePlaceholderGeometry(
            MemoriesMainTimelineDayIndex(
                listOf(
                    NativeMediaDay(60L, 2),
                    NativeMediaDay(59L, 3),
                    NativeMediaDay(31L, 4),
                    NativeMediaDay(30L, 1),
                ),
            ),
        ),
    )
}
