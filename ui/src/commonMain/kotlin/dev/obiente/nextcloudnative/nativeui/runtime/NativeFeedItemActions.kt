package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema

internal enum class NativeFeedItemActionKind(val label: String) {
    MarkRead("Mark read"),
    MarkUnread("Mark unread"),
    Star("Star"),
    Unstar("Unstar"),
}

internal data class NativeFeedItemActionPlan(
    val kind: NativeFeedItemActionKind,
    val action: ActionSpec,
    private val values: Map<String, String>,
) {
    fun request(): NativeActionRequest.Submit = NativeActionRequest.Submit(action, values, confirmed = true)

    /** The article state this change requests, applied optimistically until the server answers. */
    val requestedState: NativeFeedArticleState
        get() = when (kind) {
            NativeFeedItemActionKind.MarkRead -> NativeFeedArticleState(unread = false)
            NativeFeedItemActionKind.MarkUnread -> NativeFeedArticleState(unread = true)
            NativeFeedItemActionKind.Star -> NativeFeedArticleState(starred = true)
            NativeFeedItemActionKind.Unstar -> NativeFeedArticleState(starred = false)
        }
}

internal data class NativeFeedItemActions(
    val readState: NativeFeedItemActionPlan?,
    val starState: NativeFeedItemActionPlan?,
) {
    val all: List<NativeFeedItemActionPlan> get() = listOfNotNull(readState, starState)
}

/**
 * Plans the read and star changes that the contract describes for this article.
 *
 * Two shapes are accepted, both idempotent state changes: a body-free route whose last segment
 * names the state (`.../{id}/read`, `.../{id}/star`), or a write with one boolean state field
 * (`unread`, `starred`, ...). Either must take this article's identity as a required path
 * parameter: a route without one (`POST /items/read`) or bound to the article's feed or folder
 * changes many articles at once and is never offered. The article must carry a contract-declared
 * identity and every required input must be bound; otherwise the reader stays read-only.
 * Equal-ranked candidates are ambiguous and offer nothing.
 */
internal fun nativeFeedItemActions(
    schema: NativeAppSchema,
    article: NativeFeedArticle,
): NativeFeedItemActions {
    val binding = article.record.safeActionBindingValues(allowUnsafeIdentity = false)
        ?: return NativeFeedItemActions(null, null)
    // A state route nested directly under the article list path targets the same articles even
    // when a compiler names its resource after the trailing verb.
    val listPaths = schema.actions.filter { action ->
        action.resourceId == article.resource.id && action.intent == ActionIntent.list && action.binding.method == HttpMethod.GET
    }.map { it.binding.path.trimEnd('/') }
    val candidates = schema.actions.filter { action ->
        (action.resourceId.sameFeedResource(article.resource.id) || listPaths.any { list -> action.binding.path.isItemRouteUnder(list) }) &&
            action.risk == ActionRisk.mutating &&
            action.binding.method in FEED_STATE_METHODS &&
            action.confidence in setOf(Confidence.high, Confidence.verified) &&
            action.evidence.any { it.source in TRUSTED_FEED_EVIDENCE }
    }
    val readKind = when (article.unread) {
        true -> NativeFeedItemActionKind.MarkRead
        false -> NativeFeedItemActionKind.MarkUnread
        null -> null
    }
    val starKind = when (article.starred) {
        true -> NativeFeedItemActionKind.Unstar
        false -> NativeFeedItemActionKind.Star
        null -> null
    }
    fun planFor(kind: NativeFeedItemActionKind?): NativeFeedItemActionPlan? = kind?.let {
        candidates.filter { action -> action.binding.bindsArticleIdentity(article.resource.id, listPaths) }
            .mapNotNull { action -> action.toFeedStateCandidate(kind, binding, article.resource.id) }
            .singleHighestFeedPlan()
    }
    return NativeFeedItemActions(planFor(readKind), planFor(starKind))
}

private data class RankedFeedPlan(val plan: NativeFeedItemActionPlan, val rank: Int)

private fun ActionSpec.toFeedStateCandidate(
    kind: NativeFeedItemActionKind,
    binding: Map<String, String>,
    resourceId: String,
): RankedFeedPlan? {
    val terminal = this.binding.path.split('/')
        .lastOrNull { segment -> segment.isNotBlank() && !segment.startsWith('{') }
        ?.feedKey()
    if (terminal != null && terminal in PATH_VERBS.getValue(kind)) {
        if (this.binding.requiredBodyFieldNames.isNotEmpty()) return null
        if (!this.binding.canResolveFeedValues(binding.keys, resourceId)) return null
        return RankedFeedPlan(NativeFeedItemActionPlan(kind, this, binding), 600)
    }
    if (terminal != null && PATH_VERBS.values.any { terminal in it }) return null
    val (fields, inverted) = when (kind) {
        NativeFeedItemActionKind.MarkRead, NativeFeedItemActionKind.MarkUnread -> READ_STATE_FIELDS
        NativeFeedItemActionKind.Star, NativeFeedItemActionKind.Unstar -> STAR_STATE_FIELDS
    }
    val field = this.binding.bodyFieldNames.filter { it.feedKey() in fields || it.feedKey() in inverted }.singleOrNull()
        ?: return null
    if ((this.binding.pathParameterNames + this.binding.queryParameterNames).any { it.feedKey() == field.feedKey() }) return null
    // A broader update would resend every other observed field and could overwrite newer edits.
    if (this.binding.bodyFieldNames.any { it != field && !it.feedKey().endsWith("id") }) return null
    val desired = kind == NativeFeedItemActionKind.MarkUnread || kind == NativeFeedItemActionKind.Star
    val wire = if (field.feedKey() in inverted) !desired else desired
    if (!this.binding.canResolveFeedValues(binding.keys + field, resourceId)) return null
    return RankedFeedPlan(NativeFeedItemActionPlan(kind, this, binding + (field to wire.toString())), 400)
}

private fun ApiBinding.canResolveFeedValues(available: Set<String>, resourceId: String): Boolean {
    val normalized = available.mapTo(mutableSetOf()) { it.feedKey() }
    fun String.resolvable(): Boolean {
        val name = feedKey()
        if (name in normalized) return true
        if ("id" !in normalized || !name.endsWith("id")) return false
        return name.removeSuffix("id").sameFeedResource(resourceId)
    }
    return requiredPathParameterNames.all { it.resolvable() } &&
        requiredQueryParameterNames.all { it.resolvable() } &&
        requiredBodyFieldNames.all { it.resolvable() }
}

/**
 * True when a required path parameter names one article: the segment before it is the article
 * collection (its list path or its resource name), or the parameter is named after the article
 * resource (`itemId` for `items`).
 */
internal fun ApiBinding.bindsArticleIdentity(resourceId: String, listPaths: List<String>): Boolean {
    val segments = path.split('/').filter(String::isNotBlank)
    val collectionSegments = listPaths.mapNotNullTo(mutableSetOf()) { list ->
        list.split('/').lastOrNull(String::isNotBlank)?.feedKey()
    }
    return requiredPathParameterNames.any { name ->
        val key = name.feedKey()
        val namedForArticle = key.length > 2 && key.endsWith("id") && key.removeSuffix("id").sameFeedResource(resourceId)
        val index = segments.indexOf("{$name}")
        val previous = segments.getOrNull(index - 1)?.takeIf { index > 0 && !it.startsWith('{') }?.feedKey()
        val underCollection = previous != null && (previous in collectionSegments || previous.sameFeedResource(resourceId))
        index >= 0 && (namedForArticle || underCollection)
    }
}

private fun List<RankedFeedPlan>.singleHighestFeedPlan(): NativeFeedItemActionPlan? {
    val highest = maxOfOrNull(RankedFeedPlan::rank) ?: return null
    return filter { it.rank == highest }.singleOrNull()?.plan
}

private fun String.isItemRouteUnder(listPath: String): Boolean {
    if (!startsWith("$listPath/")) return false
    val rest = removePrefix("$listPath/").split('/')
    return rest.size == 2 && rest[0].startsWith('{') && rest[0].endsWith('}') && rest[1].isNotBlank() && !rest[1].startsWith('{')
}

private fun String.feedKey(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.sameFeedResource(other: String): Boolean {
    fun String.singular(): String = feedKey().let {
        when {
            it.endsWith("ies") && it.length > 3 -> it.dropLast(3) + "y"
            it.endsWith("ses") || it.endsWith("xes") -> it.dropLast(2)
            it.endsWith('s') && !it.endsWith("ss") && it.length > 1 -> it.dropLast(1)
            else -> it
        }
    }
    return singular() == other.singular()
}

private val FEED_STATE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
private val TRUSTED_FEED_EVIDENCE = setOf(
    EvidenceSource.openApi,
    EvidenceSource.verifiedAdapter,
    EvidenceSource.verifiedAppPackage,
    EvidenceSource.appStoreLinkedSourceTag,
)
private val PATH_VERBS = mapOf(
    NativeFeedItemActionKind.MarkRead to setOf("read", "markread", "seen", "markseen"),
    NativeFeedItemActionKind.MarkUnread to setOf("unread", "markunread", "unseen", "markunseen"),
    NativeFeedItemActionKind.Star to setOf("star", "starred", "favorite", "favourite"),
    NativeFeedItemActionKind.Unstar to setOf("unstar", "unfavorite", "unfavourite"),
)

/** Direct state field names, and names whose boolean is the inverse of the direct state. */
private val READ_STATE_FIELDS = setOf("unread", "isunread", "unseen") to setOf("read", "isread", "seen")
private val STAR_STATE_FIELDS = setOf("starred", "isstarred", "favorite", "favourite") to emptySet<String>()
