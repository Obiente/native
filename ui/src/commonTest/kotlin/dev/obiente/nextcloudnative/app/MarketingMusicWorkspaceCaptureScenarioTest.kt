package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketingMusicWorkspaceCaptureScenarioTest {
    @Test
    fun `music workspace captures cover compact library and desktop playback recovery`() {
        assertEquals(
            listOf(
                MarketingCaptureScenario.MusicLibraryAlbumTracksMobile,
                MarketingCaptureScenario.MusicLibraryPlaybackErrorDesktop,
            ),
            musicWorkspaceCaptureScenarios,
        )
        assertTrue(marketingCaptureScenarios.containsAll(musicWorkspaceCaptureScenarios))
        assertEquals(
            setOf(NextcloudPresentation.Adaptive, NextcloudPresentation.Desktop),
            musicWorkspaceCaptureScenarios.map(MarketingCaptureScenario::presentation).toSet(),
        )
        assertEquals(setOf(56), musicWorkspaceCaptureScenarios.mapNotNull(MarketingCaptureScenario::issue).toSet())
    }
}
