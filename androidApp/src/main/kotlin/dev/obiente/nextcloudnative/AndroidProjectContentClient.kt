package dev.obiente.nextcloudnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.obiente.nextcloudnative.app.ANDROID_DIRECT_UPDATE_METADATA_URL
import dev.obiente.nextcloudnative.app.AndroidDirectRelease
import dev.obiente.nextcloudnative.app.AppDistributionChannel
import dev.obiente.nextcloudnative.app.AppUpdateCheckResult
import dev.obiente.nextcloudnative.app.AppUpdateInstallResult
import dev.obiente.nextcloudnative.app.AppUpdateSupport
import dev.obiente.nextcloudnative.app.MAX_ANDROID_UPDATE_APK_BYTES
import dev.obiente.nextcloudnative.app.MAX_ANDROID_UPDATE_METADATA_BYTES
import dev.obiente.nextcloudnative.app.MAX_PROJECT_NEWS_FEED_BYTES
import dev.obiente.nextcloudnative.app.PROJECT_NEWS_FEED_URL
import dev.obiente.nextcloudnative.app.ProjectNewsResult
import dev.obiente.nextcloudnative.app.isNewerAndroidRelease
import dev.obiente.nextcloudnative.app.parseAndroidDirectRelease
import dev.obiente.nextcloudnative.app.parseProjectNewsFeed
import dev.obiente.nextcloudnative.app.validateAndroidDirectRelease
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS = 10L
internal const val PROJECT_CONTENT_READ_TIMEOUT_SECONDS = 30L
internal const val PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS = 30L
internal const val PROJECT_CONTENT_CALL_TIMEOUT_SECONDS = 10L * 60L

internal fun buildProjectContentHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROJECT_CONTENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PROJECT_CONTENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

internal class AndroidProjectContentClient(
    context: Context,
    private val activity: Activity?,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val client = buildProjectContentHttpClient()
    private val newsCache = File(appContext.cacheDir, "project-content/news-feed-v1.json")
    private val updateDirectory = File(appContext.cacheDir, "app-updates")

    fun support(): AppUpdateSupport {
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                appContext.packageManager
                    .getInstallSourceInfo(appContext.packageName)
                    .installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getInstallerPackageName(appContext.packageName)
        }
        val channel = classifyAndroidDistribution(source, BuildConfig.DEBUG)
        return AppUpdateSupport(
            channel = channel,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            canCheckDirectUpdates = channel == AppDistributionChannel.DirectApk,
            explanation = when (channel) {
                AppDistributionChannel.DirectApk ->
                    "This APK was installed directly. Updates are checked securely by Nextcloud Native."
                AppDistributionChannel.GooglePlay ->
                    "Google Play owns updates for this installation."
                AppDistributionChannel.FDroid ->
                    "F-Droid owns updates for this installation."
                AppDistributionChannel.OtherStore ->
                    "The installer that provided this app owns its updates."
                AppDistributionChannel.Development ->
                    "Development builds are updated through the development workflow."
                AppDistributionChannel.Unsupported ->
                    "This installation source cannot use direct in-app updates."
            },
        )
    }

    fun loadNews(forceRefresh: Boolean): ProjectNewsResult {
        val cached = readNewsCache()
        val cacheAge = System.currentTimeMillis() - preferences.getLong(KEY_NEWS_FETCHED_AT, 0)
        if (!forceRefresh && cached != null && cacheAge in 0..NEWS_CACHE_TTL_MILLIS) {
            return ProjectNewsResult(cached, cached = true)
        }
        return runCatching {
            val bytes = getBounded(PROJECT_NEWS_FEED_URL, MAX_PROJECT_NEWS_FEED_BYTES.toLong())
            val feed = parseProjectNewsFeed(bytes)
            newsCache.parentFile?.mkdirs()
            val temporary = File(newsCache.parentFile, "${newsCache.name}.part")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(newsCache)) { "Could not replace the project news cache." }
            preferences.edit().putLong(KEY_NEWS_FETCHED_AT, System.currentTimeMillis()).apply()
            ProjectNewsResult(feed, cached = false)
        }.getOrElse { failure ->
            cached?.let { ProjectNewsResult(it, cached = true) }
                ?: throw IllegalStateException(
                    failure.message ?: "Could not load project news.",
                    failure,
                )
        }
    }

    fun checkForUpdate(): AppUpdateCheckResult {
        val support = support()
        if (!support.canCheckDirectUpdates) return AppUpdateCheckResult.Unavailable(support)
        return runCatching {
            val bytes = getBounded(
                ANDROID_DIRECT_UPDATE_METADATA_URL,
                MAX_ANDROID_UPDATE_METADATA_BYTES.toLong(),
            )
            val release = parseAndroidDirectRelease(bytes)
            if (isNewerAndroidRelease(support.currentVersionCode, release)) {
                AppUpdateCheckResult.Available(support, release)
            } else {
                AppUpdateCheckResult.Current(support)
            }
        }.getOrElse { failure ->
            AppUpdateCheckResult.Failed(
                support,
                failure.message ?: "The update check failed.",
            )
        }
    }

    suspend fun beginUpdate(release: AndroidDirectRelease): AppUpdateInstallResult {
        val support = support()
        if (!support.canCheckDirectUpdates) {
            return AppUpdateInstallResult.Rejected(support.explanation)
        }
        if (!isNewerAndroidRelease(support.currentVersionCode, release)) {
            return AppUpdateInstallResult.Rejected("This release is not newer than the installed app.")
        }
        validateAndroidDirectRelease(release)
        val foregroundActivity = activity
            ?: return AppUpdateInstallResult.Rejected("Open the app before installing an update.")
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main.immediate) {
                foregroundActivity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}"),
                    ),
                )
            }
            return AppUpdateInstallResult.PermissionRequired(
                "Allow installs from Nextcloud Native, then return and confirm the update again.",
            )
        }

        return runCatching {
            updateDirectory.mkdirs()
            val staged = File(updateDirectory, "nextcloud-native-${release.versionCode}.apk")
            val temporary = File(updateDirectory, "${staged.name}.part")
            stageVerifiedUpdate(temporary, staged) {
                downloadApk(release, temporary)
                verifyDownloadedApk(release, temporary)
            }
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.sharedfiles",
                staged,
            )
            withContext(Dispatchers.Main.immediate) {
                foregroundActivity.startActivity(
                    Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    },
                )
            }
            AppUpdateInstallResult.ConfirmationOpened
        }.getOrElse { failure ->
            AppUpdateInstallResult.Rejected(failure.message ?: "The update could not be verified.")
        }
    }

    private fun readNewsCache() = runCatching {
        if (!newsCache.isFile || newsCache.length() > MAX_PROJECT_NEWS_FEED_BYTES) return@runCatching null
        parseProjectNewsFeed(newsCache.readBytes())
    }.getOrNull()

    private fun getBounded(url: String, maximumBytes: Long): ByteArray {
        require(url == PROJECT_NEWS_FEED_URL || url == ANDROID_DIRECT_UPDATE_METADATA_URL)
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            check(response.isSuccessful) { "Public content request failed (HTTP ${response.code})." }
            val body = requireNotNull(response.body)
            check(body.contentLength() in -1..maximumBytes)
            return body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size().toLong() + read <= maximumBytes) {
                        "Public content exceeds the allowed size."
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

    private fun downloadApk(release: AndroidDirectRelease, target: File) {
        check(release.apkUrl.startsWith("https://nc-native.obiente.dev/releases/android/"))
        client.newCall(Request.Builder().url(release.apkUrl).get().build()).execute().use { response ->
            check(response.isSuccessful) { "Update download failed (HTTP ${response.code})." }
            val body = requireNotNull(response.body)
            check(body.contentLength() == release.apkSize) { "Update size does not match its metadata." }
            check(release.apkSize <= MAX_ANDROID_UPDATE_APK_BYTES)
            FileOutputStream(target).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= release.apkSize && total <= MAX_ANDROID_UPDATE_APK_BYTES)
                        output.write(buffer, 0, read)
                    }
                    check(total == release.apkSize)
                }
            }
        }
    }

    private fun verifyDownloadedApk(release: AndroidDirectRelease, apk: File) {
        check(apk.sha256() == release.apkSha256) { "Update checksum verification failed." }
        val installed = appContext.packageManager.packageInfo(appContext.packageName)
        val archive = requireNotNull(appContext.packageManager.archiveInfo(apk)) {
            "The downloaded file is not a valid Android package."
        }
        check(archive.packageName == appContext.packageName) { "Update package identity does not match." }
        check(archive.longVersionCodeCompat() == release.versionCode) {
            "Update package version does not match its metadata."
        }
        val expected = release.signingCertificateSha256
        check(expected in installed.signingCertificateDigests()) {
            "Release metadata does not match the installed app signer."
        }
        check(expected in archive.signingCertificateDigests()) {
            "Update signing certificate does not match the installed app."
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.archiveInfo(apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signingCertificateDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = requireNotNull(signingInfo)
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .toHex()
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val PREFERENCES = "project-content-v1"
        const val KEY_NEWS_FETCHED_AT = "news-fetched-at"
        const val NEWS_CACHE_TTL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

internal inline fun stageVerifiedUpdate(
    temporary: File,
    staged: File,
    downloadAndVerify: () -> Unit,
) {
    temporary.delete()
    try {
        downloadAndVerify()
        if (staged.exists()) check(staged.delete())
        check(temporary.renameTo(staged)) { "Could not stage the verified update." }
    } finally {
        temporary.delete()
    }
}

internal fun classifyAndroidDistribution(
    installerPackage: String?,
    debugBuild: Boolean,
): AppDistributionChannel = when {
    debugBuild -> AppDistributionChannel.Development
    installerPackage == "com.android.vending" -> AppDistributionChannel.GooglePlay
    installerPackage in setOf("org.fdroid.fdroid", "org.fdroid.basic") ->
        AppDistributionChannel.FDroid
    installerPackage == null ||
        installerPackage in setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
        ) -> AppDistributionChannel.DirectApk
    else -> AppDistributionChannel.OtherStore
}
