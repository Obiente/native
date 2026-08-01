package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.Serializable

@Serializable
data class NativeRecord(
    val id: String,
    val values: Map<String, String?>,
    val displayValues: Map<String, String> = emptyMap(),
    /** Read-response observations for this record. They never become schema or action evidence. */
    val ephemeralFields: List<FieldSpec> = emptyList(),
    /** Whether [id] came from a contract-declared identity field and may bind a mutation. */
    val actionSafeIdentity: Boolean = true,
    /** Bounded, display-only structure retained from safe JSON arrays and objects. */
    val structuredValues: Map<String, NativeStructuredValue> = emptyMap(),
    /**
     * Exact parent/request identities used to load this record. These are action-binding context,
     * not response data, and therefore never render as fields.
     */
    val bindingContext: Map<String, String> = emptyMap(),
    /** False when display data was retained after detecting ambiguous action provenance. */
    val actionBindingProvenanceValid: Boolean = true,
)

@Serializable
sealed interface NativeStructuredValue {
    @Serializable
    data class Scalar(
        val value: String?,
        val kind: NativeStructuredScalarKind,
    ) : NativeStructuredValue

    @Serializable
    data class ListValue(
        val items: List<NativeStructuredValue>,
        val omittedItems: Int = 0,
    ) : NativeStructuredValue

    @Serializable
    data class ObjectValue(
        val entries: List<NativeStructuredEntry>,
        val omittedEntries: Int = 0,
    ) : NativeStructuredValue
}

@Serializable
data class NativeStructuredEntry(
    val key: String,
    val label: String,
    val value: NativeStructuredValue,
)

@Serializable
enum class NativeStructuredScalarKind {
    string,
    number,
    boolean,
    nullValue,
}

internal fun NativeRecord.presentationValue(fieldId: String): String? = displayValues[fieldId] ?: values[fieldId]

internal const val NATIVE_SYNTHETIC_RESOURCE_FIELD = "__nextcloud_native_resource"

internal fun NativeRecord.effectiveNativeResourceId(fallback: String): String =
    values[NATIVE_SYNTHETIC_RESOURCE_FIELD]?.takeIf(String::isNotBlank) ?: fallback

/**
 * Values allowed to cross from a selected read record into action/path/query binding.
 *
 * This compatibility helper deliberately returns no values when provenance is ambiguous. Mutation
 * planners should use [safeActionBindingValues] directly so an unavailable action remains explicit.
 */
internal fun NativeRecord.actionBindingValues(allowUnsafeIdentity: Boolean = false): Map<String, String> =
    safeActionBindingValues(allowUnsafeIdentity).orEmpty()

/**
 * Resolves values for a mutation without silently choosing between conflicting provenance.
 *
 * [bindingContext] contains the exact request and navigation identities used to load the record.
 * Response values and additional semantic context may confirm those identities, but may not
 * replace them. A response field literally named `id` is display/protocol data when [id] is a
 * different contract-selected backing identity, so only the canonical identity is exported under
 * the generic `id` key. Callers must keep an action unavailable when this returns `null`.
 */
internal fun NativeRecord.safeActionBindingValues(
    allowUnsafeIdentity: Boolean = false,
    contextualValues: Map<String, String> = emptyMap(),
): Map<String, String>? {
    if (!actionBindingProvenanceValid) return null
    val usableCanonicalIdentity =
        actionSafeIdentity || (allowUnsafeIdentity && canResolveUnsafeActionIdentity())
    val authoritativeContext = safeActionBindingValues(bindingContext, contextualValues) ?: return null
    val contextualIdentityValues = authoritativeContext.entries
        .filter { (key, _) -> key.actionBindingSemanticKey() == ACTION_BINDING_CANONICAL_ID }
        .map { (_, value) -> value }
        .distinct()
    if (
        usableCanonicalIdentity &&
        contextualIdentityValues.any { contextualIdentity -> contextualIdentity != id }
    ) {
        return null
    }
    val contextWithoutGenericIdentity = authoritativeContext.filterKeys { key ->
        key.actionBindingSemanticKey() != ACTION_BINDING_CANONICAL_ID
    }
    val observedValues = values.mapNotNull { (key, value) ->
        value
            ?.takeIf {
                key !in structuredValues &&
                    key.actionBindingSemanticKey() != ACTION_BINDING_CANONICAL_ID
            }
            ?.let { key to it }
    }.toMap()
    val canonicalIdentity = when {
        usableCanonicalIdentity -> mapOf("id" to id)
        contextualIdentityValues.size == 1 -> mapOf("id" to contextualIdentityValues.single())
        else -> emptyMap()
    }
    return safeActionBindingValues(
        contextWithoutGenericIdentity,
        observedValues,
        canonicalIdentity,
    )
}

/**
 * Merges flat action values only when every semantic key has one exact value.
 *
 * Exact spellings are retained because the request executor binds declared parameter names
 * literally. Normalized aliases such as `parentId` and `parent_id` may coexist only when they
 * confirm the same value.
 */
internal fun safeActionBindingValues(
    vararg sources: Map<String, String>,
): Map<String, String>? {
    val semanticValues = linkedMapOf<String, String>()
    val merged = linkedMapOf<String, String>()
    sources.forEach { source ->
        source.forEach { (key, value) ->
            val semanticKey = key.actionBindingSemanticKey()
            if (semanticKey.isBlank()) return null
            val existingSemanticValue = semanticValues[semanticKey]
            if (existingSemanticValue != null && existingSemanticValue != value) return null
            val existingExactValue = merged[key]
            if (existingExactValue != null && existingExactValue != value) return null
            semanticValues.putIfAbsent(semanticKey, value)
            merged.putIfAbsent(key, value)
        }
    }
    return merged
}

internal fun NativeRecord.canResolveUnsafeActionIdentity(): Boolean {
    if (actionSafeIdentity) return true
    if (id.isSafeDynamicActionValue()) return true
    return values.any { (key, value) ->
        key.isSemanticActionIdentityField() && value?.isSafeDynamicActionValue() == true
    }
}

private fun String.isSemanticActionIdentityField(): Boolean =
    lowercase().filter(Char::isLetterOrDigit) in setOf(
        "id",
        "databaseid",
        "uuid",
        "uid",
        "token",
    )

private fun String.isSafeDynamicActionValue(): Boolean =
    isNotBlank() && length <= 256 && none { character ->
        character == '/' || character == '\\' || character.isISOControl()
    }

private fun String.actionBindingSemanticKey(): String = lowercase().filter(Char::isLetterOrDigit)

private const val ACTION_BINDING_CANONICAL_ID = "id"

/** Adds bounded response-only fields to a renderer-local copy of the resource. */
internal fun ResourceSpec.withEphemeralDisplayFields(records: List<NativeRecord>): ResourceSpec {
    val declaredIds = fields.mapTo(mutableSetOf()) { it.id.lowercase() }
    val observed = linkedMapOf<String, FieldSpec>()
    records.asSequence().flatMap { it.ephemeralFields.asSequence() }.forEach { field ->
        val normalizedId = field.id.lowercase()
        if (normalizedId !in declaredIds && normalizedId !in observed && observed.size < MAX_EPHEMERAL_FIELDS) {
            observed[normalizedId] = field
        }
    }
    return if (observed.isEmpty()) this else copy(fields = fields + observed.values)
}

private const val MAX_EPHEMERAL_FIELDS = 24

sealed interface NativeScreenState {
    data object Loading : NativeScreenState

    data class Ready(
        val records: List<NativeRecord>,
    ) : NativeScreenState

    data class Error(
        val message: String,
        val retry: (() -> Unit)? = null,
        val retryLabel: String = "Try again",
    ) : NativeScreenState
}

sealed interface NativeActionRequest {
    val action: ActionSpec

    data class Load(
        override val action: ActionSpec,
    ) : NativeActionRequest

    data class Submit(
        override val action: ActionSpec,
        val values: Map<String, String>,
        val confirmed: Boolean,
    ) : NativeActionRequest
}
