package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Path
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider

/**
 * Finds libVLC once per process and owns the shared player factory.
 *
 * The copy bundled in the desktop package wins over a system VLC through
 * [DesktopBundledLibVlcDirectoryProvider]. Without either, in-app playback is unavailable and the
 * viewer keeps its external-app fallback.
 */
internal class DesktopLibVlcRuntime(private val enabled: Boolean) {
    private val lock = Any()
    private val discoveryStarted = AtomicBoolean(false)
    private val discovered = mutableStateOf<Boolean?>(if (enabled) null else false)
    private var factory: MediaPlayerFactory? = null
    private var factoryFailed = false

    /** The directory libVLC was loaded from, for local diagnostics; null when unavailable. */
    val libraryDirectory: String? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!enabled) return@lazy null
        try {
            val discovery = NativeDiscovery()
            // A library already on the default search path reports no directory.
            if (discovery.discover()) discovery.discoveredPath() ?: "" else null
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Blocks on discovery; call only off the UI thread. */
    val isAvailable: Boolean
        get() = libraryDirectory != null

    /**
     * Whether in-app playback can be offered right now, without blocking. Before discovery has
     * finished this is false, and the snapshot read recomposes the caller once it completes.
     */
    fun availableOrStartDiscovery(): Boolean {
        val known = discovered.value
        if (known == null) startDiscovery()
        return known == true
    }

    fun startDiscovery() {
        if (!enabled || !discoveryStarted.compareAndSet(false, true)) return
        Thread({ discovered.value = isAvailable }, "nc-libvlc-discovery").apply {
            isDaemon = true
            start()
        }
    }

    /** Creating the factory scans libVLC plugins, so it must run off the UI thread. */
    fun factoryOrNull(): MediaPlayerFactory? {
        if (!isAvailable) return null
        synchronized(lock) {
            factory?.let { return it }
            if (factoryFailed) return null
            return try {
                MediaPlayerFactory(*DESKTOP_LIBVLC_ARGUMENTS).also { factory = it }
            } catch (_: LinkageError) {
                factoryFailed = true
                null
            } catch (_: RuntimeException) {
                factoryFailed = true
                null
            }
        }
    }
}

internal val sharedDesktopLibVlcRuntime = DesktopLibVlcRuntime(
    enabled = System.getProperty(DESKTOP_LIBVLC_PROPERTY) != DESKTOP_LIBVLC_DISABLED,
)

/**
 * Finds libVLC on a background thread at startup so the media viewer never loads native
 * libraries during composition. A slow or failed discovery only delays in-app video.
 */
fun warmDesktopVideoPlayback() = sharedDesktopLibVlcRuntime.startDiscovery()

/** Registered through `META-INF/services` so vlcj checks the bundled copy before system paths. */
class DesktopBundledLibVlcDirectoryProvider : DiscoveryDirectoryProvider {
    override fun priority(): Int = BUNDLED_LIBVLC_DISCOVERY_PRIORITY

    override fun supported(): Boolean = bundledDirectory() != null

    override fun directories(): Array<String> = bundledDirectory()?.let { arrayOf(it.toString()) } ?: emptyArray()

    private fun bundledDirectory(): Path? = bundledLibVlcLibraryDirectory(
        resourcesDirectory = System.getProperty("compose.application.resources.dir"),
        operatingSystem = System.getProperty("os.name").orEmpty(),
    )
}

/**
 * The directory holding the bundled libVLC library, or null when this package carries none.
 * Windows keeps `libvlc.dll` beside `plugins`; macOS keeps `lib/libvlc.dylib` beside `plugins`.
 */
internal fun bundledLibVlcLibraryDirectory(resourcesDirectory: String?, operatingSystem: String): Path? {
    if (resourcesDirectory.isNullOrBlank()) return null
    val root = Path.of(resourcesDirectory).resolve(BUNDLED_LIBVLC_DIRECTORY)
    val (libraryDirectory, libraryName) = when {
        operatingSystem.startsWith("Windows", ignoreCase = true) -> root to "libvlc.dll"
        operatingSystem.startsWith("Mac", ignoreCase = true) -> root.resolve("lib") to "libvlc.dylib"
        else -> root.resolve("lib") to "libvlc.so"
    }
    return libraryDirectory.takeIf {
        Files.isRegularFile(it.resolve(libraryName)) && Files.isDirectory(root.resolve("plugins"))
    }
}

internal const val DESKTOP_LIBVLC_PROPERTY = "dev.obiente.nextcloudnative.libvlc"
internal const val DESKTOP_LIBVLC_DISABLED = "disabled"
internal const val BUNDLED_LIBVLC_DIRECTORY = "libvlc"
private const val BUNDLED_LIBVLC_DISCOVERY_PRIORITY = 2
private val DESKTOP_LIBVLC_ARGUMENTS = arrayOf(
    "--quiet",
    "--no-video-title-show",
    "--no-snapshot-preview",
    "--no-osd",
)
