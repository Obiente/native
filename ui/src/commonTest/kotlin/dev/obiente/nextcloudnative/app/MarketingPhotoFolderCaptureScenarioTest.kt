package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketingPhotoFolderCaptureScenarioTest {
    @Test
    fun `photo folder capture registry covers mobile and desktop`() {
        assertEquals(
            setOf("mobile", "desktop"),
            photoFolderCaptureScenarios.map(MarketingCaptureScenario::platform).toSet(),
        )
        assertTrue(marketingCaptureScenarios.containsAll(photoFolderCaptureScenarios))
        photoFolderCaptureScenarios.forEach { scenario ->
            assertEquals("Photos", scenario.feature)
            assertEquals("Folder browser", scenario.surface)
            assertEquals(245, scenario.pullRequest)
            assertEquals(243, scenario.issue)
        }
    }
}
