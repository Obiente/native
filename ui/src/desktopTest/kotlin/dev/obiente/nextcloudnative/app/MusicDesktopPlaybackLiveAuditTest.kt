package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in physical decoder audit. No media bytes, metadata, URL, or credentials are logged. */
class MusicDesktopPlaybackLiveAuditTest {
    @Test
    fun `real saved-session flac reaches playing and supports controls`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_AUDIO_PLAYBACK_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        val app = assertNotNull(server.apps.firstOrNull { it.id == "music" })
        val discovery = discoverDynamicAppDescriptor(services, session, app, server.version)
        assertTrue(discovery.acquisition != DynamicDescriptorAcquisition.MetadataFallback)
        val action = assertNotNull(discovery.descriptor.actions.firstOrNull { candidate ->
            candidate.binding.method == HttpMethod.GET &&
                candidate.intent == ActionIntent.list &&
                candidate.binding.path.trimEnd('/').endsWith("/tracks") &&
                candidate.binding.pathParameters.isEmpty()
        })
        val records = loadDynamicRecords(services, session, discovery.descriptor, action.id)
        val resource = assertNotNull(
            discovery.descriptor.toNativeAppSchema().resources.firstOrNull { it.id == action.resourceId },
        )
        val flacTrack = assertNotNull(
            records.asSequence()
                .mapNotNull { record -> nativeAudioTrack(resource, record) }
                .mapNotNull { track ->
                    track.files.firstOrNull { file -> "flac" in file.mimeType }
                        ?.let { file -> track.copy(files = listOf(file)) }
                }
                .firstOrNull(),
            "The live library did not expose a FLAC representation.",
        )
        val source = assertNotNull(nativeAudioSourceCapability(discovery, action)?.source(flacTrack))
        val engine = DesktopAudioPlaybackEngine()
        try {
            engine.play(session, source)
            val started = withTimeout(LIVE_PLAYBACK_TIMEOUT_MILLIS) {
                engine.state.first { state ->
                    state.status in setOf(NativeAudioEngineStatus.Playing, NativeAudioEngineStatus.Error)
                }
            }
            assertEquals(
                NativeAudioEngineStatus.Playing,
                started.status,
                started.error ?: "The real FLAC source did not reach Playing.",
            )
            val initialPosition = started.positionMillis
            repeat(20) {
                delay(500)
                val state = engine.state.value
                assertEquals(
                    NativeAudioEngineStatus.Playing,
                    state.status,
                    state.error ?: "The real FLAC source stopped before the 10-second stability audit completed.",
                )
            }
            assertTrue(
                engine.state.value.positionMillis > initialPosition + 5_000,
                "The real FLAC source stayed nominally Playing but its position did not progress.",
            )

            engine.pause()
            withTimeout(5_000) {
                engine.state.first { it.status == NativeAudioEngineStatus.Paused }
            }
            engine.resume()
            withTimeout(5_000) {
                engine.state.first { it.status == NativeAudioEngineStatus.Playing }
            }
            engine.seekTo(1_000)
            withTimeout(15_000) {
                engine.state.first { it.status == NativeAudioEngineStatus.Playing && it.positionMillis >= 900 }
            }
            engine.stop()
            assertEquals(NativeAudioEngineStatus.Idle, engine.state.value.status)
        } finally {
            engine.release()
        }
    }

    private companion object {
        const val LIVE_PLAYBACK_TIMEOUT_MILLIS = 180_000L
    }
}
