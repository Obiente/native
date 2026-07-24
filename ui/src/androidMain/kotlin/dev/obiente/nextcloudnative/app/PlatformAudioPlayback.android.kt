package dev.obiente.nextcloudnative.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.StateFlow

@Composable
internal actual fun rememberPlatformAudioPlaybackEngine(): PlatformAudioPlaybackEngine {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidAudioPlaybackEngine(context) }
}

/**
 * Thin UI controller for the process-owned MediaSessionService.
 *
 * Releasing a Compose screen deliberately does not stop playback. The Android media session owns
 * that lifecycle so music continues on the lock screen and while another app is in front.
 */
private class AndroidAudioPlaybackEngine(
    private val context: Context,
) : PlatformAudioPlaybackEngine {
    override val state: StateFlow<NativeAudioEngineState> = AndroidAudioPlaybackBridge.state

    override fun play(session: NextcloudSession, source: NativeAudioPlaybackSource) {
        playQueue(session, listOf(source), 0)
    }

    override fun playQueue(
        session: NextcloudSession,
        sources: List<NativeAudioPlaybackSource>,
        currentIndex: Int,
    ) {
        if (sources.isEmpty() || currentIndex !in sources.indices) return
        AndroidAudioPlaybackBridge.pendingRequest = AndroidAudioPlaybackRequest(session, sources, currentIndex)
        send(AndroidAudioPlaybackService.ACTION_PLAY)
    }

    override fun pause() {
        send(AndroidAudioPlaybackService.ACTION_PAUSE)
    }

    override fun resume() {
        send(AndroidAudioPlaybackService.ACTION_RESUME)
    }

    override fun seekTo(positionMillis: Long) {
        send(
            AndroidAudioPlaybackService.ACTION_SEEK,
            AndroidAudioPlaybackService.EXTRA_POSITION_MILLIS to positionMillis.coerceAtLeast(0),
        )
    }

    override fun stop() {
        send(AndroidAudioPlaybackService.ACTION_STOP)
    }

    override fun release() = Unit

    private fun send(action: String, extra: Pair<String, Long>? = null) {
        val intent = Intent(context, AndroidAudioPlaybackService::class.java).setAction(action)
        extra?.let { (key, value) -> intent.putExtra(key, value) }
        context.startService(intent)
    }
}

internal data class AndroidAudioPlaybackRequest(
    val session: NextcloudSession,
    val sources: List<NativeAudioPlaybackSource>,
    val currentIndex: Int,
)
