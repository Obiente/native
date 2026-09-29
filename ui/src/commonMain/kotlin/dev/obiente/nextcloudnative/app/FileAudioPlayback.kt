package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioFileReference
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioTrack

internal fun fileAudioPlaybackSource(session: NextcloudSession, userId: String, file: NextcloudFile): NativeAudioPlaybackSource? {
    val mime = file.mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
    if (file.isDirectory || !file.originalAccessAllowed || !file.davPathAuthoritative ||
        userId.isBlank() || file.size?.let { it <= 0 } == true || mime !in FILE_AUDIO_MIME_TYPES) return null
    return try {
        requireSafeFilePath(file.path, allowRoot = false)
        val revision = file.etag ?: file.lastModified.orEmpty()
        NativeAudioPlaybackSource(
            id = "file-audio:" + session.accountId.storageKey + ":" +
                publicContentSha256((file.path + "\u0000" + revision).encodeToByteArray()),
            relativePath = buildNextcloudFileUrl("", userId, file.path),
            mimeType = mime,
            knownSize = file.size,
            title = file.name.filterNot(Char::isISOControl).take(512),
        )
    } catch (_: IllegalArgumentException) { null }
}

/** Owns only explicit playback started by this file screen, including a pending asynchronous start. */
internal class FileAudioPlaybackSession(
    private val session: NextcloudSession,
    val source: NativeAudioPlaybackSource,
    private val engine: PlatformAudioPlaybackEngine,
) {
    private var started = false
    private var closed = false
    private var stateBeforeStart: NativeAudioEngineState? = null

    fun toggle() {
        if (closed) return
        val current = engine.state.value
        when (current.status.takeIf { current.sourceId == source.id }) {
            NativeAudioEngineStatus.Playing -> engine.pause()
            NativeAudioEngineStatus.Paused -> engine.resume()
            NativeAudioEngineStatus.Loading -> stop()
            else -> {
                stateBeforeStart = current
                started = true
                engine.play(session, source)
            }
        }
    }

    fun seek(position: Long) {
        if (!closed && engine.state.value.sourceId == source.id) engine.seekTo(position)
    }

    fun stop() {
        if (closed) return
        stopOwned()
    }

    fun close() {
        if (closed) return
        stopOwned()
        closed = true
    }

    private fun stopOwned() {
        val current = engine.state.value
        if (started && (current.sourceId == source.id || current == stateBeforeStart)) engine.stop()
        started = false
    }
}

@Composable
internal fun FileAudioPlayback(
    session: NextcloudSession,
    source: NativeAudioPlaybackSource,
    engine: PlatformAudioPlaybackEngine = rememberPlatformAudioPlaybackEngine(),
) {
    val owner = remember(session, source, engine) { FileAudioPlaybackSession(session, source, engine) }
    DisposableEffect(owner) { onDispose(owner::close) }
    val current by engine.state.collectAsState()
    val state = current.takeIf { it.sourceId == source.id } ?: NativeAudioEngineState()
    val queue = remember(source) {
        NativeAudioQueueState(listOf(NativeAudioTrack(source.id, source.title.orEmpty(), null, null, null, null,
            listOf(NativeAudioFileReference(null, source.mimeType, source.relativePath)))), 0)
    }
    NativeAudioMiniPlayer(queue, state, null, null,
        onPrevious = { owner.seek(0) }, onTogglePlayback = owner::toggle, onNext = {}, onSelectTrack = {},
        onSeek = owner::seek, onStop = owner::stop)
}

private val FILE_AUDIO_MIME_TYPES = setOf("audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac", "audio/x-m4a",
    "audio/ogg", "audio/opus", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav")

@Composable
internal fun FileAudioPlaybackOrIcon(session: NextcloudSession, userId: String, file: NextcloudFile, icon: ImageVector) {
    val source = remember(session, userId, file) { fileAudioPlaybackSource(session, userId, file) }
    if (source != null) FileAudioPlayback(session, source) else {
        Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(NextcloudRadii.Medium)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(18.dp).size(38.dp))
        }
    }
}
