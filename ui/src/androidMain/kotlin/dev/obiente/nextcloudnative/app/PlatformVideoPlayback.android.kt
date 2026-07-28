package dev.obiente.nextcloudnative.app

import android.util.Base64
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    rangeSource: NativeVideoRangeSource,
    onPlaybackEnded: () -> Unit,
    onFailure: (NativeVideoPlaybackFailure) -> Unit,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnFailure = rememberUpdatedState(onFailure)
    var playback by remember(rangeSource) {
        mutableStateOf<LibVlcRangePlaybackResources?>(null)
    }
    var initializationFailed by remember(rangeSource) { mutableStateOf(false) }
    var playing by remember(rangeSource) { mutableStateOf(false) }
    LaunchedEffect(rangeSource) {
        val result = withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                LibVlcRangePlaybackResources.create(
                    context = context.applicationContext,
                    rangeSource = rangeSource,
                    onPlayingChanged = { playing = it },
                    onPlaybackEnded = {
                        playing = false
                        currentOnPlaybackEnded.value()
                    },
                    onFailure = {
                        playing = false
                        currentOnFailure.value(NativeVideoPlaybackFailure.Unknown)
                    },
                )
            }
        }
        if (isActive) {
            playback = result.getOrNull()
            initializationFailed = result.isFailure
        } else {
            result.getOrNull()?.closeAsync()
        }
    }
    val currentPlayback = playback
    DisposableEffect(currentPlayback) {
        onDispose {
            currentPlayback?.closeAsync()
        }
    }
    DisposableEffect(currentPlayback, lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                currentPlayback?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
    if (currentPlayback == null) {
        if (initializationFailed) {
            LaunchedEffect(rangeSource) {
                currentOnFailure.value(
                    NativeVideoPlaybackFailure.DecoderInitializationFailed(format = null),
                )
            }
        } else {
            Box(modifier) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        return
    }
    Box(modifier) {
        AndroidView(
            factory = { viewContext ->
                VLCVideoLayout(viewContext).also { layout ->
                    currentPlayback.attach(layout)
                    currentPlayback.play()
                }
            },
            update = { layout ->
                currentPlayback.attach(layout)
                layout.keepScreenOn = playing
            },
            onRelease = { layout -> currentPlayback.detach(layout) },
            modifier = Modifier.matchParentSize(),
        )
        Button(
            onClick = {
                if (playing) {
                    currentPlayback.pause()
                } else {
                    currentPlayback.play()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
        ) {
            Text(if (playing) "Pause" else "Play")
        }
    }
}

private class LibVlcRangePlaybackResources private constructor(
    private val callbackThread: HandlerThread,
    private val fileDescriptor: ParcelFileDescriptor,
    private val libVlc: LibVLC,
    private val media: Media,
    private val player: MediaPlayer,
    private val rangeSource: NativeVideoRangeSource,
    private val closed: AtomicBoolean,
) {
    private var attachedLayout: VLCVideoLayout? = null

    fun attach(layout: VLCVideoLayout) {
        if (closed.get() || attachedLayout === layout) return
        if (attachedLayout != null) {
            player.detachViews()
        }
        player.attachViews(layout, null, true, false)
        attachedLayout = layout
    }

    fun detach(layout: VLCVideoLayout) {
        if (attachedLayout !== layout) return
        player.detachViews()
        attachedLayout = null
    }

    fun play() {
        if (!closed.get()) player.play()
    }

    fun pause() {
        if (!closed.get() && player.isPlaying) player.pause()
    }

    fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        rangeSource.close()
        attachedLayout?.let {
            player.detachViews()
            attachedLayout = null
        }
        Thread(
            {
                releaseResources(
                    callbackThread = callbackThread,
                    fileDescriptor = fileDescriptor,
                    libVlc = libVlc,
                    media = media,
                    player = player,
                    rangeSource = rangeSource,
                )
            },
            "nc-native-video-release",
        ).apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        fun create(
            context: android.content.Context,
            rangeSource: NativeVideoRangeSource,
            onPlayingChanged: (Boolean) -> Unit,
            onPlaybackEnded: () -> Unit,
            onFailure: () -> Unit,
        ): LibVlcRangePlaybackResources {
            val closed = AtomicBoolean(false)
            val callbackThread = HandlerThread("nc-native-video-range").apply { start() }
            var fileDescriptor: ParcelFileDescriptor? = null
            var libVlc: LibVLC? = null
            var media: Media? = null
            var player: MediaPlayer? = null
            try {
                val rangeCache = NativeVideoRangeCache(rangeSource)
                fileDescriptor = context.getSystemService(StorageManager::class.java)
                    .openProxyFileDescriptor(
                        ParcelFileDescriptor.MODE_READ_ONLY,
                        object : ProxyFileDescriptorCallback() {
                            override fun onGetSize(): Long = rangeSource.size

                            override fun onRead(
                                offset: Long,
                                size: Int,
                                data: ByteArray,
                            ): Int {
                                if (closed.get()) {
                                    throw ErrnoException("read", OsConstants.EBADF)
                                }
                                if (offset < 0L || size < 0) {
                                    throw ErrnoException("read", OsConstants.EINVAL)
                                }
                                if (offset >= rangeSource.size || size == 0) return 0
                                val requested = minOf(
                                    size.toLong(),
                                    rangeSource.size - offset,
                                ).toInt()
                                val bytes = try {
                                    runBlocking { rangeCache.read(offset, requested) }
                                } catch (failure: Exception) {
                                    throw ErrnoException("read", OsConstants.EIO, failure)
                                }
                                if (bytes.size != requested) {
                                    throw ErrnoException("read", OsConstants.EIO)
                                }
                                bytes.copyInto(data, endIndex = requested)
                                return requested
                            }

                            override fun onRelease() {
                                rangeSource.close()
                            }
                        },
                        Handler(callbackThread.looper),
                    )
                libVlc = LibVLC(context, arrayListOf())
                media = Media(libVlc, fileDescriptor.fileDescriptor).apply {
                    setHWDecoderEnabled(false, false)
                }
                player = MediaPlayer(media).apply {
                    setEventListener { event ->
                        when (event.type) {
                            MediaPlayer.Event.Playing -> onPlayingChanged(true)
                            MediaPlayer.Event.Paused,
                            MediaPlayer.Event.Stopped,
                            -> onPlayingChanged(false)
                            MediaPlayer.Event.EndReached -> onPlaybackEnded()
                            MediaPlayer.Event.EncounteredError -> onFailure()
                        }
                    }
                }
                return LibVlcRangePlaybackResources(
                    callbackThread = callbackThread,
                    fileDescriptor = fileDescriptor,
                    libVlc = libVlc,
                    media = media,
                    player = player,
                    rangeSource = rangeSource,
                    closed = closed,
                )
            } catch (failure: Exception) {
                closed.set(true)
                rangeSource.close()
                releaseResources(
                    callbackThread = callbackThread,
                    fileDescriptor = fileDescriptor,
                    libVlc = libVlc,
                    media = media,
                    player = player,
                    rangeSource = rangeSource,
                )
                throw failure
            }
        }

        private fun releaseResources(
            callbackThread: HandlerThread,
            fileDescriptor: ParcelFileDescriptor?,
            libVlc: LibVLC?,
            media: Media?,
            player: MediaPlayer?,
            rangeSource: NativeVideoRangeSource,
        ) {
            rangeSource.close()
            runCatching { player?.stop() }
            runCatching { player?.release() }
            runCatching { media?.release() }
            runCatching { libVlc?.release() }
            runCatching { fileDescriptor?.close() }
            callbackThread.quitSafely()
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
