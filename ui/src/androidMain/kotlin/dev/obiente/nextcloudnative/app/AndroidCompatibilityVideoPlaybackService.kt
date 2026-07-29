package dev.obiente.nextcloudnative.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

internal enum class AndroidCompatibilityVideoPlaybackStatus {
    Loading,
    Playing,
    Paused,
    Ended,
    Error,
}

internal data class AndroidCompatibilityVideoPlaybackState(
    val requestId: Long? = null,
    val status: AndroidCompatibilityVideoPlaybackStatus? = null,
    val failure: NativeVideoPlaybackFailure? = null,
)

internal data class AndroidCompatibilityVideoPlaybackRequest(
    val requestId: Long,
    val rangeSource: NativeVideoRangeSource,
    val title: String,
)

internal interface AndroidCompatibilityVideoSurfaceController {
    val requestId: Long

    fun attach(layout: VLCVideoLayout)

    fun detach(layout: VLCVideoLayout)
}

internal interface AndroidCompatibilityVideoCommandController {
    fun play(requestId: Long)

    fun pause(requestId: Long)

    fun close(requestId: Long)
}

/**
 * Defers autoplay until LibVLC has a real video surface.
 *
 * A MediaSession pause can arrive while the compatibility decoder is still loading. Keeping that
 * intent separate from surface readiness prevents a later attach from accidentally restarting
 * playback.
 */
internal class CompatibilityVideoPlaybackStartGate {
    var playWhenReady: Boolean = false
        private set

    private var surfaceAttached: Boolean = false

    fun beginAutoplay() {
        playWhenReady = true
        surfaceAttached = false
    }

    fun attachSurface(): Boolean {
        if (surfaceAttached) return false
        surfaceAttached = true
        return playWhenReady
    }

    fun detachSurface() {
        surfaceAttached = false
    }

    fun updatePlayWhenReady(value: Boolean): Boolean {
        playWhenReady = value
        return value && surfaceAttached
    }

    fun reset() {
        playWhenReady = false
        surfaceAttached = false
    }
}

/**
 * The UI passes the credential-bound range reader through process memory only.
 *
 * Intents and MediaItems carry an ephemeral request ID, never credentials, server URLs, paths or
 * live range readers. The service consumes and owns the request before playback starts.
 */
internal object AndroidCompatibilityVideoPlaybackBridge {
    private val nextRequestId = AtomicLong(0)
    private val pendingRequest = AtomicReference<AndroidCompatibilityVideoPlaybackRequest?>(null)
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
        AndroidCompatibilityVideoPlaybackState(),
    )
    private val mutableSurfaceController =
        kotlinx.coroutines.flow.MutableStateFlow<AndroidCompatibilityVideoSurfaceController?>(null)
    private val commandController =
        AtomicReference<AndroidCompatibilityVideoCommandController?>(null)

    val state: kotlinx.coroutines.flow.StateFlow<AndroidCompatibilityVideoPlaybackState> =
        mutableState
    val surfaceController:
        kotlinx.coroutines.flow.StateFlow<AndroidCompatibilityVideoSurfaceController?> =
        mutableSurfaceController

    fun createRequest(
        source: NativeVideoPlaybackSource,
        rangeSource: NativeVideoRangeSource,
    ): AndroidCompatibilityVideoPlaybackRequest =
        AndroidCompatibilityVideoPlaybackRequest(
            requestId = nextRequestId.incrementAndGet(),
            rangeSource = rangeSource,
            title = when (source) {
                is NativeVideoPlaybackSource.DavFile ->
                    source.file.name.takeIf(String::isNotBlank) ?: "Nextcloud video"
                is NativeVideoPlaybackSource.MemoriesLivePhoto -> "Live Photo motion"
            },
        )

    fun submit(request: AndroidCompatibilityVideoPlaybackRequest) {
        pendingRequest.getAndSet(request)
            ?.takeUnless { previous -> previous.requestId == request.requestId }
            ?.rangeSource
            ?.close()
    }

    fun takePendingRequest(requestId: Long): AndroidCompatibilityVideoPlaybackRequest? {
        while (true) {
            val candidate = pendingRequest.get() ?: return null
            if (candidate.requestId != requestId) return null
            if (pendingRequest.compareAndSet(candidate, null)) return candidate
        }
    }

    fun closePendingRequest(requestId: Long) {
        while (true) {
            val candidate = pendingRequest.get() ?: return
            if (candidate.requestId != requestId) return
            if (pendingRequest.compareAndSet(candidate, null)) {
                candidate.rangeSource.close()
                return
            }
        }
    }

    fun registerCommandController(value: AndroidCompatibilityVideoCommandController) {
        check(commandController.compareAndSet(null, value)) {
            "Compatibility video command controller is already registered."
        }
    }

    fun unregisterCommandController(value: AndroidCompatibilityVideoCommandController) {
        commandController.compareAndSet(value, null)
    }

    fun play(requestId: Long) {
        commandController.get()?.play(requestId)
    }

    fun pause(requestId: Long) {
        commandController.get()?.pause(requestId)
    }

    fun close(requestId: Long) {
        closePendingRequest(requestId)
        commandController.get()?.close(requestId)
    }

    fun publishState(value: AndroidCompatibilityVideoPlaybackState) {
        mutableState.value = value
    }

    fun publishSurfaceController(value: AndroidCompatibilityVideoSurfaceController?) {
        mutableSurfaceController.value = value
    }

    fun clear(requestId: Long) {
        if (mutableState.value.requestId == requestId) {
            mutableState.value = AndroidCompatibilityVideoPlaybackState()
        }
        if (mutableSurfaceController.value?.requestId == requestId) {
            mutableSurfaceController.value = null
        }
        closePendingRequest(requestId)
    }
}

/**
 * Process-owned LibVLC compatibility playback with a real Media3 session.
 *
 * The custom Media3 Player mirrors LibVLC transport state. MediaSessionService then supplies the
 * Android notification, lock-screen, Bluetooth and external play/pause/seek controls.
 */
@OptIn(UnstableApi::class)
class AndroidCompatibilityVideoPlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: LibVlcMedia3Player
    private var mediaSession: MediaSession? = null
    private var loadJob: Job? = null
    private var loadGeneration: Long = 0
    private val commandController = object : AndroidCompatibilityVideoCommandController {
        override fun play(requestId: Long) {
            if (player.matches(requestId)) player.play()
        }

        override fun pause(requestId: Long) {
            if (player.matches(requestId)) player.pause()
        }

        override fun close(requestId: Long) {
            closeRequest(requestId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = LibVlcMedia3Player(
            onRequestClosed = { requestId ->
                AndroidCompatibilityVideoPlaybackBridge.clear(requestId)
                stopSelf()
            },
        )
        mediaSession = MediaSession.Builder(this, player)
            .setId(COMPATIBILITY_VIDEO_SESSION_ID)
            .build()
        AndroidCompatibilityVideoPlaybackBridge.registerCommandController(commandController)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getLongExtra(EXTRA_REQUEST_ID, INVALID_REQUEST_ID)
            ?: INVALID_REQUEST_ID
        when (intent?.action) {
            ACTION_OPEN -> openPendingRequest(requestId)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        loadGeneration += 1
        loadJob?.cancel()
        loadJob = null
        AndroidCompatibilityVideoPlaybackBridge.unregisterCommandController(commandController)
        mediaSession?.release()
        mediaSession = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun openPendingRequest(requestId: Long) {
        if (requestId == INVALID_REQUEST_ID) return
        val request = AndroidCompatibilityVideoPlaybackBridge.takePendingRequest(requestId)
        if (request == null) {
            if (!player.hasActiveRequest()) stopSelf()
            return
        }
        val generation = ++loadGeneration
        loadJob?.cancel()
        player.begin(request)
        loadJob = scope.launch {
            val result = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    LibVlcRangePlaybackResources.create(
                        context = applicationContext,
                        rangeSource = request.rangeSource,
                        onEvent = { event ->
                            scope.launch {
                                player.onLibVlcEvent(request.requestId, event)
                            }
                        },
                    )
                }
            }
            if (isActive && generation == loadGeneration && player.matches(request.requestId)) {
                result.fold(
                    onSuccess = player::install,
                    onFailure = {
                        player.fail(
                            requestId = request.requestId,
                            failure = NativeVideoPlaybackFailure.DecoderInitializationFailed(
                                format = null,
                            ),
                        )
                    },
                )
            } else {
                result.getOrNull()?.closeAsync()
                if (result.isFailure) request.rangeSource.close()
            }
        }
    }

    private fun closeRequest(requestId: Long) {
        if (requestId == INVALID_REQUEST_ID) return
        AndroidCompatibilityVideoPlaybackBridge.closePendingRequest(requestId)
        if (!player.matches(requestId)) return
        loadGeneration += 1
        loadJob?.cancel()
        loadJob = null
        player.closeCurrentRequest()
    }

    companion object {
        internal const val ACTION_OPEN =
            "dev.obiente.nextcloudnative.compatibilityvideo.OPEN"
        internal const val EXTRA_REQUEST_ID = "requestId"
        private const val INVALID_REQUEST_ID = -1L
        private const val COMPATIBILITY_VIDEO_SESSION_ID = "compatibility-video"
    }
}

@OptIn(UnstableApi::class)
private class LibVlcMedia3Player(
    private val onRequestClosed: (Long) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var request: AndroidCompatibilityVideoPlaybackRequest? = null
    private var resources: LibVlcRangePlaybackResources? = null
    private var status: AndroidCompatibilityVideoPlaybackStatus? = null
    private val startGate = CompatibilityVideoPlaybackStartGate()
    private var seekable: Boolean = false
    private var durationMillis: Long = C.TIME_UNSET
    private var failure: NativeVideoPlaybackFailure? = null

    fun matches(requestId: Long): Boolean = request?.requestId == requestId

    fun hasActiveRequest(): Boolean = request != null

    fun begin(value: AndroidCompatibilityVideoPlaybackRequest) {
        releaseResources()
        request = value
        status = AndroidCompatibilityVideoPlaybackStatus.Loading
        startGate.beginAutoplay()
        seekable = false
        durationMillis = C.TIME_UNSET
        failure = null
        publishState()
    }

    fun install(value: LibVlcRangePlaybackResources) {
        if (request == null) {
            value.closeAsync()
            return
        }
        resources = value
        seekable = value.isSeekable()
        durationMillis = value.durationMillis()
        AndroidCompatibilityVideoPlaybackBridge.publishSurfaceController(
            object : AndroidCompatibilityVideoSurfaceController {
                override val requestId: Long = requireNotNull(request).requestId

                override fun attach(layout: VLCVideoLayout) {
                    if (matches(requestId)) {
                        resources?.attach(layout)
                        if (startGate.attachSurface()) {
                            resources?.play()
                        }
                    }
                }

                override fun detach(layout: VLCVideoLayout) {
                    if (matches(requestId)) {
                        startGate.detachSurface()
                        resources?.detach(layout)
                    }
                }
            },
        )
        status = if (startGate.playWhenReady) {
            AndroidCompatibilityVideoPlaybackStatus.Loading
        } else {
            AndroidCompatibilityVideoPlaybackStatus.Paused
        }
        publishState()
    }

    fun onLibVlcEvent(requestId: Long, event: LibVlcPlaybackEvent) {
        if (!matches(requestId)) return
        when (event) {
            LibVlcPlaybackEvent.Opening,
            is LibVlcPlaybackEvent.Buffering,
            -> status = AndroidCompatibilityVideoPlaybackStatus.Loading
            LibVlcPlaybackEvent.Playing -> {
                startGate.updatePlayWhenReady(true)
                status = AndroidCompatibilityVideoPlaybackStatus.Playing
            }
            LibVlcPlaybackEvent.Paused -> {
                startGate.updatePlayWhenReady(false)
                status = AndroidCompatibilityVideoPlaybackStatus.Paused
            }
            LibVlcPlaybackEvent.Stopped -> {
                if (status != AndroidCompatibilityVideoPlaybackStatus.Ended) {
                    startGate.updatePlayWhenReady(false)
                    status = AndroidCompatibilityVideoPlaybackStatus.Paused
                }
            }
            LibVlcPlaybackEvent.Ended -> {
                startGate.updatePlayWhenReady(false)
                status = AndroidCompatibilityVideoPlaybackStatus.Ended
            }
            LibVlcPlaybackEvent.Error -> {
                startGate.updatePlayWhenReady(false)
                failure = NativeVideoPlaybackFailure.Unknown
                status = AndroidCompatibilityVideoPlaybackStatus.Error
            }
            is LibVlcPlaybackEvent.DurationChanged -> {
                durationMillis = event.durationMillis.takeIf { it > 0 } ?: C.TIME_UNSET
            }
            is LibVlcPlaybackEvent.SeekableChanged -> {
                seekable = event.seekable
            }
        }
        publishState()
    }

    fun fail(requestId: Long, failure: NativeVideoPlaybackFailure) {
        if (!matches(requestId)) return
        request?.rangeSource?.close()
        startGate.updatePlayWhenReady(false)
        this.failure = failure
        status = AndroidCompatibilityVideoPlaybackStatus.Error
        publishState()
    }

    fun closeCurrentRequest() {
        val requestId = request?.requestId ?: return
        releaseResources()
        request = null
        status = null
        startGate.reset()
        seekable = false
        durationMillis = C.TIME_UNSET
        failure = null
        invalidateState()
        onRequestClosed(requestId)
    }

    override fun getState(): State {
        val activeRequest = request
        val playbackState = when (status) {
            AndroidCompatibilityVideoPlaybackStatus.Loading -> Player.STATE_BUFFERING
            AndroidCompatibilityVideoPlaybackStatus.Playing,
            AndroidCompatibilityVideoPlaybackStatus.Paused,
            -> Player.STATE_READY
            AndroidCompatibilityVideoPlaybackStatus.Ended -> Player.STATE_ENDED
            AndroidCompatibilityVideoPlaybackStatus.Error,
            null,
            -> Player.STATE_IDLE
        }
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_MEDIA_ITEMS_METADATA)
            .add(Player.COMMAND_GET_METADATA)
            .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, seekable)
            .addIf(Player.COMMAND_SEEK_BACK, seekable)
            .addIf(Player.COMMAND_SEEK_FORWARD, seekable)
            .build()
        return State.Builder()
            .setAvailableCommands(
                if (activeRequest == null) Player.Commands.EMPTY else commands,
            )
            .setPlayWhenReady(
                startGate.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackState(playbackState)
            .apply {
                if (activeRequest != null) {
                    val metadata = MediaMetadata.Builder()
                        .setTitle(activeRequest.title)
                        .setDisplayTitle(activeRequest.title)
                        .build()
                    val item = MediaItem.Builder()
                        .setMediaId("compatibility-video:${activeRequest.requestId}")
                        .setMediaMetadata(metadata)
                        .build()
                    setPlaylist(
                        listOf(
                            MediaItemData.Builder(activeRequest.requestId)
                                .setMediaItem(item)
                                .setMediaMetadata(metadata)
                                .setIsSeekable(seekable)
                                .setDurationUs(
                                    durationMillis.takeIf { it > 0 }
                                        ?.let(C::msToUs)
                                        ?: C.TIME_UNSET,
                                )
                                .build(),
                        ),
                    )
                    setCurrentMediaItemIndex(0)
                    setContentPositionMs(
                        PositionSupplier {
                            resources?.positionMillis()?.coerceAtLeast(0) ?: 0L
                        },
                    )
                }
                if (status == AndroidCompatibilityVideoPlaybackStatus.Error) {
                    setPlayerError(
                        PlaybackException(
                            "Compatibility video playback failed.",
                            null,
                            PlaybackException.ERROR_CODE_UNSPECIFIED,
                        ),
                    )
                }
            }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (startGate.updatePlayWhenReady(playWhenReady)) {
            resources?.play()
        } else if (!playWhenReady) {
            resources?.pause()
            if (status == AndroidCompatibilityVideoPlaybackStatus.Playing) {
                status = AndroidCompatibilityVideoPlaybackStatus.Paused
            }
        }
        publishState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        closeCurrentRequest()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        closeCurrentRequest()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (mediaItemIndex == 0 && seekable) {
            resources?.seekTo(positionMs.coerceAtLeast(0))
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    private fun publishState() {
        val activeRequest = request
        AndroidCompatibilityVideoPlaybackBridge.publishState(
            AndroidCompatibilityVideoPlaybackState(
                requestId = activeRequest?.requestId,
                status = status,
                failure = failure,
            ),
        )
        invalidateState()
    }

    private fun releaseResources() {
        val activeRequestId = request?.requestId
        if (activeRequestId != null) {
            AndroidCompatibilityVideoPlaybackBridge.publishSurfaceController(null)
        }
        resources?.closeAsync()
        resources = null
    }
}

private sealed interface LibVlcPlaybackEvent {
    data object Opening : LibVlcPlaybackEvent

    data class Buffering(val percent: Float) : LibVlcPlaybackEvent

    data object Playing : LibVlcPlaybackEvent

    data object Paused : LibVlcPlaybackEvent

    data object Stopped : LibVlcPlaybackEvent

    data object Ended : LibVlcPlaybackEvent

    data object Error : LibVlcPlaybackEvent

    data class DurationChanged(val durationMillis: Long) : LibVlcPlaybackEvent

    data class SeekableChanged(val seekable: Boolean) : LibVlcPlaybackEvent
}

private class LibVlcRangePlaybackResources private constructor(
    private val callbackThread: HandlerThread,
    private val fileDescriptor: ParcelFileDescriptor,
    private val libVlc: LibVLC,
    private val media: Media,
    private val player: MediaPlayer,
    private val closeRangeSource: () -> Unit,
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

    fun seekTo(positionMillis: Long) {
        if (!closed.get() && player.isSeekable) {
            player.setTime(positionMillis.coerceAtLeast(0))
        }
    }

    fun isSeekable(): Boolean = !closed.get() && player.isSeekable

    fun positionMillis(): Long =
        if (closed.get()) 0L else player.time.coerceAtLeast(0)

    fun durationMillis(): Long =
        if (closed.get()) C.TIME_UNSET else player.length.takeIf { it > 0 } ?: C.TIME_UNSET

    fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        closeRangeSource()
        attachedLayout?.let {
            player.detachViews()
            attachedLayout = null
        }
        Thread(
            {
                runCatching { player.stop() }
                runCatching { player.release() }
                runCatching { media.release() }
                runCatching { libVlc.release() }
                runCatching { fileDescriptor.close() }
                callbackThread.quitSafely()
            },
            "nc-native-video-release",
        ).apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        fun create(
            context: Context,
            rangeSource: NativeVideoRangeSource,
            onEvent: (LibVlcPlaybackEvent) -> Unit,
        ): LibVlcRangePlaybackResources {
            val closed = AtomicBoolean(false)
            val sourceClosed = AtomicBoolean(false)
            val closeRangeSource = {
                if (sourceClosed.compareAndSet(false, true)) {
                    rangeSource.close()
                }
            }
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
                                    kotlinx.coroutines.runBlocking {
                                        rangeCache.read(offset, requested)
                                    }
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
                                closeRangeSource()
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
                            MediaPlayer.Event.Opening -> onEvent(LibVlcPlaybackEvent.Opening)
                            MediaPlayer.Event.Buffering ->
                                onEvent(LibVlcPlaybackEvent.Buffering(event.buffering))
                            MediaPlayer.Event.Playing -> onEvent(LibVlcPlaybackEvent.Playing)
                            MediaPlayer.Event.Paused -> onEvent(LibVlcPlaybackEvent.Paused)
                            MediaPlayer.Event.Stopped -> onEvent(LibVlcPlaybackEvent.Stopped)
                            MediaPlayer.Event.EndReached -> onEvent(LibVlcPlaybackEvent.Ended)
                            MediaPlayer.Event.EncounteredError -> onEvent(LibVlcPlaybackEvent.Error)
                            MediaPlayer.Event.LengthChanged ->
                                onEvent(
                                    LibVlcPlaybackEvent.DurationChanged(event.lengthChanged),
                                )
                            MediaPlayer.Event.SeekableChanged ->
                                onEvent(
                                    LibVlcPlaybackEvent.SeekableChanged(event.seekable),
                                )
                        }
                    }
                }
                return LibVlcRangePlaybackResources(
                    callbackThread = callbackThread,
                    fileDescriptor = fileDescriptor,
                    libVlc = libVlc,
                    media = media,
                    player = player,
                    closeRangeSource = closeRangeSource,
                    closed = closed,
                )
            } catch (failure: Exception) {
                closed.set(true)
                closeRangeSource()
                runCatching { player?.stop() }
                runCatching { player?.release() }
                runCatching { media?.release() }
                runCatching { libVlc?.release() }
                runCatching { fileDescriptor?.close() }
                callbackThread.quitSafely()
                throw failure
            }
        }
    }
}
