import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val ncVersionName = providers.gradleProperty("ncVersionName").get()
val ncVersionCode = providers.gradleProperty("ncVersionCode").get().toInt()
val releaseSigningEnvironment = listOf(
    "NC_ANDROID_KEYSTORE_PATH",
    "NC_ANDROID_KEYSTORE_PASSWORD",
    "NC_ANDROID_KEY_ALIAS",
    "NC_ANDROID_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningEnvironment.associateWith { name ->
    providers.environmentVariable(name).orNull
}
val releaseKeystorePath = releaseSigningValues.getValue("NC_ANDROID_KEYSTORE_PATH")
val hasCompleteReleaseSigningEnvironment = releaseSigningValues.values.all { value ->
    !value.isNullOrBlank()
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "dev.obiente.nextcloudnative"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.obiente.nextcloudnative"
        minSdk = 26
        targetSdk = 36
        versionCode = ncVersionCode
        versionName = ncVersionName
        buildConfigField("boolean", "DIRECT_APK_UPDATES", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCompleteReleaseSigningEnvironment) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseSigningValues["NC_ANDROID_KEYSTORE_PASSWORD"])
                keyAlias = requireNotNull(releaseSigningValues["NC_ANDROID_KEY_ALIAS"])
                keyPassword = requireNotNull(releaseSigningValues["NC_ANDROID_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
        }
        create("directApk") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "DIRECT_APK_UPDATES", "true")
        }
    }

    sourceSets.getByName("dev").java.srcDir("src/debug/kotlin")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkTestSources = true
        xmlReport = true
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":contractAcquisition"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Requires complete protected signing input before producing release artifacts."
    doLast {
        val missing = releaseSigningEnvironment.filter { name ->
            releaseSigningValues[name].isNullOrBlank()
        }
        check(missing.isEmpty()) {
            "Android release signing is incomplete. Missing: ${missing.joinToString()}."
        }
        check(file(requireNotNull(releaseKeystorePath)).isFile) {
            "The configured Android release keystore does not exist."
        }
    }
}

tasks.matching { task ->
    task.name == "assembleRelease" ||
        task.name == "bundleRelease" ||
        task.name == "assembleDirectApk"
}.configureEach {
    dependsOn(validateReleaseSigning)
}

val verifyReleaseLintGate by tasks.registering {
    group = "verification"
    description = "Runs full release lint and proves the analyzer inspected its test probe."
    dependsOn("lintRelease")

    val lintReport = layout.buildDirectory.file("reports/lint-results-release.xml")
    inputs.file(lintReport)

    doLast {
        val report = lintReport.get().asFile
        check(report.isFile) {
            "Release lint did not produce ${report.relativeTo(projectDir)}."
        }
        val reportText = report.readText()
        check("id=\"SdCardPath\"" in reportText &&
            "ReleaseLintGateFixture.kt" in reportText
        ) {
            "Release lint did not detect its known SdCardPath test probe."
        }
    }
}
