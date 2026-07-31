package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import kotlinx.serialization.json.JsonObject

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
    if (values.keys != specs.keys) return null
    val encoded = runCatching {
        values.mapValues { (fieldId, rows) -> specs.getValue(fieldId).encode(rows) }
    }.getOrNull() ?: return null
    return encodeNativeRecordFormDraft(encoded)
}

internal fun decodeNativeRepeatableObjectDraft(
    saved: List<String>,
    specs: Map<String, RepeatableObjectInputSpec>,
): Map<String, List<RepeatableObjectInputRow>>? {
    val encoded = decodeNativeRecordFormDraft(saved) ?: return null
    if (encoded.keys != specs.keys) return null
    return buildMap {
        encoded.forEach { (fieldId, value) ->
            val rows = specs.getValue(fieldId).decodeNativeRepeatableObjectRows(value)
                ?: return null
            put(fieldId, rows)
        }
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
