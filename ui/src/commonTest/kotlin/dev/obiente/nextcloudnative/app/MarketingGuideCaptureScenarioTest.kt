package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketingGuideCaptureScenarioTest {
    @Test
    fun `every guide step owns a real dark and light Compose capture`() {
        val guideScenarios = MarketingCaptureScenario.entries.filter { it.feature == "Guides" }
        val guideVariants = marketingCaptureVariants.filter { it.scenario.feature == "Guides" }

        assertEquals(30, guideScenarios.size)
        assertEquals(guideScenarios.size * MarketingCaptureTheme.entries.size, guideVariants.size)
        val directlyRenderedGuides = setOf(
            MarketingCaptureScenario.GuideAndroidFolderSyncLocations,
            MarketingCaptureScenario.GuideAndroidFolderSyncRules,
            MarketingCaptureScenario.GuideLinuxFolderSyncLocations,
            MarketingCaptureScenario.GuideLinuxFolderSyncRules,
            MarketingCaptureScenario.GuideAndroidCalendarEdit,
            MarketingCaptureScenario.GuideAndroidOfflineFilesTransfers,
            MarketingCaptureScenario.GuideWindowsCloudFilesSettings,
            MarketingCaptureScenario.GuideAndroidPhotoBackupLibrary,
        )
        guideScenarios.forEach { scenario ->
            if (scenario in directlyRenderedGuides) {
                assertEquals(null, scenario.guideCaptureSourceScenarioOrNull())
            } else {
                assertNotNull(scenario.guideCaptureSourceScenarioOrNull())
            }
            val pair = guideVariants.filter { it.baseScenario == scenario.id }
            assertEquals(setOf(MarketingCaptureTheme.Dark, MarketingCaptureTheme.Light), pair.map { it.theme }.toSet())
            assertTrue(pair.all { it.scenario.purpose == MarketingCapturePurpose.Showcase })
        }
    }

    @Test
    fun `ordinary marketing scenarios never resolve as guide aliases`() {
        MarketingCaptureScenario.entries
            .filterNot { it.feature == "Guides" }
            .forEach { scenario ->
                assertEquals(null, scenario.guideCaptureSourceScenarioOrNull(), scenario.id)
            }
    }

    @Test
    fun `guide library entry point is secure and canonical`() {
        assertEquals("https://nc-native.obiente.dev/guides/", NEXTCLOUD_NATIVE_GUIDES_URL)
    }
}
