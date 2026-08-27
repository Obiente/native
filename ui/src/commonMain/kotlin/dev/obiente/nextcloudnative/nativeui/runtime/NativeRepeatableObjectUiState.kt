package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

internal fun initialNativeCreateRepeatableObjectDraft(
    fields: List<FieldSpec>,
    initialValues: Map<String, String>,
): Map<String, List<RepeatableObjectInputRow>>? {
    val repeatableFieldIds = fields
        .filter { field -> field.repeatableObjectInput != null }
        .mapTo(hashSetOf(), FieldSpec::id)
    return initialNativeRepeatableObjectDraft(
        fields = fields,
        initialValues = initialValues.filterNot { (fieldId, value) ->
            fieldId in repeatableFieldIds && value.isBlank()
        },
    )
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
    val updatedNullFieldIds = rows[rowIndex].nullFieldIds - field.id
    return rows.toMutableList().apply {
        this[rowIndex] = RepeatableObjectInputRow(updatedValues, updatedNullFieldIds)
    }
}

internal fun updateNativeRepeatableObjectNull(
    rows: List<RepeatableObjectInputRow>,
    rowIndex: Int,
    field: RepeatableObjectInputFieldSpec,
    explicitNull: Boolean,
): List<RepeatableObjectInputRow> {
    if (rowIndex !in rows.indices || !field.nullable) return rows
    val row = rows[rowIndex]
    val updatedValues = if (explicitNull) row.values - field.id else row.values
    val updatedNullFieldIds = if (explicitNull) {
        row.nullFieldIds + field.id
    } else {
        row.nullFieldIds - field.id
    }
    return rows.toMutableList().apply {
        this[rowIndex] = RepeatableObjectInputRow(updatedValues, updatedNullFieldIds)
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
                row.nullFieldIds.any { fieldId -> fieldId !in declaredFieldIds } ||
                row.values.keys.any(row.nullFieldIds::contains) ||
                row.nullFieldIds.any { fieldId ->
                    spec.fields.single { field -> field.id == fieldId }.nullable.not()
                } ||
                row.values.values.any { value ->
                    value.length > MAX_NATIVE_REPEATABLE_OBJECT_SCALAR_LENGTH
                }
        }
    ) {
        return null
    }
    return JsonArray(
        rows.map { row ->
            JsonObject(
                buildMap {
                    row.values.forEach { (fieldId, value) -> put(fieldId, JsonPrimitive(value)) }
                    row.nullFieldIds.forEach { fieldId -> put(fieldId, JsonNull) }
                },
            )
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
        val values = linkedMapOf<String, String>()
        val nullFieldIds = linkedSetOf<String>()
        row.forEach { (fieldId, value) ->
            if (value is JsonNull) {
                val field = spec.fields.single { field -> field.id == fieldId }
                if (!field.nullable) return null
                nullFieldIds += fieldId
            } else {
                val primitive = value as? JsonPrimitive ?: return null
                val draft = primitive.takeIf(JsonPrimitive::isString)?.content
                    ?.takeIf { candidate ->
                        candidate.length <= MAX_NATIVE_REPEATABLE_OBJECT_SCALAR_LENGTH
                    }
                    ?: return null
                values[fieldId] = draft
            }
        }
        RepeatableObjectInputRow(values, nullFieldIds)
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
        val nullFieldIds = item.entries
            .filter { (_, value) -> value is JsonNull }
            .mapTo(linkedSetOf()) { (fieldId, _) -> fieldId }
        RepeatableObjectInputRow(
            values = fields.mapNotNull { field ->
                item[field.id]?.let { value ->
                    if (value is JsonNull) return@let null
                    field.id to field.wireValue(value, rowIndex)
                }
            }.toMap(),
            nullFieldIds = nullFieldIds,
        )
    }
}.getOrNull()

private const val MAX_NATIVE_REPEATABLE_OBJECT_SCALAR_LENGTH = 4_096
private const val MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELDS = 64
private const val MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_FIELD_ID_LENGTH = 256
private const val MAX_NATIVE_REPEATABLE_OBJECT_DRAFT_LENGTH = 256 * 1_024
