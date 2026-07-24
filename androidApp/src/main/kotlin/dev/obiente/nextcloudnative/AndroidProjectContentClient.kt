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
import dev.obiente.nextcloudnative.app.AppUpdateInstallState
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
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Call
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
    private val updateMutex = Mutex()
    private val mutableUpdateState = MutableStateFlow<AppUpdateInstallState>(AppUpdateInstallState.Idle)
    @Volatile private var activeUpdateCall: Call? = null
    @Volatile private var updateCancellationRequested = false

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
        val directUpdatesEnabled = canCheckAndroidDirectUpdates(
            channel = channel,
            directApkBuild = BuildConfig.DIRECT_APK_UPDATES,
        )
        return AppUpdateSupport(
            channel = channel,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            canCheckDirectUpdates = directUpdatesEnabled,
            explanation = when (channel) {
                AppDistributionChannel.DirectApk if directUpdatesEnabled ->
                    "This APK was installed directly. Updates are checked securely by Nextcloud Native."
                AppDistributionChannel.DirectApk ->
                    "This build does not include direct APK installation. Use its distribution channel for updates."
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

    fun observeUpdateState(): Flow<AppUpdateInstallState> = mutableUpdateState.asStateFlow()

    fun cancelUpdate(): Boolean {
        if (mutableUpdateState.value !is AppUpdateInstallState.Downloading) return false
        updateCancellationRequested = true
        activeUpdateCall?.cancel()
        return true
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
        if (!updateMutex.tryLock()) {
            return AppUpdateInstallResult.Rejected("An app update is already in progress.")
        }
        try {
            return beginUpdateLocked(release)
        } finally {
            activeUpdateCall = null
            updateMutex.unlock()
        }
    }

    private suspend fun beginUpdateLocked(release: AndroidDirectRelease): AppUpdateInstallResult {
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
            val message =
                "Allow installs from Nextcloud Native, then return and confirm the update again."
            mutableUpdateState.value = AppUpdateInstallState.PermissionRequired(
                versionName = release.versionName,
                versionCode = release.versionCode,
                message = message,
            )
            withContext(Dispatchers.Main.immediate) {
                foregroundActivity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}"),
                    ),
                )
            }
            return AppUpdateInstallResult.PermissionRequired(message)
        }

        updateDirectory.mkdirs()
        val staged = File(updateDirectory, "nextcloud-native-${release.versionCode}.apk")
        val temporary = File(updateDirectory, "${staged.name}.part")
        updateCancellationRequested = false
        return try {
            val resumedFromBytes = settleUpdatePartial(
                file = temporary,
                expectedSize = release.apkSize,
                retain = true,
            )
            mutableUpdateState.value = release.downloadingState(
                downloadedBytes = resumedFromBytes,
                resumedFromBytes = resumedFromBytes,
            )
            var lastReportedBytes = resumedFromBytes
            downloadUpdateApk(
                client = client,
                url = release.apkUrl,
                expectedSize = release.apkSize,
                target = temporary,
                isCancelled = { updateCancellationRequested },
                onCallChanged = { activeUpdateCall = it },
                onProgress = { downloadedBytes ->
                    if (
                        downloadedBytes == release.apkSize ||
                        downloadedBytes - lastReportedBytes >= UPDATE_PROGRESS_STEP_BYTES
                    ) {
                        lastReportedBytes = downloadedBytes
                        mutableUpdateState.value = release.downloadingState(
                            downloadedBytes = downloadedBytes,
                            resumedFromBytes = resumedFromBytes,
                        )
                    }
                },
            )
            mutableUpdateState.value = AppUpdateInstallState.Verifying(
                versionName = release.versionName,
                versionCode = release.versionCode,
            )
            verifyDownloadedApk(release, temporary)
            if (staged.exists()) check(staged.delete())
            check(temporary.renameTo(staged)) { "Could not stage the verified update." }
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
            mutableUpdateState.value = AppUpdateInstallState.ConfirmationOpened(
                versionName = release.versionName,
                versionCode = release.versionCode,
            )
            AppUpdateInstallResult.ConfirmationOpened
        } catch (_: UpdateDownloadCancelledException) {
            val retainedBytes = settleUpdatePartial(
                file = temporary,
                expectedSize = release.apkSize,
                retain = true,
            )
            mutableUpdateState.value = AppUpdateInstallState.Cancelled(
                versionName = release.versionName,
                versionCode = release.versionCode,
                downloadedBytes = retainedBytes,
                canResume = retainedBytes in 1 until release.apkSize,
            )
            AppUpdateInstallResult.Cancelled
        } catch (cancelled: CancellationException) {
            activeUpdateCall?.cancel()
            val retainedBytes = settleUpdatePartial(
                file = temporary,
                expectedSize = release.apkSize,
                retain = true,
            )
            mutableUpdateState.value = AppUpdateInstallState.Cancelled(
                versionName = release.versionName,
                versionCode = release.versionCode,
                downloadedBytes = retainedBytes,
                canResume = retainedBytes in 1 until release.apkSize,
            )
            throw cancelled
        } catch (failure: Exception) {
            val recoverable = failure is IOException
            val retainedBytes = settleUpdatePartial(
                file = temporary,
                expectedSize = release.apkSize,
                retain = recoverable,
            )
            mutableUpdateState.value = AppUpdateInstallState.Failed(
                versionName = release.versionName,
                versionCode = release.versionCode,
                message = failure.message ?: "The update could not be verified.",
                downloadedBytes = retainedBytes,
                canResume = recoverable && retainedBytes in 1 until release.apkSize,
            )
            AppUpdateInstallResult.Rejected(failure.message ?: "The update could not be verified.")
        } finally {
            updateCancellationRequested = false
        }
    }

    private fun AndroidDirectRelease.downloadingState(
        downloadedBytes: Long,
        resumedFromBytes: Long,
    ) = AppUpdateInstallState.Downloading(
        versionName = versionName,
        versionCode = versionCode,
        downloadedBytes = downloadedBytes,
        totalBytes = apkSize,
        resumedFromBytes = resumedFromBytes,
    )

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
        const val UPDATE_PROGRESS_STEP_BYTES = 256L * 1024L
    }
}

internal class UpdateDownloadCancelledException : IOException("Update download cancelled.")

internal fun settleUpdatePartial(
    file: File,
    expectedSize: Long,
    retain: Boolean,
): Long {
    val length = file.takeIf(File::isFile)?.length() ?: return 0
    if (!retain || length !in 0..expectedSize) {
        check(file.delete()) { "Could not discard an invalid partial update." }
        return 0
    }
    return length
}

internal fun downloadUpdateApk(
    client: OkHttpClient,
    url: String,
    expectedSize: Long,
    target: File,
    isCancelled: () -> Boolean,
    onCallChanged: (Call?) -> Unit = {},
    onProgress: (Long) -> Unit = {},
) {
    require(expectedSize in 1..MAX_ANDROID_UPDATE_APK_BYTES)
    target.parentFile?.mkdirs()
    var existingBytes = target.takeIf(File::isFile)?.length() ?: 0L
    if (existingBytes !in 0..expectedSize) {
        check(target.delete()) { "Could not discard an invalid partial update." }
        existingBytes = 0
    }
    onProgress(existingBytes)
    if (existingBytes == expectedSize) return
    if (isCancelled()) throw UpdateDownloadCancelledException()

    val request = Request.Builder()
        .url(url)
        .get()
        .header("Accept-Encoding", "identity")
        .apply {
            if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
        }
        .build()
    val call = client.newCall(request)
    onCallChanged(call)
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val message = "Update download failed (HTTP ${response.code})."
                if (response.code == 408 || response.code == 429 || response.code >= 500) {
                    throw IOException(message)
                }
                error(message)
            }
            val body = requireNotNull(response.body) { "Update download returned no content." }
            val append = existingBytes > 0 && response.code == 206
            if (response.code == 206) {
                check(existingBytes > 0) { "Update server returned an unexpected partial response." }
                check(
                    response.header("Content-Range") ==
                        "bytes $existingBytes-${expectedSize - 1}/$expectedSize",
                ) {
                    "Update server returned an invalid byte range."
                }
                check(body.contentLength() in setOf(-1L, expectedSize - existingBytes)) {
                    "Update byte range size does not match its metadata."
                }
            } else {
                check(response.code == 200) { "Update server returned an unsupported response." }
                check(body.contentLength() in setOf(-1L, expectedSize)) {
                    "Update size does not match its metadata."
                }
                existingBytes = 0
            }

            FileOutputStream(target, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = existingBytes
                    while (true) {
                        if (isCancelled()) {
                            call.cancel()
                            throw UpdateDownloadCancelledException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= expectedSize && total <= MAX_ANDROID_UPDATE_APK_BYTES) {
                            "Update download exceeded its declared size."
                        }
                        output.write(buffer, 0, read)
                        onProgress(total)
                    }
                    check(total == expectedSize) { "Update download ended before it was complete." }
                }
                output.fd.sync()
            }
        }
    } catch (failure: IOException) {
        if (isCancelled()) throw UpdateDownloadCancelledException()
        throw failure
    } finally {
        onCallChanged(null)
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

internal fun canCheckAndroidDirectUpdates(
    channel: AppDistributionChannel,
    directApkBuild: Boolean,
): Boolean = directApkBuild && channel == AppDistributionChannel.DirectApk
