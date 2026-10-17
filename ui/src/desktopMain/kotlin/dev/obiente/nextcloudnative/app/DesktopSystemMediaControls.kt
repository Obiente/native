package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Window
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** A command from the operating system's media controls: media keys, the media flyout, or a remote. */
internal enum class SystemMediaCommand { Play, Pause, TogglePlayPause, Stop, Next, Previous }

/** What the operating system's media controls show for the playing video. */
internal data class SystemMediaSnapshot(
    val title: String,
    val playing: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
)

/**
 * One platform integration: Windows system media transport controls, Linux MPRIS, or macOS Now
 * Playing. Every call happens on the owning [DesktopSystemMediaSession] thread. Commands may
 * arrive on any thread.
 */
internal interface DesktopSystemMediaControls : AutoCloseable {
    fun update(snapshot: SystemMediaSnapshot)
}

internal object UnavailableDesktopSystemMediaControls : DesktopSystemMediaControls {
    override fun update(snapshot: SystemMediaSnapshot) = Unit

    override fun close() = Unit
}

/** Opens the integration for this operating system, or [UnavailableDesktopSystemMediaControls]. */
internal typealias DesktopSystemMediaControlsFactory =
    (window: Window?, onCommand: (SystemMediaCommand) -> Unit) -> DesktopSystemMediaControls

internal val platformDesktopSystemMediaControls: DesktopSystemMediaControlsFactory = { window, onCommand ->
    if (System.getProperty(DESKTOP_SYSTEM_MEDIA_PROPERTY) == DESKTOP_SYSTEM_MEDIA_DISABLED) {
        UnavailableDesktopSystemMediaControls
    } else {
        val os = System.getProperty("os.name").orEmpty()
        when {
            os.startsWith("Windows", ignoreCase = true) -> WindowsSystemMediaControls.openOrNull(window, onCommand)
            os.startsWith("Mac", ignoreCase = true) -> MacOsSystemMediaControls.openOrNull(onCommand)
            else -> LinuxMprisSystemMediaControls.openOrNull(onCommand)
        } ?: UnavailableDesktopSystemMediaControls
    }
}

/**
 * Owns one integration off the UI thread, so native calls, D-Bus round trips, and COM activation
 * never block it. Only the newest snapshot is published.
 *
 * Every session shares one thread. When the viewer replaces a video, the old session's close is
 * queued before the new session opens, so tearing down the old integration can never disable the
 * window's Windows controls or clear the process-wide macOS Now Playing entry after the new video
 * published them.
 */
internal class DesktopSystemMediaSession(
    window: Window?,
    factory: DesktopSystemMediaControlsFactory,
    onCommand: (SystemMediaCommand) -> Unit,
) : AutoCloseable {
    private val executor: ExecutorService = sharedSystemMediaExecutor

    @Volatile
    private var closed = false
    private var controls: DesktopSystemMediaControls = UnavailableDesktopSystemMediaControls
    private var published: SystemMediaSnapshot? = null

    init {
        submit {
            controls = try {
                factory(window) { command -> if (!closed) onCommand(command) }
            } catch (_: RuntimeException) {
                UnavailableDesktopSystemMediaControls
            } catch (_: LinkageError) {
                UnavailableDesktopSystemMediaControls
            }
        }
    }

    fun update(snapshot: SystemMediaSnapshot) = submit {
        if (snapshot == published) return@submit
        published = snapshot
        try {
            controls.update(snapshot)
        } catch (_: RuntimeException) {
            discardFailedControls()
        } catch (_: LinkageError) {
            discardFailedControls()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            executor.execute { closeQuietly(controls) }
        } catch (_: RejectedExecutionException) {
            // The shared thread is never shut down; nothing else can reject.
        }
    }

    private fun submit(block: () -> Unit) {
        if (closed) return
        try {
            executor.execute { if (!closed) block() }
        } catch (_: RejectedExecutionException) {
            // Closed while submitting.
        }
    }

    /**
     * A failing platform integration must never break playback. It stops updating, but is still
     * closed so its button handler, bus name, or Now Playing entry is not left registered.
     */
    private fun discardFailedControls() {
        val failed = controls
        controls = UnavailableDesktopSystemMediaControls
        closeQuietly(failed)
    }

    private fun closeQuietly(target: DesktopSystemMediaControls) {
        try {
            target.close()
        } catch (_: RuntimeException) {
            // Best effort: the integration is already unusable.
        } catch (_: LinkageError) {
            // Best effort: the integration is already unusable.
        }
    }
}

private val sharedSystemMediaExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
    Thread(task, "nc-system-media").apply { isDaemon = true }
}

/** The desktop window that hosts the app, for integrations bound to a window handle. */
val LocalDesktopWindow = staticCompositionLocalOf<Window?> { null }

internal const val DESKTOP_SYSTEM_MEDIA_PROPERTY = "dev.obiente.nextcloudnative.systemmedia"
internal const val DESKTOP_SYSTEM_MEDIA_DISABLED = "disabled"
