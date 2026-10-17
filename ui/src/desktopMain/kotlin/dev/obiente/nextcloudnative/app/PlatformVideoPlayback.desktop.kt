package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

// Snapshot state written by the discovery thread: composition never loads native libraries,
// and the viewer recomposes once libVLC has been found.
internal actual val platformNativeVideoPlaybackAvailable: Boolean
    get() = sharedDesktopLibVlcRuntime.availableOrStartDiscovery()

internal actual val platformNativeVideoPlaybackDiscoveryPending: Boolean
    get() = sharedDesktopLibVlcRuntime.discoveryPending()

internal actual val platformNativeVideoPlaybackUsesRangeSource: Boolean = true

// libVLC already decodes in software when hardware decoding fails; there is no second player.
internal actual val platformVideoCompatibilityPlaybackAvailable: Boolean = false

@Composable
internal actual fun PlatformNativeVideoPlayer(
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    rangeSource: NativeVideoRangeSource?,
    compatibilityPlaybackRequested: Boolean,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    modifier: Modifier,
    onControlsVisibilityChanged: (Boolean) -> Unit,
    systemMedia: NativeVideoSystemMedia,
) {
    val currentOnFailure by rememberUpdatedState(onFailure)
    if (rangeSource == null) {
        // Without a size- and ETag-pinned source there is nothing safe to stream.
        LaunchedEffect(source) { currentOnFailure(NativeVideoPlaybackFailure.SourceChanged) }
        Box(modifier.background(Color.Black))
        return
    }
    val engine = remember(source, rangeSource) { DesktopVlcVideoEngine(rangeSource) }
    DesktopVideoSurface(
        engine = engine,
        title = when (source) {
            is NativeVideoPlaybackSource.DavFile -> source.file.name
            is NativeVideoPlaybackSource.MemoriesLivePhoto -> "Live Photo motion"
        },
        onPlaybackEnded = onPlaybackEnded,
        onFailure = onFailure,
        onControlsVisibilityChanged = onControlsVisibilityChanged,
        modifier = modifier,
        // A Live Photo's motion is too short to publish to the system media controls.
        systemMedia = systemMedia.takeIf { source is NativeVideoPlaybackSource.DavFile },
    )
}

/**
 * Draws [engine] frames with transport controls that hide while playing and return on pointer
 * movement, a click, or a key. A click toggles playback as in other desktop players.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopVideoSurface(
    engine: DesktopVideoEngine,
    title: String,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    onControlsVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    controlsHideDelayMillis: Long = DESKTOP_VIDEO_CONTROLS_HIDE_DELAY_MILLIS,
    systemMedia: NativeVideoSystemMedia? = null,
    systemMediaControls: DesktopSystemMediaControlsFactory = platformDesktopSystemMediaControls,
) {
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentOnControlsVisibilityChanged by rememberUpdatedState(onControlsVisibilityChanged)
    DisposableEffect(engine) {
        engine.start()
        onDispose(engine::release)
    }
    LaunchedEffect(engine) {
        engine.outcomes.collect { outcome ->
            when (outcome) {
                DesktopVideoOutcome.Ended -> currentOnPlaybackEnded()
                is DesktopVideoOutcome.Failed -> currentOnFailure(outcome.failure)
            }
        }
    }
    val state by engine.state.collectAsState()
    val windowVisible by LocalAppWindowVisibility.current.collectAsState()
    var interaction by remember(engine) { mutableIntStateOf(0) }
    var controlsVisible by remember(engine) { mutableStateOf(true) }
    val playing = state.status == NativeVideoTransportStatus.Playing
    // Hiding or minimizing the window keeps this composition alive, so playback pauses then, and
    // playback that starts on its own while hidden pauses once. A later play from the system media
    // controls or a media key is the user's choice and keeps playing in the background.
    var previouslyVisible by remember(engine) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(engine, windowVisible) {
        val becameHidden = previouslyVisible == true && !windowVisible
        previouslyVisible = windowVisible
        if (becameHidden && engine.state.value.status == NativeVideoTransportStatus.Playing) {
            engine.command(NativeVideoTransportCommand.Pause)
        }
    }
    var autoplayChecked by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, playing) {
        if (playing && !autoplayChecked) {
            autoplayChecked = true
            if (!windowVisible) engine.command(NativeVideoTransportCommand.Pause)
        }
    }
    DesktopSystemMediaEffect(engine, state, systemMedia, systemMediaControls)
    LaunchedEffect(engine, playing, interaction) {
        controlsVisible = true
        if (playing) {
            delay(controlsHideDelayMillis)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) { currentOnControlsVisibilityChanged(controlsVisible) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(engine) { focusRequester.requestFocus() }
    fun send(command: NativeVideoTransportCommand) {
        interaction += 1
        engine.command(command)
    }
    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag(DESKTOP_VIDEO_SURFACE_TEST_TAG)
            .semantics {
                contentDescription = "Video: $title"
                stateDescription = state.accessibleDescription()
            }
            .drawBehind {
                // Reading the version here redraws on each frame without recomposing.
                engine.frames.frameVersion.longValue
                drawLatestFrame(engine.frames)
            }
            .onPointerEvent(PointerEventType.Enter) { interaction += 1 }
            .onPointerEvent(PointerEventType.Move) { interaction += 1 }
            .pointerInput(engine) {
                detectTapGestures(onTap = { send(NativeVideoTransportCommand.TogglePlayback) })
            }
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val command = nativeVideoTransportCommandForKey(event.key) ?: return@onKeyEvent false
                send(command)
                true
            }
            .focusable(),
    ) {
        NativeVideoTransportOverlay(state = state, visible = controlsVisible, onCommand = ::send)
    }
}

private fun DrawScope.drawLatestFrame(frames: DesktopVideoFrameBuffer) {
    frames.withLatestFrame { image, geometry ->
        drawIntoCanvas { canvas ->
            drawDesktopVideoFrame(canvas.nativeCanvas, image, geometry, size.width, size.height)
        }
    }
}

private fun NativeVideoTransportState.accessibleDescription(): String = buildString {
    append(
        when (status) {
            NativeVideoTransportStatus.Loading -> "Loading"
            NativeVideoTransportStatus.Playing -> "Playing"
            NativeVideoTransportStatus.Paused -> "Paused"
            NativeVideoTransportStatus.Ended -> "Ended"
        },
    )
    append(", ").append(formatVideoTimestamp(positionMillis))
    durationMillis?.let { append(" of ").append(formatVideoTimestamp(it)) }
    append(". Space plays or pauses, J and L seek 10 seconds, M mutes.")
}

internal const val DESKTOP_VIDEO_SURFACE_TEST_TAG = "desktop-video-surface"
private const val DESKTOP_VIDEO_CONTROLS_HIDE_DELAY_MILLIS = 3_000L

/**
 * Publishes the playing video to the operating system's media controls and routes their
 * commands back on the UI thread: play and pause to [engine], previous and next to the viewer.
 */
@Composable
private fun DesktopSystemMediaEffect(
    engine: DesktopVideoEngine,
    state: NativeVideoTransportState,
    systemMedia: NativeVideoSystemMedia?,
    factory: DesktopSystemMediaControlsFactory,
) {
    if (systemMedia == null) return
    val window = LocalDesktopWindow.current
    val currentSystemMedia by rememberUpdatedState(systemMedia)
    val commands = remember(engine) { Channel<SystemMediaCommand>(capacity = Channel.BUFFERED) }
    var session by remember(engine) { mutableStateOf<DesktopSystemMediaSession?>(null) }
    DisposableEffect(engine, window, factory) {
        val opened = DesktopSystemMediaSession(window, factory) { command -> commands.trySend(command) }
        session = opened
        onDispose {
            session = null
            opened.close()
        }
    }
    LaunchedEffect(engine) {
        for (command in commands) {
            when (command) {
                SystemMediaCommand.Play -> engine.command(NativeVideoTransportCommand.Play)
                SystemMediaCommand.Pause, SystemMediaCommand.Stop -> engine.command(NativeVideoTransportCommand.Pause)
                SystemMediaCommand.TogglePlayPause -> engine.command(NativeVideoTransportCommand.TogglePlayback)
                SystemMediaCommand.Next -> currentSystemMedia.onNext?.invoke()
                SystemMediaCommand.Previous -> currentSystemMedia.onPrevious?.invoke()
            }
        }
    }
    val snapshot = SystemMediaSnapshot(
        title = systemMedia.title,
        playing = state.status == NativeVideoTransportStatus.Playing,
        canPrevious = systemMedia.onPrevious != null,
        canNext = systemMedia.onNext != null,
    )
    LaunchedEffect(session, snapshot) { session?.update(snapshot) }
}
