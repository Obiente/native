package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.jseproject.DecodedFlacAudioInputStream
import io.github.jseproject.FlacAudioFileReader
import io.github.jseproject.FlacAudioInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

internal data class JavaSoundPlayerCallbacks(
    val onLoading: (Long) -> Unit,
    val onReady: (Long?) -> Unit,
    val onPlaying: () -> Unit,
    val onPaused: () -> Unit,
    val onPosition: (Long, Long?) -> Unit,
    val onEnded: (Long?) -> Unit,
    val onError: (Throwable) -> Unit,
)

/**
 * Streaming JavaSound player backed by bundled decoder SPIs.
 *
 * Compressed media is decoded incrementally into PCM. Seeking reopens the immutable staged file and
 * skips decoded frames, avoiding an unbounded in-memory PCM copy.
 */
internal class JavaSoundAudioPlayer(
    private val callbacks: JavaSoundPlayerCallbacks,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private var job: Job? = null
    @Volatile
    private var sourcePath: Path? = null
    @Volatile
    private var activeStream: AudioInputStream? = null
    @Volatile
    private var activeLine: SourceDataLine? = null
    @Volatile
    private var paused = false
    @Volatile
    private var positionMillis = 0L
    @Volatile
    private var durationMillis: Long? = null

    fun play(path: Path, startMillis: Long = 0, startPlaying: Boolean = true) {
        sourcePath = path
        launchPlayback(path, startMillis.coerceAtLeast(0), startPlaying)
    }

    fun pause() {
        if (paused) return
        paused = true
        activeLine?.stop()
        callbacks.onPaused()
    }

    fun resume() {
        if (!paused) return
        paused = false
        activeLine?.start()
        callbacks.onPlaying()
    }

    fun seekTo(targetMillis: Long) {
        val path = sourcePath ?: return
        launchPlayback(path, targetMillis.coerceAtLeast(0), startPlaying = !paused)
    }

    fun stop() {
        generation.incrementAndGet()
        job?.cancel()
        closeActiveTransport()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun launchPlayback(path: Path, startMillis: Long, startPlaying: Boolean) {
        val playbackGeneration = generation.incrementAndGet()
        job?.cancel()
        closeActiveTransport()
        paused = !startPlaying
        positionMillis = startMillis
        callbacks.onLoading(startMillis)
        job = scope.launch {
            var ownedStream: AudioInputStream? = null
            var ownedLine: SourceDataLine? = null
            runCatching {
                val opened = openDecodedPcmAudio(path)
                if (generation.get() != playbackGeneration) {
                    opened.stream.close()
                    return@launch
                }
                durationMillis = opened.durationMillis
                callbacks.onReady(opened.durationMillis)
                val skippedMillis = opened.stream.skipDecodedMillis(startMillis)
                positionMillis = skippedMillis
                val line = AudioSystem.getSourceDataLine(opened.format)
                line.open(opened.format, JAVA_SOUND_OUTPUT_BUFFER_BYTES)
                if (generation.get() != playbackGeneration) {
                    line.close()
                    opened.stream.close()
                    return@launch
                }
                ownedStream = opened.stream
                ownedLine = line
                activeStream = opened.stream
                activeLine = line
                if (startPlaying) {
                    line.start()
                } else {
                    callbacks.onPaused()
                }
                val buffer = ByteArray(JAVA_SOUND_DECODE_BUFFER_BYTES)
                var decodedBytesWritten = 0L
                var playbackAnnounced = false
                var reachedEnd = false
                while (generation.get() == playbackGeneration) {
                    while (paused && generation.get() == playbackGeneration) delay(20)
                    if (generation.get() != playbackGeneration) break
                    val read = opened.stream.read(buffer)
                    if (read < 0) {
                        reachedEnd = true
                        break
                    }
                    var offset = 0
                    while (offset < read && generation.get() == playbackGeneration) {
                        val written = line.write(buffer, offset, read - offset)
                        offset += written
                        decodedBytesWritten += written
                        if (startPlaying && !playbackAnnounced && written > 0) {
                            playbackAnnounced = true
                            callbacks.onPlaying()
                        }
                    }
                    val decodedMillis = opened.format.frameSize.takeIf { it > 0 }?.let { frameSize ->
                        opened.format.frameRate.takeIf { it > 0f }?.let { frameRate ->
                            ((decodedBytesWritten / frameSize) * 1_000.0 / frameRate).toLong()
                        }
                    } ?: 0L
                    val deviceMillis = (line.microsecondPosition / 1_000L).coerceAtLeast(0)
                    positionMillis = skippedMillis + maxOf(deviceMillis, decodedMillis)
                    callbacks.onPosition(positionMillis, opened.durationMillis)
                }
                if (reachedEnd && generation.get() == playbackGeneration) {
                    line.drain()
                    positionMillis = opened.durationMillis ?: positionMillis
                    callbacks.onEnded(opened.durationMillis)
                }
            }.onFailure { failure ->
                if (generation.get() == playbackGeneration) callbacks.onError(failure)
            }
            closeTransport(ownedLine, ownedStream)
            if (activeLine === ownedLine) activeLine = null
            if (activeStream === ownedStream) activeStream = null
        }
    }

    private fun closeActiveTransport() {
        val line = activeLine
        activeLine = null
        val stream = activeStream
        activeStream = null
        closeTransport(line, stream)
    }

    private fun closeTransport(line: SourceDataLine?, stream: AudioInputStream?) {
        runCatching { line?.stop() }
        runCatching { line?.flush() }
        runCatching { line?.close() }
        runCatching { stream?.close() }
    }
}

internal data class DecodedPcmAudio(
    val stream: AudioInputStream,
    val format: AudioFormat,
    val durationMillis: Long?,
)

internal fun openDecodedPcmAudio(path: Path): DecodedPcmAudio {
    val source = if (path.toString().endsWith(".flac", ignoreCase = true)) {
        openFlacAudioStream(path)
    } else {
        AudioSystem.getAudioInputStream(path.toFile())
    }
    val sourceFormat = source.format
    val sampleRate = sourceFormat.sampleRate.takeIf { it > 0f }
        ?: error("The decoder did not provide an audio sample rate.")
    val channels = sourceFormat.channels.takeIf { it in 1..8 }
        ?: error("The decoder did not provide a supported channel count.")
    val sourceBits = sourceFormat.sampleSizeInBits.takeIf { it in setOf(8, 16, 24) }
        ?: JAVA_SOUND_PCM_BITS
    val pcmFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        sampleRate,
        sourceBits,
        channels,
        channels * (sourceBits / 8),
        sampleRate,
        false,
    )
    val decoded = when {
        sourceFormat.matches(pcmFormat) -> source
        source is FlacAudioInputStream -> DecodedFlacAudioInputStream(pcmFormat, source)
        else -> {
        runCatching { AudioSystem.getAudioInputStream(pcmFormat, source) }
            .getOrElse { failure ->
                source.close()
                throw failure
            }
        }
    }
    val duration = (sourceFormat.properties()["duration"] as? Number)
        ?.toLong()
        ?.takeIf { it > 0 }
        ?.div(1_000L)
        ?: source.frameLength.takeIf { it > 0 && sampleRate > 0f }
            ?.let { frames -> (frames * 1_000.0 / sampleRate).toLong() }
    return DecodedPcmAudio(decoded, pcmFormat, duration)
}

/**
 * JSE's file overload buffers only 512,001 bytes while parsing FLAC metadata. Album artwork can
 * exceed that limit and invalidates its mark before it rewinds to the audio header. This seekable
 * stream gives the reader a real file-position mark, so artwork size cannot make a valid FLAC
 * unreadable.
 */
private fun openFlacAudioStream(path: Path): AudioInputStream {
    neutralizeUnsupportedFlacSeekTables(path)
    val input = RewindableFileInputStream(path)
    return runCatching { FlacAudioFileReader().getAudioInputStream(input) }
        .getOrElse { failure ->
            input.close()
            throw failure
        }
}

/**
 * The bundled pure-Java FLAC decoder crashes while copying valid SEEKTABLE metadata from some
 * encoders. Playback seeking already reopens and decodes from the beginning, so the staged
 * download does not need that optional index. Re-labeling only its metadata header as PADDING
 * preserves every byte offset and never touches the user's server-side file.
 */
private fun neutralizeUnsupportedFlacSeekTables(path: Path) {
    RandomAccessFile(path.toFile(), "rw").use { file ->
        if (file.length() < FLAC_SIGNATURE.size + 4) return
        val signature = ByteArray(FLAC_SIGNATURE.size)
        file.readFully(signature)
        if (!signature.contentEquals(FLAC_SIGNATURE)) return
        var lastBlock = false
        while (!lastBlock) {
            val headerOffset = file.filePointer
            if (headerOffset + 4 > file.length()) error("Truncated FLAC metadata header.")
            val header = file.readUnsignedByte()
            lastBlock = header and 0x80 != 0
            val blockType = header and 0x7f
            val blockLength =
                (file.readUnsignedByte() shl 16) or
                    (file.readUnsignedByte() shl 8) or
                    file.readUnsignedByte()
            val nextBlock = file.filePointer + blockLength
            if (nextBlock > file.length()) error("Truncated FLAC metadata block.")
            if (blockType == FLAC_SEEK_TABLE_BLOCK_TYPE) {
                file.seek(headerOffset)
                file.write((header and 0x80) or FLAC_PADDING_BLOCK_TYPE)
            }
            file.seek(nextBlock)
        }
    }
}

private class RewindableFileInputStream(path: Path) : InputStream() {
    private val file = RandomAccessFile(path.toFile(), "r")
    private var markPosition = 0L

    override fun read(): Int = file.read()

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        file.read(bytes, offset, length)

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0) return 0
        val current = file.filePointer
        val target = (current + byteCount).coerceAtMost(file.length())
        file.seek(target)
        return target - current
    }

    override fun available(): Int =
        (file.length() - file.filePointer).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun mark(readLimit: Int) {
        markPosition = file.filePointer
    }

    override fun reset() {
        file.seek(markPosition)
    }

    override fun markSupported(): Boolean = true

    override fun close() = file.close()
}

private fun AudioInputStream.skipDecodedMillis(targetMillis: Long): Long {
    if (targetMillis <= 0) return 0
    val frameSize = format.frameSize.takeIf { it > 0 } ?: return 0
    val frameRate = format.frameRate.takeIf { it > 0f } ?: return 0
    var remaining = (targetMillis * frameRate / 1_000.0).toLong() * frameSize
    val scratch = ByteArray(JAVA_SOUND_DECODE_BUFFER_BYTES)
    var skipped = 0L
    while (remaining > 0) {
        val direct = skip(remaining)
        if (direct > 0) {
            remaining -= direct
            skipped += direct
            continue
        }
        val read = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
        if (read < 0) break
        remaining -= read
        skipped += read
    }
    return ((skipped / frameSize) * 1_000.0 / frameRate).toLong()
}

internal fun desktopAudioBackend(mimeType: String): DesktopAudioBackend = when (mimeType.lowercase()) {
    "audio/mp4", "audio/x-m4a" -> DesktopAudioBackend.JavaFx
    else -> DesktopAudioBackend.JavaSound
}

internal enum class DesktopAudioBackend {
    JavaSound,
    JavaFx,
}

private const val JAVA_SOUND_PCM_BITS = 16
private const val JAVA_SOUND_DECODE_BUFFER_BYTES = 64 * 1024
private const val JAVA_SOUND_OUTPUT_BUFFER_BYTES = 256 * 1024
private val FLAC_SIGNATURE = "fLaC".encodeToByteArray()
private const val FLAC_PADDING_BLOCK_TYPE = 1
private const val FLAC_SEEK_TABLE_BLOCK_TYPE = 3
