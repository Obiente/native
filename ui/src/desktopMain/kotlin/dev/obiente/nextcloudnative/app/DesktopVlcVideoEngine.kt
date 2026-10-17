package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import uk.co.caprica.vlcj.media.VideoOrientation
import uk.co.caprica.vlcj.media.callback.DefaultCallbackMedia
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

internal sealed interface DesktopVideoOutcome {
    data object Ended : DesktopVideoOutcome
    data class Failed(val failure: NativeVideoPlaybackFailure) : DesktopVideoOutcome
}

/** The playback a desktop video surface drives; the libVLC engine is the production owner. */
internal interface DesktopVideoEngine {
    val state: StateFlow<NativeVideoTransportState>
    val outcomes: SharedFlow<DesktopVideoOutcome>
    val frames: DesktopVideoFrameBuffer

    fun start()

    fun command(command: NativeVideoTransportCommand)

    fun release()
}

/**
 * Plays one range source through libVLC with frames copied into Compose.
 *
 * Every libVLC call runs on one engine thread, never on a libVLC callback thread, because libVLC
 * must not be re-entered from its own events. [release] stops events from reaching the UI before
 * libVLC is torn down, so a read failing during close is never reported as a playback failure.
 */
internal class DesktopVlcVideoEngine(
    rangeSource: NativeVideoRangeSource,
    private val runtime: DesktopLibVlcRuntime = sharedDesktopLibVlcRuntime,
) : DesktopVideoEngine {
    private val sourceSize = rangeSource.size
    private val mutableState = MutableStateFlow(NativeVideoTransportState())
    override val state: StateFlow<NativeVideoTransportState> = mutableState
    private val mutableOutcomes = MutableSharedFlow<DesktopVideoOutcome>(extraBufferCapacity = 8)
    override val outcomes: SharedFlow<DesktopVideoOutcome> = mutableOutcomes
    override val frames = DesktopVideoFrameBuffer()

    private val rangeCache = NativeVideoRangeCache(
        source = rangeSource,
        readAheadBytes = DESKTOP_VIDEO_READ_AHEAD_BYTES,
        maximumCachedRanges = DESKTOP_VIDEO_CACHED_RANGES,
    )
    private val cursor = DesktopVideoRangeCursor(rangeSource.size) { offset, length ->
        runBlocking { rangeCache.read(offset, length) }
    }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nc-desktop-video").apply { isDaemon = true }
    }

    @Volatile
    private var released = false

    // Engine-thread confined. The media and callbacks stay strongly reachable while libVLC
    // holds native pointers to them.
    private var player: EmbeddedMediaPlayer? = null
    private var media: DefaultCallbackMedia? = null
    private var bufferFormatCallback: BufferFormatCallback? = null
    private var renderCallback: RenderCallback? = null

    override fun start() = onEngineThread {
        val factory = runtime.factoryOrNull() ?: return@onEngineThread fail(NativeVideoPlaybackFailure.Unknown)
        val created = factory.mediaPlayers().newEmbeddedMediaPlayer()
        player = created
        val formatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                val size = desktopVideoBufferSize(sourceWidth, sourceHeight, DESKTOP_VIDEO_MAXIMUM_LONG_SIDE)
                frames.configure(size.width, size.height, sourceWidth, sourceHeight)
                return RV32BufferFormat(size.width, size.height)
            }

            // These sizes still include decoder padding; the visible size comes from videoOutput.
            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit

            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }
        val render = object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit

            override fun display(
                mediaPlayer: MediaPlayer,
                nativeBuffers: Array<out ByteBuffer>,
                bufferFormat: BufferFormat,
                displayWidth: Int,
                displayHeight: Int,
            ) {
                nativeBuffers.firstOrNull()?.let(frames::publish)
            }

            override fun unlock(mediaPlayer: MediaPlayer) = Unit
        }
        bufferFormatCallback = formatCallback
        renderCallback = render
        created.videoSurface().set(factory.videoSurfaces().newVideoSurface(formatCallback, render, true))
        created.events().addMediaPlayerEventListener(Events())
        val callbackMedia = RangeCallbackMedia(cursor, sourceSize)
        media = callbackMedia
        if (!created.media().play(callbackMedia)) fail(NativeVideoPlaybackFailure.MalformedMedia)
    }

    override fun command(command: NativeVideoTransportCommand) = onEngineThread {
        val active = player ?: return@onEngineThread
        when (command) {
            NativeVideoTransportCommand.TogglePlayback -> when (mutableState.value.status) {
                NativeVideoTransportStatus.Playing -> active.controls().setPause(true)
                NativeVideoTransportStatus.Ended -> {
                    mutableState.update { it.copy(status = NativeVideoTransportStatus.Loading, positionMillis = 0L) }
                    media?.let { active.media().play(it) }
                }
                NativeVideoTransportStatus.Paused, NativeVideoTransportStatus.Loading -> active.controls().play()
            }
            is NativeVideoTransportCommand.SeekBy, is NativeVideoTransportCommand.SeekTo ->
                mutableState.value.seekTargetMillis(command)?.let { target ->
                    active.controls().setTime(target)
                    mutableState.update { it.copy(positionMillis = target) }
                }
            NativeVideoTransportCommand.ToggleMute -> active.audio().setMute(!active.audio().isMute)
            is NativeVideoTransportCommand.SetVolume -> {
                val volume = command.percent.coerceIn(0, MAX_VIDEO_VOLUME_PERCENT)
                active.audio().setVolume(volume)
                if (volume > 0 && active.audio().isMute) active.audio().setMute(false)
                mutableState.update { it.copy(volumePercent = volume, muted = if (volume > 0) false else it.muted) }
            }
        }
    }

    override fun release() {
        if (released) return
        released = true
        try {
            executor.execute {
                player?.let { active ->
                    active.controls().stop()
                    active.release()
                }
                player = null
                media = null
                bufferFormatCallback = null
                renderCallback = null
                frames.close()
            }
        } catch (_: RejectedExecutionException) {
            frames.close()
        }
        executor.shutdown()
    }

    private fun onEngineThread(block: () -> Unit) {
        if (released) return
        try {
            executor.execute {
                if (released) return@execute
                try {
                    block()
                } catch (_: RuntimeException) {
                    fail(NativeVideoPlaybackFailure.Unknown)
                } catch (_: LinkageError) {
                    fail(NativeVideoPlaybackFailure.Unknown)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Released while the command was being submitted.
        }
    }

    /** Engine thread: the picture size without padding, after any automatic rotation. */
    private fun readVisibleSize() {
        val active = player ?: return
        val dimension = active.video().videoDimension() ?: return
        val track = active.media().info()?.videoTracks()?.firstOrNull()
        val sampleAspect = track
            ?.takeIf { it.sampleAspectRatio() > 0 && it.sampleAspectRatioBase() > 0 }
            ?.let { it.sampleAspectRatio().toDouble() / it.sampleAspectRatioBase() }
            ?: 1.0
        // The frame geometry decides from the buffer shape whether libVLC already rotated.
        frames.setVisibleSize(dimension.width, dimension.height, sampleAspect, desktopVideoQuarterTurns(track?.orientation()))
    }

    private fun fail(failure: NativeVideoPlaybackFailure) {
        if (released) return
        mutableState.update { it.copy(status = NativeVideoTransportStatus.Paused) }
        mutableOutcomes.tryEmit(DesktopVideoOutcome.Failed(failure))
    }

    /** libVLC event thread: only records state; never calls back into libVLC. */
    private inner class Events : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) = publish { it.copy(status = NativeVideoTransportStatus.Playing) }

        override fun paused(mediaPlayer: MediaPlayer) = publish { it.copy(status = NativeVideoTransportStatus.Paused) }

        override fun finished(mediaPlayer: MediaPlayer) {
            publish { it.copy(status = NativeVideoTransportStatus.Ended, positionMillis = it.durationMillis ?: it.positionMillis) }
            if (!released) mutableOutcomes.tryEmit(DesktopVideoOutcome.Ended)
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) =
            publish { it.copy(positionMillis = newTime.coerceAtLeast(0L)) }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) =
            publish { it.copy(durationMillis = newLength.takeIf { length -> length > 0L }) }

        override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) =
            publish { it.copy(seekable = newSeekable != 0) }

        override fun muted(mediaPlayer: MediaPlayer, muted: Boolean) = publish { it.copy(muted = muted) }

        override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
            if (volume >= 0f) {
                publish { it.copy(volumePercent = (volume * 100f).toInt().coerceIn(0, MAX_VIDEO_VOLUME_PERCENT)) }
            }
        }

        override fun error(mediaPlayer: MediaPlayer) = fail(desktopVideoReadFailure(cursor.lastFailure))

        override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
            if (newCount > 0) onEngineThread(::readVisibleSize)
        }

        private fun publish(change: (NativeVideoTransportState) -> NativeVideoTransportState) {
            if (!released) mutableState.update(change)
        }
    }
}

/**
 * Clockwise quarter turns that make a picture stored with [orientation] upright. Mirrored
 * orientations, which cameras do not record, are drawn unrotated.
 */
internal fun desktopVideoQuarterTurns(orientation: VideoOrientation?): Int = when (orientation) {
    VideoOrientation.LEFT_BOTTOM -> 3
    VideoOrientation.BOTTOM_RIGHT -> 2
    VideoOrientation.RIGHT_TOP -> 1
    else -> 0
}

/** Maps the last range read failure to user-facing playback recovery; no exception text is parsed. */
internal fun desktopVideoReadFailure(failure: Exception?): NativeVideoPlaybackFailure = when (failure) {
    null -> NativeVideoPlaybackFailure.MalformedMedia
    is IOException -> NativeVideoPlaybackFailure.NetworkUnavailable
    else -> NativeVideoPlaybackFailure.Unknown
}

private class RangeCallbackMedia(
    private val cursor: DesktopVideoRangeCursor,
    private val sourceSize: Long,
) : DefaultCallbackMedia(true, DESKTOP_VIDEO_CALLBACK_BUFFER_BYTES) {
    override fun onGetSize(): Long = sourceSize

    override fun onOpen(): Boolean = cursor.open()

    override fun onRead(buffer: ByteArray, bufferSize: Int): Int = cursor.readInto(buffer, bufferSize)

    override fun onSeek(offset: Long): Boolean = cursor.seek(offset)

    override fun onClose() = Unit
}

private const val DESKTOP_VIDEO_READ_AHEAD_BYTES = 4 * 1_024 * 1_024
private const val DESKTOP_VIDEO_CACHED_RANGES = 3
private const val DESKTOP_VIDEO_CALLBACK_BUFFER_BYTES = 256 * 1_024
private const val DESKTOP_VIDEO_MAXIMUM_LONG_SIDE = 2_560
