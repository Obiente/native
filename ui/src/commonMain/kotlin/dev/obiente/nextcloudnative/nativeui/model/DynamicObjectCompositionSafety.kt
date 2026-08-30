package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Only shape keywords preserved by flattening and inert descriptive annotations are accepted.
// A blacklist would silently drop new validation vocabularies, including 3.1 conditionals.
private val PRESERVED_FLATTENED_OBJECT_KEYS = setOf(
    "allOf", "type", "properties", "required", "additionalProperties",
    "title", "description", "default", "example", "examples", "deprecated", "externalDocs", "\$comment",
)

internal fun JsonObject.isSafeToFlattenObjectShape(
    properties: JsonObject?,
    requiredProperties: List<String>,
    allowImplicitlyOpen: Boolean,
): Boolean {
    if (keys.any { it !in PRESERVED_FLATTENED_OBJECT_KEYS }) return false
    if ("type" in this && get("type") != JsonPrimitive("object")) return false
    if ("properties" in this && properties == null) return false
    if ("required" in this) {
        val required = get("required") as? JsonArray ?: return false
        if (required.any { it !is JsonPrimitive || !it.isString }) return false
    }
    if ("allOf" in this && (get("allOf") as? JsonArray)?.isNotEmpty() != true) return false
    val additionalProperties = get("additionalProperties")
    if (additionalProperties != null) {
        return (additionalProperties as? JsonPrimitive)?.booleanOrNull == false
    }
    return allowImplicitlyOpen || properties == null && requiredProperties.isEmpty()
}
