package dev.obiente.nextcloudnative.nativeui.model

/*
 * Feed reader semantics. Any app whose contract exposes subscribed sources and the articles they
 * publish gets the feed reader, whatever its identifier or version. The rules use resource names,
 * field names, and declared reads only.
 */

/** Whether [resource] is the article collection of a feed reader. */
internal fun DynamicAppDescriptor.isFeedItemCollection(resource: DynamicResource): Boolean {
    if (!resource.collection) return false
    val fieldIds = resource.fields.mapTo(mutableSetOf()) { it.id.feedSemanticId() }
    if (hasFeedItemFieldShape(fieldIds)) return true
    // Sparse contracts, such as signed route tables, describe reads without response fields. The
    // wording of the resource and a sibling feed collection then identify the article list; its
    // records are checked again at render time.
    return fieldIds.isEmpty() &&
        resource.feedWords().any { it in FEED_ITEM_WORDS } &&
        resources.any { other -> other.id != resource.id && other.collection && other.feedWords().any { it in FEED_SOURCE_WORDS } }
}

/** Root ordering bonus: a feed reader opens on its articles rather than its subscription list. */
internal fun DynamicAppDescriptor.feedReaderRootBonus(resourceId: String): Int =
    resources.firstOrNull { it.id == resourceId }
        ?.takeIf(::isFeedItemCollection)
        ?.let { FEED_READER_ROOT_BONUS }
        ?: 0

/**
 * Field shape of an article: a title, a link, content, and at least one publication marker or a
 * reference to its feed. A collection that only has a title and a URL is a bookmark list, not a
 * feed, and stays an ordinary collection.
 */
internal fun hasFeedItemFieldShape(normalizedFieldIds: Set<String>): Boolean =
    normalizedFieldIds.any { it in FEED_ITEM_TITLE_FIELDS } &&
        normalizedFieldIds.any { it in FEED_ITEM_LINK_FIELDS } &&
        normalizedFieldIds.any { it in FEED_ITEM_CONTENT_FIELDS } &&
        normalizedFieldIds.any { it in FEED_ITEM_SOURCE_FIELDS || it in FEED_ITEM_PUBLICATION_FIELDS }

/** Whether a resource with these words and fields is a subscribed source or a folder of sources. */
internal fun isFeedSourceShape(words: Set<String>, normalizedFieldIds: Set<String>): Boolean =
    words.any { it in FEED_SOURCE_WORDS } &&
        (normalizedFieldIds.isEmpty() || normalizedFieldIds.any { it in FEED_SOURCE_FIELDS })

/**
 * Views that list a feed reader's sources, so the reader can show them beside the articles. Only
 * parameter-free, verified, read-only collection reads qualify; nothing is loaded that the user
 * could not open from the app's own navigation.
 */
fun NativeAppSchema.feedReaderSourceViews(view: ViewSpec): List<Pair<ViewSpec, ActionSpec>> {
    if (view.component != NativeComponent.feedReader) return emptyList()
    return views.mapNotNull { candidate ->
        if (candidate.resourceId == view.resourceId || candidate.component == NativeComponent.form) return@mapNotNull null
        val resource = resource(candidate.resourceId) ?: return@mapNotNull null
        val words = "${resource.id} ${resource.name}".feedWords()
        if (words.none { it in FEED_SOURCE_WORDS || it in FEED_FOLDER_WORDS }) return@mapNotNull null
        val action = action(candidate.sourceActionId) ?: return@mapNotNull null
        action.takeIf {
            it.resourceId == resource.id &&
                it.intent == ActionIntent.list &&
                it.risk == ActionRisk.readOnly &&
                it.binding.method == HttpMethod.GET &&
                it.confidence in setOf(Confidence.high, Confidence.verified) &&
                it.binding.requiredPathParameterNames.isEmpty() &&
                it.binding.requiredQueryParameterNames.isEmpty()
        }?.let { candidate to it }
    }.distinctBy { (candidate, _) -> candidate.resourceId }.take(MAX_FEED_SOURCE_VIEWS)
}

internal fun String.feedWords(): Set<String> = lowercase()
    .split(Regex("[^a-z0-9]+"))
    .filter(String::isNotBlank)
    .toSet()

private fun DynamicResource.feedWords(): Set<String> = "$id $label".feedWords()

internal fun String.feedSemanticId(): String = lowercase().filter(Char::isLetterOrDigit)

internal val FEED_SOURCE_WORDS = setOf("feed", "feeds", "subscription", "subscriptions", "rss", "atom")
internal val FEED_FOLDER_WORDS = setOf("folder", "folders", "category", "categories")
internal val FEED_ITEM_WORDS = setOf("item", "items", "article", "articles", "entry", "entries")
internal val FEED_ITEM_TITLE_FIELDS = setOf("title", "headline")
internal val FEED_ITEM_LINK_FIELDS = setOf("url", "link", "permalink", "articleurl")
internal val FEED_ITEM_CONTENT_FIELDS = setOf("body", "content", "contenthtml", "summary", "description", "excerpt")
internal val FEED_ITEM_SOURCE_FIELDS = setOf("feedid", "feed", "subscriptionid", "channelid")
internal val FEED_ITEM_PUBLICATION_FIELDS = setOf(
    "guid", "guidhash", "pubdate", "published", "publishedat", "enclosurelink", "enclosureurl",
)
private val FEED_SOURCE_FIELDS = setOf("url", "link", "feedurl", "unreadcount", "faviconlink", "favicon", "folderid", "title", "name")
private const val FEED_READER_ROOT_BONUS = 5_000
private const val MAX_FEED_SOURCE_VIEWS = 2
