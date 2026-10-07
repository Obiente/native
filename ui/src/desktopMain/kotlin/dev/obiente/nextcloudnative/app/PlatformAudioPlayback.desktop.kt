package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaException
import javafx.scene.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

@Composable
internal actual fun rememberPlatformAudioPlaybackEngine(): PlatformAudioPlaybackEngine {
    val engine = remember { DesktopAudioPlaybackEngine() }
    DisposableEffect(engine) {
        onDispose(engine::release)
    }
    return engine
}

internal class DesktopAudioPlaybackEngine : PlatformAudioPlaybackEngine {
    private val mutableState = MutableStateFlow(NativeAudioEngineState())
    override val state: StateFlow<NativeAudioEngineState> = mutableState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val generation = AtomicLong(0)
    private val playbackLock = Any()
    private val playbackRetirement = AccountPlaybackRetirementOwner()
    private var downloadJob: Job? = null
    private var positionJob: Job? = null
    @Volatile
    private var activeCall: Call? = null
    private var javaSoundPlayer: JavaSoundAudioPlayer? = null
    @Volatile
    private var player: MediaPlayer? = null
    private var stagedFile: Path? = null
    private var backend: DesktopAudioBackend? = null

    override fun play(session: NextcloudSession, source: NativeAudioPlaybackSource) {
        val producer = sharedAccountPrivateMemoryGate.producer(previewCacheDigest(session)) ?: return
        var playGeneration = 0L
        if (!playbackRetirement.bind(producer, onRetired = {
                val retiredGeneration = playGeneration + 1L
                if (generation.compareAndSet(playGeneration, retiredGeneration)) {
                    activeCall?.cancel()
                    downloadJob?.cancel()
                    scope.launch {
                        synchronized(playbackLock) {
                            if (generation.get() == retiredGeneration) stopLocked()
                        }
                    }
                }
            }, publish = {
                playGeneration = synchronized(playbackLock) {
                    val nextGeneration = generation.incrementAndGet()
                    activeCall?.cancel()
                    downloadJob?.cancel()
                    positionJob?.cancel()
                    disposePlayerAndFile()
                    mutableState.value = NativeAudioEngineState(
                        sourceId = source.id,
                        status = NativeAudioEngineStatus.Loading,
                    )
                    nextGeneration
                }
            })) return
        val download = scope.launch(start = CoroutineStart.LAZY) {
            if (generation.get() != playGeneration) return@launch
            val staged = runCatching { stageAuthenticatedAudio(session, source, playGeneration) }
                .getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    if (generation.get() == playGeneration) {
                        mutableState.value = mutableState.value.copy(
                            status = NativeAudioEngineStatus.Error,
                            error = failure.message ?: "Desktop could not download this audio file.",
                        )
                    }
                    return@launch
                }
            val selectedBackend = synchronized(playbackLock) {
                if (generation.get() != playGeneration) {
                    null
                } else {
                    stagedFile = staged
                    desktopAudioBackend(source.mimeType).also { backend = it }
                }
            }
            if (selectedBackend == null) {
                Files.deleteIfExists(staged)
                return@launch
            }
            if (selectedBackend == DesktopAudioBackend.JavaSound) {
                startJavaSoundPlayback(staged, playGeneration)
                return@launch
            }
            DesktopJavaFxRuntime.runLater {
                if (generation.get() != playGeneration) {
                    Files.deleteIfExists(staged)
                    return@runLater
                }
                runCatching {
                    val mediaPlayer = MediaPlayer(Media(staged.toUri().toString()))
                    val accepted = synchronized(playbackLock) {
                        if (generation.get() != playGeneration) false else {
                            player = mediaPlayer
                            true
                        }
                    }
                    if (!accepted) {
                        mediaPlayer.dispose()
                        return@runLater
                    }
                    mediaPlayer.setOnReady {
                        if (generation.get() != playGeneration) return@setOnReady
                        mutableState.value = mutableState.value.copy(
                            status = NativeAudioEngineStatus.Playing,
                            durationMillis = mediaPlayer.totalDuration.toMillis()
                                .takeIf(Double::isFinite)
                                ?.toLong(),
                        )
                        mediaPlayer.play()
                        startPositionUpdates(playGeneration)
                    }
                    mediaPlayer.setOnPlaying {
                        if (generation.get() != playGeneration) return@setOnPlaying
                        mutableState.value = mutableState.value.copy(status = NativeAudioEngineStatus.Playing)
                        startPositionUpdates(playGeneration)
                    }
                    mediaPlayer.setOnPaused {
                        if (generation.get() != playGeneration) return@setOnPaused
                        positionJob?.cancel()
                        mutableState.value = mutableState.value.copy(
                            status = NativeAudioEngineStatus.Paused,
                            positionMillis = mediaPlayer.currentTime.toMillis().coerceAtLeast(0.0).toLong(),
                        )
                    }
                    mediaPlayer.setOnEndOfMedia {
                        if (generation.get() != playGeneration) return@setOnEndOfMedia
                        positionJob?.cancel()
                        mutableState.value = mutableState.value.copy(
                            status = NativeAudioEngineStatus.Ended,
                            positionMillis = mediaPlayer.totalDuration.toMillis().coerceAtLeast(0.0).toLong(),
                        )
                    }
                    mediaPlayer.setOnError {
                        if (generation.get() != playGeneration) return@setOnError
                        failPlayback(mediaPlayer.error)
                    }
                }.onFailure { failure ->
                    if (generation.get() == playGeneration) failPlayback(failure)
                }
            }
        }
        val accepted = sharedAccountPrivateMemoryGate.read(producer, false) {
            synchronized(playbackLock) {
                if (generation.get() != playGeneration) false else {
                    downloadJob = download
                    true
                }
            }
        }
        if (accepted) download.start() else download.cancel()
    }

    override fun pause() {
        javaSoundPlayer?.pause()
            ?: DesktopJavaFxRuntime.runLater { player?.pause() }
    }

    override fun resume() {
        javaSoundPlayer?.resume()
            ?: DesktopJavaFxRuntime.runLater { player?.play() }
    }

    override fun seekTo(positionMillis: Long) {
        javaSoundPlayer?.let { javaSound ->
            javaSound.seekTo(positionMillis)
            return
        }
        DesktopJavaFxRuntime.runLater {
            player?.seek(javafx.util.Duration.millis(positionMillis.coerceAtLeast(0).toDouble()))
        }
    }

    override fun stop() {
        // Keep the lock order gate -> playback; retirement also follows that order.
        playbackRetirement.clear()
        synchronized(playbackLock) { stopLocked() }
    }

    private fun stopLocked() {
        generation.incrementAndGet()
        activeCall?.cancel()
        activeCall = null
        downloadJob?.cancel()
        downloadJob = null
        positionJob?.cancel()
        disposePlayerAndFile()
        mutableState.value = NativeAudioEngineState()
    }
    override fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun stageAuthenticatedAudio(
        session: NextcloudSession,
        source: NativeAudioPlaybackSource,
        playGeneration: Long,
    ): Path {
        val reads = prepareNativeAudioReads(session, listOf(source), client)
        val request = Request.Builder()
            .url(nativeAudioPlaybackUrl(session, source))
            .header("Accept", source.mimeType)
            .header("User-Agent", "nati.ve")
            .get()
            .build()
        val suffix = source.mimeType.audioFileSuffix()
        val call = reads.newCall(request)
        val destination = Files.createTempFile("nextcloud-native-audio-", suffix)
        try {
            currentCoroutineContext().ensureActive()
            synchronized(playbackLock) {
                if (generation.get() != playGeneration) throw CancellationException("Audio playback changed.")
                activeCall = call
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Audio download failed with HTTP ${response.code}.")
                }
                val body = response.body
                val maximumBytes = stagedFileTransferLimit(
                    availableBytes = Files.getFileStore(destination).usableSpace.coerceAtLeast(0L),
                    declaredByteCount = body.contentLength().takeIf { it >= 0L },
                )
                body.byteStream().use { input ->
                    Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                        val buffer = ByteArray(DEFAULT_AUDIO_COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total = Math.addExact(total, read.toLong())
                            check(total <= maximumBytes) {
                                "There is not enough free storage to stage this audio file."
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            return destination
        } catch (failure: Throwable) {
            Files.deleteIfExists(destination)
            throw failure
        } finally {
            synchronized(playbackLock) { if (activeCall === call) activeCall = null }
        }
    }

    private fun startPositionUpdates(playGeneration: Long) {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (generation.get() == playGeneration) {
                DesktopJavaFxRuntime.runLater {
                    if (generation.get() != playGeneration) return@runLater
                    val activePlayer = player ?: return@runLater
                    mutableState.value = mutableState.value.copy(
                        positionMillis = activePlayer.currentTime.toMillis().coerceAtLeast(0.0).toLong(),
                        durationMillis = activePlayer.totalDuration.toMillis()
                            .takeIf(Double::isFinite)
                            ?.toLong(),
                    )
                }
                delay(500)
            }
        }
    }

    private fun startJavaSoundPlayback(staged: Path, playGeneration: Long) {
        if (generation.get() != playGeneration) return
        val callbacks = JavaSoundPlayerCallbacks(
            onLoading = { position ->
                if (generation.get() == playGeneration) {
                    mutableState.value = mutableState.value.copy(
                        status = NativeAudioEngineStatus.Loading,
                        positionMillis = position,
                        error = null,
                    )
                }
            },
            onReady = { duration ->
                if (generation.get() == playGeneration) {
                    mutableState.value = mutableState.value.copy(durationMillis = duration)
                }
            },
            onPlaying = {
                if (generation.get() == playGeneration) {
                    mutableState.value = mutableState.value.copy(status = NativeAudioEngineStatus.Playing)
                }
            },
            onPaused = {
                if (generation.get() == playGeneration) {
                    mutableState.value = mutableState.value.copy(status = NativeAudioEngineStatus.Paused)
                }
            },
            onPosition = { position, duration ->
                if (generation.get() == playGeneration) {
                    mutableState.value = mutableState.value.copy(
                        positionMillis = position,
                        durationMillis = duration,
                    )
                }
            },
            onEnded = { duration ->
                if (generation.get() == playGeneration) {
                    mutableState.value = mutableState.value.copy(
                        status = NativeAudioEngineStatus.Ended,
                        positionMillis = duration ?: mutableState.value.positionMillis,
                        durationMillis = duration,
                    )
                }
            },
            onError = { failure ->
                if (generation.get() == playGeneration) failPlayback(failure)
            },
        )
        val javaSound = JavaSoundAudioPlayer(callbacks)
        synchronized(playbackLock) {
            if (generation.get() != playGeneration) {
                javaSound.release()
                return
            }
            javaSoundPlayer = javaSound
            javaSound.play(staged)
        }
    }

    private fun disposePlayerAndFile() {
        javaSoundPlayer?.release()
        javaSoundPlayer = null
        val oldFile = stagedFile
        stagedFile = null
        val oldBackend = backend
        backend = null
        val oldPlayer = player
        player = null
        if (oldBackend == DesktopAudioBackend.JavaFx || oldPlayer != null) {
            DesktopJavaFxRuntime.runLater {
                oldPlayer?.stop()
                oldPlayer?.dispose()
                oldFile?.let { runCatching { Files.deleteIfExists(it) } }
            }
        } else {
            oldFile?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun failPlayback(failure: Throwable?) {
        positionJob?.cancel()
        val message = when (failure) {
            is MediaException -> failure.message
            else -> failure?.message
        }
        mutableState.value = mutableState.value.copy(
            status = NativeAudioEngineStatus.Error,
            error = message ?: "The desktop audio backend could not decode this file.",
        )
    }
}

private object DesktopJavaFxRuntime {
    private val lock = Any()
    @Volatile
    private var started = false

    fun ensureStarted() {
        if (started) return
        synchronized(lock) {
            if (started) return
            runCatching { Platform.startup {} }
                .onFailure { failure ->
                    if (failure !is IllegalStateException) throw failure
                }
            started = true
        }
    }

    fun runLater(block: () -> Unit) {
        ensureStarted()
        Platform.runLater(block)
    }
}

private fun String.audioFileSuffix(): String = when (lowercase()) {
    "audio/mpeg", "audio/mp3" -> ".mp3"
    "audio/mp4", "audio/aac", "audio/x-m4a" -> ".m4a"
    "audio/ogg", "audio/opus" -> ".ogg"
    "audio/flac", "audio/x-flac" -> ".flac"
    "audio/wav", "audio/x-wav" -> ".wav"
    else -> ".audio"
}

private const val DEFAULT_AUDIO_COPY_BUFFER_BYTES = 64 * 1024
