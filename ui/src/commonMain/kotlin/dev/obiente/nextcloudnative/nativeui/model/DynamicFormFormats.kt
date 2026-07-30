package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Set-like user settings rendered one item per line; duplicate entries are normalized away. */
const val DYNAMIC_STRING_LIST_FORMAT = "nextcloud-string-list"

/**
 * Ordered contract-declared text arrays such as ingredients or instructions.
 *
 * This marker is added only for signed/advertised `type: array` schemas with string items. It
 * never upgrades an untyped object into an editable list, and duplicate entries remain data.
 */
const val DYNAMIC_STRING_ARRAY_FORMAT = "nextcloud-string-array"

/**
 * Ordered contract-declared integer arrays.
 *
 * The marker identifies a `type: array` schema whose direct item schema declares `type: integer`.
 * The generic editor additionally requires a supported exact constraint subset and accepts a
 * bounded JSON array so integer types remain distinguishable from quoted strings, decimals,
 * objects, and other unsupported values.
 */
const val DYNAMIC_INTEGER_ARRAY_FORMAT = "nextcloud-integer-array"

internal const val MAX_DYNAMIC_INTEGER_ARRAY_ITEMS = 256
private const val MAX_DYNAMIC_INTEGER_ARRAY_INPUT_LENGTH = 16 * 1024

internal sealed interface DynamicIntegerArrayParseResult {
    data class Valid(val values: List<Long>) : DynamicIntegerArrayParseResult
    data class Invalid(val message: String) : DynamicIntegerArrayParseResult
}

private data class DynamicIntegerArrayConstraints(
    val minItems: Int = 0,
    val maxItems: Int = MAX_DYNAMIC_INTEGER_ARRAY_ITEMS,
    val uniqueItems: Boolean = false,
    val minimum: Long? = null,
    val maximum: Long? = null,
    val exclusiveMinimum: Long? = null,
    val exclusiveMaximum: Long? = null,
    val multipleOf: Long? = null,
)

private val SUPPORTED_DYNAMIC_INTEGER_ARRAY_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
    "example",
    "examples",
    "format",
    "items",
    "maxItems",
    "minItems",
    "nullable",
    "readOnly",
    "title",
    "type",
    "uniqueItems",
    "writeOnly",
    "x-nextcloud-native-wire-name",
)

private val SUPPORTED_DYNAMIC_INTEGER_ITEM_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
    "example",
    "examples",
    "exclusiveMaximum",
    "exclusiveMinimum",
    "maximum",
    "minimum",
    "multipleOf",
    "nullable",
    "readOnly",
    "title",
    "type",
    "writeOnly",
)

internal fun JsonElement?.isExactDynamicIntegerArraySchema(): Boolean {
    return dynamicIntegerArrayConstraints() != null
}

private fun JsonElement?.dynamicIntegerArrayConstraints(): DynamicIntegerArrayConstraints? {
    val schema = this as? JsonObject ?: return null
    if ((schema["type"] as? JsonPrimitive)?.contentOrNull != "array") return null
    if ((schema["format"] as? JsonPrimitive)?.contentOrNull != DYNAMIC_INTEGER_ARRAY_FORMAT) return null
    if (schema.keys.any { it !in SUPPORTED_DYNAMIC_INTEGER_ARRAY_KEYS }) return null
    val items = schema["items"] as? JsonObject ?: return null
    if ((items["type"] as? JsonPrimitive)?.contentOrNull != "integer") return null
    if (items.keys.any { it !in SUPPORTED_DYNAMIC_INTEGER_ITEM_KEYS }) return null

    val minItems = schema.exactNonNegativeInt("minItems") ?: if ("minItems" in schema) return null else 0
    val declaredMaxItems = schema.exactNonNegativeInt("maxItems")
        ?: if ("maxItems" in schema) return null else MAX_DYNAMIC_INTEGER_ARRAY_ITEMS
    if (minItems > declaredMaxItems || minItems > MAX_DYNAMIC_INTEGER_ARRAY_ITEMS) return null
    val uniqueItems = schema.exactBoolean("uniqueItems")
        ?: if ("uniqueItems" in schema) return null else false

    var minimum = items.exactLong("minimum") ?: if ("minimum" in items) return null else null
    var maximum = items.exactLong("maximum") ?: if ("maximum" in items) return null else null
    var exclusiveMinimum = items.exactLong("exclusiveMinimum")
    var exclusiveMaximum = items.exactLong("exclusiveMaximum")
    if ("exclusiveMinimum" in items && exclusiveMinimum == null) {
        val exclusive = items.exactBoolean("exclusiveMinimum") ?: return null
        if (exclusive) {
            exclusiveMinimum = minimum ?: return null
            minimum = null
        }
    }
    if ("exclusiveMaximum" in items && exclusiveMaximum == null) {
        val exclusive = items.exactBoolean("exclusiveMaximum") ?: return null
        if (exclusive) {
            exclusiveMaximum = maximum ?: return null
            maximum = null
        }
    }
    val multipleOf = items.exactLong("multipleOf") ?: if ("multipleOf" in items) return null else null
    if (multipleOf != null && multipleOf <= 0L) return null

    return DynamicIntegerArrayConstraints(
        minItems = minItems,
        maxItems = minOf(declaredMaxItems, MAX_DYNAMIC_INTEGER_ARRAY_ITEMS),
        uniqueItems = uniqueItems,
        minimum = minimum,
        maximum = maximum,
        exclusiveMinimum = exclusiveMinimum,
        exclusiveMaximum = exclusiveMaximum,
        multipleOf = multipleOf,
    )
}

private fun JsonObject.exactLong(name: String): Long? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun JsonObject.exactNonNegativeInt(name: String): Int? =
    exactLong(name)?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()

private fun JsonObject.exactBoolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

internal fun parseDynamicIntegerArrayInput(
    value: String,
    schema: JsonElement? = null,
): DynamicIntegerArrayParseResult {
    val constraints = if (schema == null) {
        DynamicIntegerArrayConstraints()
    } else {
        schema.dynamicIntegerArrayConstraints()
            ?: return DynamicIntegerArrayParseResult.Invalid(
                "This integer list does not have a supported exact contract schema.",
            )
    }
    if (value.length > MAX_DYNAMIC_INTEGER_ARRAY_INPUT_LENGTH) {
        return DynamicIntegerArrayParseResult.Invalid("This integer list is too large.")
    }
    val array = runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonArray
        ?: return DynamicIntegerArrayParseResult.Invalid("Enter a JSON array of whole numbers.")
    if (array.size < constraints.minItems) {
        return DynamicIntegerArrayParseResult.Invalid(
            "Use at least ${constraints.minItems} whole numbers.",
        )
    }
    if (array.size > constraints.maxItems) {
        return DynamicIntegerArrayParseResult.Invalid(
            "Use at most ${constraints.maxItems} whole numbers.",
        )
    }
    val values = array.map { element ->
        val scalar = element as? JsonPrimitive
            ?: return DynamicIntegerArrayParseResult.Invalid("Use only whole numbers in this array.")
        val parsed = scalar.takeUnless { it.isString }?.longOrNull
            ?: return DynamicIntegerArrayParseResult.Invalid("Use only whole numbers in this array.")
        parsed
    }
    if (constraints.uniqueItems && values.distinct().size != values.size) {
        return DynamicIntegerArrayParseResult.Invalid("Use each whole number only once.")
    }
    values.forEach { item ->
        if (constraints.minimum?.let { item < it } == true) {
            return DynamicIntegerArrayParseResult.Invalid(
                "Use whole numbers greater than or equal to ${constraints.minimum}.",
            )
        }
        if (constraints.maximum?.let { item > it } == true) {
            return DynamicIntegerArrayParseResult.Invalid(
                "Use whole numbers less than or equal to ${constraints.maximum}.",
            )
        }
        if (constraints.exclusiveMinimum?.let { item <= it } == true) {
            return DynamicIntegerArrayParseResult.Invalid(
                "Use whole numbers greater than ${constraints.exclusiveMinimum}.",
            )
        }
        if (constraints.exclusiveMaximum?.let { item >= it } == true) {
            return DynamicIntegerArrayParseResult.Invalid(
                "Use whole numbers less than ${constraints.exclusiveMaximum}.",
            )
        }
        if (constraints.multipleOf?.let { item % it != 0L } == true) {
            return DynamicIntegerArrayParseResult.Invalid(
                "Use whole numbers divisible by ${constraints.multipleOf}.",
            )
        }
    }
    return DynamicIntegerArrayParseResult.Valid(values)
}
