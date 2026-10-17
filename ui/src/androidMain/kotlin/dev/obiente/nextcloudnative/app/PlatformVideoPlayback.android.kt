package dev.obiente.nextcloudnative.app

import android.util.Base64
import android.content.Intent
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import org.videolan.libvlc.util.VLCVideoLayout

internal actual val platformNativeVideoPlaybackAvailable: Boolean = true

internal actual val platformNativeVideoPlaybackDiscoveryPending: Boolean = false

// Media3 streams DAV over HTTP itself; only LibVLC compatibility playback reads ranges.
internal actual val platformNativeVideoPlaybackUsesRangeSource: Boolean = false

internal actual val platformVideoCompatibilityPlaybackAvailable: Boolean = true

@OptIn(UnstableApi::class)
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
) {
    // Transport controls sit above the gesture navigation area; the picture stays centered.
    val playerModifier = modifier.navigationBarsPadding()
    if (compatibilityPlaybackRequested && rangeSource != null) {
        // Its Play/Pause control never hides, so the viewer chrome Media3 may have hidden returns.
        val currentOnControlsVisibilityChanged by rememberUpdatedState(onControlsVisibilityChanged)
        LaunchedEffect(rangeSource) { currentOnControlsVisibilityChanged(true) }
        LibVlcVideoPlayer(
            source = source,
            rangeSource = rangeSource,
            onPlaybackEnded = onPlaybackEnded,
            onFailure = onFailure,
            modifier = playerModifier,
        )
    } else {
        Media3VideoPlayer(
            session = session,
            userId = userId,
            source = source,
            onPlaybackEnded = onPlaybackEnded,
            onFailure = onFailure,
            onControlsVisibilityChanged = onControlsVisibilityChanged,
            modifier = playerModifier,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3VideoPlayer(
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    onControlsVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnFailure = rememberUpdatedState(onFailure)
    val currentOnControlsVisibilityChanged = rememberUpdatedState(onControlsVisibilityChanged)
    var player by remember(session.serverUrl, session.loginName, userId, source) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(session.serverUrl, session.loginName, userId, source) { mutableStateOf(false) }
    // The player is created, prepared, and started only by a committed effect, whose disposal
    // always releases it; an abandoned composition can never leave decoding or audio running.
    DisposableEffect(session.serverUrl, session.loginName, userId, source) {
        val created = createMedia3VideoPlayer(
            context = context,
            session = session,
            userId = userId,
            source = source,
            onPlaybackEnded = { currentOnPlaybackEnded.value() },
            onFailure = { failure -> currentOnFailure.value(failure) },
            reportIsPlaying = { isPlaying -> playing = isPlaying },
        )
        created.prepare()
        // Opening a video is the request to watch it, as with a Live Photo's motion. A player
        // created after the activity stopped (a lookup finished in the background) waits for
        // the user instead, because the earlier ON_STOP is not replayed to the observer.
        created.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        player = created
        onDispose {
            player = null
            created.release()
        }
    }
    val active = player
    DisposableEffect(active, lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) active?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) }
    }
    if (active == null) {
        Box(modifier)
        return
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = active
                useController = true
                // The viewer owns previous/next media; one-item players would show dead buttons.
                setShowPreviousButton(false)
                setShowNextButton(false)
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        currentOnControlsVisibilityChanged.value(visibility == View.VISIBLE)
                    },
                )
                keepScreenOn = playing
            }
        },
        update = { view ->
            view.player = active
            view.keepScreenOn = playing
        },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

/** Builds an unprepared player for [source]; the caller owns preparing and releasing it. */
@OptIn(UnstableApi::class)
private fun createMedia3VideoPlayer(
    context: android.content.Context,
    session: NextcloudSession,
    userId: String,
    source: NativeVideoPlaybackSource,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    reportIsPlaying: (Boolean) -> Unit,
): ExoPlayer {
    val authorization = Base64.encodeToString(
        "${session.loginName}:${session.appPassword}".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP,
    )
    val client = OkHttpClient.Builder()
        .useAndroidNextcloudCertificateTrust(context.applicationContext)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    val sourceFactory = OkHttpDataSource.Factory(client)
        .setUserAgent("nati.ve")
        .setDefaultRequestProperties(
            source.authenticatedRequestProperties("Basic $authorization"),
        )
    val mediaId = when (source) {
        is NativeVideoPlaybackSource.DavFile ->
            source.file.fileId?.toString() ?: source.file.path
        is NativeVideoPlaybackSource.MemoriesLivePhoto ->
            "live-photo:${source.source.fileId}"
    }
    val uri = when (source) {
        is NativeVideoPlaybackSource.DavFile ->
            buildNextcloudFileUrl(session.serverUrl, userId, source.file.path)
        is NativeVideoPlaybackSource.MemoriesLivePhoto ->
            buildNextcloudApiUrl(session.serverUrl, memoriesLivePhotoVideoRequest(source.source))
    }
    val item = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .apply {
            if (source is NativeVideoPlaybackSource.DavFile) {
                setMimeType(source.file.mimeType)
            }
        }
        .build()
    val renderersFactory = DefaultRenderersFactory(context.applicationContext)
        .setEnableDecoderFallback(true)
    // Autoplay takes audio focus so other media pauses or ducks instead of mixing, and unplugging
    // headphones pauses instead of moving the sound to the speaker.
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    return ExoPlayer.Builder(context.applicationContext, renderersFactory)
        .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
        addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    onFailure(error.toNativeVideoPlaybackFailure())
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    reportIsPlaying(isPlaying)
                }
            },
        )
        setMediaSource(ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(item))
    }
}

@Composable
private fun LibVlcVideoPlayer(
    source: NativeVideoPlaybackSource,
    rangeSource: NativeVideoRangeSource,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnFailure = rememberUpdatedState(onFailure)
    val request = remember(source, rangeSource) {
        AndroidCompatibilityVideoPlaybackBridge.createRequest(source, rangeSource)
    }
    val state by AndroidCompatibilityVideoPlaybackBridge.state.collectAsState()
    val surfaceController by AndroidCompatibilityVideoPlaybackBridge.surfaceController.collectAsState()
    val currentState = state.takeIf { it.requestId == request.requestId }
    val currentSurfaceController = surfaceController
        ?.takeIf { it.requestId == request.requestId }

    LaunchedEffect(request) {
        AndroidCompatibilityVideoPlaybackBridge.submit(request)
        context.startService(
            Intent(context, AndroidCompatibilityVideoPlaybackService::class.java)
                .setAction(AndroidCompatibilityVideoPlaybackService.ACTION_OPEN)
                .putExtra(
                    AndroidCompatibilityVideoPlaybackService.EXTRA_REQUEST_ID,
                    request.requestId,
                ),
        )
    }
    LaunchedEffect(currentState?.status, currentState?.failure) {
        when (currentState?.status) {
            AndroidCompatibilityVideoPlaybackStatus.Ended ->
                currentOnPlaybackEnded.value()
            AndroidCompatibilityVideoPlaybackStatus.Error ->
                currentOnFailure.value(
                    currentState.failure ?: NativeVideoPlaybackFailure.Unknown,
                )
            AndroidCompatibilityVideoPlaybackStatus.Loading,
            AndroidCompatibilityVideoPlaybackStatus.Playing,
            AndroidCompatibilityVideoPlaybackStatus.Paused,
            null,
            -> Unit
        }
    }
    DisposableEffect(request) {
        onDispose {
            AndroidCompatibilityVideoPlaybackBridge.close(request.requestId)
        }
    }
    DisposableEffect(request, lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                AndroidCompatibilityVideoPlaybackBridge.pause(request.requestId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    if (currentSurfaceController == null) {
        Box(modifier) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    Box(modifier) {
        AndroidView(
            factory = { viewContext ->
                VLCVideoLayout(viewContext).also { layout ->
                    currentSurfaceController.attach(layout)
                }
            },
            update = { layout ->
                currentSurfaceController.attach(layout)
                layout.keepScreenOn =
                    currentState?.status == AndroidCompatibilityVideoPlaybackStatus.Playing
            },
            onRelease = { layout -> currentSurfaceController.detach(layout) },
            modifier = Modifier.matchParentSize(),
        )
        Button(
            onClick = {
                if (currentState?.status == AndroidCompatibilityVideoPlaybackStatus.Playing) {
                    AndroidCompatibilityVideoPlaybackBridge.pause(request.requestId)
                } else {
                    AndroidCompatibilityVideoPlaybackBridge.play(request.requestId)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
        ) {
            Text(
                if (currentState?.status == AndroidCompatibilityVideoPlaybackStatus.Playing) {
                    "Pause"
                } else {
                    "Play"
                },
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun PlaybackException.toNativeVideoPlaybackFailure(): NativeVideoPlaybackFailure {
    val summary = (this as? ExoPlaybackException)?.rendererFormat?.let { format ->
        NativeVideoFormatSummary(
            mimeType = format.sampleMimeType,
            codec = format.codecs?.take(80),
            width = format.width.takeIf { it > 0 },
            height = format.height.takeIf { it > 0 },
            frameRate = format.frameRate.takeIf { it > 0f },
        )
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            NativeVideoPlaybackFailure.FormatExceedsCapabilities(summary)
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            NativeVideoPlaybackFailure.FormatUnsupported(summary)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            NativeVideoPlaybackFailure.DecoderInitializationFailed(summary)
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            NativeVideoPlaybackFailure.DecodeFailed(summary)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> NativeVideoPlaybackFailure.NetworkUnavailable
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            nativeVideoPlaybackFailureForHttpStatus(httpResponseCodeOrNull() ?: -1)
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            NativeVideoPlaybackFailure.SourceChanged
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> NativeVideoPlaybackFailure.MalformedMedia
        else -> NativeVideoPlaybackFailure.Unknown
    }
}

private fun PlaybackException.httpResponseCodeOrNull(): Int? {
    var current: Throwable? = this
    repeat(MAXIMUM_PLAYBACK_CAUSE_DEPTH) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return current.responseCode
        }
        current = current?.cause
    }
    return null
}

private const val MAXIMUM_PLAYBACK_CAUSE_DEPTH = 8
