package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityVideoPlaybackStartGateTest {
    @Test
    fun autoplayWaitsForSurfaceAttachment() {
        val gate = CompatibilityVideoPlaybackStartGate()

        gate.beginAutoplay()

        assertTrue(gate.playWhenReady)
        assertTrue(gate.attachSurface())
    }

    @Test
    fun pauseBeforeSurfaceAttachmentPreventsLateAutoplay() {
        val gate = CompatibilityVideoPlaybackStartGate()

        gate.beginAutoplay()
        assertFalse(gate.updatePlayWhenReady(false))

        assertFalse(gate.attachSurface())
        assertFalse(gate.playWhenReady)
    }

    @Test
    fun playAfterSurfaceAttachmentStartsImmediately() {
        val gate = CompatibilityVideoPlaybackStartGate()

        gate.beginAutoplay()
        gate.updatePlayWhenReady(false)
        gate.attachSurface()

        assertTrue(gate.updatePlayWhenReady(true))
    }

    @Test
    fun detachedSurfaceDefersRequestedPlaybackUntilReattachment() {
        val gate = CompatibilityVideoPlaybackStartGate()

        gate.beginAutoplay()
        gate.attachSurface()
        gate.detachSurface()

        assertFalse(gate.updatePlayWhenReady(true))
        assertTrue(gate.attachSurface())
    }
}
