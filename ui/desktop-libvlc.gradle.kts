// libVLC for in-app desktop video playback.
//
// Windows and macOS packages carry VideoLAN's official VLC 3 release libraries, pinned by
// SHA-256 below. Linux packages instead recommend the distribution's libVLC, which vlcj finds
// at runtime. Only packaging and `run` tasks need these files; tests never download them.

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

val desktopLibVlcVersion = "3.0.24"

data class DesktopLibVlcArchive(val url: String, val sha256: String, val platform: String) {
    val fileName: String get() = url.substringAfterLast('/')

    /** The same file on VideoLAN's own server, used when mirrors do not match the pin. */
    val originUrl: String
        get() = url.replace("https://get.videolan.org/vlc/", "https://download.videolan.org/pub/videolan/vlc/")
}

val desktopLibVlcHostArchitecture = System.getProperty("os.arch").lowercase()
val desktopLibVlcArchive: DesktopLibVlcArchive? = System.getProperty("os.name").let { os ->
    val base = "https://get.videolan.org/vlc/$desktopLibVlcVersion"
    when {
        os.startsWith("Windows", ignoreCase = true) -> DesktopLibVlcArchive(
            url = "$base/win64/vlc-$desktopLibVlcVersion-win64.zip",
            sha256 = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323",
            platform = "windows",
        )
        os.startsWith("Mac", ignoreCase = true) &&
            desktopLibVlcHostArchitecture in setOf("aarch64", "arm64") -> DesktopLibVlcArchive(
            url = "$base/macosx/vlc-$desktopLibVlcVersion-arm64.dmg",
            sha256 = "64a89d93cdd30b0e97131743e246373db82d6826ea882d265d46d74b136da2b7",
            platform = "macos",
        )
        os.startsWith("Mac", ignoreCase = true) -> DesktopLibVlcArchive(
            url = "$base/macosx/vlc-$desktopLibVlcVersion-intel64.dmg",
            sha256 = "1ef6c903e2dc026d4e58ddf7b2a9ed0eb6f8caf80a9a9f46f96490340b962707",
            platform = "macos",
        )
        else -> null
    }
}

// Plugin groups that only serve VLC's own interfaces, scripting, or streaming output.
val desktopLibVlcExcludedPluginGroups = listOf(
    "access_output", "control", "gui", "lua", "mux", "services_discovery", "stream_out",
    "video_splitter", "visualization",
)

val desktopLibVlcDownloadDirectory = layout.buildDirectory.dir("libvlc/download")
val desktopLibVlcLicenseDirectory = rootProject.layout.projectDirectory.dir("release/third-party/libvlc")
val desktopAppResourcesDirectory = layout.buildDirectory.dir("generated/desktop-app-resources")

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadDesktopLibVlc by tasks.registering {
    description = "Downloads and verifies the pinned VideoLAN libVLC release for this desktop OS."
    val archive = desktopLibVlcArchive
    onlyIf { archive != null }
    if (archive != null) {
        inputs.property("url", archive.url)
        inputs.property("sha256", archive.sha256)
        outputs.file(desktopLibVlcDownloadDirectory.map { it.file(archive.fileName) })
    }
    doLast {
        val pinned = checkNotNull(archive)
        val target = desktopLibVlcDownloadDirectory.get().file(pinned.fileName).asFile
        if (target.isFile && target.sha256() == pinned.sha256) return@doLast
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${pinned.fileName}.partial")
        // get.videolan.org redirects to a random mirror, and a mirror can serve a stale or broken
        // file. Only bytes matching the pin are accepted; after two mirror attempts the VideoLAN
        // origin server is used.
        val sources = listOf(pinned.url, pinned.url, pinned.originUrl)
        val rejected = mutableListOf<String>()
        for ((index, source) in sources.withIndex()) {
            try {
                URI.create(source).toURL().openStream().use { input ->
                    Files.copy(input, partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (failure: java.io.IOException) {
                rejected += "$source: ${failure.javaClass.simpleName}"
                Thread.sleep(2_000L * (index + 1))
                continue
            }
            val actual = partial.sha256()
            if (actual == pinned.sha256) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return@doLast
            }
            rejected += "$source: SHA-256 $actual (${partial.length()} bytes)"
            partial.delete()
        }
        throw GradleException(
            "No source served ${pinned.fileName} with SHA-256 ${pinned.sha256}:\n" + rejected.joinToString("\n"),
        )
    }
}

val prepareDesktopLibVlc by tasks.registering {
    description = "Stages libVLC and its playback plugins as desktop application resources."
    dependsOn(downloadDesktopLibVlc)
    val archive = desktopLibVlcArchive
    if (archive != null) {
        inputs.file(desktopLibVlcDownloadDirectory.map { it.file(archive.fileName) })
        inputs.property("excludedPluginGroups", desktopLibVlcExcludedPluginGroups)
        inputs.dir(desktopLibVlcLicenseDirectory)
    }
    outputs.dir(desktopAppResourcesDirectory)
    doLast {
        val resources = desktopAppResourcesDirectory.get().asFile
        resources.deleteRecursively()
        // Compose packages `common` on every OS; it stays empty but must exist.
        File(resources, "common").mkdirs()
        val pinned = archive ?: return@doLast
        val output = File(resources, "${pinned.platform}/libvlc")
        val downloaded = desktopLibVlcDownloadDirectory.get().file(pinned.fileName).asFile
        when (pinned.platform) {
            "windows" -> {
                val root = "vlc-$desktopLibVlcVersion"
                copy {
                    from(zipTree(downloaded)) {
                        include("$root/libvlc.dll", "$root/libvlccore.dll", "$root/plugins/**")
                        desktopLibVlcExcludedPluginGroups.forEach { exclude("$root/plugins/$it/**") }
                        // Blu-ray menu jars would otherwise join the application class path.
                        exclude("**/*.jar")
                        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                    }
                    includeEmptyDirs = false
                    into(output)
                }
            }
            "macos" -> {
                val mount = File(temporaryDir, "vlc-mount")
                mount.deleteRecursively()
                mount.mkdirs()
                providers.exec {
                    commandLine("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mount.absolutePath, downloaded.absolutePath)
                }.result.get().assertNormalExitValue()
                try {
                    val macOs = File(mount, "VLC.app/Contents/MacOS")
                    copy {
                        from(File(macOs, "lib")) { into("lib") }
                        // macOS plugins are one flat directory, so all of them are kept.
                        from(File(macOs, "plugins")) {
                            into("plugins")
                            exclude("**/*.jar")
                        }
                        into(output)
                    }
                } finally {
                    providers.exec { commandLine("hdiutil", "detach", mount.absolutePath) }.result.get()
                }
            }
        }
        check(File(output, if (pinned.platform == "windows") "libvlc.dll" else "lib/libvlc.dylib").isFile) {
            "The libVLC archive did not contain the expected library."
        }
        check(File(output, "plugins").isDirectory) { "The libVLC archive did not contain its plugins." }
        // VideoLAN's binary archives omit COPYING.LIB, so both texts come from the 3.0.24 source tag.
        copy {
            from(desktopLibVlcLicenseDirectory) { include("COPYING", "COPYING.LIB") }
            into(output)
        }
        check(File(output, "COPYING.LIB").isFile && File(output, "COPYING").isFile) {
            "The libVLC license texts were not staged."
        }
        File(output, "README.txt").writeText(
            listOf(
                "libVLC $desktopLibVlcVersion from VideoLAN, used by nati.ve for in-app video playback.",
                "License: libVLC is under the GNU Lesser General Public License 2.1 or later (COPYING.LIB).",
                "Some VLC plugins are under the GNU General Public License 2 or later (COPYING).",
                "Release binaries: ${pinned.url}",
                "Corresponding source: https://get.videolan.org/vlc/$desktopLibVlcVersion/vlc-$desktopLibVlcVersion.tar.xz",
            ).joinToString(separator = "\n", postfix = "\n"),
        )
    }
}
