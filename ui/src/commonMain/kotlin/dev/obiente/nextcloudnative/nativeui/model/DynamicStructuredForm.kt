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
    val observedReadOnlyFieldIds: Set<String> = emptySet(),
) {
    init {
        require(minimumItems in 0..maximumItems)
        require(maximumItems in 1..MAX_REPEATABLE_OBJECT_INPUT_ITEMS)
        require(fields.isNotEmpty() && fields.size <= MAX_REPEATABLE_OBJECT_INPUT_FIELDS)
        require(fields.map(RepeatableObjectInputFieldSpec::id).distinct().size == fields.size)
        require(observedReadOnlyFieldIds.none { id -> fields.any { field -> field.id == id } })
        require(observedReadOnlyFieldIds.all(String::isSafeRepeatableObjectFieldId))
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
                require(
                    row.values.keys.all(fieldsById::containsKey) &&
                        row.nullFieldIds.all(fieldsById::containsKey) &&
                        row.values.keys.none(row.nullFieldIds::contains),
                ) {
                    "Item ${index + 1} contains an undeclared field."
                }
                JsonObject(
                    fields.mapNotNull { field ->
                        if (field.id in row.nullFieldIds) {
                            require(field.nullable) {
                                "Item ${index + 1}: ${field.label} cannot be null."
                            }
                            return@mapNotNull field.id to JsonNull
                        }
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
            val normalizedItem = JsonObject(item.filterKeys { fieldId ->
                fieldId !in observedReadOnlyFieldIds
            })
            val nullFieldIds = normalizedItem.entries
                .filter { (_, value) -> value is JsonNull }
                .mapTo(linkedSetOf()) { (fieldId, _) -> fieldId }
            RepeatableObjectInputRow(
                values = normalizedItem.filterValues { value -> value !is JsonNull }.mapValues { (fieldId, value) ->
                    fields.singleOrNull { field -> field.id == fieldId }
                        ?.wireValue(value, index)
                        ?: error("Item ${index + 1} contains an undeclared field.")
                },
                nullFieldIds = nullFieldIds,
            )
        }
        return canonicalJson(rows)
    }
}

@Serializable
data class RepeatableObjectInputRow(
    val values: Map<String, String> = emptyMap(),
    val nullFieldIds: Set<String> = emptySet(),
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
    val enumLabels: Map<String, String>? = null,
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
        require(enumLabels?.let { labels ->
            enumValues != null &&
                labels.keys == enumValues.toSet() &&
                labels.values.all { label ->
                    label.isNotBlank() && label.length <= MAX_REPEATABLE_OBJECT_INPUT_LABEL_LENGTH
                }
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
                val parsed = value.toExactJsonDecimalOrNull()
                    ?: error("$prefix must be an exact JSON number.")
                minimum?.toExactJsonDecimalOrNull()?.let { lower ->
                    require(parsed >= lower) { "$prefix is below the allowed minimum." }
                }
                maximum?.toExactJsonDecimalOrNull()?.let { upper ->
                    require(parsed <= upper) { "$prefix exceeds the allowed maximum." }
                }
                parsed.primitive
            }
            RepeatableObjectInputScalarKind.Boolean ->
                JsonPrimitive(value.toBooleanStrictOrNull() ?: error("$prefix must be true or false."))
        }
    }

    internal fun wireValue(value: JsonElement, rowIndex: Int): String {
        require(value !is JsonNull) { "Item ${rowIndex + 1}: $label requires explicit null state." }
        val primitive = value as? JsonPrimitive
            ?: error("Item ${rowIndex + 1}: $label must be a scalar value.")
        return when (kind) {
            RepeatableObjectInputScalarKind.String,
            RepeatableObjectInputScalarKind.Enumeration,
            -> primitive.takeIf(JsonPrimitive::isString)?.contentOrNull
            RepeatableObjectInputScalarKind.Integer ->
                primitive.takeUnless(JsonPrimitive::isString)?.longOrNull?.toString()
            RepeatableObjectInputScalarKind.Decimal ->
                primitive.takeUnless(JsonPrimitive::isString)?.contentOrNull
                    ?.takeIf { value -> value.toExactJsonDecimalOrNull() != null }
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
    val readOnlyFieldIds = properties.mapNotNullTo(linkedSetOf()) { (id, element) ->
        val property = element as? JsonObject ?: return@mapNotNullTo null
        id.takeIf { (property["readOnly"] as? JsonPrimitive)?.booleanOrNull == true }
    }
    val fields = properties.filterKeys { id -> id !in readOnlyFieldIds }.mapNotNull { (id, element) ->
        element.toRepeatableObjectInputField(id, id in required)
    }
    if (fields.size != properties.size - readOnlyFieldIds.size || fields.isEmpty()) return null
    return runCatching {
        RepeatableObjectInputSpec(
            minimumItems = declaredMinimum,
            maximumItems = maximum,
            fields = fields,
            observedReadOnlyFieldIds = readOnlyFieldIds,
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
    if ("readOnly" in schema && (schema["readOnly"] as? JsonPrimitive)?.booleanOrNull == null) return null
    if ("writeOnly" in schema && (schema["writeOnly"] as? JsonPrimitive)?.booleanOrNull == null) return null
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
    val enumLabels = (schema[ENUM_LABELS_EXTENSION] as? JsonObject)?.entries
        ?.mapNotNull { (wireValue, labelElement) ->
            (labelElement as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf { label -> label.isNotBlank() && label.length <= MAX_REPEATABLE_OBJECT_INPUT_LABEL_LENGTH }
                ?.let { label -> wireValue to label }
        }
        ?.toMap()
        ?.takeIf { labels -> allowed != null && labels.keys == allowed.toSet() }
    if (ENUM_LABELS_EXTENSION in schema && enumLabels == null) return null
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
                val exactMinimum = minimum?.toExactJsonDecimalOrNull()
                val exactMaximum = maximum?.toExactJsonDecimalOrNull()
                require(exactMinimum != null || minimum == null)
                require(exactMaximum != null || maximum == null)
                require(exactMinimum == null || exactMaximum == null || exactMinimum <= exactMaximum)
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
            enumLabels = enumLabels,
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

/**
 * A bounded, platform-neutral decimal representation used to compare JSON numbers without first
 * rounding them through binary floating point. The original primitive is retained for emission so
 * monetary, scientific, and large integer-like decimal values reach the server byte-for-byte.
 */
private data class ExactJsonDecimal(
    val primitive: JsonPrimitive,
    val negative: Boolean,
    val digits: String,
    val exponent: Int,
) : Comparable<ExactJsonDecimal> {
    override fun compareTo(other: ExactJsonDecimal): Int {
        if (digits == "0" && other.digits == "0") return 0
        if (negative != other.negative) return if (negative) -1 else 1
        val magnitude = compareMagnitude(other)
        return if (negative) -magnitude else magnitude
    }

    private fun compareMagnitude(other: ExactJsonDecimal): Int {
        val decimalLength = digits.length.toLong() + exponent
        val otherDecimalLength = other.digits.length.toLong() + other.exponent
        if (decimalLength != otherDecimalLength) return decimalLength.compareTo(otherDecimalLength)
        val width = maxOf(digits.length, other.digits.length)
        repeat(width) { index ->
            val left = digits.getOrElse(index) { '0' }
            val right = other.digits.getOrElse(index) { '0' }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}

private fun String.toExactJsonDecimalOrNull(): ExactJsonDecimal? {
    if (isEmpty() || length > MAX_REPEATABLE_OBJECT_SCALAR_LENGTH) return null
    val primitive = runCatching { Json.parseToJsonElement(this) as? JsonPrimitive }.getOrNull()
        ?.takeUnless(JsonPrimitive::isString)
        ?.takeIf { parsed -> parsed.contentOrNull == this }
        ?: return null
    val negative = startsWith('-')
    val unsigned = if (negative) substring(1) else this
    val exponentMarker = unsigned.indexOfFirst { character -> character == 'e' || character == 'E' }
    val significand = if (exponentMarker < 0) unsigned else unsigned.substring(0, exponentMarker)
    val declaredExponent = if (exponentMarker < 0) {
        0
    } else {
        unsigned.substring(exponentMarker + 1).toIntOrNull() ?: return null
    }
    val decimalMarker = significand.indexOf('.')
    val fractionLength = if (decimalMarker < 0) 0 else significand.length - decimalMarker - 1
    val rawDigits = significand.replace(".", "")
    val firstSignificant = rawDigits.indexOfFirst { character -> character != '0' }
    if (firstSignificant < 0) {
        return ExactJsonDecimal(primitive, negative = false, digits = "0", exponent = 0)
    }
    val withoutLeadingZeros = rawDigits.substring(firstSignificant)
    val trailingZeros = withoutLeadingZeros.reversed().indexOfFirst { character -> character != '0' }
        .let { count -> if (count < 0) withoutLeadingZeros.length else count }
    val digits = withoutLeadingZeros.dropLast(trailingZeros)
    val exponent = declaredExponent.toLong() - fractionLength + trailingZeros
    if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
    return ExactJsonDecimal(
        primitive = primitive,
        negative = negative,
        digits = digits,
        exponent = exponent.toInt(),
    )
}

private fun String.structuredHumanize(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replaceFirstChar { character -> character.uppercase() }
        .take(MAX_REPEATABLE_OBJECT_INPUT_LABEL_LENGTH)

internal const val DESCRIPTION_ENUM_EXTENSION = "x-nextcloud-native-description-enum"
internal const val ENUM_LABELS_EXTENSION = "x-nextcloud-native-enum-labels"
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
    ENUM_LABELS_EXTENSION,
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
