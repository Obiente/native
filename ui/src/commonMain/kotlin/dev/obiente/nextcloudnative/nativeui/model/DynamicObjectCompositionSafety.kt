package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val UNSUPPORTED_FLATTENED_OBJECT_CONSTRAINTS = setOf(
    "anyOf",
    "dependencies",
    "dependentRequired",
    "dependentSchemas",
    "maxProperties",
    "minProperties",
    "not",
    "oneOf",
    "patternProperties",
    "propertyNames",
    "unevaluatedProperties",
)

internal fun JsonObject.isSafeToFlattenObjectShape(
    properties: JsonObject?,
    requiredProperties: List<String>,
    allowImplicitlyOpen: Boolean,
): Boolean {
    if (keys.any(UNSUPPORTED_FLATTENED_OBJECT_CONSTRAINTS::contains)) return false
    val additionalProperties = get("additionalProperties")
    if (additionalProperties != null) {
        return (additionalProperties as? JsonPrimitive)?.booleanOrNull == false
    }
    return allowImplicitlyOpen || properties == null && requiredProperties.isEmpty()
}
