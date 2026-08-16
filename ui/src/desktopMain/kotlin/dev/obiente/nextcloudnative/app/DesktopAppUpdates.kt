package dev.obiente.nextcloudnative.app

import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
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
    val directPackageUpdates: Boolean,
)

internal data class DesktopUpdateTarget(
    val platform: String,
    val format: String,
    val architecture: String,
)

internal fun canUseDirectDesktopUpdates(
    buildIdentity: DesktopUpdateBuildIdentity,
    target: DesktopUpdateTarget?,
): Boolean = target != null &&
    buildIdentity.versionCode > 0 &&
    buildIdentity.directPackageUpdates

internal enum class DesktopPackageInstallerOutcome {
    InstallerHandoffStarted,
    InstallationCompleted,
}

internal fun currentDesktopUpdateBuildIdentity(): DesktopUpdateBuildIdentity =
    DesktopUpdateBuildIdentity(
        versionName = System.getProperty(DESKTOP_VERSION_NAME_PROPERTY, "development"),
        versionCode = System.getProperty(DESKTOP_VERSION_CODE_PROPERTY, "0").toLongOrNull() ?: 0L,
        packageVersion = System.getProperty(DESKTOP_PACKAGE_VERSION_PROPERTY, "0.0.0"),
        releaseBuild = System.getProperty(DESKTOP_RELEASE_BUILD_PROPERTY, "false").toBooleanStrictOrNull() == true,
        directPackageUpdates =
            System.getProperty(DESKTOP_DIRECT_PACKAGE_UPDATES_PROPERTY, "false").toBooleanStrictOrNull() == true,
    )

internal fun detectDesktopUpdateTarget(
    osName: String = System.getProperty("os.name", ""),
    architecture: String = System.getProperty("os.arch", ""),
    debianMarker: Boolean = File("/etc/debian_version").isFile,
    rpmMarker: Boolean = File("/etc/redhat-release").isFile || File("/etc/fedora-release").isFile,
    installedPackageFormat: String? = detectInstalledDesktopPackageFormat(osName),
): DesktopUpdateTarget? {
    val normalizedArchitecture = when (architecture.lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> return null
    }
    if (osName.startsWith("Windows", ignoreCase = true)) {
        return if (normalizedArchitecture == "x86_64") {
            DesktopUpdateTarget("windows", "msi", normalizedArchitecture)
        } else {
            null
        }
    }
    if (!osName.startsWith("Linux", ignoreCase = true)) return null
    val format = installedPackageFormat?.takeIf { it in DESKTOP_LINUX_PACKAGE_FORMATS }
        ?: when {
            rpmMarker && !debianMarker -> "rpm"
            debianMarker && !rpmMarker -> "deb"
            else -> return null
        }
    return DesktopUpdateTarget("linux", format, normalizedArchitecture)
}

internal fun detectInstalledDesktopPackageFormat(
    osName: String,
    packageQueryOutput: (List<String>) -> String? = ::desktopPackageQueryOutput,
): String? {
    if (!osName.startsWith("Linux", ignoreCase = true)) return null
    val debInstalled = packageQueryOutput(
        listOf("/usr/bin/dpkg-query", "--show", "--showformat=\${db:Status-Abbrev}", DESKTOP_PACKAGE_NAME),
    )?.startsWith("ii") == true
    val rpmInstalled = packageQueryOutput(
        listOf("/usr/bin/rpm", "--query", "--queryformat=%{NAME}", DESKTOP_PACKAGE_NAME),
    ) == DESKTOP_PACKAGE_NAME
    return when {
        debInstalled && !rpmInstalled -> "deb"
        rpmInstalled && !debInstalled -> "rpm"
        else -> null
    }
}

internal fun detectInstalledDesktopPackageVersion(
    target: DesktopUpdateTarget,
    packageQueryOutput: (List<String>) -> String? = ::desktopPackageQueryOutput,
): String? = when {
    target.platform != "linux" -> null
    target.format == "rpm" -> packageQueryOutput(
        listOf("/usr/bin/rpm", "--query", "--queryformat=%{VERSION}", DESKTOP_PACKAGE_NAME),
    )
    target.format == "deb" -> packageQueryOutput(
        listOf("/usr/bin/dpkg-query", "--show", "--showformat=\${Version}", DESKTOP_PACKAGE_NAME),
    )
    else -> null
}?.takeIf(String::isNotBlank)

internal fun requireInstalledDesktopPackageVersion(installedVersion: String?, expectedVersion: String) {
    check(installedVersion == expectedVersion) {
        "The system installer finished, but package version " +
            "${installedVersion ?: "could not be read"} is installed instead of $expectedVersion."
    }
}

private fun desktopPackageQueryOutput(command: List<String>): String? = runCatching {
    val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(DESKTOP_PACKAGE_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        null
    } else if (process.exitValue() != 0) {
        null
    } else {
        process.inputStream.bufferedReader().use { output ->
            output.readText().take(DESKTOP_PACKAGE_QUERY_MAX_OUTPUT_CHARACTERS).trimEnd()
        }
    }
}.getOrNull()

internal class DesktopAppUpdater(
    private val preferences: Preferences,
    private val buildIdentity: DesktopUpdateBuildIdentity = currentDesktopUpdateBuildIdentity(),
    private val target: DesktopUpdateTarget? = detectDesktopUpdateTarget(),
    private val updateDirectory: File = defaultDesktopUpdateDirectory(),
    private val client: OkHttpClient = buildDesktopUpdateHttpClient(),
    private val prepareInstaller: (File, DesktopDirectRelease) -> Unit = ::prepareDesktopPackageInstaller,
    private val openInstaller: (File) -> DesktopPackageInstallerOutcome = ::openDesktopPackageInstaller,
    private val installedPackageVersion: (DesktopUpdateTarget) -> String? = ::detectInstalledDesktopPackageVersion,
    private val onInstallerConfirmationOpened: (DesktopUpdateTarget) -> Unit = {},
) {
    private val mutableCheckResult = MutableStateFlow<AppUpdateCheckResult?>(null)
    private val mutableInstallState = MutableStateFlow<AppUpdateInstallState>(AppUpdateInstallState.Idle)
    private val channelStateLock = Any()
    private val updateMutex = Mutex()
    @Volatile private var activeCall: Call? = null
    @Volatile private var cancellationRequested = false

    fun support(): AppUpdateSupport {
        val canUpdate = canUseDirectDesktopUpdates(buildIdentity, target)
        return AppUpdateSupport(
            channel = if (canUpdate) {
                AppDistributionChannel.DirectDesktopPackage
            } else {
                AppDistributionChannel.Development
            },
            currentVersionName = buildIdentity.versionName,
            currentVersionCode = buildIdentity.versionCode,
            canCheckDirectUpdates = canUpdate,
            explanation = when {
                canUpdate && !buildIdentity.releaseBuild ->
                    "This development build can update to a newer release from the selected channel. Downloads " +
                        "are matched to their advertised checksum before using your system installer."
                canUpdate ->
                    "This native package checks the selected release channel, matches downloads to its advertised " +
                        "checksum, and uses your system installer."
                !buildIdentity.releaseBuild ->
                    "This development build cannot check for updates directly. Install a newer development build " +
                        "or release through the same download or package workflow that provided this build."
                else ->
                    "Distribution-managed and unsupported desktop packages are updated through their distribution " +
                        "workflow."
            },
        )
    }

    fun updateChannel(): AndroidUpdateChannel = synchronized(channelStateLock) {
        storedUpdateChannel()
    }

    fun saveUpdateChannel(channel: AndroidUpdateChannel): Boolean = synchronized(channelStateLock) {
        if (!canSelectAppUpdateChannel(support(), channel)) return false
        if (channel != storedUpdateChannel()) mutableCheckResult.value = null
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
        return synchronized(channelStateLock) {
            if (channel != storedUpdateChannel()) {
                AppUpdateCheckResult.Failed(
                    support,
                    "The update channel changed. Check again using the saved channel.",
                )
            } else {
                mutableCheckResult.value = result
                result
            }
        }
    }

    suspend fun beginUpdate(release: AppUpdateRelease): AppUpdateInstallResult {
        val desktopRelease = release as? DesktopDirectRelease
            ?: return AppUpdateInstallResult.Rejected(
                "This is not a desktop update package.",
                "desktop-package-type",
            )
        if (desktopRelease.updateChannel != updateChannel()) {
            return AppUpdateInstallResult.Rejected(
                "The update channel changed. Check again before downloading this package.",
                "desktop-channel-changed",
            )
        }
        if (!updateMutex.tryLock()) {
            return AppUpdateInstallResult.Rejected(
                "An app update is already in progress.",
                "desktop-already-running",
            )
        }
        var diagnosticStage = "preflight"
        try {
            val support = support()
            if (!support.canCheckDirectUpdates || !isNewerAppRelease(support.currentVersionCode, desktopRelease)) {
                return AppUpdateInstallResult.Rejected(
                    "This release cannot update the installed desktop package.",
                    "desktop-release-ineligible",
                )
            }
            val selectedTarget = requireNotNull(target)
            check(desktopRelease.asset.platform == selectedTarget.platform)
            check(desktopRelease.asset.format == selectedTarget.format)
            check(desktopRelease.asset.architecture == selectedTarget.architecture)
            check(updateDirectory.isDirectory || updateDirectory.mkdirs()) {
                "Could not create the desktop app-update cache."
            }
            diagnosticStage = "cache"
            val packageFile = File(updateDirectory, desktopRelease.asset.url.substringAfterLast('/'))
            val temporary = File(updateDirectory, "${packageFile.name}.part")
            cleanupDesktopUpdatePackages(updateDirectory, activePartial = temporary)
            temporary.delete()
            cancellationRequested = false
            mutableInstallState.value = AppUpdateInstallState.Downloading(
                versionName = desktopRelease.versionName,
                versionCode = desktopRelease.versionCode,
                downloadedBytes = 0,
                totalBytes = desktopRelease.asset.size,
                resumedFromBytes = 0,
            )
            diagnosticStage = "download"
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
            diagnosticStage = "verification"
            check(temporary.sha256() == desktopRelease.asset.sha256) {
                "Update checksum verification failed."
            }
            Files.move(
                temporary.toPath(),
                packageFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            diagnosticStage = "installer-preparation"
            prepareInstaller(packageFile, desktopRelease)
            mutableInstallState.value = AppUpdateInstallState.Installing(
                desktopRelease.versionName,
                desktopRelease.versionCode,
            )
            diagnosticStage = "installer-handoff"
            return when (openInstaller(packageFile)) {
                DesktopPackageInstallerOutcome.InstallerHandoffStarted -> {
                    mutableInstallState.value = AppUpdateInstallState.ConfirmationOpened(
                        desktopRelease.versionName,
                        desktopRelease.versionCode,
                    )
                    onInstallerConfirmationOpened(selectedTarget)
                    AppUpdateInstallResult.ConfirmationOpened
                }
                DesktopPackageInstallerOutcome.InstallationCompleted -> {
                    diagnosticStage = "installed-version-verification"
                    val installedVersion = installedPackageVersion(selectedTarget)
                    requireInstalledDesktopPackageVersion(installedVersion, desktopRelease.packageVersion)
                    mutableInstallState.value = AppUpdateInstallState.Installed(
                        desktopRelease.versionName,
                        desktopRelease.versionCode,
                    )
                    AppUpdateInstallResult.Installed
                }
            }
        } catch (_: DesktopUpdateCancelledException) {
            File(updateDirectory, "${desktopRelease.asset.url.substringAfterLast('/')}.part").delete()
            mutableInstallState.value = AppUpdateInstallState.Cancelled(
                desktopRelease.versionName,
                desktopRelease.versionCode,
                downloadedBytes = 0,
                canResume = false,
            )
            return AppUpdateInstallResult.Cancelled(canResume = false)
        } catch (cancelled: CancellationException) {
            activeCall?.cancel()
            File(updateDirectory, "${desktopRelease.asset.url.substringAfterLast('/')}.part").delete()
            mutableInstallState.value = AppUpdateInstallState.Cancelled(
                desktopRelease.versionName,
                desktopRelease.versionCode,
                downloadedBytes = 0,
                canResume = false,
            )
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
                return AppUpdateInstallResult.Cancelled(canResume = false)
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
                "desktop-$diagnosticStage",
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

    private fun storedUpdateChannel(): AndroidUpdateChannel {
        val storedValue = preferences.get(KEY_UPDATE_CHANNEL, null)
        val channel = parseAndroidUpdateChannel(storedValue)
        if (storedValue != channel.storageValue) {
            preferences.put(KEY_UPDATE_CHANNEL, channel.storageValue)
        }
        return channel
    }

    private companion object {
        const val KEY_UPDATE_CHANNEL = "app-update-channel"
        const val KEY_AUTOMATIC_UPDATE_CHECKS = "automatic-update-checks"
        const val KEY_UNMETERED_UPDATE_CHECKS = "unmetered-update-checks"
        const val KEY_UPDATE_NOTIFICATIONS = "update-notifications"
    }
}

internal fun cleanupDesktopUpdatePackages(
    directory: File,
    activePartial: File? = null,
    nowMillis: Long = System.currentTimeMillis(),
    partialRetentionMillis: Long = DESKTOP_PARTIAL_RETENTION_MILLIS,
    maximumPartialBytes: Long = MAX_DESKTOP_UPDATE_PACKAGE_BYTES,
): Int {
    if (!directory.isDirectory) return 0
    require(partialRetentionMillis >= 0)
    require(maximumPartialBytes >= 0)
    var removed = 0
    val regularFiles = directory.listFiles().orEmpty().filter { candidate ->
        Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
    regularFiles.forEach { candidate ->
        val extension = candidate.extension.lowercase()
        if (extension in DESKTOP_UPDATE_PACKAGE_EXTENSIONS) {
            check(candidate.delete()) { "Could not clear an older desktop update package." }
            removed += 1
        }
    }
    val activePath = activePartial?.toPath()?.toAbsolutePath()?.normalize()
    val partials = regularFiles
        .filter(File::isDesktopUpdatePartial)
        .filter { candidate -> candidate.toPath().toAbsolutePath().normalize() != activePath }
        .sortedBy(File::lastModified)
    var retainedPartialBytes = partials.sumOf(File::length)
    partials.forEach { candidate ->
        val expired = candidate.lastModified() <= nowMillis - partialRetentionMillis
        val overBudget = retainedPartialBytes > maximumPartialBytes
        if (expired || overBudget) {
            val size = candidate.length()
            check(candidate.delete()) { "Could not clear an abandoned desktop update download." }
            retainedPartialBytes -= size
            removed += 1
        }
    }
    return removed
}

private fun File.isDesktopUpdatePartial(): Boolean {
    if (!name.endsWith(".part", ignoreCase = true)) return false
    val packageExtension = name.dropLast(".part".length).substringAfterLast('.').lowercase()
    return packageExtension in DESKTOP_UPDATE_PACKAGE_EXTENSIONS
}

internal val DESKTOP_APP_UPDATE_CHECK_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

private val DESKTOP_UPDATE_PACKAGE_EXTENSIONS = setOf("deb", "rpm", "msi", "dmg", "pkg")
private val DESKTOP_LINUX_PACKAGE_FORMATS = setOf("deb", "rpm")
private const val DESKTOP_PACKAGE_NAME = "nextcloudnative"
private const val DESKTOP_PACKAGE_QUERY_TIMEOUT_MILLIS = 500L
private const val DESKTOP_PACKAGE_QUERY_MAX_OUTPUT_CHARACTERS = 64
internal val DESKTOP_PARTIAL_RETENTION_MILLIS: Long = TimeUnit.HOURS.toMillis(24)

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
    if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Local")
        return File(localAppData, "Nextcloud Native/Cache/App Updates")
    }
    val cacheRoot = System.getenv("XDG_CACHE_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/app-updates")
}

private fun prepareDesktopPackageInstaller(
    packageFile: File,
    release: DesktopDirectRelease,
) {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return
    val zoneIdentifier = File("${packageFile.absolutePath}:Zone.Identifier")
    zoneIdentifier.writeText(
        windowsZoneIdentifier(
            sourceUrl = release.asset.url,
            referrerUrl = release.releaseNotesUrl,
        ),
        Charsets.UTF_8,
    )
    check(zoneIdentifier.isFile) { "Windows could not attach Internet-zone metadata to the update package." }
}

internal fun windowsZoneIdentifier(sourceUrl: String, referrerUrl: String): String {
    require(sourceUrl.startsWith("https://") && '\r' !in sourceUrl && '\n' !in sourceUrl)
    require(referrerUrl.startsWith("https://") && '\r' !in referrerUrl && '\n' !in referrerUrl)
    return "[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=$sourceUrl\r\nReferrerUrl=$referrerUrl\r\n"
}

private fun openDesktopPackageInstaller(packageFile: File): DesktopPackageInstallerOutcome {
    if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
        startWindowsInstallerAfterAppExit(packageFile)
        return DesktopPackageInstallerOutcome.InstallerHandoffStarted
    }
    if (runLinuxNativePackageInstaller(packageFile)) {
        return DesktopPackageInstallerOutcome.InstallationCompleted
    }
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(packageFile)
    } else {
        ProcessBuilder("xdg-open", packageFile.absolutePath).start()
    }
    return DesktopPackageInstallerOutcome.InstallerHandoffStarted
}

internal fun startWindowsInstallerAfterAppExit(
    packageFile: File,
    parentProcessId: Long = ProcessHandle.current().pid(),
    windowsDirectory: File? = System.getenv("SystemRoot")?.takeIf(String::isNotBlank)?.let(::File),
    launcherFile: File? = packagedDesktopLauncherPath()?.let(::File),
    updateGateFile: File = desktopUpdateHandoffGateFile(),
    processStarter: (List<String>) -> WindowsInstallerHandoffProcess = ::startWindowsInstallerHandoffProcess,
    readinessWaiter: (File, String) -> Boolean = ::waitForWindowsInstallerHandoffReadiness,
) {
    check(parentProcessId > 0L) { "The current Windows process could not be identified." }
    check(packageFile.extension.equals("msi", ignoreCase = true)) { "The Windows update package is not an MSI." }
    check(Files.isRegularFile(packageFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "The verified Windows update package is no longer a regular file."
    }
    val systemRoot = requireNotNull(windowsDirectory) { "The Windows system directory is unavailable." }
    val powershell = File(systemRoot, "System32/WindowsPowerShell/v1.0/powershell.exe")
    check(Files.isRegularFile(powershell.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "The trusted Windows PowerShell executable could not be found."
    }
    val launcher = requireNotNull(launcherFile) { "The installed Nextcloud Native launcher is unavailable." }
    check(Files.isRegularFile(launcher.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "The installed Nextcloud Native launcher could not be found."
    }
    val updateGate = updateGateFile.toPath().toAbsolutePath().normalize()
    val updateGateDirectory = requireNotNull(updateGate.parent)
    Files.createDirectories(updateGateDirectory)
    check(!Files.isSymbolicLink(updateGateDirectory)) { "The desktop update runtime folder cannot be a symlink." }
    check(!Files.isSymbolicLink(updateGate)) { "The desktop update handoff gate cannot be a symlink." }
    val script = writeWindowsInstallerHandoffScript(requireNotNull(packageFile.parentFile))
    val acknowledgement = Files.createTempFile(packageFile.parentFile.toPath(), "installer-ready-", ".ack").toFile()
    Files.delete(acknowledgement.toPath())
    val acknowledgementToken = ByteArray(32).also(SecureRandom()::nextBytes).lowercaseHex()
    val cancellation = Files.createTempFile(packageFile.parentFile.toPath(), "installer-cancel-", ".ack").toFile()
    Files.delete(cancellation.toPath())
    val cancellationToken = ByteArray(32).also(SecureRandom()::nextBytes).lowercaseHex()
    var handoffProcess: WindowsInstallerHandoffProcess? = null
    try {
        handoffProcess = processStarter(
            windowsInstallerHandoffCommand(
                powershell = powershell,
                script = script,
                parentProcessId = parentProcessId,
                packageFile = packageFile,
                launcherFile = launcher,
                updateGateFile = updateGate.toFile(),
                acknowledgementFile = acknowledgement,
                acknowledgementToken = acknowledgementToken,
                cancellationFile = cancellation,
                cancellationToken = cancellationToken,
            ),
        )
        check(readinessWaiter(acknowledgement, acknowledgementToken)) {
            "The Windows installer handoff did not confirm that it was ready."
        }
    } catch (failure: Throwable) {
        val cancellationRecorded = runCatching {
            Files.writeString(
                cancellation.toPath(),
                cancellationToken,
                Charsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            true
        }.getOrDefault(false)
        val processStopped = runCatching { handoffProcess?.cancelAndWait() != false }.getOrDefault(false)
        Files.deleteIfExists(acknowledgement.toPath())
        if (processStopped) {
            Files.deleteIfExists(cancellation.toPath())
            Files.deleteIfExists(script.toPath())
        } else if (!cancellationRecorded) {
            failure.addSuppressed(
                IOException("The Windows installer handoff could not be cancelled safely."),
            )
        }
        throw failure
    }
}

internal fun interface WindowsInstallerHandoffProcess {
    fun cancelAndWait(): Boolean
}

private fun startWindowsInstallerHandoffProcess(command: List<String>): WindowsInstallerHandoffProcess {
    val process = ProcessBuilder(command).start()
    return WindowsInstallerHandoffProcess {
        var interrupted = false
        runCatching { process.destroy() }
        val exitedNormally = try {
            process.waitFor(WINDOWS_INSTALLER_HANDOFF_CANCEL_GRACE_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!exitedNormally) {
            runCatching { process.destroyForcibly() }
            while (process.isAlive) {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        !process.isAlive
    }
}

internal fun windowsInstallerHandoffCommand(
    powershell: File,
    script: File,
    parentProcessId: Long,
    packageFile: File,
    launcherFile: File,
    updateGateFile: File,
    acknowledgementFile: File,
    acknowledgementToken: String,
    cancellationFile: File,
    cancellationToken: String,
): List<String> = listOf(
    powershell.absolutePath,
    "-NoLogo",
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-WindowStyle",
    "Hidden",
    "-File",
    script.absolutePath,
    "-ParentProcessId",
    parentProcessId.toString(),
    "-InstallerPath",
    packageFile.absolutePath,
    "-LauncherPath",
    launcherFile.absolutePath,
    "-UpdateGatePath",
    updateGateFile.absolutePath,
    "-AcknowledgementPath",
    acknowledgementFile.absolutePath,
    "-AcknowledgementToken",
    acknowledgementToken,
    "-CancellationPath",
    cancellationFile.absolutePath,
    "-CancellationToken",
    cancellationToken,
)

internal fun desktopUpdateHandoffGateFile(
    runtimeDirectory: File = defaultDesktopRuntimeDirectory(),
): File = runtimeDirectory.resolve(WINDOWS_INSTALLER_HANDOFF_GATE_NAME)

internal fun desktopUpdateHandoffActive(
    gateFile: File = desktopUpdateHandoffGateFile(),
    windows: Boolean = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true),
): Boolean {
    if (!windows) return false
    val path = gateFile.toPath().toAbsolutePath().normalize()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true
    return try {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) return true
            lock.release()
        }
        Files.deleteIfExists(path)
        false
    } catch (_: IOException) {
        true
    }
}

private fun waitForWindowsInstallerHandoffReadiness(
    acknowledgementFile: File,
    expectedToken: String,
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WINDOWS_INSTALLER_HANDOFF_READY_TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
        val acknowledged = runCatching {
            Files.isRegularFile(acknowledgementFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                acknowledgementFile.length() <= WINDOWS_INSTALLER_ACKNOWLEDGEMENT_MAX_BYTES &&
                MessageDigest.isEqual(
                    acknowledgementFile.readBytes(),
                    expectedToken.encodeToByteArray(),
                )
        }.getOrDefault(false)
        if (acknowledged) return true
        try {
            Thread.sleep(WINDOWS_INSTALLER_HANDOFF_READY_POLL_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }
    return false
}

private fun writeWindowsInstallerHandoffScript(directory: File): File {
    check(directory.isDirectory) { "The Windows update cache is unavailable." }
    val target = File(directory, WINDOWS_INSTALLER_HANDOFF_SCRIPT_NAME)
    val temporary = Files.createTempFile(directory.toPath(), "windows-installer-handoff-", ".ps1")
    try {
        Files.writeString(temporary, WINDOWS_INSTALLER_HANDOFF_SCRIPT)
        try {
            Files.move(
                temporary,
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
    return target
}

internal fun runLinuxNativePackageInstaller(
    packageFile: File,
    commandResolver: (File) -> List<String>? = ::linuxNativePackageInstallerCommand,
    commandRunner: (List<String>) -> Int = ::runNativePackageInstallerCommand,
): Boolean {
    val command = commandResolver(packageFile) ?: return false
    check(Files.isRegularFile(packageFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "The verified Linux update package is no longer a regular file."
    }
    val exitCode = commandRunner(command)
    check(exitCode == 0) { "The system package transaction failed with exit code $exitCode." }
    return true
}

private fun runNativePackageInstallerCommand(command: List<String>): Int {
    val process = ProcessBuilder(command).inheritIO().start()
    return try {
        process.waitFor()
    } catch (interrupted: InterruptedException) {
        process.destroy()
        Thread.currentThread().interrupt()
        throw IOException("The system package transaction was interrupted.", interrupted)
    }
}

internal fun linuxNativePackageInstallerCommand(
    packageFile: File,
    executableAvailable: (File) -> Boolean = { executable -> executable.isFile && executable.canExecute() },
): List<String>? {
    if (packageFile.extension.lowercase() !in DESKTOP_LINUX_PACKAGE_FORMATS) return null
    val packageKitClient = File("/usr/bin/pkcon")
    if (!executableAvailable(packageKitClient)) return null
    return listOf(
        packageKitClient.absolutePath,
        "--noninteractive",
        "install-local",
        packageFile.toPath().toAbsolutePath().normalize().toString(),
    )
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

private fun ByteArray.lowercaseHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

internal class DesktopUpdateCancelledException : IOException("Update download cancelled.")

private const val WINDOWS_INSTALLER_HANDOFF_SCRIPT_NAME = "install-after-app-exit.ps1"
private const val WINDOWS_INSTALLER_HANDOFF_GATE_NAME = "windows-update-in-progress.lock"
private const val WINDOWS_INSTALLER_HANDOFF_READY_TIMEOUT_SECONDS = 5L
private const val WINDOWS_INSTALLER_HANDOFF_READY_POLL_MILLIS = 25L
private const val WINDOWS_INSTALLER_HANDOFF_CANCEL_GRACE_SECONDS = 2L
private const val WINDOWS_INSTALLER_ACKNOWLEDGEMENT_MAX_BYTES = 128L
private val WINDOWS_INSTALLER_HANDOFF_SCRIPT = """
    param(
        [Parameter(Mandatory = ${'$'}true)][long]${'$'}ParentProcessId,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}InstallerPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}LauncherPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}UpdateGatePath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}AcknowledgementPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}AcknowledgementToken,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}CancellationPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}CancellationToken
    )

    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}updateGateStream = ${'$'}null
    ${'$'}relaunchApplication = ${'$'}false
    ${'$'}relaunchWithFailure = ${'$'}false
    function Test-HandoffCancellation {
        if (-not (Test-Path -LiteralPath ${'$'}CancellationPath -PathType Leaf)) {
            return ${'$'}false
        }
        ${'$'}cancellationInfo = Get-Item -LiteralPath ${'$'}CancellationPath -ErrorAction SilentlyContinue
        if (${'$'}null -eq ${'$'}cancellationInfo -or
            ${'$'}cancellationInfo.Length -gt 128) {
            return ${'$'}false
        }
        ${'$'}recordedToken = Get-Content -LiteralPath ${'$'}CancellationPath -Raw -ErrorAction SilentlyContinue
        return ${'$'}recordedToken -eq ${'$'}CancellationToken
    }
    try {
        if (-not (Test-Path -LiteralPath ${'$'}InstallerPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath ${'$'}LauncherPath -PathType Leaf)) {
            throw 'The verified installer or application launcher is unavailable.'
        }
        ${'$'}updateGateStream = [System.IO.File]::Open(
            ${'$'}UpdateGatePath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        ${'$'}updateGateStream.SetLength(0)
        ${'$'}gateBytes = [System.Text.Encoding]::ASCII.GetBytes([string]${'$'}PID)
        ${'$'}updateGateStream.Write(${'$'}gateBytes, 0, ${'$'}gateBytes.Length)
        ${'$'}updateGateStream.Flush(${'$'}true)
        Set-Content -LiteralPath ${'$'}AcknowledgementPath -Value ${'$'}AcknowledgementToken -NoNewline -Encoding ascii
        if (Test-HandoffCancellation) {
            throw 'The Windows installer handoff was cancelled before application exit.'
        }
        Wait-Process -Id ${'$'}ParentProcessId -ErrorAction SilentlyContinue
        if (Test-HandoffCancellation) {
            throw 'The Windows installer handoff was cancelled before installer launch.'
        }
        ${'$'}msiexecPath = Join-Path ${'$'}env:SystemRoot 'System32\msiexec.exe'
        if (-not (Test-Path -LiteralPath ${'$'}msiexecPath -PathType Leaf)) {
            throw 'The Windows Installer service executable is unavailable.'
        }
        ${'$'}quotedInstallerPath = '"' + ${'$'}InstallerPath + '"'
        ${'$'}installerProcess = Start-Process -FilePath ${'$'}msiexecPath `
            -ArgumentList @('/i', ${'$'}quotedInstallerPath, 'NEXTCLOUD_NATIVE_UPDATER_HANDOFF=1') `
            -PassThru -Wait
        ${'$'}successfulExitCodes = @(0, 1641, 3010)
        if (${'$'}installerProcess.ExitCode -notin ${'$'}successfulExitCodes) {
            throw "The Windows installer exited with code ${'$'}(${'$'}installerProcess.ExitCode)."
        }
        ${'$'}relaunchApplication = ${'$'}true
    } catch {
        if (-not (Get-Process -Id ${'$'}ParentProcessId -ErrorAction SilentlyContinue) -and
            (Test-Path -LiteralPath ${'$'}LauncherPath -PathType Leaf)) {
            ${'$'}relaunchApplication = ${'$'}true
            ${'$'}relaunchWithFailure = ${'$'}true
        }
    } finally {
        Remove-Item -LiteralPath ${'$'}AcknowledgementPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ${'$'}CancellationPath -Force -ErrorAction SilentlyContinue
        if (${'$'}null -ne ${'$'}updateGateStream) {
            ${'$'}updateGateStream.Dispose()
        }
        Remove-Item -LiteralPath ${'$'}UpdateGatePath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ${'$'}PSCommandPath -Force -ErrorAction SilentlyContinue
    }
    if (${'$'}relaunchApplication -and (Test-Path -LiteralPath ${'$'}LauncherPath -PathType Leaf)) {
        try {
            if (${'$'}relaunchWithFailure) {
                Start-Process -FilePath ${'$'}LauncherPath `
                    -ArgumentList @('--update-handoff-failed') `
                    -ErrorAction Stop
            } else {
                Start-Process -FilePath ${'$'}LauncherPath -ErrorAction Stop
            }
        } catch {
            if (-not ${'$'}relaunchWithFailure) {
                Start-Process -FilePath ${'$'}LauncherPath `
                    -ArgumentList @('--update-handoff-failed') `
                    -ErrorAction SilentlyContinue
            }
        }
    }
""".trimIndent() + "\r\n"

internal const val DESKTOP_VERSION_NAME_PROPERTY = "dev.obiente.nextcloudnative.versionName"
internal const val DESKTOP_VERSION_CODE_PROPERTY = "dev.obiente.nextcloudnative.versionCode"
internal const val DESKTOP_PACKAGE_VERSION_PROPERTY = "dev.obiente.nextcloudnative.packageVersion"
internal const val DESKTOP_RELEASE_BUILD_PROPERTY = "dev.obiente.nextcloudnative.releaseBuild"
internal const val DESKTOP_DIRECT_PACKAGE_UPDATES_PROPERTY = "dev.obiente.nextcloudnative.directPackageUpdates"
