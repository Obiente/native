package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contract action selection for record editors.
 *
 * Record editors never name an app, a version, or a literal route. They start from the read the
 * screen already performed, the list action of a parent-scoped collection such as
 * `.../{container}/{parentId}/{collection}`, and look for writes that the installed app's own
 * contract declares at the matching route positions:
 * - create: POST on exactly the list route;
 * - scoped item write: the list route followed by one identity placeholder;
 * - global item write: the collection segment directly below the route base, followed by one
 *   identity placeholder (`.../{collection}/{id}`).
 *
 * Every candidate must carry trusted contract provenance, the expected semantic intent, and a
 * unique match. Two candidates for one position are ambiguous, and nothing is chosen.
 */
internal data class NativeContractAction(
    val action: ActionSpec,
    /** Path placeholder names in route order, so callers bind identities by position, not by name. */
    val placeholders: List<String>,
)

internal enum class NativeContractWriteKind { Create, Update, Delete }

/** A parent-scoped collection read whose route ends in `literal/{parent}/collection`. */
internal data class NativeCollectionRoute(
    val listAction: ActionSpec,
    val segments: List<String>,
    val parentPlaceholder: String,
) {
    val collectionSegment: String get() = segments.last()
    val listShape: List<String?> get() = segments.map { it.takeUnless(String::isRoutePlaceholder) }
    val scopedItemShape: List<String?> get() = listShape + null
    val globalItemShape: List<String?> get() = listShape.dropLast(3) + collectionSegment + null
    val listPlaceholders: List<String> get() = segments.filter(String::isRoutePlaceholder).map(String::placeholderName)

    /** How records and inputs name the parent, for example `tableId` for `tables/{id}` or `{tableId}`. */
    val parentReferenceKey: String get() = parentReferenceKey(segments[segments.size - 3], parentPlaceholder)
}

/**
 * The field name that refers to the parent identity bound at `container/{placeholder}`. A bare
 * `{id}` names the container's own identity, so the reference is the singular container name
 * plus `id`; it never matches a child record's own `id`.
 */
internal fun parentReferenceKey(container: String, placeholder: String): String {
    val key = placeholder.contractKey()
    if (key != "id") return key
    val word = container.contractKey()
    val singular = when {
        word.endsWith("ies") && word.length > 3 -> word.dropLast(3) + "y"
        word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }
    return singular + "id"
}

internal fun ActionSpec.nativeCollectionRoute(): NativeCollectionRoute? {
    if (binding.method != HttpMethod.GET || risk != ActionRisk.readOnly) return null
    val segments = binding.path.routeSegments()
    if (segments.size < 3) return null
    val collection = segments[segments.size - 1]
    val parent = segments[segments.size - 2]
    val container = segments[segments.size - 3]
    if (collection.isRoutePlaceholder() || !parent.isRoutePlaceholder() || container.isRoutePlaceholder()) return null
    val names = segments.filter(String::isRoutePlaceholder).map(String::placeholderName)
    if (names.distinct().size != names.size) return null
    return NativeCollectionRoute(this, segments, parent.placeholderName())
}

/**
 * The exact positional values of a collection read, or null when one of them is not known.
 *
 * The parent placeholder is bound to [parentId]. Any other placeholder must already have been
 * resolved by navigation. A navigation value that disagrees with the parent record means the
 * screen is not scoped to that record, so nothing is bound.
 */
internal fun NativeCollectionRoute.listValues(parent: NativeRecord, context: NativeDatasetContext): List<String>? {
    val parentId = parent.id.takeIf(String::isSafeContractPathValue) ?: return null
    return listPlaceholders.map { name ->
        val declared = context.bindingValues[name] ?: parent.bindingContext[name]
        if (name == parentPlaceholder) {
            if (declared != null && declared != parentId) return null
            parentId
        } else {
            declared?.takeIf(String::isSafeContractPathValue) ?: return null
        }
    }
}

/** An item write and how it binds the collection's parent scope. */
internal data class NativeItemWrite(
    val write: NativeContractAction,
    /** True for `.../{collection}/{id}` below the route base, which does not name the parent. */
    val global: Boolean,
    /** Body or query input that names the parent for a global route, when the contract declares one. */
    val parentInput: String?,
) {
    /**
     * Positional route values for one item, or null when the item cannot be bound safely. A
     * global route is used only when the contract lets it name the parent, or the item record
     * itself names the parent, so it can never address an item of another container.
     */
    fun values(listValues: List<String>, parentId: String, itemId: String, itemNamesParent: Boolean): Map<String, String>? {
        val routeValues = if (global) listValues.dropLast(1) + itemId else listValues + itemId
        if (write.placeholders.size != routeValues.size) return null
        if (global && parentInput == null && !itemNamesParent) return null
        return write.placeholders.zip(routeValues).toMap() + listOfNotNull(parentInput?.let { it to parentId })
    }

    /** Body inputs bound from the route and navigation rather than from the stored representation. */
    val contextInputs: Set<String> get() = write.placeholders.toSet() + setOfNotNull(parentInput)
}

/** The item write for [kind]: the scoped route under the list wins over the global route. */
internal fun NativeAppSchema.itemWrite(kind: NativeContractWriteKind, route: NativeCollectionRoute): NativeItemWrite? {
    contractWrite(kind, route.scopedItemShape)?.let { return NativeItemWrite(it, global = false, parentInput = null) }
    val global = contractWrite(kind, route.globalItemShape) ?: return null
    val inputs = global.action.binding.bodyFieldNames + global.action.binding.queryParameterNames
    val parentInput = inputs.singleOrNull { it.contractKey() == route.parentReferenceKey }
    return NativeItemWrite(global, global = true, parentInput = parentInput)
}

/** Finds the single trusted write at [shape] for [kind], or null when none or several match. */
internal fun NativeAppSchema.contractWrite(
    kind: NativeContractWriteKind,
    shape: List<String?>,
    methods: Set<HttpMethod> = kind.defaultMethods,
): NativeContractAction? {
    val matches = actions.mapNotNull { action ->
        if (action.binding.method !in methods || !action.hasTrustedWriteEvidence()) return@mapNotNull null
        if (action.risk == ActionRisk.readOnly || action.binding.allowsObservedBodyFields) return@mapNotNull null
        if (!action.hasWriteSemantics(kind)) return@mapNotNull null
        if (action.binding.requiredQueryParameterNames.isNotEmpty()) return@mapNotNull null
        val placeholders = action.binding.path.routePlaceholders(shape) ?: return@mapNotNull null
        if (action.binding.requiredPathParameterNames.toSet() != placeholders.toSet()) return@mapNotNull null
        NativeContractAction(action, placeholders)
    }
    return matches.singleOrNull()
}

private val NativeContractWriteKind.defaultMethods: Set<HttpMethod>
    get() = when (this) {
        NativeContractWriteKind.Create -> setOf(HttpMethod.POST)
        NativeContractWriteKind.Update -> setOf(HttpMethod.PUT, HttpMethod.PATCH)
        NativeContractWriteKind.Delete -> setOf(HttpMethod.DELETE)
    }

private fun ActionSpec.hasWriteSemantics(kind: NativeContractWriteKind): Boolean = when (kind) {
    NativeContractWriteKind.Create -> intent == ActionIntent.create || effect == ActionEffect.create
    NativeContractWriteKind.Update -> intent == ActionIntent.update || effect == ActionEffect.update
    NativeContractWriteKind.Delete -> intent == ActionIntent.delete ||
        effect in setOf(ActionEffect.delete, ActionEffect.permanentDelete)
}

/**
 * Writes come only from the installed app's own published contract: a server-advertised
 * OpenAPI document, or the verified package or exact source tag acquired for that installation.
 */
internal fun ActionSpec.hasTrustedWriteEvidence(): Boolean =
    confidence in setOf(Confidence.high, Confidence.verified) &&
        evidence.any { evidence -> evidence.source in TRUSTED_WRITE_EVIDENCE }

private val TRUSTED_WRITE_EVIDENCE = setOf(
    EvidenceSource.openApi,
    EvidenceSource.verifiedAppPackage,
    EvidenceSource.appStoreLinkedSourceTag,
)

/** Whether a replacement (PUT) result can be reconciled through a contract-declared same-route read. */
internal val NativeContractAction.declaresReplacementRead: Boolean
    get() = action.binding.method == HttpMethod.PUT && action.resultRecoveryActionId != null

/** Names [name] in the body or the query of this action exactly. */
internal fun NativeContractAction.declaresInput(name: String): Boolean =
    name in action.binding.bodyFieldNames || name in action.binding.queryParameterNames

internal fun String.routeSegments(): List<String> = substringBefore('?').split('/').filter(String::isNotEmpty)

internal fun String.isRoutePlaceholder(): Boolean = length >= 3 && startsWith('{') && endsWith('}')

private fun String.placeholderName(): String = substring(1, length - 1)

/** Placeholder names of this path in order when it matches [shape] (null entries are placeholders). */
internal fun String.routePlaceholders(shape: List<String?>): List<String>? {
    val segments = routeSegments()
    if (segments.size != shape.size) return null
    val placeholders = mutableListOf<String>()
    segments.zip(shape).forEach { (segment, expected) ->
        if (expected == null) {
            if (!segment.isRoutePlaceholder()) return null
            placeholders += segment.placeholderName()
        } else if (segment != expected) {
            return null
        }
    }
    return placeholders.takeIf { it.distinct().size == it.size }
}

/** A bounded identity that is safe to place in one path segment. */
internal fun String.isSafeContractPathValue(): Boolean =
    length in 1..128 && this != "." && this != ".." &&
        all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' }

/** A positive integer identity that is safe to place in a path or a JSON key. */
internal fun String?.contractPositiveId(): Long? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.length <= 18 && it.all(Char::isDigit) }
        ?.toLongOrNull()?.takeIf { it > 0 }

/** Lowercase letters and digits only, so `payer_id`, `payerId`, and `PayerID` compare equal. */
internal fun String.contractKey(): String = lowercase().filter(Char::isLetterOrDigit)

/** Parses a bounded JSON value, also unwrapping a JSON document that was sent as a string. */
internal fun String?.contractJsonOrNull(): JsonElement? {
    val text = this?.takeIf { it.isNotBlank() && it.length <= MAX_CONTRACT_JSON_LENGTH } ?: return null
    val parsed = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val nested = (parsed as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    return if (nested != null && (nested.startsWith('[') || nested.startsWith('{'))) {
        nested.contractJsonOrNull()
    } else {
        parsed
    }
}

private const val MAX_CONTRACT_JSON_LENGTH = 256 * 1024
