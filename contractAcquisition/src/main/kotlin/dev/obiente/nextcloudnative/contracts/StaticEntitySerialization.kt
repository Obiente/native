package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Field names an entity in lib/Db serializes, read from a `jsonSerialize()` or `toAPI()` method
 * whose only top-level `return` is a literal array with literal string keys. Every key is present
 * in every serialization, so all keys are required. A value is typed only from what the package
 * states: an entity getter or property typed by `addType()` in a constructor (including inherited
 * entity constructors in the package and the framework's integer `id`), a scalar cast, a comparison,
 * or a literal. Everything else stays an unconstrained schema.
 */
internal data class StaticEntitySerialization(
    val className: String,
    /** The serializing method, `jsonSerialize` or `toAPI`. */
    val method: String,
    val schema: JSONObject,
    /** True when the class implements JsonSerializable, so the framework uses [schema] for JSON. */
    val jsonSerializable: Boolean,
)

internal fun readEntitySerializations(declarations: List<PhpClassDeclaration>): List<StaticEntitySerialization> {
    val byName = declarations.associateBy(PhpClassDeclaration::qualifiedName)
    return declarations.flatMap { declaration ->
        val types = declaration.entityFieldTypes(byName, depth = 0) ?: return@flatMap emptyList()
        val jsonSerializable = declaration.interfaces.any { declaration.resolveClass(it) == "JsonSerializable" }
        declaration.methods
            .filter { it.public && !it.static && it.name.lowercase() in SERIALIZING_METHODS }
            .mapNotNull { method ->
                val schema = declaration.serializationSchema(method, types) ?: return@mapNotNull null
                StaticEntitySerialization(
                    className = declaration.qualifiedName,
                    method = method.name,
                    schema = schema,
                    jsonSerializable = jsonSerializable && method.name.equals("jsonSerialize", ignoreCase = true),
                )
            }
    }.take(MAX_ENTITY_SERIALIZATIONS)
}

/**
 * Lower-case property name to entity column type, or null when the class is not a framework
 * entity (directly or through package classes).
 */
private fun PhpClassDeclaration.entityFieldTypes(
    byName: Map<String, PhpClassDeclaration>,
    depth: Int,
): Map<String, String>? {
    if (depth > MAX_ENTITY_INHERITANCE_DEPTH) return null
    val base = baseName?.let(::resolveClass) ?: return null
    val inherited = if (base == FRAMEWORK_ENTITY) {
        mapOf("id" to "integer")
    } else {
        byName[base]?.entityFieldTypes(byName, depth + 1) ?: return null
    }
    val constructor = methods.firstOrNull { it.name.equals("__construct", ignoreCase = true) } ?: return inherited
    val body = methodBodyTokens(tokens, constructor.nameIndex) ?: return inherited
    val declared = linkedMapOf<String, String>()
    var index = 0
    while (index < body.size) {
        val match = body.addTypeAt(index, this)
        if (match != null) {
            declared[match.first.lowercase()] = match.second
        }
        index += 1
    }
    return inherited + declared
}

/** `$this->addType('name', 'integer')` or `$this->addType('name', Types::INTEGER)`. */
private fun List<PhpToken>.addTypeAt(start: Int, declaration: PhpClassDeclaration): Pair<String, String>? {
    if (!matchesThisCall(start, "addType")) return null
    var index = start + 6
    val name = (getOrNull(index++) as? PhpToken.Text)?.takeIf { it.literal }?.value ?: return null
    if ((getOrNull(index++) as? PhpToken.Symbol)?.value != ',') return null
    val type = when (val token = getOrNull(index++)) {
        is PhpToken.Text -> token.takeIf { it.literal }?.value
        is PhpToken.Word -> {
            val className = token.value
            val colon = (getOrNull(index) as? PhpToken.Symbol)?.value == ':' && (getOrNull(index + 1) as? PhpToken.Symbol)?.value == ':'
            val constant = (getOrNull(index + 2) as? PhpToken.Word)?.value
            if (colon && constant != null && declaration.resolveClass(className) == DB_TYPES_CLASS) {
                index += 3
                constant.lowercase()
            } else {
                null
            }
        }
        else -> null
    } ?: return null
    if ((getOrNull(index) as? PhpToken.Symbol)?.value != ')') return null
    return name to type
}

private fun List<PhpToken>.matchesThisCall(start: Int, method: String): Boolean =
    (getOrNull(start) as? PhpToken.Symbol)?.value == '$' &&
        (getOrNull(start + 1) as? PhpToken.Word)?.value == "this" &&
        (getOrNull(start + 2) as? PhpToken.Symbol)?.value == '-' &&
        (getOrNull(start + 3) as? PhpToken.Symbol)?.value == '>' &&
        (getOrNull(start + 4) as? PhpToken.Word)?.value.equals(method, ignoreCase = true) &&
        (getOrNull(start + 5) as? PhpToken.Symbol)?.value == '('

private fun PhpClassDeclaration.serializationSchema(
    method: PhpMethodDeclaration,
    types: Map<String, String>,
): JSONObject? {
    val body = methodBodyTokens(tokens, method.nameIndex) ?: return null
    val returns = mutableListOf<Int>()
    var depth = 0
    body.forEachIndexed { index, token ->
        when ((token as? PhpToken.Symbol)?.value) {
            '{' -> depth += 1
            '}' -> depth -= 1
        }
        if (depth == 0 && (token as? PhpToken.Word)?.value == "return") returns += index
    }
    val returnIndex = returns.singleOrNull() ?: return null
    val open = returnIndex + 1
    val close = when {
        (body.getOrNull(open) as? PhpToken.Symbol)?.value == '[' -> body.matchingClose(open, '[', ']')
        else -> null
    } ?: return null
    if ((body.getOrNull(close + 1) as? PhpToken.Symbol)?.value != ';') return null
    val entries = body.subList(open + 1, close).splitTopLevel(',')
    if (entries.isEmpty() || entries.size > MAX_ENTITY_FIELDS) return null
    val properties = JSONObject()
    val required = JSONArray()
    entries.forEach { entry ->
        if (entry.isEmpty()) return@forEach
        val arrow = entry.indexOf(PhpToken.Arrow)
        if (arrow != 1) return null
        val key = (entry[0] as? PhpToken.Text)?.takeIf { it.literal }?.value ?: return null
        if (key.isEmpty() || key.length > 128 || properties.has(key)) return null
        properties.put(key, entry.subList(2, entry.size).valueSchema(types))
        required.put(key)
    }
    return JSONObject().put("type", "object").put("properties", properties).put("required", required)
}

private fun List<PhpToken>.valueSchema(types: Map<String, String>): JSONObject {
    val value = unwrapParentheses()
    val single = value.singleOrNull()
    when {
        single is PhpToken.Text && single.literal -> return JSONObject().put("type", "string")
        single is PhpToken.Word && single.value.all(Char::isDigit) -> return JSONObject().put("type", "integer").put("format", "int64")
        single is PhpToken.Word && single.value.lowercase() in setOf("true", "false") -> return JSONObject().put("type", "boolean")
    }
    castSchema()?.let { return it }
    if (topLevelComparison()) return JSONObject().put("type", "boolean")
    val property = value.entityPropertyName() ?: return JSONObject()
    return types[property.lowercase()]?.let(::columnSchema) ?: JSONObject()
}

/** `(int) expr`, `(bool) expr`, and similar casts state the serialized scalar type. */
private fun List<PhpToken>.castSchema(): JSONObject? {
    if ((getOrNull(0) as? PhpToken.Symbol)?.value != '(' || (getOrNull(2) as? PhpToken.Symbol)?.value != ')') return null
    if (size < 4) return null
    val type = when ((getOrNull(1) as? PhpToken.Word)?.value?.lowercase()) {
        "int", "integer" -> "integer"
        "bool", "boolean" -> "boolean"
        "float", "double" -> "number"
        "string" -> "string"
        else -> return null
    }
    return JSONObject().put("type", type)
}

private fun List<PhpToken>.topLevelComparison(): Boolean {
    val value = unwrapParentheses()
    var depth = 0
    value.forEachIndexed { index, token ->
        when ((token as? PhpToken.Symbol)?.value) {
            '(', '[' -> depth += 1
            ')', ']' -> depth -= 1
            '=' -> if (depth == 0 && (value.getOrNull(index + 1) as? PhpToken.Symbol)?.value == '=') {
                val before = (value.getOrNull(index - 1) as? PhpToken.Symbol)?.value
                if (before == '=' || before == '!' || (value.getOrNull(index + 2) as? PhpToken.Symbol)?.value == '=') return true
            }
        }
    }
    return false
}

/** `$this->prop`, `$this->getProp()`, or `$this->isProp()`; entity getters map to properties. */
private fun List<PhpToken>.entityPropertyName(): String? {
    if ((getOrNull(0) as? PhpToken.Symbol)?.value != '$' || (getOrNull(1) as? PhpToken.Word)?.value != "this") return null
    if ((getOrNull(2) as? PhpToken.Symbol)?.value != '-' || (getOrNull(3) as? PhpToken.Symbol)?.value != '>') return null
    val member = (getOrNull(4) as? PhpToken.Word)?.value ?: return null
    return when (size) {
        5 -> member
        7 -> {
            if ((get(5) as? PhpToken.Symbol)?.value != '(' || (get(6) as? PhpToken.Symbol)?.value != ')') return null
            val stem = when {
                member.startsWith("get") && member.length > 3 -> member.substring(3)
                member.startsWith("is") && member.length > 2 -> member.substring(2)
                else -> return null
            }
            stem.replaceFirstChar(Char::lowercaseChar)
        }
        else -> null
    }
}

private fun columnSchema(type: String): JSONObject = when (type.lowercase()) {
    "integer", "int", "bigint", "smallint" -> JSONObject().put("type", "integer").put("format", "int64")
    "boolean", "bool" -> JSONObject().put("type", "boolean")
    "float", "double", "decimal" -> JSONObject().put("type", "number")
    "string", "text" -> JSONObject().put("type", "string")
    else -> JSONObject()
}

private fun List<PhpToken>.unwrapParentheses(): List<PhpToken> {
    var value = this
    while (value.size >= 2 && (value.first() as? PhpToken.Symbol)?.value == '(' &&
        value.matchingClose(0, '(', ')') == value.lastIndex
    ) {
        value = value.subList(1, value.lastIndex)
    }
    return value
}

private fun List<PhpToken>.matchingClose(open: Int, opening: Char, closing: Char): Int? {
    var depth = 0
    for (index in open until size) {
        when ((get(index) as? PhpToken.Symbol)?.value) {
            opening -> depth += 1
            closing -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
    }
    return null
}

private fun List<PhpToken>.splitTopLevel(separator: Char): List<List<PhpToken>> {
    val parts = mutableListOf<List<PhpToken>>()
    var depth = 0
    var start = 0
    forEachIndexed { index, token ->
        when ((token as? PhpToken.Symbol)?.value) {
            '(', '[', '{' -> depth += 1
            ')', ']', '}' -> depth -= 1
            separator -> if (depth == 0) {
                parts += subList(start, index)
                start = index + 1
            }
        }
    }
    if (start < size) parts += subList(start, size)
    return parts
}

private val SERIALIZING_METHODS = setOf("jsonserialize", "toapi")
private const val FRAMEWORK_ENTITY = "OCP\\AppFramework\\Db\\Entity"
private const val DB_TYPES_CLASS = "OCP\\DB\\Types"
private const val MAX_ENTITY_INHERITANCE_DEPTH = 4
private const val MAX_ENTITY_FIELDS = 256
private const val MAX_ENTITY_SERIALIZATIONS = 256
