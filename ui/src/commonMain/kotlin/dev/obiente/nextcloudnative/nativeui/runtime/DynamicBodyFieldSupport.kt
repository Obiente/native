package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.isExactDynamicIntegerArraySchema
import dev.obiente.nextcloudnative.nativeui.model.repeatableObjectInputSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun ActionSpec.hasSupportedDynamicArrayBodyField(field: FieldSpec): Boolean {
    val properties = (binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject
        ?: return true
    val property = properties[field.id] as? JsonObject ?: return true
    if ((property["type"] as? JsonPrimitive)?.contentOrNull != "array") return true
    val itemType = ((property["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
    val format = (property["format"] as? JsonPrimitive)?.contentOrNull
    field.repeatableObjectInput?.let { structured ->
        return structured == property.repeatableObjectInputSpec()
    }
    return when (field.format) {
        DYNAMIC_INTEGER_ARRAY_FORMAT -> property.isExactDynamicIntegerArraySchema()
        DYNAMIC_STRING_ARRAY_FORMAT,
        DYNAMIC_STRING_LIST_FORMAT,
        -> itemType == "string" && format == field.format
        else -> false
    }
}
