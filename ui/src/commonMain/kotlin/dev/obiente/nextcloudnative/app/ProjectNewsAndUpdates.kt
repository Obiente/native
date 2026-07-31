package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val PROJECT_NEWS_FEED_URL = "https://nc-native.obiente.dev/news-feed-v1.json"
const val MAX_PROJECT_NEWS_FEED_BYTES = 512 * 1024
const val MAX_PROJECT_NEWS_IMAGE_BYTES = 8 * 1024 * 1024
const val MAX_ANDROID_UPDATE_METADATA_BYTES = 64 * 1024
const val MAX_ANDROID_UPDATE_APK_BYTES = 256L * 1024L * 1024L
const val MAX_DESKTOP_UPDATE_METADATA_BYTES = 128 * 1024
const val MAX_DESKTOP_UPDATE_PACKAGE_BYTES = 512L * 1024L * 1024L

@Serializable
data class ProjectNewsFeed(
    val schemaVersion: Int,
    val feedRevision: String,
    val entries: List<ProjectNewsArticle>,
)

@Serializable
data class ProjectNewsArticle(
    val id: String,
    val title: String,
    val description: String,
    val publishedDate: String,
    val lastUpdated: String? = null,
    val tags: List<String>,
    val bodyMarkdown: String,
    val webUrl: String,
    val contentSha256: String,
    val image: ProjectNewsImage,
)

@Serializable
data class ProjectNewsImage(
    val url: String,
    val alt: String,
    val width: Int,
    val height: Int,
    val sha256: String,
)

data class ProjectNewsResult(
    val feed: ProjectNewsFeed,
    val cached: Boolean,
)

data class ProjectNewsArticlePresentation(
    val heroImage: ProjectNewsImage,
)

fun projectNewsArticlePresentation(article: ProjectNewsArticle): ProjectNewsArticlePresentation =
    ProjectNewsArticlePresentation(heroImage = article.image)

enum class AppDistributionChannel {
    DirectApk,
    DirectDesktopPackage,
    GooglePlay,
    FDroid,
    OtherStore,
    Development,
    Unsupported,
}

internal fun appUpdateDownloadCancellationLabel(channel: AppDistributionChannel): String =
    if (channel == AppDistributionChannel.DirectDesktopPackage) "Cancel download" else "Pause download"

data class AppUpdateSupport(
    val channel: AppDistributionChannel,
    val currentVersionName: String,
    val currentVersionCode: Long,
    val canCheckDirectUpdates: Boolean,
    val explanation: String,
)

sealed interface AppUpdateInstallState {
    data object Idle : AppUpdateInstallState

    data class Downloading(
        val versionName: String,
        val versionCode: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val resumedFromBytes: Long,
    ) : AppUpdateInstallState

    data class Verifying(
        val versionName: String,
        val versionCode: Long,
    ) : AppUpdateInstallState

    data class PermissionRequired(
        val versionName: String,
        val versionCode: Long,
        val message: String,
    ) : AppUpdateInstallState

    data class Cancelled(
        val versionName: String,
        val versionCode: Long,
        val downloadedBytes: Long,
        val canResume: Boolean,
    ) : AppUpdateInstallState

    data class Failed(
        val versionName: String,
        val versionCode: Long,
        val message: String,
        val downloadedBytes: Long,
        val canResume: Boolean,
    ) : AppUpdateInstallState

    data class ConfirmationOpened(
        val versionName: String,
        val versionCode: Long,
    ) : AppUpdateInstallState
}

sealed interface AppUpdateRelease {
    val versionName: String
    val versionCode: Long
    val packageSize: Long
    val releaseNotesUrl: String
}

@Serializable
data class AndroidDirectRelease(
    val schemaVersion: Int,
    val channel: String,
    override val versionName: String,
    override val versionCode: Long,
    val packageName: String,
    val minimumAndroidSdk: Int,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
    val signingCertificateSha256Digests: List<String>,
    override val releaseNotesUrl: String,
) : AppUpdateRelease {
    override val packageSize: Long get() = apkSize
}

@Serializable
data class DesktopUpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val versionName: String,
    val versionCode: Long,
    val packageVersion: String,
    val releaseNotesUrl: String,
    val assets: List<DesktopUpdateAsset>,
)

@Serializable
data class DesktopUpdateAsset(
    val platform: String,
    val format: String,
    val architecture: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

data class DesktopDirectRelease(
    override val versionName: String,
    override val versionCode: Long,
    val packageVersion: String,
    val asset: DesktopUpdateAsset,
    override val releaseNotesUrl: String,
) : AppUpdateRelease {
    override val packageSize: Long get() = asset.size
}

data class AppUpdatePreferences(
    val automaticChecks: Boolean = true,
    val unmeteredNetworkOnly: Boolean = true,
    val notifications: Boolean = true,
)

enum class AndroidUpdateChannel(
    val storageValue: String,
    val manifestChannel: String,
    val pointerTag: String,
    val available: Boolean,
) {
    Alpha("alpha", "prerelease-v1", "channel-prerelease", true),
    Nightly("nightly", "nightly-v1", "channel-nightly", true),
    Beta("beta", "beta-v1", "channel-beta", false),
    Stable("stable", "stable-v1", "channel-stable", false),
}

fun AndroidUpdateChannel.manifestUrl(): String {
    require(available) { "$name updates are not available yet." }
    return "https://github.com/Obiente/nc-native/releases/download/$pointerTag/update-manifest.json"
}

fun AndroidUpdateChannel.desktopManifestUrl(): String {
    require(available) { "$name updates are not available yet." }
    return "https://github.com/Obiente/nc-native/releases/download/" +
        "$pointerTag/desktop-update-manifest.json"
}

fun parseAndroidUpdateChannel(value: String?): AndroidUpdateChannel =
    AndroidUpdateChannel.entries
        .singleOrNull { channel ->
            channel.available &&
                (
                    channel.name == value ||
                        channel.storageValue == value ||
                        channel.manifestChannel == value
                    )
        }
        ?: AndroidUpdateChannel.Alpha

sealed interface AppUpdateCheckResult {
    data class Current(val support: AppUpdateSupport) : AppUpdateCheckResult
    data class Available(
        val support: AppUpdateSupport,
        val release: AppUpdateRelease,
    ) : AppUpdateCheckResult
    data class Unavailable(val support: AppUpdateSupport) : AppUpdateCheckResult
    data class Failed(val support: AppUpdateSupport, val message: String) : AppUpdateCheckResult
}

sealed interface AppUpdateInstallResult {
    data object ConfirmationOpened : AppUpdateInstallResult
    data class Cancelled(val canResume: Boolean) : AppUpdateInstallResult
    data class PermissionRequired(val message: String) : AppUpdateInstallResult
    data class Rejected(val message: String) : AppUpdateInstallResult
}

private val publicContentJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

fun parseProjectNewsFeed(bytes: ByteArray): ProjectNewsFeed {
    require(bytes.isNotEmpty() && bytes.size <= MAX_PROJECT_NEWS_FEED_BYTES)
    val feed = publicContentJson.decodeFromString<ProjectNewsFeed>(bytes.decodeToString())
    require(feed.schemaVersion == 1)
    require(feed.feedRevision.isSha256())
    require(feed.entries.size in 1..100)
    require(feed.entries.map(ProjectNewsArticle::id).distinct().size == feed.entries.size)
    feed.entries.forEach { article ->
        require(article.id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
        require(article.title.isBoundedPublicText(160))
        require(article.description.isBoundedPublicText(320))
        require(article.publishedDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        article.lastUpdated?.let { updated ->
            require(updated.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            require(updated >= article.publishedDate)
        }
        require(article.tags.size <= 12 && article.tags.all { it.isBoundedPublicText(48) })
        require(article.bodyMarkdown.isBoundedMarkdown(64 * 1024))
        require(
            article.webUrl == "https://nc-native.obiente.dev/news/${article.id}/",
        )
        require(article.contentSha256.isSha256())
        require(
            publicContentSha256(article.bodyMarkdown.encodeToByteArray()) ==
                article.contentSha256,
        )
        require(isCanonicalProjectNewsImageUrl(article.image.url))
        require(article.image.alt.isBoundedPublicText(240))
        require(article.image.width in 1..8_192 && article.image.height in 1..8_192)
        require(article.image.sha256.isSha256())
    }
    require(feed.entries.zipWithNext().all { (left, right) ->
        left.publishedDate >= right.publishedDate
    })
    require(feed.feedRevision == projectNewsFeedRevision(feed.entries))
    return feed
}

fun projectNewsFeedRevision(entries: List<ProjectNewsArticle>): String =
    publicContentSha256(
        buildString {
            append("project-news-revision-v1\n")
            entries.forEach { article ->
                appendRevisionField(article.id)
                appendRevisionField(article.title)
                appendRevisionField(article.description)
                appendRevisionField(article.publishedDate)
                appendRevisionField(article.lastUpdated.orEmpty())
                appendRevisionField(article.tags.size.toString())
                article.tags.forEach(::appendRevisionField)
                appendRevisionField(article.contentSha256)
                appendRevisionField(article.webUrl)
                appendRevisionField(article.image.url)
                appendRevisionField(article.image.alt)
                appendRevisionField(article.image.width.toString())
                appendRevisionField(article.image.height.toString())
                appendRevisionField(article.image.sha256)
            }
        }.encodeToByteArray(),
    )

private fun StringBuilder.appendRevisionField(value: String) {
    append(value.encodeToByteArray().size)
    append(':')
    append(value)
    append('\n')
}

fun parseAndroidDirectRelease(
    bytes: ByteArray,
    metadataUrl: String,
    expectedChannel: AndroidUpdateChannel,
): AndroidDirectRelease {
    require(bytes.isNotEmpty() && bytes.size <= MAX_ANDROID_UPDATE_METADATA_BYTES)
    require(isCanonicalAndroidUpdateManifestUrl(metadataUrl, expectedChannel))
    val release = publicContentJson.decodeFromString<AndroidDirectRelease>(bytes.decodeToString())
    return validateAndroidDirectRelease(release, expectedChannel, metadataUrl)
}

fun validateAndroidDirectRelease(
    release: AndroidDirectRelease,
    expectedChannel: AndroidUpdateChannel,
    metadataUrl: String = release.canonicalMetadataUrl(expectedChannel),
): AndroidDirectRelease {
    require(expectedChannel.available)
    require(release.schemaVersion == 1)
    require(release.channel == expectedChannel.manifestChannel)
    require(release.versionName.isBoundedPublicText(64) && release.versionCode > 0)
    require(release.packageName == "dev.obiente.nextcloudnative")
    require(release.minimumAndroidSdk in 26..64)
    require(release.apkSize in 1..MAX_ANDROID_UPDATE_APK_BYTES)
    require(release.apkSha256.isSha256())
    require(
        release.signingCertificateSha256Digests.isNotEmpty() &&
            release.signingCertificateSha256Digests.size <= 8 &&
            release.signingCertificateSha256Digests.distinct().size ==
            release.signingCertificateSha256Digests.size &&
            release.signingCertificateSha256Digests.all(String::isSha256),
    )
    val tag = release.releaseTag(expectedChannel)
    require(
        metadataUrl == expectedChannel.manifestUrl() ||
            metadataUrl ==
            "https://github.com/Obiente/nc-native/releases/download/$tag/update-manifest.json",
    )
    require(
        release.apkUrl.hasCanonicalPathUnder(
            "https://github.com/Obiente/nc-native/releases/download/$tag/",
            trailingSlash = false,
        ) && release.apkUrl.endsWith(".apk"),
    )
    require(
        release.releaseNotesUrl ==
            "https://github.com/Obiente/nc-native/releases/tag/$tag",
    )
    return release
}

fun isNewerAndroidRelease(currentVersionCode: Long, release: AndroidDirectRelease): Boolean =
    release.versionCode > currentVersionCode

fun isNewerAppRelease(currentVersionCode: Long, release: AppUpdateRelease): Boolean =
    release.versionCode > currentVersionCode

fun parseDesktopDirectRelease(
    bytes: ByteArray,
    metadataUrl: String,
    expectedChannel: AndroidUpdateChannel,
    platform: String,
    format: String,
    architecture: String,
): DesktopDirectRelease {
    require(bytes.isNotEmpty() && bytes.size <= MAX_DESKTOP_UPDATE_METADATA_BYTES)
    require(isCanonicalDesktopUpdateManifestUrl(metadataUrl, expectedChannel))
    val manifest = publicContentJson.decodeFromString<DesktopUpdateManifest>(bytes.decodeToString())
    validateDesktopUpdateManifest(manifest, expectedChannel, metadataUrl)
    val asset = manifest.assets.singleOrNull { candidate ->
        candidate.platform == platform &&
            candidate.format == format &&
            candidate.architecture == architecture
    } ?: error("No $format update is available for $platform $architecture.")
    return DesktopDirectRelease(
        versionName = manifest.versionName,
        versionCode = manifest.versionCode,
        packageVersion = manifest.packageVersion,
        asset = asset,
        releaseNotesUrl = manifest.releaseNotesUrl,
    )
}

fun validateDesktopUpdateManifest(
    manifest: DesktopUpdateManifest,
    expectedChannel: AndroidUpdateChannel,
    metadataUrl: String,
): DesktopUpdateManifest {
    require(expectedChannel.available)
    require(manifest.schemaVersion == 1)
    require(manifest.channel == expectedChannel.manifestChannel)
    require(manifest.versionName.isBoundedPublicText(64) && manifest.versionCode > 0)
    require(manifest.packageVersion.matches(Regex("[1-9][0-9]*\\.[0-9]+\\.[0-9]+")))
    require(manifest.assets.isNotEmpty() && manifest.assets.size <= 8)
    require(
        manifest.assets.map { asset -> Triple(asset.platform, asset.format, asset.architecture) }
            .distinct().size == manifest.assets.size,
    )
    val tag = releaseTag(expectedChannel, manifest.versionName)
    require(
        metadataUrl == expectedChannel.desktopManifestUrl() ||
            metadataUrl ==
            "https://github.com/Obiente/nc-native/releases/download/" +
            "$tag/desktop-update-manifest.json",
    )
    require(
        manifest.releaseNotesUrl ==
            "https://github.com/Obiente/nc-native/releases/tag/$tag",
    )
    manifest.assets.forEach { asset ->
        require(asset.platform in setOf("linux", "windows", "macos"))
        require(
            asset.format in when (asset.platform) {
                "linux" -> setOf("deb", "rpm")
                "windows" -> setOf("msi")
                "macos" -> setOf("dmg")
                else -> emptySet()
            },
        )
        require(asset.architecture in setOf("x86_64", "aarch64"))
        require(asset.size in 1..MAX_DESKTOP_UPDATE_PACKAGE_BYTES)
        require(asset.sha256.isSha256())
        require(
            asset.url.hasCanonicalPathUnder(
                "https://github.com/Obiente/nc-native/releases/download/$tag/",
                trailingSlash = false,
            ) && asset.url.endsWith(".${asset.format}"),
        )
    }
    return manifest
}

fun isCanonicalDesktopUpdateManifestUrl(
    url: String,
    channel: AndroidUpdateChannel,
): Boolean {
    if (!channel.available) return false
    if (url == channel.desktopManifestUrl()) return true
    val prefix = "https://github.com/Obiente/nc-native/releases/download/"
    if (!url.hasCanonicalPathUnder(prefix, trailingSlash = false)) return false
    val path = url.removePrefix(prefix).split('/')
    return path.size == 2 &&
        path[0].matches(channel.releaseTagPattern()) &&
        path[1] == "desktop-update-manifest.json"
}

fun isCanonicalAndroidPrereleaseManifestUrl(url: String): Boolean {
    return isCanonicalAndroidUpdateManifestUrl(url, AndroidUpdateChannel.Alpha)
}

fun isCanonicalAndroidUpdateManifestUrl(url: String): Boolean =
    AndroidUpdateChannel.entries.filter { it.available }.any { channel ->
        isCanonicalAndroidUpdateManifestUrl(url, channel)
    }

fun isCanonicalAndroidUpdateManifestUrl(
    url: String,
    channel: AndroidUpdateChannel,
): Boolean {
    if (!channel.available) return false
    if (url == channel.manifestUrl()) return true
    val prefix = "https://github.com/Obiente/nc-native/releases/download/"
    if (!url.hasCanonicalPathUnder(prefix, trailingSlash = false)) return false
    val path = url.removePrefix(prefix).split('/')
    return path.size == 2 &&
        path[0].matches(channel.releaseTagPattern()) &&
        path[1] == "update-manifest.json"
}

private fun AndroidUpdateChannel.releaseTagPattern(): Regex = when (this) {
    AndroidUpdateChannel.Alpha ->
        Regex("v0\\.[0-9]+\\.[0-9]+-(?:alpha|beta|rc)\\.[0-9]+")
    AndroidUpdateChannel.Nightly ->
        Regex("nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}")
    AndroidUpdateChannel.Beta,
    AndroidUpdateChannel.Stable,
    -> error("$name updates are not available yet.")
}

private fun AndroidDirectRelease.releaseTag(channel: AndroidUpdateChannel): String =
    releaseTag(channel, versionName)

private fun releaseTag(channel: AndroidUpdateChannel, versionName: String): String = when (channel) {
    AndroidUpdateChannel.Alpha -> {
        require(versionName.matches(Regex("0\\.[0-9]+\\.[0-9]+-(?:alpha|beta|rc)\\.[0-9]+")))
        "v$versionName"
    }
    AndroidUpdateChannel.Nightly -> {
        require(versionName.matches(channel.releaseTagPattern()))
        versionName
    }
    AndroidUpdateChannel.Beta,
    AndroidUpdateChannel.Stable,
    -> error("${channel.name} updates are not available yet.")
}

private fun AndroidDirectRelease.canonicalMetadataUrl(
    expectedChannel: AndroidUpdateChannel,
): String {
    require(expectedChannel.available)
    require(channel == expectedChannel.manifestChannel)
    return "https://github.com/Obiente/nc-native/releases/download/" +
        "${releaseTag(expectedChannel)}/update-manifest.json"
}

fun isCanonicalProjectNewsImageUrl(url: String): Boolean =
    url.hasCanonicalPathUnder("https://nc-native.obiente.dev/screenshots/", trailingSlash = false) &&
        url.endsWith(".png")

private fun String.hasCanonicalPathUnder(
    requiredPrefix: String,
    trailingSlash: Boolean,
): Boolean {
    if (!startsWith(requiredPrefix) || any { it == '%' || it == '?' || it == '#' || it == '\\' }) {
        return false
    }
    val relativePath = removePrefix(requiredPrefix)
    if (relativePath.isEmpty() || endsWith('/') != trailingSlash) return false
    val pathWithoutTrailingSlash = if (trailingSlash) relativePath.dropLast(1) else relativePath
    if (pathWithoutTrailingSlash.isEmpty()) return false
    return pathWithoutTrailingSlash
        .split('/')
        .all { segment ->
            segment != "." &&
                segment != ".." &&
                segment.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"))
        }
}

private fun String.isSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isBoundedPublicText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)

private fun String.isBoundedMarkdown(maxLength: Int): Boolean =
    isNotBlank() &&
        length <= maxLength &&
        none { character ->
            character.isISOControl() && character != '\n' && character != '\r' && character != '\t'
        }
