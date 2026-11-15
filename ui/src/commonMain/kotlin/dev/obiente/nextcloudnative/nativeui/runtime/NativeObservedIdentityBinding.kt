package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.DynamicRouteFamilyRelation
import dev.obiente.nextcloudnative.nativeui.model.dynamicRouteFamilyRelation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Observed identity binding (technique A). An id seen only in a list response may fill a write's
 * path placeholder when every invariant holds. The proof is per action and per load; it never
 * promotes an observed field to a declared identity.
 *
 * 1. Route family: the write's target is one placeholder below the collection read (an optional
 *    terminal command segment allowed), or the write acts on a verified item read.
 * 2. Name and type: the placeholder is `id`, `{singular}Id`, or `{singular}_id` of the collection,
 *    and the observed value matches the placeholder's declared type.
 * 3. Freshness: the record belongs to the current authoritative load generation.
 * 4. Scope: every other placeholder is a parent bound by route position from navigation context,
 *    never from the record body.
 * 5. Round trip: with an item read, the id is read once and must come back as the same identity
 *    before the first write; otherwise the post-write read-back must find the record by this id.
 * 6. Uniqueness: the id is unique in the page and no other identity-like field disagrees with it.
 */

/** A route template with the declared type of each placeholder (`integer`, `string`, or null). */
internal data class NativeIdentityRoute(val path: String, val placeholderTypes: Map<String, String?> = emptyMap())

internal sealed interface NativeIdentityRoundTrip {
    /** Read [readPath] with the bound values once; its single record must carry the same id. */
    data class ItemRead(val readPath: String) : NativeIdentityRoundTrip

    /** No item read exists: the post-write read-back must find the record by the same id. */
    data object ReadBackByIdentity : NativeIdentityRoundTrip
}

internal enum class NativeIdentityBindingRefusal {
    DifferentRouteFamily,
    PlaceholderNameMismatch,
    PlaceholderTypeMismatch,
    UnsafeValue,
    StaleGeneration,
    ParentUnbound,
    AmbiguousIdentity,
}

internal sealed interface NativeObservedIdentityProof {
    data class Bound(val values: Map<String, String>, val roundTrip: NativeIdentityRoundTrip) : NativeObservedIdentityProof

    data class Refused(val reason: NativeIdentityBindingRefusal) : NativeObservedIdentityProof
}

/**
 * Proves or refuses binding [record]'s observed id into [write]. [collection] is the read the
 * record came from; [itemRead] is a verified item read of the same family, if declared.
 * [navigationValues] holds parent identities by the collection read's placeholder names.
 */
internal fun bindObservedIdentity(
    write: NativeIdentityRoute,
    collection: NativeIdentityRoute,
    itemRead: NativeIdentityRoute?,
    record: NativeRecord,
    page: List<NativeRecord>,
    recordGeneration: Long,
    currentGeneration: Long,
    navigationValues: Map<String, String>,
): NativeObservedIdentityProof {
    // 1. Route family.
    val collectionRelation = dynamicRouteFamilyRelation(write.path, collection.path)
    val itemRelation = itemRead?.let { dynamicRouteFamilyRelation(write.path, it.path) }
    val viaItem = itemRelation == DynamicRouteFamilyRelation.sameRoute || itemRelation == DynamicRouteFamilyRelation.itemCommand
    if (collectionRelation != DynamicRouteFamilyRelation.collectionItem && !viaItem) {
        return refused(NativeIdentityBindingRefusal.DifferentRouteFamily)
    }
    val writeSegments = write.path.routeSegments()
    val collectionSegments = collection.path.routeSegments()
    val targetIndex = collectionSegments.size
    val targetSegment = writeSegments.getOrNull(targetIndex)?.takeIf(String::isRoutePlaceholder)
        ?: return refused(NativeIdentityBindingRefusal.DifferentRouteFamily)
    val placeholder = targetSegment.removeSurrounding("{", "}")
    // 2. Name and type.
    val resourceWord = collectionSegments.lastOrNull()?.takeUnless(String::isRoutePlaceholder)
        ?: return refused(NativeIdentityBindingRefusal.DifferentRouteFamily)
    if (placeholder.contractKey() !in acceptedIdentityNames(resourceWord)) return refused(NativeIdentityBindingRefusal.PlaceholderNameMismatch)
    // 6. Uniqueness, and agreement of every identity-like field.
    val id = record.id
    val identityValues = record.observedRuleValues.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull } + record.values
    if (identityValues.none { (key, value) -> key.contractKey() in acceptedIdentityNames(resourceWord) && value == id }) {
        return refused(NativeIdentityBindingRefusal.AmbiguousIdentity)
    }
    if (page.count { it.id == id } != 1 || record.safeActionBindingValues(allowUnsafeIdentity = true) == null) {
        return refused(NativeIdentityBindingRefusal.AmbiguousIdentity)
    }
    val disagreeing = identityValues.any { (key, value) ->
        key.contractKey() in (acceptedIdentityNames(resourceWord) + setOf("uid", "uuid", "databaseid")) && value != null && value.trim() != id
    }
    if (disagreeing) return refused(NativeIdentityBindingRefusal.AmbiguousIdentity)
    if (!id.isSafeContractPathValue()) return refused(NativeIdentityBindingRefusal.UnsafeValue)
    when (write.placeholderTypes[placeholder]) {
        "integer" -> if (id.contractPositiveId() == null) return refused(NativeIdentityBindingRefusal.PlaceholderTypeMismatch)
        "string", null -> Unit
        else -> return refused(NativeIdentityBindingRefusal.PlaceholderTypeMismatch)
    }
    // 3. Freshness: cached and locally derived records carry generation 0.
    if (recordGeneration == 0L || recordGeneration != currentGeneration) return refused(NativeIdentityBindingRefusal.StaleGeneration)
    // 4. Scope: parents by route position, from navigation only.
    val values = linkedMapOf<String, String>()
    writeSegments.take(targetIndex).forEachIndexed { index, segment ->
        if (!segment.isRoutePlaceholder()) return@forEachIndexed
        val parentName = collectionSegments[index].takeIf(String::isRoutePlaceholder)?.removeSurrounding("{", "}")
            ?: return refused(NativeIdentityBindingRefusal.ParentUnbound)
        val parentValue = navigationValues[parentName]?.takeIf(String::isSafeContractPathValue)
            ?: return refused(NativeIdentityBindingRefusal.ParentUnbound)
        val parentNames = setOf(parentName.contractKey(), segment.removeSurrounding("{", "}").contractKey())
        if (identityValues.any { (name, value) -> name.contractKey() in parentNames && value != null && value != parentValue }) {
            return refused(NativeIdentityBindingRefusal.AmbiguousIdentity)
        }
        values[segment.removeSurrounding("{", "}")] = parentValue
    }
    if (writeSegments.drop(targetIndex + 1).any(String::isRoutePlaceholder)) return refused(NativeIdentityBindingRefusal.ParentUnbound)
    values[placeholder] = id
    // 5. Round trip.
    val roundTrip = itemRead?.takeIf { viaItem }?.let { NativeIdentityRoundTrip.ItemRead(it.path) }
        ?: NativeIdentityRoundTrip.ReadBackByIdentity
    return NativeObservedIdentityProof.Bound(values, roundTrip)
}

/** Invariant 5 with an item read: the single record read back must carry exactly [id]. */
internal fun confirmsObservedIdentity(id: String, itemRecords: List<NativeRecord>): Boolean =
    itemRecords.singleOrNull()?.id == id

private fun acceptedIdentityNames(collectionWord: String): Set<String> {
    val word = collectionWord.contractKey()
    val singular = when {
        word.endsWith("ies") && word.length > 3 -> word.dropLast(3) + "y"
        word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }
    return setOf("id", singular + "id")
}

private fun refused(reason: NativeIdentityBindingRefusal) = NativeObservedIdentityProof.Refused(reason)
