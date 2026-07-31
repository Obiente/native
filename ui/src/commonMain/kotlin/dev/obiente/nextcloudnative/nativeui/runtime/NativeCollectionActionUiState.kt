package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal fun toggleNativeCollectionSelection(
    selectedRecordIds: List<String>,
    recordId: String,
    availableRecordIds: List<String>,
    maximumSelectionSize: Int,
): List<String> {
    if (maximumSelectionSize < 1 || recordId !in availableRecordIds) return selectedRecordIds
    val available = availableRecordIds.distinct()
    val selected = selectedRecordIds
        .asSequence()
        .filter(available::contains)
        .distinct()
        .take(maximumSelectionSize)
        .toMutableSet()
    if (!selected.remove(recordId) && selected.size < maximumSelectionSize) {
        selected += recordId
    }
    return available.filter(selected::contains)
}

internal fun moveNativeCollectionRecord(
    orderedRecordIds: List<String>,
    recordId: String,
    offset: Int,
): List<String> {
    if (offset !in setOf(-1, 1) || orderedRecordIds.distinct().size != orderedRecordIds.size) {
        return orderedRecordIds
    }
    val fromIndex = orderedRecordIds.indexOf(recordId)
    val toIndex = fromIndex + offset
    if (fromIndex < 0 || toIndex !in orderedRecordIds.indices) return orderedRecordIds
    return orderedRecordIds.toMutableList().apply {
        this[fromIndex] = this[toIndex]
        this[toIndex] = recordId
    }
}

internal fun NativeCollectionBatchInputField.toNativeCollectionFieldSpec(): FieldSpec =
    FieldSpec(
        id = id,
        label = id.nativeCollectionFieldLabel(),
        kind = when {
            enumValues != null -> FieldKind.enumeration
            kind == NativeCollectionBatchInputKind.Boolean && required -> FieldKind.boolean
            kind == NativeCollectionBatchInputKind.Boolean -> FieldKind.enumeration
            kind == NativeCollectionBatchInputKind.Integer ||
                kind == NativeCollectionBatchInputKind.IntegerArray -> FieldKind.integer
            kind == NativeCollectionBatchInputKind.Decimal -> FieldKind.decimal
            else -> FieldKind.string
        },
        required = required,
        readOnly = false,
        format = when (kind) {
            NativeCollectionBatchInputKind.IntegerArray -> DYNAMIC_INTEGER_ARRAY_FORMAT
            NativeCollectionBatchInputKind.StringArray -> DYNAMIC_STRING_ARRAY_FORMAT
            else -> null
        },
        enumValues = when {
            kind == NativeCollectionBatchInputKind.Boolean && !required ->
                NATIVE_COLLECTION_BATCH_BOOLEAN_OPTIONS
            else -> enumValues
        },
    )

internal fun initialNativeCollectionBatchDraft(
    fields: List<NativeCollectionBatchInputField>,
): Map<String, String> = fields
    .filter { field -> field.required && field.kind == NativeCollectionBatchInputKind.Boolean }
    .associate { field -> field.id to "false" }

internal fun nativeCollectionBatchRequestValues(
    fields: List<NativeCollectionBatchInputField>,
    draft: Map<String, String>,
): Map<String, String> = buildMap {
    fields.forEach { field ->
        val value = draft[field.id] ?: return@forEach
        if (
            field.kind == NativeCollectionBatchInputKind.Boolean &&
            !field.required &&
            value == NATIVE_COLLECTION_BATCH_UNCHANGED_VALUE
        ) {
            return@forEach
        }
        if (value.isBlank() && !field.required) return@forEach
        put(
            field.id,
            when (field.kind) {
                NativeCollectionBatchInputKind.IntegerArray ->
                    if (field.relatedResourceId != null) value.trim() else value.toNativeCollectionIntegerArray()
                NativeCollectionBatchInputKind.StringArray ->
                    if (field.relatedResourceId != null) value.trim() else value.toNativeCollectionStringArray()
                else -> value.trim()
            },
        )
    }
}

internal const val NATIVE_COLLECTION_BATCH_UNCHANGED_VALUE = "unchanged"

private val NATIVE_COLLECTION_BATCH_BOOLEAN_OPTIONS = listOf(
    NATIVE_COLLECTION_BATCH_UNCHANGED_VALUE,
    "true",
    "false",
)

private fun String.toNativeCollectionIntegerArray(): String {
    val values = lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { value ->
            value.toLongOrNull()
                ?: error("Enter one whole number per line.")
        }
        .toList()
    return JsonArray(values.map(::JsonPrimitive)).toString()
}

private fun String.toNativeCollectionStringArray(): String {
    val values = lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    return JsonArray(values.map(::JsonPrimitive)).toString()
}

private fun String.nativeCollectionFieldLabel(): String =
    replace('-', ' ')
        .replace('_', ' ')
        .fold(StringBuilder()) { label, character ->
            if (
                character.isUpperCase() &&
                label.isNotEmpty() &&
                label.last() != ' '
            ) {
                label.append(' ')
            }
            label.append(character)
        }
        .toString()
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercaseChar) }
