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
import dev.obiente.nextcloudnative.app.AndroidDirectRelease
import dev.obiente.nextcloudnative.app.AndroidUpdateChannel
import dev.obiente.nextcloudnative.app.AppDistributionChannel
import dev.obiente.nextcloudnative.app.AppUpdateCheckResult
import dev.obiente.nextcloudnative.app.AppUpdateInstallResult
import dev.obiente.nextcloudnative.app.AppUpdateInstallState
import dev.obiente.nextcloudnative.app.AppUpdateSupport
import dev.obiente.nextcloudnative.app.MAX_ANDROID_UPDATE_APK_BYTES
import dev.obiente.nextcloudnative.app.MAX_ANDROID_UPDATE_METADATA_BYTES
import dev.obiente.nextcloudnative.app.MAX_PROJECT_NEWS_FEED_BYTES
import dev.obiente.nextcloudnative.app.MAX_PROJECT_NEWS_IMAGE_BYTES
import dev.obiente.nextcloudnative.app.PROJECT_NEWS_FEED_URL
import dev.obiente.nextcloudnative.app.ProjectNewsResult
import dev.obiente.nextcloudnative.app.ProjectNewsImage
import dev.obiente.nextcloudnative.app.isNewerAndroidRelease
import dev.obiente.nextcloudnative.app.isCanonicalAndroidUpdateManifestUrl
import dev.obiente.nextcloudnative.app.isCanonicalProjectNewsImageUrl
import dev.obiente.nextcloudnative.app.manifestUrl
import dev.obiente.nextcloudnative.app.parseAndroidDirectRelease
import dev.obiente.nextcloudnative.app.parseAndroidUpdateChannel
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
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private val newsImageDirectory = File(appContext.cacheDir, "project-content/news-images")
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

    fun loadNewsImage(image: ProjectNewsImage): ByteArray {
        require(isCanonicalProjectNewsImageUrl(image.url))
        val cached = File(newsImageDirectory, "${image.sha256}.png")
        if (cached.isFile && cached.length() <= MAX_PROJECT_NEWS_IMAGE_BYTES) {
            cached.readBytes().takeIf { publicContent ->
                publicContent.sha256() == image.sha256
            }?.let { return it }
        }
        val bytes = getBounded(image.url, MAX_PROJECT_NEWS_IMAGE_BYTES.toLong())
        check(bytes.sha256() == image.sha256) { "Project news image verification failed." }
        newsImageDirectory.mkdirs()
        val temporary = File(newsImageDirectory, "${image.sha256}.part")
        temporary.writeBytes(bytes)
        if (cached.exists()) check(cached.delete())
        check(temporary.renameTo(cached)) { "Could not cache the project news image." }
        return bytes
    }

    fun updateChannel(): AndroidUpdateChannel =
        parseAndroidUpdateChannel(preferences.getString(KEY_UPDATE_CHANNEL, null))

    fun saveUpdateChannel(channel: AndroidUpdateChannel) {
        preferences.edit().putString(KEY_UPDATE_CHANNEL, channel.manifestChannel).apply()
    }

    fun checkForUpdate(channel: AndroidUpdateChannel): AppUpdateCheckResult {
        val support = support()
        if (!support.canCheckDirectUpdates) return AppUpdateCheckResult.Unavailable(support)
        return runCatching {
            val metadataUrl = channel.manifestUrl()
            val metadata = getBounded(metadataUrl, MAX_ANDROID_UPDATE_METADATA_BYTES.toLong())
            val release = parseAndroidDirectRelease(metadata, metadataUrl)
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
        require(
            url == PROJECT_NEWS_FEED_URL ||
                isCanonicalAndroidUpdateManifestUrl(url) ||
                isCanonicalProjectNewsImageUrl(url),
        )
        val request = Request.Builder().url(url).get().build()
        executeWithTrustedGitHubReleaseRedirect(client, request).use { response ->
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
        val sdkRequirements = archive.sdkRequirements()
        check(sdkRequirements.minSdk == release.minimumAndroidSdk) {
            "Update package Android compatibility does not match its metadata."
        }
        androidSdkCompatibilityFailure(
            minSdk = sdkRequirements.minSdk,
            maxSdk = sdkRequirements.maxSdk,
            deviceSdk = Build.VERSION.SDK_INT,
        )?.let { failure -> error(failure) }
        val installedIdentity = installed.signingCertificateIdentity()
        val archiveIdentity = archive.signingCertificateIdentity()
        androidSignerCompatibilityFailure(
            metadataCurrentSigners = release.signingCertificateSha256Digests.toSet(),
            installed = installedIdentity,
            archive = archiveIdentity,
        )?.let { failure -> error(failure) }
    }

    private fun PackageManager.packageInfo(packageName: String): PackageInfo =
        requireNotNull(packageInfoWithSignatures(AndroidPackageInfoTarget.Installed(packageName))) {
            "Android could not inspect the installed app package."
        }

    private fun PackageManager.archiveInfo(apk: File): PackageInfo? =
        packageInfoWithSignatures(AndroidPackageInfoTarget.Archive(apk.absolutePath))

    @Suppress("DEPRECATION")
    private fun PackageManager.packageInfoWithSignatures(
        target: AndroidPackageInfoTarget,
    ): PackageInfo? {
        val query = androidPackageInfoSignatureQuery(
            lookup = when (target) {
                is AndroidPackageInfoTarget.Installed -> AndroidPackageInfoLookup.Installed
                is AndroidPackageInfoTarget.Archive -> AndroidPackageInfoLookup.Archive
            },
            sdkInt = Build.VERSION.SDK_INT,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.PackageInfoFlags.of(query.flags.toLong())
            when (target) {
                is AndroidPackageInfoTarget.Installed -> getPackageInfo(target.packageName, flags)
                is AndroidPackageInfoTarget.Archive -> getPackageArchiveInfo(target.apkPath, flags)
            }
        } else {
            when (target) {
                is AndroidPackageInfoTarget.Installed -> getPackageInfo(target.packageName, query.flags)
                is AndroidPackageInfoTarget.Archive -> getPackageArchiveInfo(target.apkPath, query.flags)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signingCertificateIdentity(): SigningCertificateIdentity {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = requireSigningCertificateInfo(signingInfo, packageName)
            val current = info.apkContentsSigners.orEmpty().mapTo(mutableSetOf(), ::signatureSha256)
            val multiple = info.hasMultipleSigners()
            val lineage = if (multiple) {
                current
            } else {
                info.signingCertificateHistory.orEmpty().mapTo(mutableSetOf(), ::signatureSha256)
            }
            return SigningCertificateIdentity(current, lineage, multiple)
        }
        val current = signatures.orEmpty().mapTo(mutableSetOf(), ::signatureSha256)
        return SigningCertificateIdentity(current, current, current.size > 1)
    }

    private fun signatureSha256(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .toHex()

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.sdkRequirements(): AndroidSdkRequirements {
        val archiveApplication = requireNotNull(applicationInfo) {
            "The update package does not expose its Android compatibility requirements."
        }
        val minimum = archiveApplication.minSdkVersion
        check(minimum > 0) {
            "The update package declares invalid Android compatibility requirements."
        }
        // PackageManager exposes minSdkVersion for archives but no public maxSdkVersion field.
        return AndroidSdkRequirements(minSdk = minimum, maxSdk = null)
    }

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

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val PREFERENCES = "project-content-v1"
        const val KEY_NEWS_FETCHED_AT = "news-fetched-at"
        const val KEY_UPDATE_CHANNEL = "update-channel"
        const val NEWS_CACHE_TTL_MILLIS = 6 * 60 * 60 * 1_000L
        const val UPDATE_PROGRESS_STEP_BYTES = 256L * 1024L
    }
}

internal class UpdateDownloadCancelledException : IOException("Update download cancelled.")

private sealed interface AndroidPackageInfoTarget {
    data class Installed(val packageName: String) : AndroidPackageInfoTarget
    data class Archive(val apkPath: String) : AndroidPackageInfoTarget
}

internal enum class AndroidPackageInfoLookup {
    Installed,
    Archive,
}

internal data class AndroidPackageInfoSignatureQuery(
    val lookup: AndroidPackageInfoLookup,
    val flags: Int,
)

@Suppress("DEPRECATION")
internal fun androidPackageInfoSignatureQuery(
    lookup: AndroidPackageInfoLookup,
    sdkInt: Int,
): AndroidPackageInfoSignatureQuery {
    require(sdkInt >= Build.VERSION_CODES.O)
    val flags = if (sdkInt >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    return AndroidPackageInfoSignatureQuery(lookup = lookup, flags = flags)
}

internal data class AndroidSdkRequirements(
    val minSdk: Int,
    val maxSdk: Int?,
)

internal data class SigningCertificateIdentity(
    val currentSigners: Set<String>,
    val lineage: Set<String>,
    val hasMultipleSigners: Boolean,
)

internal fun androidSignerCompatibilityFailure(
    metadataCurrentSigners: Set<String>,
    installed: SigningCertificateIdentity,
    archive: SigningCertificateIdentity,
): String? {
    if (
        metadataCurrentSigners.isEmpty() ||
        installed.currentSigners.isEmpty() ||
        archive.currentSigners.isEmpty() ||
        archive.lineage.isEmpty()
    ) {
        return "Signing certificate information is incomplete."
    }
    if (metadataCurrentSigners != archive.currentSigners) {
        return "Update signing certificates do not match the release metadata."
    }
    if (installed.hasMultipleSigners || archive.hasMultipleSigners) {
        return if (
            installed.hasMultipleSigners &&
            archive.hasMultipleSigners &&
            installed.currentSigners == archive.currentSigners
        ) {
            null
        } else {
            "Multi-signer updates require an exact current signer-set match."
        }
    }
    val installedCurrent = installed.currentSigners.singleOrNull()
        ?: return "The installed app signer is ambiguous."
    return if (installedCurrent in archive.lineage) {
        null
    } else {
        "The update signer lineage is not a forward-compatible rotation of the installed app signer."
    }
}

internal fun <T : Any> requireSigningCertificateInfo(
    signingInfo: T?,
    packageIdentity: String?,
): T = requireNotNull(signingInfo) {
    val identity = packageIdentity?.takeIf(String::isNotBlank) ?: "the update package"
    "Android could not read signing certificate information for $identity. " +
        "Download the update again and retry. If this continues, reinstall from a trusted release."
}

internal fun androidSdkCompatibilityFailure(
    minSdk: Int,
    maxSdk: Int?,
    deviceSdk: Int,
): String? {
    require(minSdk > 0)
    require(deviceSdk > 0)
    require(maxSdk == null || maxSdk >= minSdk)
    return when {
        deviceSdk < minSdk ->
            "This update requires Android API $minSdk or newer, but this device uses API $deviceSdk."
        maxSdk != null && deviceSdk > maxSdk ->
            "This update supports Android API $maxSdk or older, but this device uses API $deviceSdk."
        else -> null
    }
}

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
    try {
        executeWithTrustedGitHubReleaseRedirect(client, request, onCallChanged).use { response ->
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
                            onCallChanged(null)
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

internal fun isTrustedGitHubReleaseAssetRedirect(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps &&
        parsed.host == "release-assets.githubusercontent.com" &&
        parsed.port == 443 &&
        parsed.username.isEmpty() &&
        parsed.password.isEmpty() &&
        parsed.fragment == null &&
        parsed.encodedPath.startsWith('/') &&
        parsed.encodedPath != "/" &&
        '\\' !in parsed.encodedPath
}

internal fun executeWithTrustedGitHubReleaseRedirect(
    client: OkHttpClient,
    request: Request,
    onCallChanged: (Call?) -> Unit = {},
): Response {
    val initialCall = client.newCall(request)
    onCallChanged(initialCall)
    val initialResponse = initialCall.execute()
    if (initialResponse.code !in setOf(302, 307, 308)) return initialResponse
    return try {
        check(
            request.url.host == "github.com" &&
                request.url.encodedPath.startsWith("/Obiente/nc-native/releases/download/"),
        ) {
            "Unexpected redirect while loading public project content."
        }
        val location = requireNotNull(initialResponse.header("Location")) {
            "GitHub release download redirect did not include a destination."
        }
        val redirectedUrl = requireNotNull(request.url.resolve(location)) {
            "GitHub release download redirect was invalid."
        }
        check(isTrustedGitHubReleaseAssetRedirect(redirectedUrl.toString())) {
            "GitHub release download redirected to an untrusted destination."
        }
        initialResponse.close()
        val redirectedCall = client.newCall(request.newBuilder().url(redirectedUrl).build())
        onCallChanged(redirectedCall)
        redirectedCall.execute()
    } catch (failure: Exception) {
        initialResponse.close()
        throw failure
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
