package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A nested form may expose an observed child row while the selected parent record carries the
 * relationship identity required by the action. Use the parent for hidden relationship binding,
 * while the observed row remains available separately for safe form prefilling.
 */
internal fun nativeFormBindingRecord(
    initialRecord: NativeRecord?,
    parentResourceId: String?,
    parentRecord: NativeRecord?,
): NativeRecord? =
    if (parentResourceId.isNullOrBlank()) initialRecord else parentRecord ?: initialRecord

/**
 * A selected parent record supplies relationship identities to a child create action, but its
 * user-authored fields are not defaults for the new child. Creating a nested child must not copy
 * the parent's title or other editable content into the new record.
 */
internal fun nativeFormPrefillRecord(
    action: ActionSpec,
    resource: ResourceSpec,
    record: NativeRecord?,
    parentResourceId: String? = null,
): NativeRecord? {
    record ?: return null
    if (action.intent != ActionIntent.create || parentResourceId.isNullOrBlank()) return record
    val parentIdentities = parentResourceId.nativeRelationResourceIdentities()
    val formIdentities = resource.id.nativeRelationResourceIdentities()
    return record.takeIf { parentIdentities.intersect(formIdentities).isNotEmpty() }
}

/**
 * Resolves already-known technical identities without exposing them as text inputs.
 * Destination fields remain user choices: source context is never destination intent.
 */
internal fun nativeFormAutoBoundValues(
    schema: NativeAppSchema,
    action: ActionSpec,
    resource: ResourceSpec,
    record: NativeRecord?,
    parentResourceId: String? = null,
): Map<String, String> = nativeFormAutoBindingResolution(
    schema = schema,
    action = action,
    resource = resource,
    record = record,
    parentResourceId = parentResourceId,
).values

internal data class NativeFormAutoBindingResolution(
    val values: Map<String, String>,
    val error: String? = null,
)

/**
 * Resolves schema-declared technical inputs from verified navigation and record provenance.
 *
 * No app, endpoint, or domain vocabulary participates. Conflicting identities leave the action
 * disabled instead of silently selecting one source. User-selected destinations remain visible.
 */
internal fun nativeFormAutoBindingResolution(
    schema: NativeAppSchema,
    action: ActionSpec,
    resource: ResourceSpec,
    record: NativeRecord?,
    parentResourceId: String? = null,
    navigationValues: Map<String, String> = emptyMap(),
): NativeFormAutoBindingResolution {
    val declaredBindingNames = buildSet {
        addAll(action.binding.pathParameterNames)
        addAll(action.binding.queryParameterNames)
        addAll(action.binding.bodyFieldNames)
    }
    val declaredNavigationValues = navigationValues.filterKeys(declaredBindingNames::contains)
    val available = when (record) {
        null -> safeActionBindingValues(declaredNavigationValues)
        else -> {
            if (!record.actionBindingProvenanceValid) {
                return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected item's identity provenance is ambiguous.",
                )
            }
            val recordValues = record.nativeFormDeclaredBindingValues(declaredBindingNames)
                ?: return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected item contains conflicting declared identities.",
                )
            safeActionBindingValues(recordValues, declaredNavigationValues)
                ?: return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected item no longer matches the navigation context.",
                )
        }
    } ?: return NativeFormAutoBindingResolution(
        values = emptyMap(),
        error = "This action cannot be linked because its navigation context is invalid.",
    )
    if (
        schema.resources.count { candidate -> candidate.id == resource.id } != 1 ||
        schema.actions.count { candidate -> candidate.id == action.id && candidate == action } != 1
    ) {
        return NativeFormAutoBindingResolution(
            values = emptyMap(),
            error = "This action cannot be linked because its schema contract is ambiguous.",
        )
    }
    val acceptedRelationships = schema.relationships.filter { relationship ->
        relationship.childResourceId == resource.id &&
            relationship.parentResourceId == parentResourceId &&
            relationship.childFieldId != null &&
            relationship.confidence == Confidence.verified
    }
    if (
        acceptedRelationships.groupBy { relationship -> relationship.childFieldId }
            .any { (_, relationships) -> relationships.distinct().size > 1 }
    ) {
        return NativeFormAutoBindingResolution(
            values = emptyMap(),
            error = "This action cannot be linked because its parent relationship is ambiguous.",
        )
    }
    val acceptedRelationshipFieldIds = acceptedRelationships.mapNotNullTo(mutableSetOf()) { relationship ->
        relationship.childFieldId
    }
    val requiredInputFieldNames = ((action.inputSchema as? JsonObject)?.get("required") as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
        .toSet()
    val resolved = linkedMapOf<String, String>()
    if (action.intent == ActionIntent.create) {
        acceptedRelationships.distinct().forEach { relationship ->
            val childFieldId = requireNotNull(relationship.childFieldId)
            if (childFieldId !in declaredBindingNames) return@forEach
            val parentValues = record
                ?.takeIf { parent ->
                    parent.actionSafeIdentity && parent.actionBindingProvenanceValid
                }
                ?.safeActionBindingValues()
                ?.get(relationship.parentFieldId)
                ?.let(::listOf)
                .orEmpty()
            val exactValues = buildList {
                declaredNavigationValues[childFieldId]?.takeIf(String::isNotBlank)?.let(::add)
                addAll(parentValues.filter(String::isNotBlank))
            }.distinct()
            if (exactValues.size > 1) {
                return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be safely linked to the selected parent because its identities conflict.",
                )
            }
            val value = exactValues.singleOrNull()
            if (
                value == null &&
                childFieldId in (
                    action.binding.pathParameterNames +
                        action.binding.requiredQueryParameterNames +
                        action.binding.requiredBodyFieldNames +
                        requiredInputFieldNames
                    )
            ) {
                return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected parent identity could not be verified.",
                )
            }
            value?.let { resolved[childFieldId] = it }
        }
    }
    if (action.intent != ActionIntent.create) {
        val requiredBindingNames = buildSet {
            addAll(action.binding.requiredPathParameterNames)
            addAll(action.binding.requiredQueryParameterNames)
            addAll(action.binding.requiredBodyFieldNames)
        }
        resource.fields.asSequence()
            .filter { field ->
                val normalized = field.id.nativeRelationSemanticId()
                field.id in requiredBindingNames &&
                    field.id !in acceptedRelationshipFieldIds &&
                    normalized.length > 2 &&
                    normalized.endsWith("id")
            }
            .forEach { field ->
                available[field.id]
                    ?.takeIf(String::isNotBlank)
                    ?.let { value -> resolved[field.id] = value }
            }
    }
    val technicalParameterNames = (
        action.binding.pathParameterNames + action.binding.queryParameterNames
        ).distinct()
    technicalParameterNames.forEach { parameterName ->
        if (parameterName in resolved) return@forEach
        if (
            action.intent == ActionIntent.create &&
            parentResourceId != null &&
            parameterName.nativeRelationSemanticId().isIdentityForNativeParent(parentResourceId) &&
            parameterName !in acceptedRelationshipFieldIds
        ) {
            declaredNavigationValues[parameterName]
                ?.takeIf(String::isNotBlank)
                ?.let { value -> resolved[parameterName] = value }
            return@forEach
        }
        val exactCandidates = available[parameterName]
            ?.takeIf(String::isNotBlank)
            ?.let(::listOf)
            .orEmpty()
        val canonicalRecordIdentity = record
            ?.takeIf {
                it.actionSafeIdentity &&
                    it.actionBindingProvenanceValid &&
                    parameterName.nativeRelationSemanticId().isIdentityForNativeParent(resource.id)
            }
            ?.id
            ?.takeIf(String::isNotBlank)
        val candidates = (exactCandidates + listOfNotNull(canonicalRecordIdentity)).distinct()
        if (candidates.size > 1) {
            return NativeFormAutoBindingResolution(
                values = emptyMap(),
                error = "This action cannot be safely linked because the required identity is ambiguous.",
            )
        }
        candidates.singleOrNull()?.let { value -> resolved[parameterName] = value }
    }
    return NativeFormAutoBindingResolution(values = resolved)
}

private fun NativeRecord.nativeFormDeclaredBindingValues(
    declaredBindingNames: Set<String>,
): Map<String, String>? {
    val declaredSemanticNames = declaredBindingNames
        .mapTo(linkedSetOf()) { name -> name.nativeRelationSemanticId() }
    val declaredContext = bindingContext.filterKeys { key ->
        key.nativeRelationSemanticId() in declaredSemanticNames
    }
    val declaredObservedValues = values.mapNotNull { (key, value) ->
        val semanticKey = key.nativeRelationSemanticId()
        value
            ?.takeIf {
                key !in structuredValues &&
                    semanticKey in declaredSemanticNames &&
                    semanticKey != "id"
            }
            ?.let { key to it }
    }.toMap()
    val canonicalIdentity = if (actionSafeIdentity) {
        declaredBindingNames
            .filter { name -> name.nativeRelationSemanticId() == "id" }
            .associateWith { id }
    } else {
        emptyMap()
    }
    return safeActionBindingValues(
        declaredContext,
        declaredObservedValues,
        canonicalIdentity,
    )
}

private fun String.nativeRelationSemanticId(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.isIdentityForNativeParent(parentResourceId: String): Boolean {
    if (length <= 2 || !endsWith("id")) return false
    return dropLast(2).nativeRelationResourceIdentities()
        .intersect(parentResourceId.nativeRelationResourceIdentities())
        .isNotEmpty()
}

private fun String.nativeRelationResourceIdentities(): Set<String> {
    val normalized = nativeRelationSemanticId()
    return buildSet {
        add(normalized)
        if (normalized.endsWith('s') && normalized.length > 1) add(normalized.dropLast(1))
        if (normalized.endsWith("ies") && normalized.length > 3) add(normalized.dropLast(3) + "y")
        if (
            normalized.endsWith("ches") ||
            normalized.endsWith("shes") ||
            normalized.endsWith("sses") ||
            normalized.endsWith("xes") ||
            normalized.endsWith("zes")
        ) {
            add(normalized.dropLast(2))
        }
    }
}
