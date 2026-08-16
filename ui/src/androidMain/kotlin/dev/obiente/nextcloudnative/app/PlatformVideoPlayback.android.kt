package dev.obiente.nextcloudnative.app

import android.util.Base64
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
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
) {
    if (compatibilityPlaybackRequested && rangeSource != null) {
        LibVlcVideoPlayer(
            source = source,
            rangeSource = rangeSource,
            onPlaybackEnded = onPlaybackEnded,
            onFailure = onFailure,
            modifier = modifier,
        )
    } else {
        Media3VideoPlayer(
            session = session,
            userId = userId,
            source = source,
            onPlaybackEnded = onPlaybackEnded,
            onFailure = onFailure,
            modifier = modifier,
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
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnFailure = rememberUpdatedState(onFailure)
    val player = remember(session.serverUrl, session.loginName, userId, source) {
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
            .setUserAgent("Nextcloud Native")
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
        ExoPlayer.Builder(context.applicationContext, renderersFactory).build().apply {
            addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            currentOnPlaybackEnded.value()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        currentOnFailure.value(error.toNativeVideoPlaybackFailure())
                    }
                },
            )
            setMediaSource(ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(item))
            prepare()
            playWhenReady = source is NativeVideoPlaybackSource.MemoriesLivePhoto
        }
    }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player, lifecycleOwner) {
        val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
            }
        }
        player.addListener(playerListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(playerListener)
            player.release()
        }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                keepScreenOn = playing
            }
        },
        update = { view ->
            view.player = player
            view.keepScreenOn = playing
        },
        modifier = modifier,
    )
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
