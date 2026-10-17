package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** The desktop video surface against a scripted engine; libVLC itself is covered separately. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class DesktopVideoSurfaceSceneTest {
    @Test
    fun `controls send typed commands from clicks, buttons, and keys`() {
        val engine = ScriptedEngine(NativeVideoTransportStatus.Paused)
        nativeSceneTest(800, 600, content = { Surface(engine) }) {
            assertEquals(listOf("start"), engine.calls)
            // Keys first: the surface holds focus until a control button is clicked.
            scene.sendKeyEvent(keyDown(Key.Spacebar))
            scene.sendKeyEvent(keyDown(Key.L))
            scene.sendKeyEvent(keyDown(Key.J))
            scene.sendKeyEvent(keyDown(Key.M))
            click(Offset(400f, 200f))
            click("Play")
            settleUntil { engine.commands.size >= 6 }

            assertEquals(
                listOf(
                    NativeVideoTransportCommand.TogglePlayback,
                    NativeVideoTransportCommand.SeekBy(10_000L),
                    NativeVideoTransportCommand.SeekBy(-10_000L),
                    NativeVideoTransportCommand.ToggleMute,
                    NativeVideoTransportCommand.TogglePlayback,
                    NativeVideoTransportCommand.TogglePlayback,
                ),
                engine.commands,
            )
            assertTrue(has("Playback position") && has("Volume") && has("Mute"))
        }
    }

    @Test
    fun `controls hide while playing and return on pointer movement`() {
        val engine = ScriptedEngine(NativeVideoTransportStatus.Playing)
        val visibility = Collections.synchronizedList(mutableListOf<Boolean>())
        nativeSceneTest(800, 600, content = { Surface(engine, onControls = { visibility += it }, hideAfter = 1_000L) }) {
            assertTrue(has("Pause"))
            settleUntil(timeoutMillis = 4_000L) { !has("Pause") }
            assertTrue(!has("Pause") && !has("Playback position"))
            assertEquals(false, visibility.last(), "The viewer chrome follows the hidden controls.")

            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 300f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(310f, 300f))
            settleUntil { has("Pause") }
            assertEquals(true, visibility.last())
        }
    }

    @Test
    fun `outcomes reach the viewer and leaving releases the engine`() {
        val engine = ScriptedEngine(NativeVideoTransportStatus.Playing)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var shown by mutableStateOf(true)
        nativeSceneTest(800, 600, content = {
            if (shown) {
                Surface(engine, onEnded = { events += "ended" }, onFailure = { events += "failed:$it" })
            } else {
                Text("closed")
            }
        }) {
            engine.outcomes.emit(DesktopVideoOutcome.Ended)
            engine.outcomes.emit(DesktopVideoOutcome.Failed(NativeVideoPlaybackFailure.NetworkUnavailable))
            settleUntil { events.size == 2 }
            assertEquals(listOf("ended", "failed:NetworkUnavailable"), events)

            shown = false
            settleUntil { has("closed") }
            assertEquals(listOf("start", "release"), engine.calls)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Surface(
        engine: ScriptedEngine,
        onEnded: () -> Unit = {},
        onFailure: (NativeVideoPlaybackFailure) -> Unit = {},
        onControls: (Boolean) -> Unit = {},
        hideAfter: Long = 300L,
    ) = DesktopVideoSurface(
        engine = engine,
        title = "clip.mp4",
        onPlaybackEnded = onEnded,
        onFailure = onFailure,
        onControlsVisibilityChanged = onControls,
        modifier = Modifier.fillMaxSize(),
        controlsHideDelayMillis = hideAfter,
    )

    private fun keyDown(key: Key) = KeyEvent(key, KeyEventType.KeyDown)

    private class ScriptedEngine(status: NativeVideoTransportStatus) : DesktopVideoEngine {
        override val state = MutableStateFlow(
            NativeVideoTransportState(status = status, durationMillis = 60_000L, seekable = true),
        )
        override val outcomes = MutableSharedFlow<DesktopVideoOutcome>(extraBufferCapacity = 4)
        override val frames = DesktopVideoFrameBuffer()
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val commands: MutableList<NativeVideoTransportCommand> = Collections.synchronizedList(mutableListOf())

        override fun start() {
            calls += "start"
        }

        override fun command(command: NativeVideoTransportCommand) {
            commands += command
        }

        override fun release() {
            calls += "release"
        }
    }
}
