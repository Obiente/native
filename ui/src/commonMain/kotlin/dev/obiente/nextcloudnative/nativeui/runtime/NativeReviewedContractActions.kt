package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Selection helpers for reviewed write adapters.
 *
 * A reviewed adapter may explain what a contract-declared payload means (for example that the
 * keys of a Tables `data` object are column IDs). It may never add an operation: every action it
 * uses must already be present in the verified schema, with trusted provenance, the exact method,
 * the exact route shape, and the body fields the adapter relies on. When any of that is missing
 * the adapter returns nothing and the screen stays read-only.
 */
internal data class NativeReviewedAction(
    val action: ActionSpec,
    /** Path placeholder names in route order, so callers bind identities by position, not by name. */
    val placeholders: List<String>,
)

/** One literal route segment, or `null` for a path placeholder. */
internal fun NativeAppSchema.reviewedAction(
    method: HttpMethod,
    route: List<String?>,
    requiredBodyFields: Set<String> = emptySet(),
): NativeReviewedAction? {
    val matches = actions.mapNotNull { action ->
        if (action.binding.method != method || !action.hasTrustedWriteEvidence()) return@mapNotNull null
        if (method != HttpMethod.GET && action.risk == ActionRisk.readOnly) return@mapNotNull null
        if (!action.binding.bodyFieldNames.containsAll(requiredBodyFields)) return@mapNotNull null
        val placeholders = action.binding.path.reviewedRoutePlaceholders(route) ?: return@mapNotNull null
        if (action.binding.requiredPathParameterNames.toSet() != placeholders.toSet()) return@mapNotNull null
        NativeReviewedAction(action, placeholders)
    }
    // Two actions for one reviewed route is ambiguous; do not choose between them.
    return matches.singleOrNull()
}

internal fun ActionSpec.hasTrustedWriteEvidence(): Boolean =
    confidence in setOf(Confidence.high, Confidence.verified) &&
        evidence.any { evidence -> evidence.source in TRUSTED_WRITE_EVIDENCE }

private val TRUSTED_WRITE_EVIDENCE = setOf(
    EvidenceSource.openApi,
    EvidenceSource.verifiedAppPackage,
    EvidenceSource.appStoreLinkedSourceTag,
    EvidenceSource.verifiedAdapter,
)

internal fun String.reviewedRoutePlaceholders(route: List<String?>): List<String>? {
    val segments = substringBefore('?').split('/').filter(String::isNotEmpty)
    if (segments.size != route.size) return null
    val placeholders = mutableListOf<String>()
    segments.zip(route).forEach { (segment, expected) ->
        if (expected == null) {
            if (!segment.startsWith('{') || !segment.endsWith('}') || segment.length < 3) return null
            placeholders += segment.substring(1, segment.length - 1)
        } else if (segment != expected) {
            return null
        }
    }
    return placeholders.takeIf { it.distinct().size == it.size }
}

/** Splits `/a/b/c` into literal segments for [reviewedAction]; `{}` marks a placeholder. */
internal fun reviewedRoute(path: String): List<String?> =
    path.split('/').filter(String::isNotEmpty).map { segment -> segment.takeUnless { it == "{}" } }

/** The installed major and minor version, or null when the version is not a plain release. */
internal fun AppIdentity.reviewedMajorMinor(): Pair<Int, Int>? {
    val parts = version.trim().split('.')
    if (parts.size < 2) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].takeWhile(Char::isDigit).toIntOrNull() ?: return null
    return major to minor
}

/** A positive integer identity that is safe to place in a path or a JSON key. */
internal fun String?.reviewedPositiveId(): Long? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.length <= 18 && it.all(Char::isDigit) }
        ?.toLongOrNull()?.takeIf { it > 0 }

/** Parses a bounded JSON value, also unwrapping a JSON document that was sent as a string. */
internal fun String?.reviewedJsonOrNull(): JsonElement? {
    val text = this?.takeIf { it.isNotBlank() && it.length <= MAX_REVIEWED_JSON_LENGTH } ?: return null
    val parsed = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val nested = (parsed as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    return if (nested != null && (nested.startsWith('[') || nested.startsWith('{'))) {
        nested.reviewedJsonOrNull()
    } else {
        parsed
    }
}

private const val MAX_REVIEWED_JSON_LENGTH = 256 * 1024
