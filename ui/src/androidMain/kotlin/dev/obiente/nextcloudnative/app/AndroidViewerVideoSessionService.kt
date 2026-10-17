package dev.obiente.nextcloudnative.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Publishes the media viewer's current video to Android's media controls: the media
 * notification, lock screen, Bluetooth, and headset buttons.
 *
 * The viewer owns the player and its lifetime. This service only hosts a session for whichever
 * player [AndroidViewerVideoSessionBridge] currently holds, and stops once the viewer detaches it.
 */
@OptIn(UnstableApi::class)
class AndroidViewerVideoSessionService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val onPlayer: (Player?) -> Unit = { player -> show(player) }

    override fun onCreate() {
        super.onCreate()
        AndroidViewerVideoSessionBridge.bind(onPlayer)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        AndroidViewerVideoSessionBridge.unbind(onPlayer)
        releaseSession()
        super.onDestroy()
    }

    private fun show(player: Player?) {
        releaseSession()
        if (player == null) {
            stopSelf()
            return
        }
        mediaSession = MediaSession.Builder(this, player)
            .setId(VIEWER_VIDEO_SESSION_ID)
            .apply { openAppIntent()?.let(::setSessionActivity) }
            .build()
            .also(::addSession)
    }

    private fun releaseSession() {
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
    }

    private fun openAppIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
}

/**
 * Hands the viewer's player to [AndroidViewerVideoSessionService] on the main thread. Each
 * attachment returns a token so a stale detach from a replaced player cannot remove a newer one.
 */
internal object AndroidViewerVideoSessionBridge {
    private var current: Player? = null
    private var token = 0L
    private var listener: ((Player?) -> Unit)? = null

    fun attach(context: Context, player: Player): Long {
        token += 1
        current = player
        listener?.invoke(player)
            ?: context.startService(Intent(context, AndroidViewerVideoSessionService::class.java))
        return token
    }

    fun detach(attachment: Long) {
        if (attachment != token) return
        current = null
        listener?.invoke(null)
    }

    fun bind(onChange: (Player?) -> Unit) {
        listener = onChange
        onChange(current)
    }

    fun unbind(onChange: (Player?) -> Unit) {
        if (listener === onChange) listener = null
    }
}

/**
 * Routes the system previous/next actions to the viewer's sequence instead of the player's
 * single-item timeline. A null callback leaves the action unavailable.
 */
@OptIn(UnstableApi::class)
internal class AndroidViewerVideoSessionPlayer(
    player: Player,
    private val previous: () -> (() -> Unit)?,
    private val next: () -> (() -> Unit)?,
) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    /**
     * Tells connected controllers, such as the notification and lock screen, that previous/next
     * availability changed. The wrapped player never reports this, because the viewer owns it.
     * Main thread.
     */
    fun refreshAvailableCommands() {
        val commands = availableCommands
        listeners.forEach { listener -> listener.onAvailableCommandsChanged(commands) }
    }

    override fun getAvailableCommands(): Player.Commands =
        viewerVideoSessionCommands(super.getAvailableCommands(), previous() != null, next() != null)

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun seekToPrevious() {
        previous()?.invoke()
    }

    override fun seekToPreviousMediaItem() {
        previous()?.invoke()
    }

    override fun seekToNext() {
        next()?.invoke()
    }

    override fun seekToNextMediaItem() {
        next()?.invoke()
    }

    override fun hasPreviousMediaItem(): Boolean = previous() != null

    override fun hasNextMediaItem(): Boolean = next() != null
}

/** The commands the session advertises: the player's own, with previous/next set by the viewer. */
internal fun viewerVideoSessionCommands(
    base: Player.Commands,
    hasPrevious: Boolean,
    hasNext: Boolean,
): Player.Commands {
    val skip = viewerVideoSkipCommands(hasPrevious, hasNext)
    return base.buildUpon()
        .removeAll(*skip.disabled.toIntArray())
        .addAll(*skip.enabled.toIntArray())
        .build()
}

/** Which skip commands the viewer's sequence enables; the single-item player never decides. */
internal data class ViewerVideoSkipCommands(val enabled: Set<Int>, val disabled: Set<Int>)

internal fun viewerVideoSkipCommands(hasPrevious: Boolean, hasNext: Boolean): ViewerVideoSkipCommands {
    val previous = setOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    val next = setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    val enabled = (if (hasPrevious) previous else emptySet()) + (if (hasNext) next else emptySet())
    return ViewerVideoSkipCommands(enabled = enabled, disabled = (previous + next) - enabled)
}

private const val VIEWER_VIDEO_SESSION_ID = "nati.ve.viewer-video"
