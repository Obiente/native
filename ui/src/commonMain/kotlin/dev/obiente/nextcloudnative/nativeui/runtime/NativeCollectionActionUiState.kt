package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

internal fun moveNativeCollectionRecordToIndex(
    orderedRecordIds: List<String>,
    recordId: String,
    targetIndex: Int,
): List<String> {
    if (orderedRecordIds.distinct().size != orderedRecordIds.size) return orderedRecordIds
    val fromIndex = orderedRecordIds.indexOf(recordId)
    if (fromIndex < 0 || targetIndex !in orderedRecordIds.indices || fromIndex == targetIndex) {
        return orderedRecordIds
    }
    return orderedRecordIds.toMutableList().apply {
        removeAt(fromIndex)
        add(targetIndex, recordId)
    }
}

internal fun validPendingNativeCollectionOrder(
    authoritativeRecordIds: List<String>,
    pendingRecordIds: List<String>?,
): List<String>? = pendingRecordIds?.takeIf { pending ->
    pending.size == authoritativeRecordIds.size &&
        pending.distinct().size == pending.size &&
        pending.toSet() == authoritativeRecordIds.toSet()
}

internal data class NativePendingCollectionReorder(
    val orderedRecordIds: List<String>,
    val recoveryRequested: Boolean,
)

internal fun nativePendingCollectionReorderKey(
    plan: NativeCollectionReorderActionPlan,
    resourceId: String,
): NativePendingMutationKey = NativePendingMutationKey(
    actionId = "$NATIVE_CATEGORY_REORDER_MUTATION_NAMESPACE:${plan.action.id}",
    targetRecordId = resourceId,
)

internal fun encodeNativePendingCollectionReorder(
    orderedRecordIds: List<String>,
    recoveryRequested: Boolean,
): Map<String, String>? {
    if (
        orderedRecordIds.isEmpty() ||
        orderedRecordIds.size > MAX_PENDING_CATEGORY_REORDER_RECORDS ||
        orderedRecordIds.distinct().size != orderedRecordIds.size
    ) {
        return null
    }
    val encodedOrder = JsonArray(orderedRecordIds.map(::JsonPrimitive)).toString()
    if (encodedOrder.length > MAX_PENDING_CATEGORY_REORDER_VALUE_LENGTH) return null
    return mapOf(
        PENDING_CATEGORY_REORDER_ORDER_KEY to encodedOrder,
        PENDING_CATEGORY_REORDER_RECOVERY_KEY to recoveryRequested.toString(),
    )
}

internal fun decodeNativePendingCollectionReorder(
    values: Map<String, String>,
): NativePendingCollectionReorder? {
    if (values.keys != PENDING_CATEGORY_REORDER_KEYS) return null
    val recoveryRequested = when (values[PENDING_CATEGORY_REORDER_RECOVERY_KEY]) {
        "true" -> true
        "false" -> false
        else -> return null
    }
    val encodedOrder = values[PENDING_CATEGORY_REORDER_ORDER_KEY]
        ?.takeIf { encoded -> encoded.length <= MAX_PENDING_CATEGORY_REORDER_VALUE_LENGTH }
        ?: return null
    val elements = runCatching { Json.parseToJsonElement(encodedOrder) as? JsonArray }
        .getOrNull() ?: return null
    val order = elements.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    }
        .takeIf { decoded ->
            decoded.size == elements.size &&
                decoded.isNotEmpty() &&
                decoded.size <= MAX_PENDING_CATEGORY_REORDER_RECORDS &&
                decoded.distinct().size == decoded.size
        }
        ?: return null
    return NativePendingCollectionReorder(order, recoveryRequested)
}

internal const val NATIVE_CATEGORY_REORDER_MUTATION_NAMESPACE = "category-reorder-v1"
private const val PENDING_CATEGORY_REORDER_ORDER_KEY = "orderedRecordIds"
private const val PENDING_CATEGORY_REORDER_RECOVERY_KEY = "recoveryRequested"
private val PENDING_CATEGORY_REORDER_KEYS = setOf(
    PENDING_CATEGORY_REORDER_ORDER_KEY,
    PENDING_CATEGORY_REORDER_RECOVERY_KEY,
)
private const val MAX_PENDING_CATEGORY_REORDER_RECORDS = 4_096
private const val MAX_PENDING_CATEGORY_REORDER_VALUE_LENGTH = 65_536

internal fun nativeVisibleReorderTargetId(
    orderedRecordIds: List<String>,
    rowBounds: Map<String, Rect>,
    pointerPosition: Offset,
    visibleItemKeys: Set<String>,
): String? = orderedRecordIds.firstOrNull { recordId ->
    recordId in visibleItemKeys && rowBounds[recordId]?.contains(pointerPosition) == true
}

internal fun moveNativeCollectionRecordAcrossAdjacentMidpoint(
    orderedRecordIds: List<String>,
    recordId: String,
    pointerY: Float,
    movementY: Float,
    rowBounds: Map<String, Rect>,
): List<String> {
    if (!pointerY.isFinite() || !movementY.isFinite() || movementY == 0f) return orderedRecordIds
    val fromIndex = orderedRecordIds.indexOf(recordId)
    if (fromIndex < 0) return orderedRecordIds
    val direction = if (movementY < 0f) -1 else 1
    val targetIndex = fromIndex + direction
    val targetId = orderedRecordIds.getOrNull(targetIndex) ?: return orderedRecordIds
    val targetBounds = rowBounds[targetId] ?: return orderedRecordIds
    val crossedMidpoint = if (direction < 0) {
        pointerY <= targetBounds.center.y
    } else {
        pointerY >= targetBounds.center.y
    }
    return if (crossedMidpoint) {
        moveNativeCollectionRecord(orderedRecordIds, recordId, direction)
    } else {
        orderedRecordIds
    }
}

/**
 * Returns a bounded snapshot that is safe to place in Android saved instance state.
 *
 * Reorder plans are already capped at 500 records, but the saver repeats that boundary so a
 * malformed or stale restored value cannot inflate the activity state independently of a plan.
 */
internal fun encodeNativeCollectionReorderDraft(
    orderedRecordIds: List<String>,
): List<String>? = orderedRecordIds
    .takeIf { recordIds ->
        recordIds.size in 2..MAX_SAVED_COLLECTION_REORDER_RECORDS &&
            recordIds.distinct().size == recordIds.size &&
            recordIds.all { recordId ->
                recordId.isNotBlank() &&
                    recordId.length <= MAX_SAVED_COLLECTION_REORDER_ID_LENGTH &&
                    recordId.none(Char::isISOControl)
            }
    }
    ?.toList()

/** Restores only a complete permutation of the identities in the current reorder plan. */
internal fun restoreNativeCollectionReorderDraft(
    savedRecordIds: List<String>,
    plannedRecordIds: List<String>,
): List<String> {
    val planned = encodeNativeCollectionReorderDraft(plannedRecordIds) ?: return plannedRecordIds
    val saved = encodeNativeCollectionReorderDraft(savedRecordIds) ?: return planned
    return saved.takeIf { recordIds -> recordIds.toSet() == planned.toSet() } ?: planned
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

private const val MAX_SAVED_COLLECTION_REORDER_RECORDS = 500
private const val MAX_SAVED_COLLECTION_REORDER_ID_LENGTH = 256

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
