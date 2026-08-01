import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Base64

val desktopArchitecture = System.getProperty("os.arch").lowercase()
val ncDesktopPackageVersion = providers.gradleProperty("ncDesktopPackageVersion").get()
val ncMacosPackageVersion = providers.gradleProperty("ncMacosPackageVersion").get()
val ncVersionName = providers.gradleProperty("ncVersionName").get()
val ncVersionCode = providers.gradleProperty("ncVersionCode").get()
val ncDesktopReleaseBuild = providers.gradleProperty("ncDesktopReleaseBuild").orElse("false").get()
val ncDirectDesktopPackageUpdates = providers.gradleProperty("ncDirectDesktopPackageUpdates").orElse("false").get()
val linuxAppStreamMetadata = rootProject.layout.projectDirectory
    .file("release/linux/dev.obiente.nextcloudnative.metainfo.xml")
val linuxJpackageTemplates = rootProject.layout.projectDirectory.dir("release/linux/jpackage")
val generatedJpackageResources = layout.buildDirectory.dir("generated/jpackage-resources")
val debPackageDirectory = layout.buildDirectory.dir("compose/binaries/main/deb")
val rpmPackageDirectory = layout.buildDirectory.dir("compose/binaries/main/rpm")
val debPackageSucceededMarker = layout.buildDirectory.file("compose/tmp/packageDeb.succeeded")
val rpmPackageSucceededMarker = layout.buildDirectory.file("compose/tmp/packageRpm.succeeded")

val prepareLinuxJpackageResources by tasks.registering {
    inputs.file(linuxAppStreamMetadata)
    inputs.dir(linuxJpackageTemplates)
    outputs.dir(generatedJpackageResources)
    doLast {
        val output = generatedJpackageResources.get().asFile
        output.deleteRecursively()
        val linuxOutput = output.resolve("linux").apply { mkdirs() }
        val metadataBase64 = Base64.getEncoder()
            .encodeToString(linuxAppStreamMetadata.asFile.readBytes())
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
        val androidMain by getting
        androidMain.dependencies {
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
        val desktopMain by getting
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
            implementation("com.github.serceman:jnr-fuse:0.5.8")
            implementation("net.java.dev.jna:jna:5.19.1")
            implementation("net.java.dev.jna:jna-platform:5.19.1")
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
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "NextcloudNative"
            packageVersion = ncDesktopPackageVersion
            description = "One native client for your complete Nextcloud account"
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
            }
            macOS {
                packageVersion = ncMacosPackageVersion
            }
        }
    }
}

val enrichDebAppStream by tasks.registering(Exec::class) {
    inputs.file(linuxAppStreamMetadata)
    doNotTrackState("Post-processes the packageDeb artifact in place.")
    onlyIf { debPackageSucceededMarker.get().asFile.isFile }
    commandLine(
        "bash",
        rootProject.file("tools/enrich-deb-appstream.sh"),
        debPackageDirectory.get().asFile,
        linuxAppStreamMetadata.asFile,
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

val desktopCaptureCompilation = kotlin.targets
    .getByName("desktop")
    .compilations
    .getByName("main")

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
