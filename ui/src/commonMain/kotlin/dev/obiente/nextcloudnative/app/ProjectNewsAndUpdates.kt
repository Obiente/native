package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val PROJECT_NEWS_FEED_URL = "https://nc-native.obiente.dev/news-feed-v1.json"
const val ANDROID_DIRECT_UPDATE_METADATA_URL =
    "https://nc-native.obiente.dev/releases/android/stable-v1.json"
const val MAX_PROJECT_NEWS_FEED_BYTES = 512 * 1024
const val MAX_ANDROID_UPDATE_METADATA_BYTES = 64 * 1024
const val MAX_ANDROID_UPDATE_APK_BYTES = 256L * 1024L * 1024L

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
)

data class ProjectNewsResult(
    val feed: ProjectNewsFeed,
    val cached: Boolean,
)

enum class AppDistributionChannel {
    DirectApk,
    GooglePlay,
    FDroid,
    OtherStore,
    Development,
    Unsupported,
}

data class AppUpdateSupport(
    val channel: AppDistributionChannel,
    val currentVersionName: String,
    val currentVersionCode: Long,
    val canCheckDirectUpdates: Boolean,
    val explanation: String,
)

@Serializable
data class AndroidDirectRelease(
    val schemaVersion: Int,
    val channel: String,
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val releaseNotesUrl: String,
)

sealed interface AppUpdateCheckResult {
    data class Current(val support: AppUpdateSupport) : AppUpdateCheckResult
    data class Available(
        val support: AppUpdateSupport,
        val release: AndroidDirectRelease,
    ) : AppUpdateCheckResult
    data class Unavailable(val support: AppUpdateSupport) : AppUpdateCheckResult
    data class Failed(val support: AppUpdateSupport, val message: String) : AppUpdateCheckResult
}

sealed interface AppUpdateInstallResult {
    data object ConfirmationOpened : AppUpdateInstallResult
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
        require(article.bodyMarkdown.isBoundedPublicText(64 * 1024))
        require(
            article.webUrl == "https://nc-native.obiente.dev/news/${article.id}/",
        )
        require(article.contentSha256.isSha256())
    }
    require(feed.entries.zipWithNext().all { (left, right) ->
        left.publishedDate >= right.publishedDate
    })
    return feed
}

fun parseAndroidDirectRelease(
    bytes: ByteArray,
    metadataUrl: String = ANDROID_DIRECT_UPDATE_METADATA_URL,
): AndroidDirectRelease {
    require(bytes.isNotEmpty() && bytes.size <= MAX_ANDROID_UPDATE_METADATA_BYTES)
    require(metadataUrl == ANDROID_DIRECT_UPDATE_METADATA_URL)
    val release = publicContentJson.decodeFromString<AndroidDirectRelease>(bytes.decodeToString())
    return validateAndroidDirectRelease(release)
}

fun validateAndroidDirectRelease(release: AndroidDirectRelease): AndroidDirectRelease {
    require(release.schemaVersion == 1 && release.channel == "direct")
    require(release.versionName.isBoundedPublicText(64) && release.versionCode > 0)
    require(release.apkSize in 1..MAX_ANDROID_UPDATE_APK_BYTES)
    require(release.apkSha256.isSha256() && release.signingCertificateSha256.isSha256())
    require(release.apkUrl.isCanonicalUpdateUrl())
    require(release.releaseNotesUrl.isCanonicalReleaseNotesUrl())
    return release
}

fun isNewerAndroidRelease(currentVersionCode: Long, release: AndroidDirectRelease): Boolean =
    release.versionCode > currentVersionCode

private fun String.isCanonicalUpdateUrl(): Boolean =
    startsWith("https://nc-native.obiente.dev/releases/android/") &&
        none { it == '#' || it == '?' || it == '\\' }

private fun String.isCanonicalReleaseNotesUrl(): Boolean =
    startsWith("https://nc-native.obiente.dev/releases/") &&
        endsWith('/') &&
        none { it == '#' || it == '?' || it == '\\' }

private fun String.isSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isBoundedPublicText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
