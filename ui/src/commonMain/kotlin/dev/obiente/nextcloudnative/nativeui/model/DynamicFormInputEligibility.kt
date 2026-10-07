package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A visible form must be able to collect every required body value. */
internal fun DynamicAction.hasMaterializableRequiredFormInputs(form: DynamicForm): Boolean {
    val body = binding.body ?: return true
    val schema = body.schema as? JsonObject ?: return !body.required
    if (listOf("allOf", "anyOf", "oneOf", "not").any(schema::containsKey)) return !body.required
    val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
    if (type != null && type != "object") return !body.required
    val properties = schema["properties"] as? JsonObject
        ?: if (type == "object" && schema["properties"] == null) JsonObject(emptyMap()) else return !body.required
    val requiredValue = schema["required"]
    val requiredArray = requiredValue as? JsonArray
    if (requiredValue != null && requiredArray == null) return false
    val required = requiredArray.orEmpty().map { value ->
        val primitive = value as? JsonPrimitive ?: return false
        if (!primitive.isString) return false
        primitive.contentOrNull?.takeIf { it.isNotBlank() && it == it.trim() } ?: return false
    }
    if (required.distinct().size != required.size || required.any { it !in properties }) return false
    val fields = form.fields.associateBy(FormField::fieldId)
    // OpenAPI applies "required" to a readOnly property only in responses, never in request bodies.
    val writableRequired = required.filterNot { id ->
        ((properties[id] as? JsonObject)?.get("readOnly") as? JsonPrimitive)?.booleanOrNull == true
    }
    return writableRequired.all { id ->
        val field = fields[id] ?: return@all false
        field.repeatableObjectInput != null || field.format in setOf(
            DYNAMIC_INTEGER_ARRAY_FORMAT, DYNAMIC_STRING_ARRAY_FORMAT, DYNAMIC_STRING_LIST_FORMAT,
        ) || field.kind !in setOf(FieldKind.objectValue, FieldKind.unknown, FieldKind.image)
    }
}
