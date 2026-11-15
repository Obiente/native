package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

internal data class StaticPhpParameter(
    val name: String,
    val type: String,
    val required: Boolean = true,
    val readUnionTypes: List<String> = emptyList(),
    /** True for `?T` and unions containing `null`. */
    val nullable: Boolean = false,
    /** The declared default when it is a single literal; null when absent or computed. */
    val defaultValue: PhpValue? = null,
)

/**
 * Parses scalar PHP controller arguments, with scalar unions enabled only for GET evidence.
 * Unsupported signatures simply do not
 * contribute query parameters; no PHP source is evaluated and no default expression is used.
 */
internal fun serializableParameters(
    tokens: List<PhpToken>,
    methodNameIndex: Int,
    allowUnsupportedOptional: Boolean = true,
    allowScalarUnions: Boolean = false,
): List<StaticPhpParameter>? {
    val signature = methodParameterTokens(tokens, methodNameIndex) ?: return null
    if (signature.isEmpty()) return emptyList()
    val segments = mutableListOf<List<PhpToken>>()
    var start = 0
    var nesting = 0
    signature.forEachIndexed { index, token ->
        val symbol = token as? PhpToken.Symbol
        when (symbol?.value) {
            '(', '[', '{' -> nesting += 1
            ')', ']', '}' -> {
                nesting -= 1
                if (nesting < 0) return null
            }
            ',' -> if (nesting == 0) {
                segments += signature.subList(start, index)
                start = index + 1
            }
        }
    }
    if (nesting != 0) return null
    if (start < signature.size) {
        segments += signature.subList(start, signature.size)
    }
    if (segments.isEmpty()) return null
    return segments.mapNotNull { segment ->
        parseSerializableParameter(segment, allowScalarUnions) ?: if (allowUnsupportedOptional && segment.hasOptionalDefault()) {
            null
        } else {
            // Never erase an unknown required argument: doing so could make a write or a
            // parameterized read look callable without all of its required inputs.
            return null
        }
    }
}

private fun List<PhpToken>.hasOptionalDefault(): Boolean {
    var nesting = 0
    forEach { token ->
        val symbol = token as? PhpToken.Symbol ?: return@forEach
        when (symbol.value) {
            '(', '[', '{' -> nesting += 1
            ')', ']', '}' -> nesting -= 1
            '=' -> if (nesting == 0) return true
        }
    }
    return false
}

internal fun parseSerializableParameter(
    tokens: List<PhpToken>,
    allowScalarUnions: Boolean = false,
): StaticPhpParameter? {
    val dollarIndex = tokens.indexOfFirst { it is PhpToken.Symbol && it.value == '$' }
    if (dollarIndex <= 0) return null
    val declared = tokens.take(dollarIndex)
    val nullable = (declared.firstOrNull() as? PhpToken.Symbol)?.value == '?'
    val typeTokens = if (nullable) declared.drop(1) else declared
    if (typeTokens.isEmpty() || typeTokens.size % 2 == 0) return null
    if (typeTokens.withIndex().any { (index, token) ->
            if (index % 2 == 0) token !is PhpToken.Word
            else token !is PhpToken.Symbol || token.value != '|'
        }) return null
    val types = typeTokens.filterIsInstance<PhpToken.Word>().map { it.value.lowercase() }
    val union = types.size > 1
    if (types.distinct().size != types.size || (nullable && union)) return null
    if (union) {
        if (!allowScalarUnions || types.any { it !in STATIC_SCALAR_UNION_TYPES } || types == listOf("null")) return null
    } else if (types.single() !in STATIC_SERIALIZABLE_PARAMETER_TYPES) return null
    val name = (tokens.getOrNull(dollarIndex + 1) as? PhpToken.Word)?.value
        ?.takeIf(::isSafePhpParameterName) ?: return null
    val remainder = tokens.drop(dollarIndex + 2)
    val required = when {
        remainder.isEmpty() -> true
        remainder.size > 1 && (remainder.firstOrNull() as? PhpToken.Symbol)?.value == '=' -> false
        else -> return null
    }
    val defaultValue = if (required) null else remainder.drop(1).literalDefault()
    val schemaTypes = when {
        union -> types
        nullable && allowScalarUnions && types.single() != "array" -> types + "null"
        else -> emptyList()
    }
    return StaticPhpParameter(
        name = name,
        type = types.first(),
        required = required,
        readUnionTypes = schemaTypes,
        nullable = nullable || "null" in types,
        defaultValue = defaultValue,
    )
}

private fun List<PhpToken>.literalDefault(): PhpValue? {
    val first = firstOrNull()
    return when {
        size == 1 && first is PhpToken.Text -> if (first.literal) PhpValue.Text(first.value) else null
        size == 1 && first is PhpToken.Word -> when {
            first.value.lowercase() == "null" -> PhpValue.Null
            first.value.lowercase() == "true" -> PhpValue.Bool(true)
            first.value.lowercase() == "false" -> PhpValue.Bool(false)
            first.value.all(Char::isDigit) -> first.value.toLongOrNull()?.let(PhpValue::Integer)
            else -> null
        }
        size == 2 && (first as? PhpToken.Symbol)?.value == '-' ->
            (get(1) as? PhpToken.Word)?.value?.toLongOrNull()?.let { PhpValue.Integer(-it) }
        size == 2 && (first as? PhpToken.Symbol)?.value == '[' && (get(1) as? PhpToken.Symbol)?.value == ']' ->
            PhpValue.Array(emptyList())
        else -> null
    }
}

/** The literal default as a JSON value for an OpenAPI `default`, or null when not representable. */
internal fun StaticPhpParameter.jsonDefault(): Any? = when (val value = defaultValue) {
    is PhpValue.Text -> value.value.takeIf { type == "string" }
    is PhpValue.Integer -> value.value.takeIf { type == "int" || type == "float" }
    is PhpValue.Bool -> value.value.takeIf { type == "bool" }
    else -> null
}

/** All non-path inputs are nullable and default to null: a structural merge hint, not a guarantee. */
internal fun List<StaticPhpParameter>.declaresNullMergeHint(): Boolean =
    isNotEmpty() && all { parameter -> parameter.nullable && parameter.defaultValue == PhpValue.Null }

private fun methodParameterTokens(
    tokens: List<PhpToken>,
    methodNameIndex: Int,
): List<PhpToken>? {
    val open = (methodNameIndex + 1 until tokens.size).firstOrNull { index ->
        (tokens[index] as? PhpToken.Symbol)?.value == '('
    } ?: return null
    var depth = 0
    for (index in open until tokens.size) {
        val symbol = tokens[index] as? PhpToken.Symbol ?: continue
        if (symbol.value == '(') depth += 1
        if (symbol.value == ')') {
            depth -= 1
            if (depth == 0) return tokens.subList(open + 1, index)
        }
    }
    return null
}

private fun isSafePhpParameterName(name: String): Boolean =
    name.length in 1..64 && name.first().let { it.isLetter() || it == '_' } &&
        name.all { it.isLetterOrDigit() || it == '_' }

internal fun singleSerializableParameter(
    tokens: List<PhpToken>,
    methodNameIndex: Int,
): StaticPhpParameter? = methodParameterTokens(tokens, methodNameIndex)
    ?.let { parseSerializableParameter(it) }
    ?.takeIf(StaticPhpParameter::required)

internal fun StaticPhpParameter.toOpenApiSchema(): JSONObject =
    if (readUnionTypes.isNotEmpty()) {
        JSONObject().put("anyOf", JSONArray(readUnionTypes.map(::phpTypeSchema)))
    } else phpTypeSchema(type)

private fun phpTypeSchema(type: String): JSONObject = when (type) {
    "bool" -> JSONObject().put("type", "boolean")
    "int" -> JSONObject().put("type", "integer")
    "float" -> JSONObject().put("type", "number")
    "null" -> JSONObject().put("type", "null")
    "array" -> JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
    else -> JSONObject().put("type", "string")
}

private val STATIC_SERIALIZABLE_PARAMETER_TYPES = setOf("array", "bool", "float", "int", "string")
private val STATIC_SCALAR_UNION_TYPES = setOf("bool", "float", "int", "string", "null")
