package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawMarketingCaptureScenarioTest {
    @Test
    fun rawCaptureIdsAndFilesRemainStableForPullRequest218() {
        assertEquals(
            listOf(
                "raw-preview-loading-mobile",
                "raw-preview-error-mobile",
                "raw-preview-memories-ready-mobile",
                "raw-preview-high-detail-desktop",
            ),
            rawPreviewCaptureScenarios.map(MarketingCaptureScenario::id),
        )
        assertEquals(
            rawPreviewCaptureScenarios.map { "${it.id}.png" },
            rawPreviewCaptureScenarios.map(MarketingCaptureScenario::fileName),
        )
        assertTrue(marketingCaptureScenarios.containsAll(rawPreviewCaptureScenarios))
    }

    @Test
    fun rawCaptureFormFactorsKeepCompactAndDesktopStatesDistinct() {
        assertEquals(
            listOf(
                NextcloudPresentation.Adaptive,
                NextcloudPresentation.Adaptive,
                NextcloudPresentation.Adaptive,
                NextcloudPresentation.Desktop,
            ),
            rawPreviewCaptureScenarios.map(MarketingCaptureScenario::presentation),
        )
        assertEquals(
            listOf(
                1_080 to 1_200,
                1_080 to 1_200,
                1_080 to 1_600,
                1_440 to 900,
            ),
            rawPreviewCaptureScenarios.map { it.width to it.height },
        )
    }

    @Test
    fun readinessDescriptionsAreTruthfulAboutRenderedData() {
        assertEquals("Loading rendered preview", MediaViewerReadiness.Loading.description)
        assertEquals("Rendered preview unavailable", MediaViewerReadiness.RenderUnavailable.description)
        assertEquals("Rendered preview ready", MediaViewerReadiness.RenderReady.description)
        assertEquals("Loading high-detail render", MediaViewerReadiness.HighDetailLoading.description)
        assertEquals("High-detail render ready", MediaViewerReadiness.HighDetailReady.description)

        MediaViewerReadiness.entries.forEach { readiness ->
            val words = readiness.description.lowercase()
            assertTrue("original" !in words)
            assertTrue("full quality" !in words)
            assertTrue("full-resolution" !in words)
        }
    }
}
