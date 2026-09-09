package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.size
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun nativeScalarRelationClearChoice(field: FieldSpec): NativeRelationOption? =
    NativeRelationOption(
        value = "",
        label = "None",
        supportingText = "Clear selection",
    ).takeIf {
        !field.required &&
            field.format !in setOf(
                DYNAMIC_INTEGER_ARRAY_FORMAT,
                DYNAMIC_STRING_ARRAY_FORMAT,
                DYNAMIC_STRING_LIST_FORMAT,
            )
    }

internal data class NativeRelationOptionWindow(
    val options: List<NativeRelationOption>,
    val hasMore: Boolean,
)

internal fun nativeRelationOptionWindow(
    options: List<NativeRelationOption>,
    query: String,
): NativeRelationOptionWindow {
    val boundedQuery = query.take(NATIVE_RELATION_MAX_QUERY_LENGTH)
    val matches = options.asSequence()
        .filter { option ->
            boundedQuery.isBlank() ||
                option.label.contains(boundedQuery, ignoreCase = true) ||
                option.supportingText?.contains(boundedQuery, ignoreCase = true) == true
        }
        .take(NATIVE_RELATION_OPTION_WINDOW_SIZE + 1)
        .toList()
    return NativeRelationOptionWindow(
        options = matches.take(NATIVE_RELATION_OPTION_WINDOW_SIZE),
        hasMore = matches.size > NATIVE_RELATION_OPTION_WINDOW_SIZE,
    )
}

internal fun retainSelectedNativeRelationOptions(
    retained: List<NativeRelationOption>,
    available: List<NativeRelationOption>,
    selectedValues: Collection<String>,
): List<NativeRelationOption> {
    val selected = selectedValues.asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .take(NATIVE_RELATION_RETAINED_SELECTION_LIMIT)
        .toSet()
    if (selected.isEmpty()) return emptyList()
    return (available + retained).asSequence()
        .filter { option -> option.value in selected }
        .distinctBy(NativeRelationOption::value)
        .take(NATIVE_RELATION_RETAINED_SELECTION_LIMIT)
        .toList()
}


internal fun String.nativeRelationSelectedValues(format: String?): List<String> {
    if (isBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(this) }.getOrNull() as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val scalar = element as? JsonPrimitive ?: return@mapNotNull null
        when (format) {
            DYNAMIC_INTEGER_ARRAY_FORMAT -> scalar.takeUnless(JsonPrimitive::isString)?.contentOrNull
            DYNAMIC_STRING_ARRAY_FORMAT -> scalar.takeIf(JsonPrimitive::isString)?.contentOrNull
            else -> null
        }
    }.distinct()
}

internal fun List<String>.toNativeRelationArray(format: String?): String = when (format) {
    DYNAMIC_INTEGER_ARRAY_FORMAT -> joinToString(prefix = "[", postfix = "]", separator = ",")
    DYNAMIC_STRING_ARRAY_FORMAT -> JsonArray(map(::JsonPrimitive)).toString()
    else -> ""
}


internal const val NATIVE_RELATION_SEARCH_THRESHOLD = 8
internal const val NATIVE_RELATION_MAX_QUERY_LENGTH = 120
internal const val NATIVE_RELATION_RETAINED_SELECTION_LIMIT = 64
internal const val NATIVE_RELATION_OPTION_WINDOW_SIZE = 40
