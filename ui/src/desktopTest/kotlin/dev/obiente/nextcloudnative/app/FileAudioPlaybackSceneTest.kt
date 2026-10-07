package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import kotlin.test.*

class FileAudioPlaybackSceneTest {
    @Test
    fun playbackIsExplicitLabeledAndDisposedWithoutLeakingAnotherSourcesState() {
        val account = NextcloudSession("https://fixture.invalid", "synthetic-user", "synthetic-password")
        val source = assertNotNull(fileAudioPlaybackSource(account, "synthetic-user",
            NextcloudFile("tone.mp3", "Synthetic tone", false, "audio/mpeg", 12000, null, 7, false)))
        val engine = FileAudioTestEngine()
        val visible = mutableStateOf(true)
        nativeSceneTest(390, 844, content = {
            if (visible.value) FileAudioPlayback(account, source, engine) else Text("Closed")
        }) {
            assertTrue(has("Play"))
            assertEquals(0, engine.plays)
            engine.state.value = NativeAudioEngineState("other-source", NativeAudioEngineStatus.Playing)
            settle()
            assertTrue(has("Play"))
            assertFalse(has("Pause"))
            click("Play")
            assertEquals(1, engine.plays)
            engine.state.value = NativeAudioEngineState(source.id, NativeAudioEngineStatus.Playing, 100, 3000)
            settle()
            assertTrue(has("Pause"))
            click("Pause")
            assertEquals(1, engine.pauses)
            visible.value = false
            settle()
            assertEquals(1, engine.stops)
        }
    }
}
