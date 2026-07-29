package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketingPhotoTimelineCaptureScenariosTest {
    @Test
    fun returnToNewestCaptureShowsAnUnloadedCrossYearTarget() {
        val newest = marketingPhotoTimelineCaptureFixture(
            MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile,
        )
        val older = marketingPhotoTimelineCaptureFixture(
            MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile,
        )
        val rawRetry = marketingPhotoTimelineCaptureFixture(
            MarketingCaptureScenario.PhotoTimelineRawRetryMobile,
        )
        val fullYears = older.fullGeometry.months
            .mapTo(sortedSetOf()) { month -> month.month.year }
        val olderYears = buildPhotoTimelineDateIndex(older.entries)
            .sections
            .mapTo(sortedSetOf()) { section -> section.month.year }

        assertEquals(setOf(2024, 2025, 2026), fullYears)
        assertEquals(setOf(2024), olderYears)
        assertEquals(
            setOf(2026),
            buildPhotoTimelineDateIndex(newest.entries)
                .sections
                .mapTo(sortedSetOf()) { section -> section.month.year },
        )
        assertTrue(older.fullGeometry.totalAdvertisedItemCount > older.entries.size)
        assertEquals(newest.entries, rawRetry.entries)
        val olderFraction = requireNotNull(
            older.fullGeometry.fractionFor(PhotoTimelineMonth(2024, 12)),
        )
        val newestFraction = requireNotNull(
            newest.fullGeometry.fractionFor(PhotoTimelineMonth(2026, 7)),
        )
        assertTrue(
            olderFraction > newestFraction,
        )
    }
}
