import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

val desktopArchitecture = System.getProperty("os.arch").lowercase()
val ncDesktopPackageVersion = providers.gradleProperty("ncDesktopPackageVersion").get()
val ncMacosPackageVersion = providers.gradleProperty("ncMacosPackageVersion").get()
val ncVersionName = providers.gradleProperty("ncVersionName").get()
val ncVersionCode = providers.gradleProperty("ncVersionCode").get()
val ncAppStreamReleaseDate = providers.gradleProperty("ncAppStreamReleaseDate")
    .orElse(LocalDate.now(ZoneOffset.UTC).toString())
    .get()
val ncDesktopReleaseBuild = providers.gradleProperty("ncDesktopReleaseBuild").orElse("false").get()
val ncDirectDesktopPackageUpdates = providers.gradleProperty("ncDirectDesktopPackageUpdates").orElse("false").get()
val linuxAppStreamMetadata = rootProject.layout.projectDirectory
    .file("release/linux/dev.obiente.nextcloudnative.metainfo.xml")
val generatedLinuxAppStreamMetadata = layout.buildDirectory
    .file("generated/linux-appstream/dev.obiente.nextcloudnative.metainfo.xml")
val linuxJpackageTemplates = rootProject.layout.projectDirectory.dir("release/linux/jpackage")
val generatedJpackageResources = layout.buildDirectory.dir("generated/jpackage-resources")
val debPackageDirectory = layout.buildDirectory.dir("compose/binaries/main/deb")
val rpmPackageDirectory = layout.buildDirectory.dir("compose/binaries/main/rpm")
val msiPackageDirectory = layout.buildDirectory.dir("compose/binaries/main/msi")
val debPackageSucceededMarker = layout.buildDirectory.file("compose/tmp/packageDeb.succeeded")
val rpmPackageSucceededMarker = layout.buildDirectory.file("compose/tmp/packageRpm.succeeded")
val windowsShellRegistrar = rootProject.layout.projectDirectory.file(
    "target/x86_64-pc-windows-msvc/release/nextcloud-native-shell-registrar.exe",
)
val windowsShellIcon = project.layout.projectDirectory.file("src/desktopMain/resources/nextcloud-native.ico")

val buildWindowsShellRegistrar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the supported Windows Explorer sync-root registration helper."
    inputs.file(rootProject.file("Cargo.toml"))
    inputs.file(rootProject.file("Cargo.lock"))
    inputs.file(rootProject.file("src/bin/nextcloud-native-shell-registrar.rs"))
    outputs.file(windowsShellRegistrar)
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    workingDir(rootProject.projectDir)
    environment("RUSTFLAGS", "-Ctarget-feature=+crt-static")
    commandLine(
        "cargo",
        "build",
        "--locked",
        "--release",
        "--target",
        "x86_64-pc-windows-msvc",
        "--bin",
        "nextcloud-native-shell-registrar",
    )
}

val stageWindowsShellAssets by tasks.registering {
    group = "distribution"
    description = "Adds the supported Windows Explorer integration to the desktop application image."
    dependsOn(buildWindowsShellRegistrar)
    inputs.file(windowsShellRegistrar)
    inputs.file(windowsShellIcon)
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/NextcloudNative")
    val packagedRegistrar = appImage.map { it.file("NextcloudNativeShellRegistrar.exe") }
    val packagedIcon = appImage.map { it.file("NextcloudNative.ico") }
    outputs.files(packagedRegistrar, packagedIcon)
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    doLast {
        val image = appImage.get().asFile
        check(image.resolve("NextcloudNative.exe").isFile) {
            "The Windows application image is unavailable for shell asset staging."
        }
        windowsShellRegistrar.asFile.copyTo(packagedRegistrar.get().asFile, overwrite = true)
        windowsShellIcon.asFile.copyTo(packagedIcon.get().asFile, overwrite = true)
        check(packagedRegistrar.get().asFile.isFile && packagedIcon.get().asFile.isFile) {
            "The Windows shell registration helper or icon was not added to the application image."
        }
    }
}

val prepareLinuxAppStreamMetadata by tasks.registering(Exec::class) {
    inputs.file(linuxAppStreamMetadata)
    inputs.file(rootProject.file("tools/render-linux-appstream-metadata.py"))
    inputs.property("packageVersion", ncDesktopPackageVersion)
    inputs.property("releaseName", ncVersionName)
    inputs.property("releaseDate", ncAppStreamReleaseDate)
    outputs.file(generatedLinuxAppStreamMetadata)
    commandLine(
        "python3",
        rootProject.file("tools/render-linux-appstream-metadata.py"),
        linuxAppStreamMetadata.asFile,
        generatedLinuxAppStreamMetadata.get().asFile,
        ncDesktopPackageVersion,
        ncVersionName,
        ncAppStreamReleaseDate,
    )
}

val prepareLinuxJpackageResources by tasks.registering {
    dependsOn(prepareLinuxAppStreamMetadata)
    inputs.file(generatedLinuxAppStreamMetadata)
    inputs.dir(linuxJpackageTemplates)
    outputs.dir(generatedJpackageResources)
    doLast {
        val output = generatedJpackageResources.get().asFile
        output.deleteRecursively()
        val linuxOutput = output.resolve("linux").apply { mkdirs() }
        val metadataBase64 = Base64.getEncoder()
            .encodeToString(generatedLinuxAppStreamMetadata.get().asFile.readBytes())
        linuxJpackageTemplates.asFile.listFiles().orEmpty().forEach { template ->
            val content = template.readText().replace("APPSTREAM_XML_BASE64", metadataBase64)
            linuxOutput.resolve(template.name).writeText(content)
        }
    }
}
val javafxClassifier = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        desktopArchitecture in setOf("aarch64", "arm64") -> "mac-aarch64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "mac"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "win"
    desktopArchitecture in setOf("aarch64", "arm64") -> "linux-aarch64"
    else -> "linux"
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

val verifyNoBraceRegexInCommonMain by tasks.registering {
    group = "verification"
    description = "Prevents Android-incompatible brace regexes from entering shared runtime code."
    val sharedKotlin = fileTree("src/commonMain") { include("**/*.kt") }
    inputs.files(sharedKotlin)
    doLast {
        val violations = sharedKotlin.files.flatMap { source ->
            source.readLines().mapIndexedNotNull { index, line ->
                val constructsRegex = "Regex(" in line || ".toRegex(" in line
                val containsEscapedBrace = "\\\\{" in line || "\\\\}" in line
                if (constructsRegex && containsEscapedBrace) {
                    "${source.relativeTo(projectDir)}:${index + 1}"
                } else {
                    null
                }
            }
        }
        check(violations.isEmpty()) {
            "Brace templates must use BracedTemplate.kt, never Regex: ${violations.joinToString()}"
        }
    }
}

tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin", ignoreCase = true)) {
        dependsOn(verifyNoBraceRegexInCommonMain)
    }
}

// libVLC for in-app desktop video playback.
//
// Windows and macOS packages carry VideoLAN's official VLC 3 release libraries, pinned by
// SHA-256 below. Linux packages instead recommend the distribution's libVLC, which vlcj finds
// at runtime. Only packaging and `run` tasks need these files; tests never download them.
//
// This stays in the module build script: Android lint (lintAnalyzeRelease, lint 9.3.1) also
// visits a Kotlin script applied with apply(from = ...) and crashes analysing it.

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
val DESKTOP_LIBVLC_CONNECT_TIMEOUT_MILLIS = 30_000
val DESKTOP_LIBVLC_READ_TIMEOUT_MILLIS = 60_000
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
                // Finite timeouts reject a mirror that accepts the connection and then stalls.
                val connection = URI.create(source).toURL().openConnection().apply {
                    connectTimeout = DESKTOP_LIBVLC_CONNECT_TIMEOUT_MILLIS
                    readTimeout = DESKTOP_LIBVLC_READ_TIMEOUT_MILLIS
                }
                connection.getInputStream().use { input ->
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

/**
 * Names a test that is still running after [threshold]. Only failures are logged, so a hung test
 * otherwise prints nothing until the task timeout or the CI job limit ends the build.
 */
class LongRunningTestReporter(private val taskLogger: Logger, private val threshold: Duration) : TestListener {
    private val scheduler = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "long-running-test-reporter").apply { isDaemon = true }
    }.apply {
        // The thread exits while no test runs, so no idle thread outlives the build.
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }
    private val pending = ConcurrentHashMap<TestDescriptor, ScheduledFuture<*>>()

    override fun beforeTest(testDescriptor: TestDescriptor) {
        pending[testDescriptor] = scheduler.schedule({
            taskLogger.lifecycle(
                "Test still running after ${threshold.toMinutes()} minutes: " +
                    "${testDescriptor.className} > ${testDescriptor.displayName}",
            )
        }, threshold.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
        pending.remove(testDescriptor)?.cancel(false)
    }

    override fun beforeSuite(suite: TestDescriptor) = Unit

    override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    // A full desktop run takes about five minutes on CI. Stop a stuck run well inside the CI job
    // limit, after naming the test that is stuck.
    timeout.set(Duration.ofMinutes(30))
    addTestListener(LongRunningTestReporter(logger, Duration.ofMinutes(3)))
    // Scene tests must not depend on whether the build machine has VLC installed.
    systemProperty("dev.obiente.nextcloudnative.libvlc", "disabled")
    // Nor may they register with the host's system media controls.
    systemProperty("dev.obiente.nextcloudnative.systemmedia", "disabled")
}

kotlin {
    androidTarget()
    jvm("desktop")
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
            implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
            // JetBrains' last published cross-platform Material Icons bundle.
            // Keep this pinned until the project moves to generated Material Symbols resources.
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            // 0.43.0 requires compileSdk 37. Keep the renderer on the newest
            // release that remains compatible with this project's SDK 36 toolchain.
            implementation("com.mikepenz:multiplatform-markdown-renderer:0.41.0")
            implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
            implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.0.0")
            implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val androidMain by getting {
            kotlin.srcDir("src/jvmMain/kotlin")
        }
        androidMain.dependencies {
            // Shared jvmMain sources translate contract acquisition failures for both JVM targets.
            implementation(project(":contractAcquisition"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.exifinterface)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.media3.session)
            implementation(libs.videolan.libvlc)
            implementation(libs.okhttp)
        }
        val desktopMain by getting {
            kotlin.srcDir("src/jvmMain/kotlin")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project(":contractAcquisition"))
            implementation(libs.okhttp)
            implementation(libs.org.json)
            implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:$javafxClassifier")
            implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$javafxClassifier")
            implementation("org.openjfx:javafx-media:${libs.versions.javafx.get()}:$javafxClassifier")
            implementation(libs.jse.spi.flac)
            implementation(libs.jse.spi.vorbis)
            implementation(libs.jse.spi.opus)
            implementation(libs.jse.spi.mp3)
            implementation(libs.jse.spi.aac)
            implementation(libs.vlcj)
            implementation("com.github.serceman:jnr-fuse:0.5.8")
            implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
            implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")
            implementation("net.java.dev.jna:jna:5.19.1")
            implementation("net.java.dev.jna:jna-platform:5.19.1")
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
            implementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
        }
    }
}

android {
    namespace = "dev.obiente.nextcloudnative.nativeui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
}

compose.desktop {
    application {
        mainClass = "dev.obiente.nextcloudnative.nativeui.preview.MainKt"
        jvmArgs += listOf(
            "-Ddev.obiente.nextcloudnative.versionName=$ncVersionName",
            "-Ddev.obiente.nextcloudnative.versionCode=$ncVersionCode",
            "-Ddev.obiente.nextcloudnative.packageVersion=$ncDesktopPackageVersion",
            "-Ddev.obiente.nextcloudnative.releaseBuild=$ncDesktopReleaseBuild",
            "-Ddev.obiente.nextcloudnative.directPackageUpdates=$ncDirectDesktopPackageUpdates",
        )

        nativeDistributions {
            modules("jdk.security.auth")
            // libVLC for in-app video on Windows and macOS; see prepareDesktopLibVlc above.
            appResourcesRootDir.set(layout.buildDirectory.dir("generated/desktop-app-resources"))
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            // Preserve Windows/Linux launcher paths used by existing integrations.
            packageName = if (System.getProperty("os.name").startsWith("Mac")) "nati.ve" else "NextcloudNative"
            packageVersion = ncDesktopPackageVersion
            // Windows uses the launcher description as its friendly process name.
            description = if (System.getProperty("os.name").startsWith("Windows")) {
                "nati.ve"
            } else {
                "One native client for your complete Nextcloud account"
            }
            vendor = "Obiente"
            copyright = "Copyright 2026 Obiente"
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                iconFile.set(project.file("src/desktopMain/resources/nextcloud-native.png"))
                shortcut = true
                appCategory = "Network"
                rpmLicenseType = "AGPL-3.0-or-later"
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/nextcloud-native.ico"))
                menu = true
                menuGroup = "nati.ve"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "81237d85-c511-47a7-b8dc-c87a5f5c5823"
            }
            macOS {
                packageName = "nati.ve"
                dockName = "nati.ve"
                packageVersion = ncMacosPackageVersion
            }
        }
    }
}

val enrichDebAppStream by tasks.registering(Exec::class) {
    dependsOn(prepareLinuxAppStreamMetadata)
    inputs.file(generatedLinuxAppStreamMetadata)
    doNotTrackState("Post-processes the packageDeb artifact in place.")
    onlyIf { debPackageSucceededMarker.get().asFile.isFile }
    commandLine(
        "bash",
        rootProject.file("tools/enrich-deb-appstream.sh"),
        debPackageDirectory.get().asFile,
        generatedLinuxAppStreamMetadata.get().asFile,
        rootProject.file("LICENSE"),
        project.file("src/desktopMain/resources/nextcloud-native.png"),
    )
}

val repackageRpmWithMetadata by tasks.registering(Exec::class) {
    dependsOn(prepareLinuxJpackageResources)
    inputs.dir(generatedJpackageResources)
    inputs.file(rootProject.file("tools/repackage-rpm-with-metadata.sh"))
    doNotTrackState("Rebuilds the packageRpm artifact with jpackage resource overrides.")
    onlyIf { rpmPackageSucceededMarker.get().asFile.isFile }
    commandLine(
        "bash",
        rootProject.file("tools/repackage-rpm-with-metadata.sh"),
        rpmPackageDirectory.get().asFile,
        layout.buildDirectory.file("compose/tmp/packageRpm.args.txt").get().asFile,
        layout.buildDirectory.dir("compose/tmp/resources").get().asFile,
        generatedJpackageResources.get().asFile.resolve("linux"),
        File(System.getProperty("java.home"), "bin/jpackage"),
        layout.buildDirectory.dir("compose/binaries/main/app/NextcloudNative").get().asFile,
    )
}

val repackageMsiWithUninstallCleanup by tasks.registering(Exec::class) {
    dependsOn(stageWindowsShellAssets)
    inputs.file(rootProject.file("tools/repackage-msi-with-uninstall-cleanup.ps1"))
    inputs.file(rootProject.file("tools/set-windows-package-display-name.ps1"))
    doNotTrackState("Rebuilds the packageMsi artifact with an uninstall cleanup action.")
    onlyIf {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
            msiPackageDirectory.get().asFile.listFiles().orEmpty().any { it.extension.equals("msi", true) }
    }
    commandLine(
        "pwsh",
        "-NoProfile",
        "-File",
        rootProject.file("tools/repackage-msi-with-uninstall-cleanup.ps1"),
        "-PackageDirectory",
        msiPackageDirectory.get().asFile,
        "-ArgumentsFile",
        layout.buildDirectory.file("compose/tmp/packageMsi.args.txt").get().asFile,
        "-JpackageResourceDirectory",
        layout.buildDirectory.dir("compose/tmp/resources").get().asFile,
        "-GeneratedResourceDirectory",
        layout.buildDirectory.dir("generated/jpackage-resources/windows").get().asFile,
        "-AppImage",
        layout.buildDirectory.dir("compose/binaries/main/app/NextcloudNative").get().asFile,
        "-Jpackage",
        File(System.getProperty("java.home"), "bin/jpackage.exe"),
    )
}

tasks.matching { task -> task.name in setOf("packageDeb", "packageRpm") }.configureEach {
    val marker = if (name == "packageDeb") debPackageSucceededMarker else rpmPackageSucceededMarker
    doFirst { marker.get().asFile.delete() }
    doLast {
        marker.get().asFile.parentFile.mkdirs()
        marker.get().asFile.writeText("succeeded\n")
    }
    if (name == "packageDeb") {
        finalizedBy(enrichDebAppStream)
    } else {
        dependsOn(prepareLinuxJpackageResources, "createDistributable")
        finalizedBy(repackageRpmWithMetadata)
    }
}

tasks.matching { task -> task.name == "packageMsi" }.configureEach {
    dependsOn("createDistributable", stageWindowsShellAssets)
    finalizedBy(repackageMsiWithUninstallCleanup)
}

tasks.matching { task -> task.name == "prepareAppResources" }.configureEach {
    dependsOn("prepareDesktopLibVlc")
}

tasks.matching { task -> task.name == "createDistributable" }.configureEach {
    finalizedBy(stageWindowsShellAssets)
}

val desktopCaptureCompilation = kotlin.targets
    .getByName("desktop")
    .compilations
    .getByName("test")

tasks.register<JavaExec>("captureMarketingScreenshots") {
    group = "documentation"
    description = "Renders real Compose marketing scenarios from isolated synthetic fixtures."
    dependsOn(desktopCaptureCompilation.compileTaskProvider)
    classpath(
        desktopCaptureCompilation.output.allOutputs,
        desktopCaptureCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "dev.obiente.nextcloudnative.nativeui.preview.MarketingCaptureMainKt",
    )
    workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("captureFileSyncTrayVisualQa") {
    group = "verification"
    description = "Captures the custom desktop tray popup with isolated synthetic sync activity."
    dependsOn(desktopCaptureCompilation.compileTaskProvider)
    classpath(
        desktopCaptureCompilation.output.allOutputs,
        desktopCaptureCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "dev.obiente.nextcloudnative.nativeui.preview.FileSyncTrayVisualQaMainKt",
    )
    environment(
        "NEXTCLOUD_NATIVE_TRAY_QA_OUTPUT",
        layout.buildDirectory.file("visual-qa/tray.png").get().asFile.absolutePath,
    )
    workingDir(rootProject.projectDir)
}

fun registerFileSyncWorkspaceVisualQa(name: String, width: Int, height: Int, fileName: String) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = "Captures the responsive folder-sync workspace with isolated conflicts."
        dependsOn(desktopCaptureCompilation.compileTaskProvider)
        classpath(
            desktopCaptureCompilation.output.allOutputs,
            desktopCaptureCompilation.runtimeDependencyFiles,
        )
        mainClass.set(
            "dev.obiente.nextcloudnative.nativeui.preview.FileSyncWorkspaceVisualQaMainKt",
        )
        environment(
            "NEXTCLOUD_NATIVE_FILESYNC_QA_OUTPUT",
            layout.buildDirectory.file("visual-qa/$fileName").get().asFile.absolutePath,
        )
        environment("NEXTCLOUD_NATIVE_FILESYNC_QA_WIDTH", width.toString())
        environment("NEXTCLOUD_NATIVE_FILESYNC_QA_HEIGHT", height.toString())
        workingDir(rootProject.projectDir)
    }

registerFileSyncWorkspaceVisualQa(
    name = "captureFileSyncWorkspaceDesktopVisualQa",
    width = 1_180,
    height = 800,
    fileName = "filesync-workspace-desktop.png",
)
registerFileSyncWorkspaceVisualQa(
    name = "captureFileSyncWorkspacePhoneVisualQa",
    width = 420,
    height = 860,
    fileName = "filesync-workspace-phone.png",
)

tasks.register<JavaExec>("captureDesktopBackgroundSettingsVisualQa") {
    group = "verification"
    description = "Captures desktop background and startup settings with isolated synthetic state."
    dependsOn(desktopCaptureCompilation.compileTaskProvider)
    classpath(
        desktopCaptureCompilation.output.allOutputs,
        desktopCaptureCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "dev.obiente.nextcloudnative.nativeui.preview.DesktopBackgroundSettingsVisualQaMainKt",
    )
    environment(
        "NEXTCLOUD_NATIVE_BACKGROUND_SETTINGS_QA_OUTPUT",
        layout.buildDirectory.file("visual-qa/background-settings.png").get().asFile.absolutePath,
    )
    workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("capturePhotoTimelinePreview") {
    group = "verification"
    description = "Renders the phone Photos timeline from isolated synthetic fixtures."
    dependsOn(desktopCaptureCompilation.compileTaskProvider)
    classpath(
        desktopCaptureCompilation.output.allOutputs,
        desktopCaptureCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "dev.obiente.nextcloudnative.nativeui.preview.PhotoTimelinePreviewMainKt",
    )
    workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("runDeckInteractionPreview") {
    group = "verification"
    description = "Opens a network-free synthetic Deck workspace for pointer and layout QA."
    dependsOn(desktopCaptureCompilation.compileTaskProvider)
    classpath(
        desktopCaptureCompilation.output.allOutputs,
        desktopCaptureCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "dev.obiente.nextcloudnative.nativeui.preview.DeckInteractionPreviewMainKt",
    )
}

tasks.register<JavaExec>("runDynamicBoardInteractionPreview") {
    group = "verification"
    description = "Opens a network-free discovered board for adaptive drag and layout QA."
    dependsOn(desktopCaptureCompilation.compileTaskProvider)
    classpath(
        desktopCaptureCompilation.output.allOutputs,
        desktopCaptureCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "dev.obiente.nextcloudnative.nativeui.preview.DynamicBoardInteractionPreviewMainKt",
    )
}
