import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val desktopArchitecture = System.getProperty("os.arch").lowercase()
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
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val androidMain by getting
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.media3.session)
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

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "NextcloudNative"
            packageVersion = "0.1.0"

            linux {
                iconFile.set(project.file("src/desktopMain/resources/nextcloud-native.png"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/nextcloud-native.ico"))
            }
        }
    }
}
