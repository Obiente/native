package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
    fun `hiding or minimizing the window pauses playback`() {
        val engine = ScriptedEngine(NativeVideoTransportStatus.Playing)
        val window = MutableStateFlow(true)
        nativeSceneTest(800, 600, content = { Surface(engine, window = window) }) {
            settle()
            assertEquals(emptyList(), engine.commands, "A visible window keeps playing.")

            window.value = false
            settleUntil { engine.commands.isNotEmpty() }
            assertEquals(listOf<NativeVideoTransportCommand>(NativeVideoTransportCommand.Pause), engine.commands)
        }
    }

    @Test
    fun `system media controls see the video and drive playback and the viewer`() {
        val engine = ScriptedEngine(NativeVideoTransportStatus.Playing)
        val published = Collections.synchronizedList(mutableListOf<SystemMediaSnapshot>())
        var send: ((SystemMediaCommand) -> Unit)? = null
        var nexts = 0
        val factory: DesktopSystemMediaControlsFactory = { _, onCommand ->
            send = onCommand
            recordingControls(published)
        }
        val media = NativeVideoSystemMedia(title = "clip.mp4", onPrevious = null, onNext = { nexts += 1 })
        nativeSceneTest(800, 600, content = { Surface(engine, systemMedia = media, systemMediaControls = factory) }) {
            settleUntil { published.isNotEmpty() }
            assertEquals(SystemMediaSnapshot("clip.mp4", playing = true, canPrevious = false, canNext = true), published.last())

            val command = requireNotNull(send)
            command(SystemMediaCommand.Play)
            command(SystemMediaCommand.Next)
            command(SystemMediaCommand.Previous)
            command(SystemMediaCommand.TogglePlayPause)
            command(SystemMediaCommand.Stop)
            settleUntil { engine.commands.size >= 3 && nexts == 1 }

            assertEquals(1, nexts, "Next moves the viewer; Previous is unavailable and does nothing.")
            assertEquals(
                listOf<NativeVideoTransportCommand>(
                    NativeVideoTransportCommand.Play,
                    NativeVideoTransportCommand.TogglePlayback,
                    NativeVideoTransportCommand.Pause,
                ),
                engine.commands,
            )
        }
    }

    @Test
    fun `a media key can resume playback while the window stays hidden`() {
        val engine = ScriptedEngine(NativeVideoTransportStatus.Playing)
        var send: ((SystemMediaCommand) -> Unit)? = null
        val factory: DesktopSystemMediaControlsFactory = { _, onCommand ->
            send = onCommand
            recordingControls(Collections.synchronizedList(mutableListOf()))
        }
        val window = MutableStateFlow(false)
        nativeSceneTest(800, 600, content = {
            Surface(engine, window = window, systemMedia = NativeVideoSystemMedia("clip.mp4"), systemMediaControls = factory)
        }) {
            settleUntil { engine.commands.isNotEmpty() && send != null }
            assertEquals(listOf<NativeVideoTransportCommand>(NativeVideoTransportCommand.Pause), engine.commands, "Autoplay while hidden pauses once.")

            requireNotNull(send)(SystemMediaCommand.Play)
            settleUntil { engine.commands.size >= 2 }
            settle()
            assertEquals(
                listOf<NativeVideoTransportCommand>(NativeVideoTransportCommand.Pause, NativeVideoTransportCommand.Play),
                engine.commands,
                "The user's play from the media controls is not paused again.",
            )
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
        window: MutableStateFlow<Boolean> = MutableStateFlow(true),
        systemMedia: NativeVideoSystemMedia? = null,
        systemMediaControls: DesktopSystemMediaControlsFactory = { _, _ -> UnavailableDesktopSystemMediaControls },
    ) = CompositionLocalProvider(LocalAppWindowVisibility provides window) {
        DesktopVideoSurface(
            engine = engine,
            title = "clip.mp4",
            onPlaybackEnded = onEnded,
            onFailure = onFailure,
            onControlsVisibilityChanged = onControls,
            modifier = Modifier.fillMaxSize(),
            controlsHideDelayMillis = hideAfter,
            systemMedia = systemMedia,
            systemMediaControls = systemMediaControls,
        )
    }

    private fun recordingControls(published: MutableList<SystemMediaSnapshot>) = object : DesktopSystemMediaControls {
        override fun update(snapshot: SystemMediaSnapshot) {
            published += snapshot
        }

        override fun close() = Unit
    }

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
