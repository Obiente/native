package dev.obiente.nextcloudnative.app

import android.content.Intent
import android.net.Uri
import java.io.IOException
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient

internal object AndroidAudioPlaybackBridge {
    private val mutableState = MutableStateFlow(NativeAudioEngineState())
    val state: StateFlow<NativeAudioEngineState> = mutableState

    @Volatile
    var pendingRequest: AndroidAudioPlaybackRequest? = null

    private val pendingRetirement = AccountPlaybackRetirementOwner()

    fun submit(session: NextcloudSession, sources: List<NativeAudioPlaybackSource>, currentIndex: Int): Boolean {
        val producer = sharedAccountPrivateMemoryGate.producer(previewCacheDigest(session)) ?: return false
        val request = AndroidAudioPlaybackRequest(session, sources, currentIndex, producer)
        return pendingRetirement.bind(producer,
            onRetired = { if (pendingRequest === request) pendingRequest = null },
            publish = { pendingRequest = request })
    }

    fun clearPending(expected: AndroidAudioPlaybackRequest? = null) = sharedAccountPrivateMemoryGate.withLock {
        if (expected == null || pendingRequest === expected) {
            pendingRetirement.clear()
            pendingRequest = null
        }
    }

    fun update(value: NativeAudioEngineState) {
        mutableState.value = value
    }
}

/**
 * Android's long-lived authenticated music player.
 *
 * MediaSessionService supplies the system media notification, lock-screen controls, Bluetooth and
 * headset commands. Credentials stay in process memory and are attached by the private data-source
 * factory; they are never placed in a MediaItem URI, Intent or notification.
 */
@OptIn(UnstableApi::class)
class AndroidAudioPlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var httpClient: OkHttpClient
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var positionJob: Job? = null
    private var preparationJob: Job? = null
    @Volatile private var preparedReads: Call.Factory? = null
    @Volatile private var activeRequest: AndroidAudioPlaybackRequest? = null
    private val playbackRetirement = AccountPlaybackRetirementOwner()

    override fun onCreate() {
        super.onCreate()
        httpClient = OkHttpClient.Builder()
            .useAndroidNextcloudCertificateTrust(applicationContext)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val authenticatedFactory = DataSource.Factory {
            OkHttpDataSource.Factory(Call.Factory { request ->
                (preparedReads ?: throw IOException("Audio playback stopped.")).newCall(request)
            })
                .setUserAgent("nati.ve")
                .createDataSource()
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(authenticatedFactory))
            .build()
        player.addListener(playbackListener)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> playPendingRequest()
            ACTION_PAUSE -> player.pause()
            ACTION_RESUME -> player.play()
            ACTION_SEEK -> player.seekTo(intent.getLongExtra(EXTRA_POSITION_MILLIS, 0L).coerceAtLeast(0))
            ACTION_STOP -> stopPlayback()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        playbackRetirement.clear()
        activeRequest?.let(AndroidAudioPlaybackBridge::clearPending)
        activeRequest = null
        preparationJob?.cancel()
        preparedReads = null
        positionJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        player.removeListener(playbackListener)
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopPlayback(expected: AndroidAudioPlaybackRequest? = null) {
        playbackRetirement.clear()
        activeRequest = null
        preparationJob?.cancel()
        preparedReads = null
        AndroidAudioPlaybackBridge.clearPending(expected)
        positionJob?.cancel()
        player.stop()
        player.clearMediaItems()
        AndroidAudioPlaybackBridge.update(NativeAudioEngineState())
        if (AndroidAudioPlaybackBridge.pendingRequest == null) stopSelf()
    }

    private fun playPendingRequest() {
        val request = AndroidAudioPlaybackBridge.pendingRequest ?: return
        val source = request.sources.getOrNull(request.currentIndex) ?: return
        if (!playbackRetirement.bind(request.producer, onRetired = {
                preparationJob?.cancel()
                preparedReads = null
                scope.launch(Dispatchers.Main) {
                    if (activeRequest === request) stopPlayback(request)
                }
            }, publish = { activeRequest = request })) return
        val items = request.sources.map { queueSource ->
            MediaItem.Builder()
                .setMediaId(queueSource.id)
                .setUri(nativeAudioPlaybackUrl(request.session, queueSource))
                .setMimeType(queueSource.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(queueSource.title ?: "Nextcloud audio")
                        .setArtist(queueSource.artist)
                        .setAlbumTitle(queueSource.album)
                        .setArtworkUri(
                            queueSource.artworkRelativePath
                                ?.let { path -> Uri.parse(nativeAudioPlaybackUrl(request.session, queueSource.copy(relativePath = path))) },
                        )
                        .build(),
                )
                .build()
        }
        positionJob?.cancel()
        AndroidAudioPlaybackBridge.update(
            NativeAudioEngineState(sourceId = source.id, status = NativeAudioEngineStatus.Loading),
        )
        preparationJob?.cancel()
        player.stop()
        player.clearMediaItems()
        val preparation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val reads = prepareNativeAudioReads(request.session, request.sources, httpClient)
                if (AndroidAudioPlaybackBridge.pendingRequest !== request) return@launch
                sharedAccountPrivateMemoryGate.read(request.producer, Unit) {
                    if (activeRequest === request) {
                        preparedReads = reads
                        player.setMediaItems(items, request.currentIndex, 0L)
                        player.prepare()
                        player.playWhenReady = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (AndroidAudioPlaybackBridge.pendingRequest === request) {
                    AndroidAudioPlaybackBridge.update(NativeAudioEngineState(
                        sourceId = source.id, status = NativeAudioEngineStatus.Error,
                        error = "Could not prepare this audio. Try again.",
                    ))
                }
            }
        }
        val accepted = sharedAccountPrivateMemoryGate.read(request.producer, false) {
            if (activeRequest !== request) false else {
                preparationJob = preparation
                true
            }
        }
        if (accepted) preparation.start() else preparation.cancel()
    }
    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player.playbackState == Player.STATE_ENDED) return
            val current = AndroidAudioPlaybackBridge.state.value
            AndroidAudioPlaybackBridge.update(
                current.copy(
                    status = if (isPlaying) {
                        NativeAudioEngineStatus.Playing
                    } else if (player.playbackState == Player.STATE_READY) {
                        NativeAudioEngineStatus.Paused
                    } else {
                        current.status
                    },
                    positionMillis = player.currentPosition.coerceAtLeast(0),
                    durationMillis = player.duration.takeIf { it > 0 },
                ),
            )
            if (isPlaying) startPositionUpdates() else positionJob?.cancel()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val current = AndroidAudioPlaybackBridge.state.value
            when (playbackState) {
                Player.STATE_READY -> AndroidAudioPlaybackBridge.update(
                    current.copy(
                        status = if (player.isPlaying) {
                            NativeAudioEngineStatus.Playing
                        } else {
                            NativeAudioEngineStatus.Paused
                        },
                        durationMillis = player.duration.takeIf { it > 0 },
                    ),
                )
                Player.STATE_ENDED -> {
                    positionJob?.cancel()
                    AndroidAudioPlaybackBridge.update(
                        current.copy(
                            status = NativeAudioEngineStatus.Ended,
                            positionMillis = player.duration.coerceAtLeast(0),
                            durationMillis = player.duration.takeIf { it > 0 },
                        ),
                    )
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val sourceId = mediaItem?.mediaId?.takeIf(String::isNotBlank) ?: return
            AndroidAudioPlaybackBridge.update(
                AndroidAudioPlaybackBridge.state.value.copy(
                    sourceId = sourceId,
                    positionMillis = 0,
                    durationMillis = null,
                    error = null,
                ),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            positionJob?.cancel()
            AndroidAudioPlaybackBridge.update(
                AndroidAudioPlaybackBridge.state.value.copy(
                    status = NativeAudioEngineStatus.Error,
                    error = error.message ?: "Android could not play this audio format.",
                ),
            )
        }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                AndroidAudioPlaybackBridge.update(
                    AndroidAudioPlaybackBridge.state.value.copy(
                        positionMillis = player.currentPosition.coerceAtLeast(0),
                        durationMillis = player.duration.takeIf { it > 0 },
                    ),
                )
                delay(500)
            }
        }
    }

    companion object {
        internal const val ACTION_PLAY = "dev.obiente.nextcloudnative.audio.PLAY"
        internal const val ACTION_PAUSE = "dev.obiente.nextcloudnative.audio.PAUSE"
        internal const val ACTION_RESUME = "dev.obiente.nextcloudnative.audio.RESUME"
        internal const val ACTION_SEEK = "dev.obiente.nextcloudnative.audio.SEEK"
        internal const val ACTION_STOP = "dev.obiente.nextcloudnative.audio.STOP"
        internal const val EXTRA_POSITION_MILLIS = "positionMillis"
    }
}
