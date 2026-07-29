package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoMediaReviewCaptureScenarioTest {
    @Test
    fun `photo media review captures stay registered and linked to the active pull request`() {
        assertTrue(marketingCaptureScenarios.containsAll(photoMediaReviewCaptureScenarios))
        assertEquals(
            setOf(
                MarketingCaptureScenario.LivePhotoMotionFailureMobile,
                MarketingCaptureScenario.NativeTiffPreviewMobile,
            ),
            photoMediaReviewCaptureScenarios.toSet(),
        )
        assertTrue(photoMediaReviewCaptureScenarios.all { scenario -> scenario.pullRequest == 249 })
        assertEquals(
            setOf(182, 84),
            photoMediaReviewCaptureScenarios.mapNotNull { scenario -> scenario.issue }.toSet(),
        )
    }
}
