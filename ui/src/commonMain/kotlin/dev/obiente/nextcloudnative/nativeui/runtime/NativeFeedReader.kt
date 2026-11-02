package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FEED_FOLDER_WORDS
import dev.obiente.nextcloudnative.nativeui.model.FEED_ITEM_CONTENT_FIELDS
import dev.obiente.nextcloudnative.nativeui.model.FEED_ITEM_LINK_FIELDS
import dev.obiente.nextcloudnative.nativeui.model.FEED_ITEM_SOURCE_FIELDS
import dev.obiente.nextcloudnative.nativeui.model.FEED_ITEM_TITLE_FIELDS
import dev.obiente.nextcloudnative.nativeui.model.FEED_SOURCE_WORDS
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.model.feedSemanticId
import dev.obiente.nextcloudnative.nativeui.model.feedWords
import dev.obiente.nextcloudnative.nativeui.model.hasFeedItemFieldShape

/** One article, read from field roles only. */
internal data class NativeFeedArticle(
    val resource: ResourceSpec,
    val record: NativeRecord,
    val title: String,
    /** HTTP(S) link to the original article, or null. Opening it is an explicit user handoff. */
    val link: String?,
    val author: String?,
    val body: String?,
    val html: Boolean,
    /** Null when the record has no read-state field; the reader then shows no read state. */
    val unread: Boolean?,
    val starred: Boolean?,
    /** Value of the article's feed reference, compared only with loaded feed identities. */
    val sourceId: String?,
    val publishedLabel: String?,
) {
    val key: String get() = "${resource.id}\u0000${record.id}"
}

/** A subscribed feed or a folder of feeds. */
internal data class NativeFeedSource(
    val resource: ResourceSpec,
    val record: NativeRecord,
    val title: String,
    val unreadCount: Int?,
    val folderId: String?,
    val folder: Boolean,
)

internal enum class NativeFeedFilter(val label: String) {
    Unread("Unread"),
    Starred("Starred"),
    All("All articles"),
}

internal data class NativeFeedReaderPlan(
    val articles: List<NativeFeedArticle>,
    val feeds: List<NativeFeedSource>,
    val folders: List<NativeFeedSource>,
    val selectedArticle: NativeFeedArticle?,
) {
    val hasReadState: Boolean get() = articles.any { it.unread != null }
    val hasStarredState: Boolean get() = articles.any { it.starred != null }

    val filters: List<NativeFeedFilter>
        get() = listOfNotNull(
            NativeFeedFilter.Unread.takeIf { hasReadState },
            NativeFeedFilter.Starred.takeIf { hasStarredState },
            NativeFeedFilter.All,
        )

    fun feedTitle(article: NativeFeedArticle): String? =
        article.sourceId?.let { id -> feeds.firstOrNull { it.record.id == id }?.title }

    /**
     * Articles shown for a filter and an optional feed. Filters work on the loaded pages only; the
     * reader says so instead of presenting them as server-wide results.
     */
    fun visible(
        filter: NativeFeedFilter,
        feedId: String?,
        overrides: Map<String, NativeFeedArticleState> = emptyMap(),
    ): List<NativeFeedArticle> = articles.map { it.withOverride(overrides[it.key]) }.filter { article ->
        (feedId == null || article.sourceId == feedId) &&
            when (filter) {
                NativeFeedFilter.Unread -> article.unread == true
                NativeFeedFilter.Starred -> article.starred == true
                NativeFeedFilter.All -> true
            }
    }
}

/** Optimistic local state for an article while a contract-described change is pending. */
internal data class NativeFeedArticleState(
    val unread: Boolean? = null,
    val starred: Boolean? = null,
)

internal fun NativeFeedArticle.withOverride(state: NativeFeedArticleState?): NativeFeedArticle =
    if (state == null) this else copy(unread = state.unread ?: unread, starred = state.starred ?: starred)

/**
 * Plans the feed reader for a collection. The view must be a feed reader, or its records must look
 * like articles; most records must be readable articles, otherwise the ordinary collection stays.
 */
internal fun nativeFeedReaderPlan(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    context: NativeDatasetContext,
    selectedRecordId: String? = null,
): NativeFeedReaderPlan? {
    if (records.isEmpty()) return null
    val declared = view.component == NativeComponent.feedReader
    if (!declared) {
        val observedFields = records.take(SHAPE_SAMPLE).flatMap { record ->
            record.values.keys + record.displayValues.keys
        }.mapTo(mutableSetOf(), String::feedSemanticId)
        if (!hasFeedItemFieldShape(observedFields)) return null
    }
    val articles = records.mapNotNull { record -> nativeFeedArticle(resource, record) }
    if (articles.size * 2 < records.size) return null
    val sources = context.relatedRecords.flatMap { (resourceId, related) ->
        if (resourceId == resource.id) return@flatMap emptyList()
        val sourceResource = schema.resource(resourceId) ?: return@flatMap emptyList()
        val words = "${sourceResource.id} ${sourceResource.name}".feedWords()
        val folder = words.any { it in FEED_FOLDER_WORDS }
        if (!folder && words.none { it in FEED_SOURCE_WORDS }) return@flatMap emptyList()
        related.take(MAX_FEED_SOURCES).mapNotNull { record -> nativeFeedSource(sourceResource, record, folder) }
    }
    return NativeFeedReaderPlan(
        articles = articles,
        feeds = sources.filterNot(NativeFeedSource::folder),
        folders = sources.filter(NativeFeedSource::folder),
        selectedArticle = selectedRecordId?.let { id -> articles.firstOrNull { it.record.id == id } },
    )
}

internal fun nativeFeedArticle(resource: ResourceSpec, record: NativeRecord): NativeFeedArticle? {
    val values = record.feedValues()
    val title = values.first(FEED_ITEM_TITLE_FIELDS)?.trim()?.takeIf(String::isNotBlank) ?: return null
    val link = values.first(FEED_ITEM_LINK_FIELDS)?.trim()?.takeIf(String::isSafeFeedHttpLink)
    val body = values.first(FEED_ITEM_CONTENT_FIELDS)?.takeIf(String::isNotBlank)?.take(MAX_ARTICLE_BODY)
    if (link == null && body == null) return null
    return NativeFeedArticle(
        resource = resource,
        record = record,
        title = title.take(MAX_TITLE),
        link = link,
        author = values.first(setOf("author", "authorname", "creator", "byline"))?.trim()?.takeIf(String::isNotBlank)?.take(MAX_TITLE),
        body = body,
        html = body?.let { it.contains('<') && it.contains('>') } == true,
        unread = values.boolean(setOf("unread", "isunread", "unseen"))
            ?: values.boolean(setOf("read", "isread", "seen"))?.not(),
        starred = values.boolean(setOf("starred", "isstarred", "favorite", "favourite", "saved", "bookmarked")),
        sourceId = values.first(FEED_ITEM_SOURCE_FIELDS)?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_ID },
        publishedLabel = nativeFeedPublishedLabel(resource, record),
    )
}

/**
 * The article's publication time from the first date-role field that can be shown: an ISO date
 * string, or an integer that the contract declares as epoch seconds. Integers of an undeclared
 * unit are not shown, because their unit cannot be told from the number.
 */
private fun nativeFeedPublishedLabel(resource: ResourceSpec, record: NativeRecord): String? {
    val entries = record.values.mapNotNull { (key, value) -> value?.let { key to it } } + record.displayValues.toList()
    return PUBLISHED_FIELDS.firstNotNullOfOrNull { role ->
        val (key, raw) = entries.firstOrNull { (key, _) -> key.feedSemanticId() == role } ?: return@firstNotNullOfOrNull null
        val value = raw.trim()
        if (ISO_DATE.matches(value)) return@firstNotNullOfOrNull value.take(16).replace('T', ' ')
        val field = resource.fields.firstOrNull { it.id == key } ?: resource.fields.firstOrNull { it.id.feedSemanticId() == role }
        field?.formatDeclaredEpochSeconds(value)
    }
}

private fun nativeFeedSource(resource: ResourceSpec, record: NativeRecord, folder: Boolean): NativeFeedSource? {
    val values = record.feedValues()
    val title = values.first(setOf("title", "name", "displayname", "label"))?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    return NativeFeedSource(
        resource = resource,
        record = record,
        title = title.take(MAX_TITLE),
        unreadCount = values.first(setOf("unreadcount", "unread", "unreaditems", "unseen"))?.trim()?.toIntOrNull()
            ?.takeIf { it >= 0 },
        folderId = values.first(setOf("folderid", "categoryid", "parentid"))?.takeIf(String::isNotBlank),
        folder = folder,
    )
}

private class FeedValues(private val byKey: Map<String, String>) {
    fun first(keys: Set<String>): String? = keys.firstNotNullOfOrNull { key -> byKey[key] }

    fun boolean(keys: Set<String>): Boolean? = first(keys)?.trim()?.lowercase()?.let { raw ->
        when (raw) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
}

private fun NativeRecord.feedValues(): FeedValues {
    val record = this
    return FeedValues(
        buildMap {
            record.values.forEach { (key, value) -> value?.let { put(key.feedSemanticId(), it) } }
            record.displayValues.forEach { (key, value) -> putIfAbsent(key.feedSemanticId(), value) }
        },
    )
}

private fun String.isSafeFeedHttpLink(): Boolean =
    length <= MAX_LINK && (startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)) &&
        none { it.isWhitespace() || it.isISOControl() }

private val PUBLISHED_FIELDS = listOf("published", "publishedat", "pubdate", "date", "updated", "updatedat")
private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}.*)?$")
private const val SHAPE_SAMPLE = 16
private const val MAX_FEED_SOURCES = 500
private const val MAX_TITLE = 500
private const val MAX_ID = 128
private const val MAX_LINK = 2_048
private const val MAX_ARTICLE_BODY = 256_000
