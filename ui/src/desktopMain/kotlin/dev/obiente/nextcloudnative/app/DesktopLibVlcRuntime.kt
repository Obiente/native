package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileFilter
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Path
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DirectoryProviderDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.factory.discovery.strategy.LinuxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.OsxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy

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
            val discovery = NativeDiscovery(*desktopLibVlcDiscoveryStrategies())
            // vlcj 4 has no default-search-path fallback: success always reports a directory.
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

    /** Whether discovery has not finished yet, without blocking; starts it when needed. */
    fun discoveryPending(): Boolean {
        val known = discovered.value
        if (known == null) startDiscovery()
        return known == null
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
 * vlcj's own strategies, with each candidate directory checked directly.
 *
 * vlcj searches every candidate recursively and follows symbolic links, so without libVLC it walks
 * all of /usr/lib, /usr/local/lib, and every PATH entry. That takes minutes or longer, and the
 * viewer hides its external-player fallback until discovery ends. The candidates, their order, and
 * the per-OS loading and plugin path handling stay vlcj's; only the subdirectory search is gone.
 * Linux also checks the Debian multiarch directories that the recursive search used to reach.
 */
internal fun desktopLibVlcDiscoveryStrategies(
    osArch: String = System.getProperty("os.arch").orEmpty(),
): Array<NativeDiscoveryStrategy> {
    val linux = LinuxNativeDiscoveryStrategy()
    return arrayOf(
        DesktopLibVlcDirectDiscoveryStrategy(linux, LINUX_LIBVLC_NAMES) {
            linuxLibVlcCandidates(linux.discoveryDirectories(), osArch)
        },
        DesktopLibVlcDirectDiscoveryStrategy(
            OsxNativeDiscoveryStrategy(),
            listOf(Regex("libvlc\\.dylib"), Regex("libvlccore\\.dylib")),
        ),
        DesktopLibVlcDirectDiscoveryStrategy(
            WindowsNativeDiscoveryStrategy(),
            listOf(Regex("libvlc\\.dll"), Regex("libvlccore\\.dll")),
        ),
    )
}

/** vlcj's Linux names, which accept the versioned runtime files such as `libvlc.so.5`. */
internal val LINUX_LIBVLC_NAMES = listOf(Regex("libvlc\\.so(?:\\.\\d)*"), Regex("libvlccore\\.so(?:\\.\\d)*"))

/**
 * vlcj's Linux candidates with Debian multiarch directories added.
 *
 * vlcj names only the x86_64 and i386 triplet directories, so on other architectures the distro
 * libVLC in `/usr/lib/<triplet>` was found only by the recursive search. The JVM's own triplet goes
 * before vlcj's first system directory. vlcj stops at the first directory holding libVLC even when
 * that copy cannot load, so the other triplets go last, where they never hide a loadable copy.
 */
internal fun linuxLibVlcCandidates(
    vlcjCandidates: List<String>,
    osArch: String,
    usrDirectory: String = "/usr",
): List<String> {
    val native = debianMultiarchTriplet(osArch)?.let { "$usrDirectory/lib/$it" }
    val others = DEBIAN_MULTIARCH_TRIPLETS.map { "$usrDirectory/lib/$it" }
    val firstSystem = vlcjCandidates.indexOfFirst { it.startsWith("$usrDirectory/") }
        .takeIf { it >= 0 } ?: vlcjCandidates.size
    return (vlcjCandidates.take(firstSystem) + listOfNotNull(native) + vlcjCandidates.drop(firstSystem) + others)
        .distinct()
}

/** The Debian multiarch triplet for a JVM `os.arch` value, or null when it is not a known one. */
internal fun debianMultiarchTriplet(osArch: String): String? = when (osArch.lowercase()) {
    "amd64", "x86_64" -> "x86_64-linux-gnu"
    "aarch64", "arm64" -> "aarch64-linux-gnu"
    "arm", "armv7l", "armhf" -> "arm-linux-gnueabihf"
    "x86", "i386", "i486", "i586", "i686" -> "i386-linux-gnu"
    "riscv64" -> "riscv64-linux-gnu"
    "ppc64le" -> "powerpc64le-linux-gnu"
    "s390x" -> "s390x-linux-gnu"
    else -> null
}

private val DEBIAN_MULTIARCH_TRIPLETS = listOf(
    "x86_64-linux-gnu",
    "aarch64-linux-gnu",
    "arm-linux-gnueabihf",
    "i386-linux-gnu",
    "riscv64-linux-gnu",
    "powerpc64le-linux-gnu",
    "s390x-linux-gnu",
)

internal class DesktopLibVlcDirectDiscoveryStrategy(
    private val delegate: DirectoryProviderDiscoveryStrategy,
    private val libraryNames: List<Regex>,
    private val candidates: () -> List<String> = delegate::discoveryDirectories,
) : NativeDiscoveryStrategy {
    override fun supported(): Boolean = delegate.supported()

    override fun discover(): String? = firstDirectoryContainingLibraries(candidates(), libraryNames)

    override fun onFound(path: String): Boolean = delegate.onFound(path)

    override fun onSetPluginPath(path: String): Boolean = delegate.onSetPluginPath(path)
}

/** The first of [directories] that itself holds a file matching each of [libraryNames]; subdirectories are never searched. */
internal fun firstDirectoryContainingLibraries(directories: List<String>, libraryNames: List<Regex>): String? =
    directories.asSequence()
        .map(::File)
        .firstOrNull { directory ->
            val names = directory.listFiles(FileFilter { it.isFile })?.map(File::getName).orEmpty()
            libraryNames.all { pattern -> names.any(pattern::matches) }
        }
        ?.absolutePath

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
