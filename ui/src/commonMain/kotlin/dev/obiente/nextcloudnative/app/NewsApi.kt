package dev.obiente.nextcloudnative.app

import com.fleeksoft.ksoup.Ksoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Reviewed adapter for the Nextcloud News external API v1-3.
 *
 * Protocol reference: https://nextcloud.github.io/news/api/api-v1-3/
 * The routes are declared in the signed package's appinfo/routes.php; the client checks the API
 * version index for v1-3 instead of assuming an app version. Only the idempotent per-article
 * state changes (read, unread, star, unstar) are exposed as writes; feed, folder, filter and bulk
 * mark-all mutations stay in the News web interface.
 */

internal const val NEWS_APP_ID = "news"
internal const val NEWS_API_ROOT_PATH = "/index.php/apps/news/api"
internal const val NEWS_API_V1_3_PATH = "$NEWS_API_ROOT_PATH/v1-3"
internal const val NEWS_ITEM_PAGE_SIZE = 40

internal data class NewsFolder(
    val id: Long,
    val name: String,
)

internal data class NewsFeed(
    val id: Long,
    val title: String,
    val url: String,
    val link: String?,
    val faviconLink: String?,
    val folderId: Long?,
    val unreadCount: Int,
    val pinned: Boolean,
    val updateErrorCount: Int,
    val lastUpdateError: String?,
)

internal data class NewsSubscriptions(
    val folders: List<NewsFolder>,
    val feeds: List<NewsFeed>,
    val starredCount: Int,
) {
    val totalUnread: Int get() = feeds.sumOf(NewsFeed::unreadCount)

    fun feed(id: Long): NewsFeed? = feeds.firstOrNull { it.id == id }

    fun folder(id: Long): NewsFolder? = folders.firstOrNull { it.id == id }

    fun feedsInFolder(folderId: Long?): List<NewsFeed> = feeds.filter { it.folderId == folderId }

    fun unreadInFolder(folderId: Long): Int = feedsInFolder(folderId).sumOf(NewsFeed::unreadCount)
}

internal data class NewsItem(
    val id: Long,
    val feedId: Long,
    val title: String,
    val author: String?,
    val url: String?,
    val publishedAtEpochSeconds: Long?,
    val bodyHtml: String,
    /** Plain-text preview derived once from the untrusted body, off the UI thread. */
    val excerpt: String,
    val unread: Boolean,
    val starred: Boolean,
    val enclosureLink: String?,
    val enclosureMime: String?,
    val rtl: Boolean,
)

/** What the article list shows. Each selection maps to one documented `type`/`id` pair. */
internal sealed interface NewsSelection {
    data object Unread : NewsSelection
    data object All : NewsSelection
    data object Starred : NewsSelection
    data class Folder(val folderId: Long) : NewsSelection
    data class Feed(val feedId: Long) : NewsSelection
}

internal enum class NewsItemStateChange(val pathSegment: String) {
    Read("read"),
    Unread("unread"),
    Star("star"),
    Unstar("unstar"),
}

internal fun NewsItem.applying(change: NewsItemStateChange): NewsItem = when (change) {
    NewsItemStateChange.Read -> copy(unread = false)
    NewsItemStateChange.Unread -> copy(unread = true)
    NewsItemStateChange.Star -> copy(starred = true)
    NewsItemStateChange.Unstar -> copy(starred = false)
}

internal fun newsApiLevelsRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = NEWS_API_ROOT_PATH,
    maximumResponseBytes = MAX_NEWS_SMALL_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
)

internal fun newsFoldersRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "$NEWS_API_V1_3_PATH/folders",
    maximumResponseBytes = MAX_NEWS_SUBSCRIPTION_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
)

internal fun newsFeedsRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "$NEWS_API_V1_3_PATH/feeds",
    maximumResponseBytes = MAX_NEWS_SUBSCRIPTION_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
)

/**
 * Reads one page of articles, newest first. News pages by item ID: `offset` is the smallest ID
 * already shown, and the server returns items with a lower ID.
 */
internal fun newsItemsRequest(
    selection: NewsSelection,
    olderThanItemId: Long? = null,
    batchSize: Int = NEWS_ITEM_PAGE_SIZE,
): NextcloudApiRequest {
    require(batchSize in 1..MAX_NEWS_ITEM_PAGE_SIZE) { "The News page size is invalid." }
    require(olderThanItemId == null || olderThanItemId > 0L) { "The News page cursor is invalid." }
    val (type, id) = when (selection) {
        is NewsSelection.Feed -> NEWS_TYPE_FEED to selection.feedId
        is NewsSelection.Folder -> NEWS_TYPE_FOLDER to selection.folderId
        NewsSelection.Starred -> NEWS_TYPE_STARRED to 0L
        NewsSelection.Unread, NewsSelection.All -> NEWS_TYPE_ALL to 0L
    }
    require(id >= 0L) { "The News selection is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "$NEWS_API_V1_3_PATH/items",
        queryParameters = buildMap {
            put("batchSize", batchSize.toString())
            put("offset", (olderThanItemId ?: 0L).toString())
            put("type", type.toString())
            put("id", id.toString())
            put("getRead", (selection != NewsSelection.Unread).toString())
            put("oldestFirst", "false")
        },
        maximumResponseBytes = MAX_NEWS_ITEMS_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
    )
}

internal fun newsItemStateRequest(itemId: Long, change: NewsItemStateChange): NextcloudApiRequest {
    require(itemId > 0L) { "The News article ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "$NEWS_API_V1_3_PATH/items/$itemId/${change.pathSegment}",
        maximumResponseBytes = MAX_NEWS_SMALL_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )
}

internal fun parseNewsApiLevels(body: String): Set<String> {
    val levels = newsJsonObject(body)["apiLevels"] as? JsonArray
        ?: throw NewsProtocolException("The News API did not list its supported versions.")
    return levels.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .filter { it.length <= 16 }
        .toSet()
}

internal fun parseNewsFolders(body: String): List<NewsFolder> =
    newsArray(newsJsonObject(body), "folders").mapNotNull { element ->
        val folder = element as? JsonObject ?: return@mapNotNull null
        val id = folder.positiveLong("id") ?: return@mapNotNull null
        NewsFolder(id = id, name = folder.cleanText("name") ?: "Folder $id")
    }.distinctBy(NewsFolder::id)

internal fun parseNewsFeeds(body: String): Pair<List<NewsFeed>, Int> {
    val root = newsJsonObject(body)
    val feeds = newsArray(root, "feeds").mapNotNull { element ->
        val feed = element as? JsonObject ?: return@mapNotNull null
        val id = feed.positiveLong("id") ?: return@mapNotNull null
        val url = feed.cleanText("url") ?: return@mapNotNull null
        NewsFeed(
            id = id,
            title = feed.cleanText("title") ?: url.feedHostLabel(),
            url = url,
            link = feed.cleanText("link")?.takeIf(String::isHttpUrl),
            faviconLink = feed.cleanText("faviconLink")?.takeIf(String::isHttpUrl),
            folderId = feed.positiveLong("folderId"),
            unreadCount = feed.nonNegativeInt("unreadCount"),
            pinned = feed.boolean("pinned"),
            updateErrorCount = feed.nonNegativeInt("updateErrorCount"),
            lastUpdateError = feed.cleanText("lastUpdateError")?.take(MAX_NEWS_ERROR_CHARACTERS),
        )
    }.distinctBy(NewsFeed::id)
    return feeds to root.nonNegativeInt("starredCount")
}

internal fun parseNewsItems(body: String): List<NewsItem> =
    newsArray(newsJsonObject(body), "items").mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.positiveLong("id") ?: return@mapNotNull null
        val feedId = item.positiveLong("feedId") ?: return@mapNotNull null
        val bodyHtml = (item["body"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(MAX_NEWS_BODY_CHARACTERS)
        NewsItem(
            id = id,
            feedId = feedId,
            title = item.cleanText("title") ?: "Untitled article",
            author = item.cleanText("author")?.take(MAX_NEWS_AUTHOR_CHARACTERS),
            url = item.cleanText("url")?.takeIf(String::isHttpUrl),
            publishedAtEpochSeconds = item.positiveLong("pubDate")?.takeIf { it <= MAX_EPOCH_SECONDS },
            bodyHtml = bodyHtml,
            excerpt = newsExcerpt(bodyHtml),
            unread = item.boolean("unread"),
            starred = item.boolean("starred"),
            enclosureLink = item.cleanText("enclosureLink")?.takeIf(String::isHttpUrl),
            enclosureMime = item.cleanText("enclosureMime")?.take(MAX_NEWS_MIME_CHARACTERS),
            rtl = item.boolean("rtl"),
        )
    }.distinctBy(NewsItem::id)

internal fun newsExcerpt(bodyHtml: String): String {
    if (bodyHtml.isBlank()) return ""
    val text = Ksoup.parse(bodyHtml.take(MAX_NEWS_EXCERPT_SOURCE_CHARACTERS)).text()
    return normalizeNewsText(text)?.take(MAX_NEWS_EXCERPT_CHARACTERS).orEmpty()
}

internal class NewsProtocolException(message: String) : Exception(message)

private fun newsJsonObject(body: String): JsonObject {
    val element = try {
        newsJson.parseToJsonElement(body)
    } catch (_: IllegalArgumentException) {
        throw NewsProtocolException("The News server returned an unreadable response.")
    }
    return element as? JsonObject ?: throw NewsProtocolException("The News server returned an unexpected response.")
}

private fun newsArray(root: JsonObject, name: String): JsonArray = when (val value = root[name]) {
    is JsonArray -> value
    null, JsonNull -> JsonArray(emptyList())
    else -> throw NewsProtocolException("The News server returned an unexpected $name list.")
}

private fun JsonObject.primitive(name: String): JsonPrimitive? = (get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.positiveLong(name: String): Long? = primitive(name)?.longOrNull?.takeIf { it > 0L }

private fun JsonObject.nonNegativeInt(name: String): Int =
    primitive(name)?.longOrNull?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0

private fun JsonObject.boolean(name: String): Boolean = primitive(name)?.booleanOrNull ?: false

/** Feed text is untrusted: collapse whitespace and control characters, and treat blanks as missing. */
private fun JsonObject.cleanText(name: String): String? = primitive(name)
    ?.takeIf(JsonPrimitive::isString)
    ?.content
    ?.let(::normalizeNewsText)

internal fun normalizeNewsText(value: String): String? = value
    .take(MAX_NEWS_TEXT_CHARACTERS)
    .map { character -> if (character.isISOControl() || character.isWhitespace()) ' ' else character }
    .joinToString("")
    .replace(WHITESPACE_RUN, " ")
    .trim()
    .takeIf(String::isNotEmpty)

private fun String.isHttpUrl(): Boolean =
    (startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)) &&
        length <= MAX_NEWS_URL_CHARACTERS && none(Char::isWhitespace)

private fun String.feedHostLabel(): String =
    substringAfter("://").substringBefore('/').substringBefore('?').ifBlank { "Feed" }

private val newsJson = Json { ignoreUnknownKeys = true }
private val WHITESPACE_RUN = Regex("  +")

private const val NEWS_TYPE_FEED = 0
private const val NEWS_TYPE_FOLDER = 1
private const val NEWS_TYPE_STARRED = 2
private const val NEWS_TYPE_ALL = 3
private const val MAX_NEWS_ITEM_PAGE_SIZE = 200
private const val MAX_NEWS_SMALL_RESPONSE_BYTES = 64L * 1024L
private const val MAX_NEWS_SUBSCRIPTION_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val MAX_NEWS_ITEMS_RESPONSE_BYTES = 8L * 1024L * 1024L
private const val MAX_NEWS_TEXT_CHARACTERS = 2_048
private const val MAX_NEWS_URL_CHARACTERS = 2_048
private const val MAX_NEWS_AUTHOR_CHARACTERS = 256
private const val MAX_NEWS_ERROR_CHARACTERS = 512
private const val MAX_NEWS_MIME_CHARACTERS = 128
private const val MAX_NEWS_BODY_CHARACTERS = 512_000
private const val MAX_NEWS_EXCERPT_SOURCE_CHARACTERS = 16_000
private const val MAX_NEWS_EXCERPT_CHARACTERS = 280
private const val MAX_EPOCH_SECONDS = 253_402_300_799L
