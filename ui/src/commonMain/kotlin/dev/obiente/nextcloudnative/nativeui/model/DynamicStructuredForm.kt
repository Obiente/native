package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Native editor marker for a bounded array whose entries are exact contract-declared objects.
 *
 * A renderer should edit [RepeatableObjectInputRow] values directly. JSON is produced only at the
 * action boundary by [encode], so users never need to author an opaque JSON document.
 */
const val DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT: String = "nextcloud-repeatable-object-array"

@Serializable
data class RepeatableObjectInputSpec(
    val minimumItems: Int,
    val maximumItems: Int,
    val fields: List<RepeatableObjectInputFieldSpec>,
) {
    init {
        require(minimumItems in 0..maximumItems)
        require(maximumItems in 1..MAX_REPEATABLE_OBJECT_INPUT_ITEMS)
        require(fields.isNotEmpty() && fields.size <= MAX_REPEATABLE_OBJECT_INPUT_FIELDS)
        require(fields.map(RepeatableObjectInputFieldSpec::id).distinct().size == fields.size)
    }

    fun encode(rows: List<RepeatableObjectInputRow>): String =
        canonicalJson(rows).toString()

    internal fun canonicalJson(rows: List<RepeatableObjectInputRow>): JsonArray {
        require(rows.size in minimumItems..maximumItems) {
            "Enter between $minimumItems and $maximumItems items."
        }
        val fieldsById = fields.associateBy(RepeatableObjectInputFieldSpec::id)
        return JsonArray(
            rows.mapIndexed { index, row ->
                require(row.values.keys.all(fieldsById::containsKey)) {
                    "Item ${index + 1} contains an undeclared field."
                }
                JsonObject(
                    fields.mapNotNull { field ->
                        val supplied = row.values[field.id]
                        if (supplied == null || supplied.isBlank()) {
                            require(!field.required) {
                                "Item ${index + 1}: ${field.label} is required."
                            }
                            null
                        } else {
                            field.id to field.canonicalJsonValue(supplied, index)
                        }
                    }.toMap(),
                )
            },
        )
    }

    internal fun canonicalJson(encoded: String): JsonArray {
        require(encoded.length <= MAX_REPEATABLE_OBJECT_INPUT_LENGTH) {
            "This structured value is too large."
        }
        val array = runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonArray
            ?: error("This value is not a structured item list.")
        val rows = array.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: error("Item ${index + 1} must be an object.")
            RepeatableObjectInputRow(
                item.mapValues { (fieldId, value) ->
                    fields.singleOrNull { field -> field.id == fieldId }
                        ?.wireValue(value, index)
                        ?: error("Item ${index + 1} contains an undeclared field.")
                },
            )
        }
        return canonicalJson(rows)
    }
}

@Serializable
data class RepeatableObjectInputRow(
    val values: Map<String, String> = emptyMap(),
)

@Serializable
data class RepeatableObjectInputFieldSpec(
    val id: String,
    val label: String,
    val kind: RepeatableObjectInputScalarKind,
    val required: Boolean,
    val nullable: Boolean = false,
    val format: String? = null,
    val enumValues: List<String>? = null,
    val minimum: String? = null,
    val maximum: String? = null,
    val minimumLength: Int? = null,
    val maximumLength: Int? = null,
) {
    init {
        require(id.isSafeRepeatableObjectFieldId())
        require(label.isNotBlank() && label.length <= MAX_REPEATABLE_OBJECT_INPUT_LABEL_LENGTH)
        require(enumValues?.let { values ->
            values.isNotEmpty() &&
                values.size <= MAX_REPEATABLE_OBJECT_ENUM_VALUES &&
                values.distinct().size == values.size &&
                values.all(String::isSafeRepeatableObjectScalar)
        } != false)
        require(minimumLength?.let { it >= 0 } != false)
        require(maximumLength?.let { it >= 0 } != false)
        require(
            minimumLength == null || maximumLength == null || minimumLength <= maximumLength,
        )
    }

    internal fun canonicalJsonValue(value: String, rowIndex: Int): JsonElement {
        val prefix = "Item ${rowIndex + 1}: $label"
        require(value.length <= MAX_REPEATABLE_OBJECT_SCALAR_LENGTH && value.none(Char::isISOControl)) {
            "$prefix contains unsupported text."
        }
        return when (kind) {
            RepeatableObjectInputScalarKind.String -> {
                minimumLength?.let { minimum ->
                    require(value.length >= minimum) { "$prefix is too short." }
                }
                maximumLength?.let { maximum ->
                    require(value.length <= maximum) { "$prefix is too long." }
                }
                JsonPrimitive(value)
            }
            RepeatableObjectInputScalarKind.Enumeration -> {
                require(value in enumValues.orEmpty()) { "$prefix is not an allowed value." }
                JsonPrimitive(value)
            }
            RepeatableObjectInputScalarKind.Integer -> {
                val parsed = value.toLongOrNull() ?: error("$prefix must be a whole number.")
                minimum?.toLongOrNull()?.let { lower ->
                    require(parsed >= lower) { "$prefix is below the allowed minimum." }
                }
                maximum?.toLongOrNull()?.let { upper ->
                    require(parsed <= upper) { "$prefix exceeds the allowed maximum." }
                }
                JsonPrimitive(parsed)
            }
            RepeatableObjectInputScalarKind.Decimal -> {
                val parsed = value.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: error("$prefix must be a finite number.")
                minimum?.toDoubleOrNull()?.let { lower ->
                    require(parsed >= lower) { "$prefix is below the allowed minimum." }
                }
                maximum?.toDoubleOrNull()?.let { upper ->
                    require(parsed <= upper) { "$prefix exceeds the allowed maximum." }
                }
                JsonPrimitive(parsed)
            }
            RepeatableObjectInputScalarKind.Boolean ->
                JsonPrimitive(value.toBooleanStrictOrNull() ?: error("$prefix must be true or false."))
        }
    }

    internal fun wireValue(value: JsonElement, rowIndex: Int): String {
        if (value is JsonNull) {
            require(nullable && !required) {
                "Item ${rowIndex + 1}: $label cannot be null."
            }
            return ""
        }
        val primitive = value as? JsonPrimitive
            ?: error("Item ${rowIndex + 1}: $label must be a scalar value.")
        return when (kind) {
            RepeatableObjectInputScalarKind.String,
            RepeatableObjectInputScalarKind.Enumeration,
            -> primitive.takeIf(JsonPrimitive::isString)?.contentOrNull
            RepeatableObjectInputScalarKind.Integer ->
                primitive.takeUnless(JsonPrimitive::isString)?.longOrNull?.toString()
            RepeatableObjectInputScalarKind.Decimal ->
                primitive.takeUnless(JsonPrimitive::isString)?.doubleOrNull?.toString()
            RepeatableObjectInputScalarKind.Boolean ->
                primitive.takeUnless(JsonPrimitive::isString)?.booleanOrNull?.toString()
        } ?: error("Item ${rowIndex + 1}: $label has the wrong scalar type.")
    }
}

@Serializable
enum class RepeatableObjectInputScalarKind {
    String,
    Integer,
    Decimal,
    Boolean,
    Enumeration,
}

internal fun JsonElement?.repeatableObjectInputSpec(): RepeatableObjectInputSpec? {
    val array = this as? JsonObject ?: return null
    if ((array["type"] as? JsonPrimitive)?.contentOrNull != "array") return null
    if (!array.keys.all(REPEATABLE_OBJECT_ARRAY_SCHEMA_KEYS::contains)) return null
    if ((array["format"] as? JsonPrimitive)?.contentOrNull != DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT) {
        return null
    }
    if ("nullable" in array && (array["nullable"] as? JsonPrimitive)?.booleanOrNull == null) return null
    if ("uniqueItems" in array) {
        val uniqueItems = (array["uniqueItems"] as? JsonPrimitive)?.booleanOrNull ?: return null
        if (uniqueItems) return null
    }
    if (!array.hasOptionalNonNegativeStructuredInt("minItems")) return null
    if (!array.hasOptionalNonNegativeStructuredInt("maxItems")) return null
    val declaredMinimum = array.nonNegativeStructuredInt("minItems") ?: 0
    val declaredMaximum = array.nonNegativeStructuredInt("maxItems")
        ?: MAX_REPEATABLE_OBJECT_INPUT_ITEMS
    val maximum = minOf(declaredMaximum, MAX_REPEATABLE_OBJECT_INPUT_ITEMS)
    if (declaredMinimum > maximum) return null
    val item = array["items"] as? JsonObject ?: return null
    if ((item["type"] as? JsonPrimitive)?.contentOrNull != "object") return null
    if (!item.keys.all(REPEATABLE_OBJECT_ITEM_SCHEMA_KEYS::contains)) return null
    if (
        "additionalProperties" in item &&
        (item["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == null
    ) {
        return null
    }
    if ((item["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == true) return null
    val properties = item["properties"] as? JsonObject ?: return null
    if (properties.isEmpty() || properties.size > MAX_REPEATABLE_OBJECT_INPUT_FIELDS) return null
    val required = item.structuredRequiredProperties() ?: return null
    if (!required.all(properties::containsKey)) return null
    val fields = properties.mapNotNull { (id, element) ->
        element.toRepeatableObjectInputField(id, id in required)
    }
    if (fields.size != properties.size) return null
    return runCatching {
        RepeatableObjectInputSpec(
            minimumItems = declaredMinimum,
            maximumItems = maximum,
            fields = fields,
        )
    }.getOrNull()
}

private fun JsonElement.toRepeatableObjectInputField(
    id: String,
    required: Boolean,
): RepeatableObjectInputFieldSpec? {
    val schema = this as? JsonObject ?: return null
    if (!schema.keys.all(REPEATABLE_OBJECT_SCALAR_SCHEMA_KEYS::contains)) return null
    val type = (schema["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    if ("nullable" in schema && (schema["nullable"] as? JsonPrimitive)?.booleanOrNull == null) return null
    if (
        "format" in schema &&
        (schema["format"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull == null
    ) {
        return null
    }
    val nullable = (schema["nullable"] as? JsonPrimitive)?.booleanOrNull == true
    val enumValues = schema.structuredStringEnum()
    if ("enum" in schema && enumValues == null) return null
    val recoveredEnum = schema[DESCRIPTION_ENUM_EXTENSION]
        ?.let { it as? JsonArray }
        ?.mapNotNull { value -> (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
        ?.takeIf { values -> values.isNotEmpty() && values.distinct().size == values.size }
    if (DESCRIPTION_ENUM_EXTENSION in schema && recoveredEnum == null) return null
    val allowed = enumValues ?: recoveredEnum
    val kind = when (type) {
        "string" -> if (allowed != null) {
            RepeatableObjectInputScalarKind.Enumeration
        } else {
            RepeatableObjectInputScalarKind.String
        }
        "integer" -> RepeatableObjectInputScalarKind.Integer
        "number" -> RepeatableObjectInputScalarKind.Decimal
        "boolean" -> RepeatableObjectInputScalarKind.Boolean
        else -> return null
    }
    return runCatching {
        val minimum = (schema["minimum"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.contentOrNull
        val maximum = (schema["maximum"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.contentOrNull
        val minimumLength = schema.nonNegativeStructuredInt("minLength")
        val maximumLength = schema.nonNegativeStructuredInt("maxLength")
        require("minimum" !in schema || minimum != null)
        require("maximum" !in schema || maximum != null)
        require(schema.hasOptionalNonNegativeStructuredInt("minLength"))
        require(schema.hasOptionalNonNegativeStructuredInt("maxLength"))
        when (type) {
            "integer" -> {
                require(minimum?.toLongOrNull() != null || minimum == null)
                require(maximum?.toLongOrNull() != null || maximum == null)
                require(minimum == null || maximum == null || minimum.toLong() <= maximum.toLong())
            }
            "number" -> {
                require(minimum?.toDoubleOrNull()?.isFinite() == true || minimum == null)
                require(maximum?.toDoubleOrNull()?.isFinite() == true || maximum == null)
                require(minimum == null || maximum == null || minimum.toDouble() <= maximum.toDouble())
            }
            else -> require("minimum" !in schema && "maximum" !in schema)
        }
        RepeatableObjectInputFieldSpec(
            id = id,
            label = (schema["title"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() && it.length <= MAX_REPEATABLE_OBJECT_INPUT_LABEL_LENGTH }
                ?: id.structuredHumanize(),
            kind = kind,
            required = required,
            nullable = nullable,
            format = (schema["format"] as? JsonPrimitive)?.contentOrNull,
            enumValues = allowed,
            minimum = minimum,
            maximum = maximum,
            minimumLength = minimumLength,
            maximumLength = maximumLength,
        )
    }.getOrNull()
}

private fun JsonObject.structuredRequiredProperties(): Set<String>? {
    val element = this["required"] ?: return emptySet()
    val array = element as? JsonArray ?: return null
    val values = array.mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    }
    return values.toSet().takeIf { values.size == array.size && values.distinct().size == values.size }
}

private fun JsonObject.structuredStringEnum(): List<String>? {
    val element = this["enum"] ?: return null
    val array = element as? JsonArray ?: return null
    val values = array.mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    }
    return values.takeIf {
        values.size == array.size &&
            values.isNotEmpty() &&
            values.size <= MAX_REPEATABLE_OBJECT_ENUM_VALUES &&
            values.distinct().size == values.size &&
            values.all(String::isSafeRepeatableObjectScalar)
    }
}

private fun JsonObject.nonNegativeStructuredInt(name: String): Int? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.contentOrNull
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

private fun JsonObject.hasOptionalNonNegativeStructuredInt(name: String): Boolean =
    name !in this || nonNegativeStructuredInt(name) != null

private fun String.isSafeRepeatableObjectFieldId(): Boolean =
    length in 1..128 && first().let { it.isLetter() || it == '_' } &&
        all { it.isLetterOrDigit() || it == '_' || it == '-' }

private fun String.isSafeRepeatableObjectScalar(): Boolean =
    length in 1..MAX_REPEATABLE_OBJECT_SCALAR_LENGTH && none(Char::isISOControl)

private fun String.structuredHumanize(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replaceFirstChar { character -> character.uppercase() }
        .take(MAX_REPEATABLE_OBJECT_INPUT_LABEL_LENGTH)

internal const val DESCRIPTION_ENUM_EXTENSION = "x-nextcloud-native-description-enum"
private const val MAX_REPEATABLE_OBJECT_INPUT_ITEMS = 64
private const val MAX_REPEATABLE_OBJECT_INPUT_FIELDS = 16
private const val MAX_REPEATABLE_OBJECT_ENUM_VALUES = 32
private const val MAX_REPEATABLE_OBJECT_SCALAR_LENGTH = 4_096
private const val MAX_REPEATABLE_OBJECT_INPUT_LENGTH = 256 * 1024
private const val MAX_REPEATABLE_OBJECT_INPUT_LABEL_LENGTH = 160
private val REPEATABLE_OBJECT_SCALAR_SCHEMA_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
    "enum",
    "example",
    "examples",
    "format",
    "maxLength",
    "maximum",
    "minLength",
    "minimum",
    "nullable",
    "readOnly",
    "title",
    "type",
    "writeOnly",
    DESCRIPTION_ENUM_EXTENSION,
)
private val REPEATABLE_OBJECT_ARRAY_SCHEMA_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
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
)
private val REPEATABLE_OBJECT_ITEM_SCHEMA_KEYS = setOf(
    "\$comment",
    "additionalProperties",
    "deprecated",
    "description",
    "properties",
    "readOnly",
    "required",
    "title",
    "type",
    "writeOnly",
)
