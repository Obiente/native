package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.rules.booleanStateBodyField

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
    val rulePlan: NativePatternRulePlan? = null,
) {
    fun request(): NativeActionRequest.Submit = NativeActionRequest.Submit(action, values, confirmed = true, rulePlan = rulePlan)

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

/** Plans only registered rules, using the actual load and separate verification-read bindings. */
internal fun nativeFeedItemActions(
    schema: NativeAppSchema,
    article: NativeFeedArticle,
    authority: NativeRuleAuthority? = null,
): NativeFeedItemActions {
    if (authority == null) return NativeFeedItemActions(null, null)
    fun planFor(kind: NativeFeedItemActionKind?): NativeFeedItemActionPlan? {
        if (kind == null) return null
        val (fields, inverted) = when (kind) {
            NativeFeedItemActionKind.MarkRead, NativeFeedItemActionKind.MarkUnread -> READ_STATE_FIELDS
            NativeFeedItemActionKind.Star, NativeFeedItemActionKind.Unstar -> STAR_STATE_FIELDS
        }
        val desired = kind == NativeFeedItemActionKind.MarkUnread || kind == NativeFeedItemActionKind.Star
        val plans = (schema.actions + schema.dedicatedActions).mapNotNull { action ->
            val inputs = action.booleanStateBodyField()?.let { field ->
                mapOf(field to (if (field.feedKey() in inverted) !desired else desired).toString())
            }.orEmpty()
            val plan = planNativePatternRule(schema, action.id, article.record.id, authority, inputs) ?: return@mapNotNull null
            val normalized = plan.stateField.feedKey()
            if (normalized !in fields && normalized !in inverted) return@mapNotNull null
            if (plan.expected != if (normalized in inverted) !desired else desired) return@mapNotNull null
            NativeFeedItemActionPlan(kind, action, plan.writeBindings, plan)
        }
        // A command pair is more specific than a general boolean update; equal candidates refuse.
        val pairs = plans.filter { it.rulePlan?.ruleId == "item-state-pair" }
        return (pairs.ifEmpty { plans }).singleOrNull()
    }
    return NativeFeedItemActions(
        planFor(article.unread?.let { if (it) NativeFeedItemActionKind.MarkRead else NativeFeedItemActionKind.MarkUnread }),
        planFor(article.starred?.let { if (it) NativeFeedItemActionKind.Unstar else NativeFeedItemActionKind.Star }),
    )
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

/** Direct state field names, and names whose boolean is the inverse of the direct state. */
private val READ_STATE_FIELDS = setOf("unread", "isunread", "unseen") to setOf("read", "isread", "seen")
private val STAR_STATE_FIELDS = setOf("starred", "isstarred", "favorite", "favourite") to emptySet<String>()
