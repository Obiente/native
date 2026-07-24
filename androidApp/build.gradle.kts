import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val ncVersionName = providers.gradleProperty("ncVersionName").get()
val ncVersionCode = providers.gradleProperty("ncVersionCode").get().toInt()
val releaseKeystorePath = providers.environmentVariable("NC_ANDROID_KEYSTORE_PATH").orNull
val releaseSigningEnvironment = listOf(
    "NC_ANDROID_KEYSTORE_PATH",
    "NC_ANDROID_KEYSTORE_PASSWORD",
    "NC_ANDROID_KEY_ALIAS",
    "NC_ANDROID_KEY_PASSWORD",
)

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
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("NC_ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NC_ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NC_ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":contractAcquisition"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Requires complete protected signing input before producing release artifacts."
    doLast {
        val missing = releaseSigningEnvironment.filter { name ->
            providers.environmentVariable(name).orNull.isNullOrBlank()
        }
        check(missing.isEmpty()) {
            "Android release signing is not configured. Use the protected release environment."
        }
        check(file(requireNotNull(releaseKeystorePath)).isFile) {
            "The configured Android release keystore does not exist."
        }
    }
}

tasks.matching { task ->
    task.name == "assembleRelease" || task.name == "bundleRelease"
}.configureEach {
    dependsOn(validateReleaseSigning)
}
