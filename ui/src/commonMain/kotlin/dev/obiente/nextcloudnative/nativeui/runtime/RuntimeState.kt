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

/** Values allowed to cross from a selected read record into action/path/query binding. */
internal fun NativeRecord.actionBindingValues(allowUnsafeIdentity: Boolean = false): Map<String, String> = buildMap {
    putAll(bindingContext)
    this@actionBindingValues.values.forEach { (key, value) ->
        if (key !in structuredValues) value?.let { put(key, it) }
    }
    // The parser may deliberately choose a contract-declared backing identity such as
    // `databaseId` over a protocol/display field also named `id`. The canonical safe identity must
    // therefore win when an action uses a conventional `{id}` parameter.
    if (actionSafeIdentity || (allowUnsafeIdentity && canResolveUnsafeActionIdentity())) put("id", id)
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
