package dev.obiente.nextcloudnative.app

import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class DesktopUpdateBuildIdentity(
    val versionName: String,
    val versionCode: Long,
    val packageVersion: String,
    val releaseBuild: Boolean,
)

internal data class DesktopUpdateTarget(
    val platform: String,
    val format: String,
    val architecture: String,
)

internal fun currentDesktopUpdateBuildIdentity(): DesktopUpdateBuildIdentity =
    DesktopUpdateBuildIdentity(
        versionName = System.getProperty(DESKTOP_VERSION_NAME_PROPERTY, "development"),
        versionCode = System.getProperty(DESKTOP_VERSION_CODE_PROPERTY, "0").toLongOrNull() ?: 0L,
        packageVersion = System.getProperty(DESKTOP_PACKAGE_VERSION_PROPERTY, "0.0.0"),
        releaseBuild = System.getProperty(DESKTOP_RELEASE_BUILD_PROPERTY, "false").toBooleanStrictOrNull() == true,
    )

internal fun detectDesktopUpdateTarget(
    osName: String = System.getProperty("os.name", ""),
    architecture: String = System.getProperty("os.arch", ""),
    debianMarker: Boolean = File("/etc/debian_version").isFile,
    rpmMarker: Boolean = File("/etc/redhat-release").isFile || File("/etc/fedora-release").isFile,
): DesktopUpdateTarget? {
    if (!osName.startsWith("Linux", ignoreCase = true)) return null
    val normalizedArchitecture = when (architecture.lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> return null
    }
    val format = when {
        rpmMarker && !debianMarker -> "rpm"
        debianMarker && !rpmMarker -> "deb"
        else -> return null
    }
    return DesktopUpdateTarget("linux", format, normalizedArchitecture)
}

internal class DesktopAppUpdater(
    private val preferences: Preferences,
    private val buildIdentity: DesktopUpdateBuildIdentity = currentDesktopUpdateBuildIdentity(),
    private val target: DesktopUpdateTarget? = detectDesktopUpdateTarget(),
    private val updateDirectory: File = defaultDesktopUpdateDirectory(),
    private val client: OkHttpClient = buildDesktopUpdateHttpClient(),
    private val openInstaller: (File) -> Unit = ::openDesktopPackageInstaller,
) {
    private val mutableCheckResult = MutableStateFlow<AppUpdateCheckResult?>(null)
    private val mutableInstallState = MutableStateFlow<AppUpdateInstallState>(AppUpdateInstallState.Idle)
    private val updateMutex = Mutex()
    @Volatile private var activeCall: Call? = null
    @Volatile private var cancellationRequested = false

    fun support(): AppUpdateSupport {
        val canUpdate = buildIdentity.releaseBuild && target != null && buildIdentity.versionCode > 0
        return AppUpdateSupport(
            channel = if (canUpdate) {
                AppDistributionChannel.DirectDesktopPackage
            } else {
                AppDistributionChannel.Development
            },
            currentVersionName = buildIdentity.versionName,
            currentVersionCode = buildIdentity.versionCode,
            canCheckDirectUpdates = canUpdate,
            explanation = if (canUpdate) {
                "This Linux package checks the selected signed release channel and uses your system installer."
            } else {
                "Development and unsupported desktop packages are updated through their distribution workflow."
            },
        )
    }

    fun updateChannel(): AndroidUpdateChannel =
        parseAndroidUpdateChannel(preferences.get(KEY_UPDATE_CHANNEL, null))

    fun saveUpdateChannel(channel: AndroidUpdateChannel): Boolean {
        if (!canSelectAppUpdateChannel(support(), channel)) return false
        if (channel != updateChannel()) mutableCheckResult.value = null
        preferences.put(KEY_UPDATE_CHANNEL, channel.storageValue)
        return true
    }

    fun updatePreferences(): AppUpdatePreferences = AppUpdatePreferences(
        automaticChecks = preferences.getBoolean(KEY_AUTOMATIC_UPDATE_CHECKS, true),
        unmeteredNetworkOnly = preferences.getBoolean(KEY_UNMETERED_UPDATE_CHECKS, true),
        notifications = preferences.getBoolean(KEY_UPDATE_NOTIFICATIONS, true),
    )

    fun saveUpdatePreferences(value: AppUpdatePreferences) {
        preferences.putBoolean(KEY_AUTOMATIC_UPDATE_CHECKS, value.automaticChecks)
        preferences.putBoolean(KEY_UNMETERED_UPDATE_CHECKS, value.unmeteredNetworkOnly)
        preferences.putBoolean(KEY_UPDATE_NOTIFICATIONS, value.notifications)
    }

    fun observeCheckResult(): Flow<AppUpdateCheckResult?> = mutableCheckResult.asStateFlow()

    fun observeInstallState(): Flow<AppUpdateInstallState> = mutableInstallState.asStateFlow()

    fun checkForUpdate(channel: AndroidUpdateChannel): AppUpdateCheckResult {
        val support = support()
        val selectedTarget = target
        val result = if (!support.canCheckDirectUpdates || selectedTarget == null) {
            AppUpdateCheckResult.Unavailable(support)
        } else if (channel != updateChannel()) {
            AppUpdateCheckResult.Failed(
                support,
                "The update channel changed. Check again using the saved channel.",
            )
        } else runCatching {
            val metadataUrl = channel.desktopManifestUrl()
            val metadata = getDesktopUpdateBytes(
                client = client,
                url = metadataUrl,
                maximumBytes = MAX_DESKTOP_UPDATE_METADATA_BYTES.toLong(),
            )
            val release = parseDesktopDirectRelease(
                bytes = metadata,
                metadataUrl = metadataUrl,
                expectedChannel = channel,
                platform = selectedTarget.platform,
                format = selectedTarget.format,
                architecture = selectedTarget.architecture,
            )
            if (isNewerAppRelease(support.currentVersionCode, release)) {
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
        mutableCheckResult.value = result
        return result
    }

    suspend fun beginUpdate(release: AppUpdateRelease): AppUpdateInstallResult {
        val desktopRelease = release as? DesktopDirectRelease
            ?: return AppUpdateInstallResult.Rejected("This is not a desktop update package.")
        if (!updateMutex.tryLock()) {
            return AppUpdateInstallResult.Rejected("An app update is already in progress.")
        }
        try {
            val support = support()
            if (!support.canCheckDirectUpdates || !isNewerAppRelease(support.currentVersionCode, desktopRelease)) {
                return AppUpdateInstallResult.Rejected("This release cannot update the installed desktop package.")
            }
            val selectedTarget = requireNotNull(target)
            check(desktopRelease.asset.platform == selectedTarget.platform)
            check(desktopRelease.asset.format == selectedTarget.format)
            check(desktopRelease.asset.architecture == selectedTarget.architecture)
            updateDirectory.mkdirs()
            val packageFile = File(updateDirectory, desktopRelease.asset.url.substringAfterLast('/'))
            val temporary = File(updateDirectory, "${packageFile.name}.part")
            temporary.delete()
            cancellationRequested = false
            mutableInstallState.value = AppUpdateInstallState.Downloading(
                versionName = desktopRelease.versionName,
                versionCode = desktopRelease.versionCode,
                downloadedBytes = 0,
                totalBytes = desktopRelease.asset.size,
                resumedFromBytes = 0,
            )
            downloadDesktopUpdatePackage(
                client = client,
                release = desktopRelease,
                target = temporary,
                isCancelled = { cancellationRequested },
                onCallChanged = { activeCall = it },
                onProgress = { downloaded ->
                    mutableInstallState.value = AppUpdateInstallState.Downloading(
                        versionName = desktopRelease.versionName,
                        versionCode = desktopRelease.versionCode,
                        downloadedBytes = downloaded,
                        totalBytes = desktopRelease.asset.size,
                        resumedFromBytes = 0,
                    )
                },
            )
            mutableInstallState.value = AppUpdateInstallState.Verifying(
                desktopRelease.versionName,
                desktopRelease.versionCode,
            )
            check(temporary.sha256() == desktopRelease.asset.sha256) {
                "Update checksum verification failed."
            }
            Files.move(
                temporary.toPath(),
                packageFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            openInstaller(packageFile)
            mutableInstallState.value = AppUpdateInstallState.ConfirmationOpened(
                desktopRelease.versionName,
                desktopRelease.versionCode,
            )
            return AppUpdateInstallResult.ConfirmationOpened
        } catch (_: DesktopUpdateCancelledException) {
            File(updateDirectory, "${desktopRelease.asset.url.substringAfterLast('/')}.part").delete()
            mutableInstallState.value = AppUpdateInstallState.Cancelled(
                desktopRelease.versionName,
                desktopRelease.versionCode,
                downloadedBytes = 0,
                canResume = false,
            )
            return AppUpdateInstallResult.Cancelled
        } catch (cancelled: CancellationException) {
            activeCall?.cancel()
            throw cancelled
        } catch (failure: Exception) {
            File(updateDirectory, "${desktopRelease.asset.url.substringAfterLast('/')}.part").delete()
            if (cancellationRequested) {
                mutableInstallState.value = AppUpdateInstallState.Cancelled(
                    desktopRelease.versionName,
                    desktopRelease.versionCode,
                    downloadedBytes = 0,
                    canResume = false,
                )
                return AppUpdateInstallResult.Cancelled
            }
            mutableInstallState.value = AppUpdateInstallState.Failed(
                desktopRelease.versionName,
                desktopRelease.versionCode,
                failure.message ?: "The desktop update could not be verified.",
                downloadedBytes = 0,
                canResume = false,
            )
            return AppUpdateInstallResult.Rejected(
                failure.message ?: "The desktop update could not be verified.",
            )
        } finally {
            cancellationRequested = false
            activeCall = null
            updateMutex.unlock()
        }
    }

    fun cancelUpdate(): Boolean {
        if (mutableInstallState.value !is AppUpdateInstallState.Downloading) return false
        cancellationRequested = true
        activeCall?.cancel()
        return true
    }

    private companion object {
        const val KEY_UPDATE_CHANNEL = "app-update-channel"
        const val KEY_AUTOMATIC_UPDATE_CHECKS = "automatic-update-checks"
        const val KEY_UNMETERED_UPDATE_CHECKS = "unmetered-update-checks"
        const val KEY_UPDATE_NOTIFICATIONS = "update-notifications"
    }
}

internal fun buildDesktopUpdateHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.MINUTES)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun getDesktopUpdateBytes(
    client: OkHttpClient,
    url: String,
    maximumBytes: Long,
): ByteArray {
    val response = executeDesktopUpdateRequest(client, Request.Builder().url(url).get().build())
    response.use {
        check(it.isSuccessful) { "Update metadata request failed (HTTP ${it.code})." }
        val body = requireNotNull(it.body)
        check(body.contentLength() in -1..maximumBytes)
        return body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                check(output.size().toLong() + read <= maximumBytes)
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }
}

internal fun downloadDesktopUpdatePackage(
    client: OkHttpClient,
    release: DesktopDirectRelease,
    target: File,
    isCancelled: () -> Boolean,
    onCallChanged: (Call?) -> Unit,
    onProgress: (Long) -> Unit,
) {
    if (isCancelled()) throw DesktopUpdateCancelledException()
    val response = executeDesktopUpdateRequest(
        client,
        Request.Builder().url(release.asset.url).get().build(),
        onCallChanged,
    )
    response.use {
        check(it.isSuccessful) { "Update download failed (HTTP ${it.code})." }
        val body = requireNotNull(it.body)
        check(body.contentLength() in -1..release.asset.size)
        target.outputStream().use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    if (isCancelled()) throw DesktopUpdateCancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= release.asset.size) { "Update download exceeded its declared size." }
                    output.write(buffer, 0, read)
                    onProgress(total)
                }
                check(total == release.asset.size) { "Update download ended before it was complete." }
            }
        }
    }
}

internal fun executeDesktopUpdateRequest(
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
        ) { "Unexpected redirect while loading update content." }
        val location = requireNotNull(initialResponse.header("Location"))
        val redirectedUrl = requireNotNull(request.url.resolve(location))
        check(isTrustedDesktopReleaseAssetRedirect(redirectedUrl.toString())) {
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

internal fun isTrustedDesktopReleaseAssetRedirect(url: String): Boolean {
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

private fun defaultDesktopUpdateDirectory(): File {
    val cacheRoot = System.getenv("XDG_CACHE_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/app-updates")
}

private fun openDesktopPackageInstaller(packageFile: File) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(packageFile)
    } else {
        ProcessBuilder("xdg-open", packageFile.absolutePath).start()
    }
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
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

internal class DesktopUpdateCancelledException : IOException("Update download cancelled.")

internal const val DESKTOP_VERSION_NAME_PROPERTY = "dev.obiente.nextcloudnative.versionName"
internal const val DESKTOP_VERSION_CODE_PROPERTY = "dev.obiente.nextcloudnative.versionCode"
internal const val DESKTOP_PACKAGE_VERSION_PROPERTY = "dev.obiente.nextcloudnative.packageVersion"
internal const val DESKTOP_RELEASE_BUILD_PROPERTY = "dev.obiente.nextcloudnative.releaseBuild"
