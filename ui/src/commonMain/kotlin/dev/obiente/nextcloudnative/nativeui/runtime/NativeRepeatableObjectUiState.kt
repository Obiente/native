package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun initialNativeRepeatableObjectDraft(
    fields: List<FieldSpec>,
    initialValues: Map<String, String>,
): Map<String, List<RepeatableObjectInputRow>>? = buildMap {
    fields.forEach { field ->
        val spec = field.repeatableObjectInput ?: return@forEach
        val rows = initialValues[field.id]?.let { encoded ->
            spec.decodeNativeRepeatableObjectRows(encoded) ?: return null
        } ?: List(spec.minimumItems) { spec.emptyNativeRepeatableObjectRow() }
        put(field.id, rows)
    }
}

internal fun addNativeRepeatableObjectRow(
    rows: List<RepeatableObjectInputRow>,
    spec: RepeatableObjectInputSpec,
): List<RepeatableObjectInputRow> =
    if (rows.size >= spec.maximumItems) rows else rows + spec.emptyNativeRepeatableObjectRow()

internal fun removeNativeRepeatableObjectRow(
    rows: List<RepeatableObjectInputRow>,
    index: Int,
    spec: RepeatableObjectInputSpec,
): List<RepeatableObjectInputRow> =
    if (rows.size <= spec.minimumItems || index !in rows.indices) {
        rows
    } else {
        rows.filterIndexed { rowIndex, _ -> rowIndex != index }
    }

internal fun updateNativeRepeatableObjectValue(
    rows: List<RepeatableObjectInputRow>,
    rowIndex: Int,
    field: RepeatableObjectInputFieldSpec,
    value: String,
): List<RepeatableObjectInputRow> {
    if (rowIndex !in rows.indices || value.length > MAX_NATIVE_REPEATABLE_OBJECT_SCALAR_LENGTH) {
        return rows
    }
    val updatedValues = rows[rowIndex].values.toMutableMap().apply {
        if (value.isBlank() && field.kind != RepeatableObjectInputScalarKind.Boolean) {
            remove(field.id)
        } else {
            put(field.id, value)
        }
    }
    return rows.toMutableList().apply {
        this[rowIndex] = RepeatableObjectInputRow(updatedValues)
    }
}

internal fun encodeNativeRepeatableObjectDraft(
    values: Map<String, List<RepeatableObjectInputRow>>,
    specs: Map<String, RepeatableObjectInputSpec>,
): List<String>? {
    if (
        values.keys != specs.keys ||
        values.size > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELDS
    ) {
        return null
    }
    val encoded = values.mapValues { (fieldId, rows) ->
        encodeNativeRepeatableObjectDraftRows(rows, specs.getValue(fieldId)) ?: return null
    }
    var totalLength = 0
    val saved = ArrayList<String>(encoded.size * 2)
    encoded.entries.sortedBy(Map.Entry<String, String>::key).forEach { (fieldId, draft) ->
        if (
            fieldId.isBlank() ||
            fieldId.length > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELD_ID_LENGTH ||
            draft.length > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_LENGTH
        ) {
            return null
        }
        totalLength += fieldId.length + draft.length
        if (totalLength > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_LENGTH) return null
        saved += fieldId
        saved += draft
    }
    return saved
}

internal fun decodeNativeRepeatableObjectDraft(
    saved: List<String>,
    specs: Map<String, RepeatableObjectInputSpec>,
): Map<String, List<RepeatableObjectInputRow>>? {
    if (
        saved.size % 2 != 0 ||
        saved.size / 2 > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELDS
    ) {
        return null
    }
    val encoded = linkedMapOf<String, String>()
    var totalLength = 0
    saved.chunked(2).forEach { (fieldId, draft) ->
        if (
            fieldId.isBlank() ||
            fieldId in encoded ||
            fieldId.length > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELD_ID_LENGTH ||
            draft.length > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_LENGTH
        ) {
            return null
        }
        totalLength += fieldId.length + draft.length
        if (totalLength > MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_LENGTH) return null
        encoded[fieldId] = draft
    }
    if (encoded.keys != specs.keys) return null
    return buildMap {
        encoded.forEach { (fieldId, value) ->
            val rows = decodeNativeRepeatableObjectDraftRows(value, specs.getValue(fieldId))
                ?: return null
            put(fieldId, rows)
        }
    }
}

private fun encodeNativeRepeatableObjectDraftRows(
    rows: List<RepeatableObjectInputRow>,
    spec: RepeatableObjectInputSpec,
): String? {
    if (rows.size > spec.maximumItems) return null
    val declaredFieldIds = spec.fields.mapTo(linkedSetOf(), RepeatableObjectInputFieldSpec::id)
    if (
        rows.any { row ->
            row.values.keys.any { fieldId -> fieldId !in declaredFieldIds } ||
                row.values.values.any { value ->
                    value.length > MAX_NATIVE_REPEATABLE_OBJECT_SCALAR_LENGTH
                }
        }
    ) {
        return null
    }
    return JsonArray(
        rows.map { row ->
            JsonObject(row.values.mapValues { (_, value) -> JsonPrimitive(value) })
        },
    ).toString()
}

private fun decodeNativeRepeatableObjectDraftRows(
    encoded: String,
    spec: RepeatableObjectInputSpec,
): List<RepeatableObjectInputRow>? {
    val rows = runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonArray
        ?: return null
    if (rows.size > spec.maximumItems) return null
    val declaredFieldIds = spec.fields.mapTo(linkedSetOf(), RepeatableObjectInputFieldSpec::id)
    return rows.map { element ->
        val row = element as? JsonObject ?: return null
        if (row.keys.any { fieldId -> fieldId !in declaredFieldIds }) return null
        val values = row.mapValues { (_, value) ->
            val primitive = value as? JsonPrimitive
                ?: return null
            primitive.takeIf(JsonPrimitive::isString)?.content
                ?.takeIf { draft ->
                    draft.length <= MAX_NATIVE_REPEATABLE_OBJECT_SCALAR_LENGTH
                }
                ?: return null
        }
        RepeatableObjectInputRow(values)
    }
}

private fun RepeatableObjectInputSpec.emptyNativeRepeatableObjectRow(): RepeatableObjectInputRow =
    RepeatableObjectInputRow(
        fields.mapNotNull { field ->
            field.takeIf {
                it.required && it.kind == RepeatableObjectInputScalarKind.Boolean
            }?.let { it.id to "false" }
        }.toMap(),
    )

private fun RepeatableObjectInputSpec.decodeNativeRepeatableObjectRows(
    encoded: String,
): List<RepeatableObjectInputRow>? = runCatching {
    canonicalJson(encoded).mapIndexed { rowIndex, element ->
        val item = element as JsonObject
        RepeatableObjectInputRow(
            fields.mapNotNull { field ->
                item[field.id]?.let { value ->
                    field.id to field.wireValue(value, rowIndex)
                }
            }.toMap(),
        )
    }
}.getOrNull()

private const val MAX_NATIVE_REPEATABLE_OBJECT_SCALAR_LENGTH = 4_096
private const val MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELDS = 64
private const val MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELD_ID_LENGTH = 256
private const val MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_LENGTH = 256 * 1_024
