package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts psalm types into OpenAPI 3.0 schemas the way Nextcloud's openapi-extractor does for its
 * supported subset. Aliases resolve through [aliases]; a class name resolves through
 * [classSchema] (verified entity serialization) or becomes an unconstrained schema marked with the
 * unresolved class name, never a guessed shape. Unsupported or oversized types return null.
 */
internal class PsalmSchemaContext(
    private val aliases: Map<String, PsalmType>,
    private val resolveClass: (String) -> String,
    private val classSchema: (String) -> JSONObject? = { null },
) {
    private var nodes = 0

    fun schema(type: PsalmType): JSONObject? = runCatching {
        nodes = 0
        convert(type, depth = 0, aliasStack = emptyList())
    }.getOrNull()

    /** The resolved alias or the type itself, used to recognize generic response wrappers. */
    fun dealias(type: PsalmType): PsalmType {
        var current = type
        repeat(MAX_PSALM_ALIAS_DEPTH) {
            val named = current as? PsalmType.Named ?: return current
            if (named.arguments.isNotEmpty()) return current
            current = aliases[named.name] ?: return current
        }
        return current
    }

    fun qualifiedClass(name: String): String = resolveClass(name)

    private fun convert(type: PsalmType, depth: Int, aliasStack: List<String>): JSONObject {
        check(depth <= MAX_PSALM_SCHEMA_DEPTH) { "The psalm schema is nested too deeply." }
        nodes += 1
        check(nodes <= MAX_PSALM_SCHEMA_NODES) { "The psalm schema is too large." }
        return when (type) {
            is PsalmType.StringLiteral -> JSONObject().put("type", "string").put("enum", JSONArray().put(type.value))
            is PsalmType.IntLiteral -> JSONObject().put("type", "integer").put("format", "int64")
                .put("enum", JSONArray().put(type.value))
            is PsalmType.ClassConstant -> error("Class constant types are not representable.")
            is PsalmType.Union -> union(type.members, depth, aliasStack)
            is PsalmType.ListOf -> JSONObject().put("type", "array").put("items", convert(type.item, depth + 1, aliasStack))
            is PsalmType.Shape -> shape(type, depth, aliasStack)
            is PsalmType.Named -> named(type, depth, aliasStack)
        }
    }

    private fun shape(type: PsalmType.Shape, depth: Int, aliasStack: List<String>): JSONObject {
        // PHP serializes an empty array as a JSON list, so `array{}` is an empty JSON array.
        if (type.entries.isEmpty()) return JSONObject().put("type", "array").put("maxItems", 0)
        val properties = JSONObject()
        val required = JSONArray()
        type.entries.forEach { entry ->
            properties.put(entry.key, convert(entry.type, depth + 1, aliasStack))
            if (!entry.optional) required.put(entry.key)
        }
        return JSONObject().put("type", "object").put("properties", properties).also { schema ->
            if (required.length() > 0) schema.put("required", required)
        }
    }

    private fun union(members: List<PsalmType>, depth: Int, aliasStack: List<String>): JSONObject {
        val nullable = members.any { it.isNull() }
        val values = members.filterNot { it.isNull() }
        check(values.isNotEmpty()) { "A null-only union is not representable." }
        val schema = when {
            values.all { it is PsalmType.StringLiteral } ->
                JSONObject().put("type", "string").put("enum", JSONArray(values.map { (it as PsalmType.StringLiteral).value }.distinct()))
            values.all { it is PsalmType.IntLiteral } ->
                JSONObject().put("type", "integer").put("format", "int64")
                    .put("enum", JSONArray(values.map { (it as PsalmType.IntLiteral).value }.distinct()))
            values.all { it.isBooleanName() } -> booleanSchema(values)
            values.size == 1 -> convert(values.single(), depth + 1, aliasStack)
            else -> {
                val branches = values.map { convert(it, depth + 1, aliasStack) }
                val distinct = branches.map(JSONObject::similarKey).distinct()
                if (distinct.size == 1) branches.first() else JSONObject().put("anyOf", JSONArray(branches))
            }
        }
        if (nullable) schema.put("nullable", true)
        return schema
    }

    private fun booleanSchema(values: List<PsalmType>): JSONObject {
        val names = values.map { (it as PsalmType.Named).name.lowercase() }.toSet()
        val schema = JSONObject().put("type", "boolean")
        if ("bool" !in names && "boolean" !in names && names.size == 1) schema.put("enum", JSONArray().put(names.single() == "true"))
        return schema
    }

    private fun named(type: PsalmType.Named, depth: Int, aliasStack: List<String>): JSONObject {
        val lower = type.name.lowercase()
        val arguments = type.arguments
        SCALAR_SCHEMAS[lower]?.takeIf { arguments.isEmpty() }?.let { return JSONObject(it) }
        when (lower) {
            "true", "false" -> return booleanSchema(listOf(type))
            "int", "integer" -> if (arguments.size == 2) return intRange(arguments)
            "list", "non-empty-list" -> {
                check(arguments.size == 1) { "A list type needs one argument." }
                val schema = JSONObject().put("type", "array").put("items", convert(arguments.single(), depth + 1, aliasStack))
                if (lower == "non-empty-list") schema.put("minItems", 1)
                return schema
            }
            "array", "non-empty-array" -> {
                if (arguments.isEmpty()) return JSONObject()
                val key = arguments.first()
                // `array<string, T>` is a JSON object. `array<T>` and `array<int, T>` may be a list
                // or a map depending on the runtime keys, so no shape is claimed for them.
                if (arguments.size == 2 && (key as? PsalmType.Named)?.name?.lowercase() == "string") {
                    return JSONObject().put("type", "object")
                        .put("additionalProperties", convert(arguments[1], depth + 1, aliasStack))
                }
                return JSONObject()
            }
            "mixed", "scalar", "array-key" -> return JSONObject()
        }
        check(arguments.isEmpty()) { "Generic class types are not representable." }
        aliases[type.name]?.let { alias ->
            check(type.name !in aliasStack && aliasStack.size < MAX_PSALM_ALIAS_DEPTH) { "Recursive psalm type alias." }
            return convert(alias, depth + 1, aliasStack + type.name)
        }
        check(type.name.first().isUpperCase() || type.name.startsWith('\\')) { "Unknown psalm pseudo type." }
        val qualified = resolveClass(type.name)
        classSchema(qualified)?.let { return JSONObject(it.toString()) }
        return JSONObject().put(UNRESOLVED_TYPE_EXTENSION, qualified.takeLast(MAX_UNRESOLVED_TYPE_CHARS))
    }

    private fun intRange(arguments: List<PsalmType>): JSONObject {
        val schema = JSONObject().put("type", "integer").put("format", "int64")
        arguments.forEachIndexed { position, bound ->
            when (bound) {
                is PsalmType.IntLiteral -> schema.put(if (position == 0) "minimum" else "maximum", bound.value)
                is PsalmType.Named -> check(bound.name.lowercase() == if (position == 0) "min" else "max") {
                    "An integer range bound is malformed."
                }
                else -> error("An integer range bound is malformed.")
            }
        }
        return schema
    }

    private fun PsalmType.isNull(): Boolean = this is PsalmType.Named && arguments.isEmpty() && name.lowercase() == "null"

    private fun PsalmType.isBooleanName(): Boolean =
        this is PsalmType.Named && arguments.isEmpty() && name.lowercase() in setOf("bool", "boolean", "true", "false")
}

private fun JSONObject.similarKey(): String = toString()

/** Recognizes `DataResponse<status, T, headers>` and `JSONResponse<status, T, headers>` members. */
internal data class PsalmResponse(val statuses: List<Int>, val data: PsalmType)

internal fun PsalmSchemaContext.declaredResponses(type: PsalmType): List<PsalmResponse>? {
    val members = (dealias(type) as? PsalmType.Union)?.members ?: listOf(dealias(type))
    val responses = members.map { member ->
        val generic = dealias(member) as? PsalmType.Named ?: return null
        if (qualifiedClass(generic.name) !in JSON_RESPONSE_CLASSES || generic.arguments.size !in 2..3) return null
        val statuses = generic.arguments.first().let { status ->
            (status as? PsalmType.Union)?.members ?: listOf(status)
        }.map { status -> status.httpStatus(this) ?: return null }
        PsalmResponse(statuses, generic.arguments[1])
    }
    return responses.takeIf(List<PsalmResponse>::isNotEmpty)
}

private fun PsalmType.httpStatus(context: PsalmSchemaContext): Int? = when (this) {
    is PsalmType.IntLiteral -> value.toInt().takeIf { it in 100..599 }
    is PsalmType.ClassConstant -> if (context.qualifiedClass(className) == HTTP_CLASS) HTTP_STATUS_CONSTANTS[constant] else null
    else -> null
}

private val JSON_RESPONSE_CLASSES = setOf(
    "OCP\\AppFramework\\Http\\DataResponse",
    "OCP\\AppFramework\\Http\\JSONResponse",
)
private const val HTTP_CLASS = "OCP\\AppFramework\\Http"

/** Status constants declared by OCP\AppFramework\Http (server lib/public/AppFramework/Http.php). */
private val HTTP_STATUS_CONSTANTS = mapOf(
    "STATUS_CONTINUE" to 100, "STATUS_SWITCHING_PROTOCOLS" to 101, "STATUS_PROCESSING" to 102,
    "STATUS_OK" to 200, "STATUS_CREATED" to 201, "STATUS_ACCEPTED" to 202,
    "STATUS_NON_AUTHORATIVE_INFORMATION" to 203, "STATUS_NO_CONTENT" to 204, "STATUS_RESET_CONTENT" to 205,
    "STATUS_PARTIAL_CONTENT" to 206, "STATUS_MULTI_STATUS" to 207, "STATUS_ALREADY_REPORTED" to 208,
    "STATUS_IM_USED" to 226, "STATUS_MULTIPLE_CHOICES" to 300, "STATUS_MOVED_PERMANENTLY" to 301,
    "STATUS_FOUND" to 302, "STATUS_SEE_OTHER" to 303, "STATUS_NOT_MODIFIED" to 304, "STATUS_USE_PROXY" to 305,
    "STATUS_RESERVED" to 306, "STATUS_TEMPORARY_REDIRECT" to 307, "STATUS_BAD_REQUEST" to 400,
    "STATUS_UNAUTHORIZED" to 401, "STATUS_PAYMENT_REQUIRED" to 402, "STATUS_FORBIDDEN" to 403,
    "STATUS_NOT_FOUND" to 404, "STATUS_METHOD_NOT_ALLOWED" to 405, "STATUS_NOT_ACCEPTABLE" to 406,
    "STATUS_PROXY_AUTHENTICATION_REQUIRED" to 407, "STATUS_REQUEST_TIMEOUT" to 408, "STATUS_CONFLICT" to 409,
    "STATUS_GONE" to 410, "STATUS_LENGTH_REQUIRED" to 411, "STATUS_PRECONDITION_FAILED" to 412,
    "STATUS_REQUEST_ENTITY_TOO_LARGE" to 413, "STATUS_REQUEST_URI_TOO_LONG" to 414,
    "STATUS_UNSUPPORTED_MEDIA_TYPE" to 415, "STATUS_REQUEST_RANGE_NOT_SATISFIABLE" to 416,
    "STATUS_EXPECTATION_FAILED" to 417, "STATUS_IM_A_TEAPOT" to 418, "STATUS_UNPROCESSABLE_ENTITY" to 422,
    "STATUS_LOCKED" to 423, "STATUS_FAILED_DEPENDENCY" to 424, "STATUS_UPGRADE_REQUIRED" to 426,
    "STATUS_PRECONDITION_REQUIRED" to 428, "STATUS_TOO_MANY_REQUESTS" to 429,
    "STATUS_REQUEST_HEADER_FIELDS_TOO_LARGE" to 431, "STATUS_INTERNAL_SERVER_ERROR" to 500,
    "STATUS_NOT_IMPLEMENTED" to 501, "STATUS_BAD_GATEWAY" to 502, "STATUS_SERVICE_UNAVAILABLE" to 503,
    "STATUS_GATEWAY_TIMEOUT" to 504, "STATUS_HTTP_VERSION_NOT_SUPPORTED" to 505,
    "STATUS_VARIANT_ALSO_NEGOTIATES" to 506, "STATUS_INSUFFICIENT_STORAGE" to 507, "STATUS_LOOP_DETECTED" to 508,
    "STATUS_BANDWIDTH_LIMIT_EXCEEDED" to 509, "STATUS_NOT_EXTENDED" to 510,
    "STATUS_NETWORK_AUTHENTICATION_REQUIRED" to 511,
)

private val SCALAR_SCHEMAS: Map<String, String> = mapOf(
    "string" to """{"type":"string"}""",
    "non-empty-string" to """{"type":"string","minLength":1}""",
    "non-falsy-string" to """{"type":"string","minLength":1}""",
    "numeric-string" to """{"type":"string","format":"numeric"}""",
    "lowercase-string" to """{"type":"string"}""",
    "int" to """{"type":"integer","format":"int64"}""",
    "integer" to """{"type":"integer","format":"int64"}""",
    "positive-int" to """{"type":"integer","format":"int64","minimum":1}""",
    "non-negative-int" to """{"type":"integer","format":"int64","minimum":0}""",
    "negative-int" to """{"type":"integer","format":"int64","maximum":-1}""",
    "non-positive-int" to """{"type":"integer","format":"int64","maximum":0}""",
    "float" to """{"type":"number","format":"double"}""",
    "double" to """{"type":"number","format":"double"}""",
    "bool" to """{"type":"boolean"}""",
    "boolean" to """{"type":"boolean"}""",
    "object" to """{"type":"object"}""",
    "stdclass" to """{"type":"object"}""",
)

internal const val UNRESOLVED_TYPE_EXTENSION = "x-nextcloud-native-unresolved-type"
private const val MAX_UNRESOLVED_TYPE_CHARS = 256
private const val MAX_PSALM_SCHEMA_DEPTH = 24
private const val MAX_PSALM_SCHEMA_NODES = 4_096
private const val MAX_PSALM_ALIAS_DEPTH = 8
