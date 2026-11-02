package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Declaration evidence read from one verified package: controller attributes and docblocks, psalm
 * type aliases (lib/ResponseDefinitions.php and controller docblocks), entity serialization in
 * lib/Db, and capability keys in lib/Capabilities.php. Everything is read statically and bounded;
 * an unreadable file contributes nothing.
 */
internal class StaticPackageEvidence private constructor(
    private val controllers: Map<String, PhpClassDeclaration>,
    val attributeRoutes: List<StaticRoute>,
    val skippedCustomRootRoutes: Int,
    val unreadableAttributeRoutes: Int,
    private val aliasesByClass: Map<String, Map<String, PsalmType>>,
    val entities: List<StaticEntitySerialization>,
    val capabilities: StaticCapabilityFacts?,
    val supplementaryTruncated: Boolean,
) {
    fun method(controller: String, method: String): StaticMethodEvidence? {
        val declaration = controllers[controller.normalizedPhpName()] ?: return null
        val declared = declaration.methods.filter { it.name.normalizedPhpName() == method.normalizedPhpName() }
            .singleOrNull() ?: return null
        val doc = declared.docComment
        val schemas = PsalmSchemaContext(
            aliases = aliasesByClass[declaration.qualifiedName].orEmpty(),
            resolveClass = declaration::resolveClass,
            classSchema = { qualified ->
                entities.firstOrNull { it.className == qualified && it.jsonSerializable }?.schema
            },
        )
        return StaticMethodEvidence(
            policy = staticOperationPolicy(declared),
            parameterTypes = doc?.parameterTypes().orEmpty(),
            returnType = doc?.returnType(),
            nativeReturnClass = nativeReturnClass(declaration, declared),
            schemas = schemas,
        )
    }

    /** Document-level facts that do not belong to one operation. */
    fun putDocumentFacts(document: JSONObject) {
        if (entities.isNotEmpty()) {
            document.put(
                ENTITY_SERIALIZATIONS_EXTENSION,
                JSONArray(
                    entities.map { entity ->
                        JSONObject()
                            .put("class", entity.className)
                            .put("method", entity.method)
                            .put("jsonSerializable", entity.jsonSerializable)
                            .put("schema", entity.schema)
                    },
                ),
            )
        }
        capabilities?.let { document.put(CAPABILITY_KEYS_EXTENSION, it.toJson()) }
        if (supplementaryTruncated) document.put(SUPPLEMENTARY_TRUNCATED_EXTENSION, true)
    }

    companion object {
        fun read(root: String, files: Map<String, ByteArray>, supplementaryTruncated: Boolean): StaticPackageEvidence {
            fun declarations(filter: (String) -> Boolean): List<Pair<String, PhpClassDeclaration>> =
                files.entries.asSequence()
                    .filter { (path, _) -> path.startsWith("$root/") && filter(path.removePrefix("$root/")) }
                    .mapNotNull { (path, bytes) -> parsePhpClassDeclaration(bytes.decodeToString())?.let { path to it } }
                    .toList()

            val controllerFiles = declarations { path -> path.startsWith("lib/Controller/") && path.endsWith("Controller.php") }
            val controllers = controllerFiles
                .groupBy { (_, declaration) -> declaration.name.removeSuffix("Controller").normalizedPhpName() }
                .mapNotNull { (name, matches) -> matches.singleOrNull()?.let { name to it.second } }
                .toMap()
            // The router reflects only files directly inside lib/Controller whose class matches the file name.
            val routed = controllerFiles.filter { (path, declaration) ->
                path.removePrefix("$root/lib/Controller/").let { '/' !in it && it == "${declaration.name}.php" }
            }
            val attributeResults = routed.map { (_, declaration) -> declaration.attributeRoutes() }
            val typeFiles = declarations { path -> path == "lib/ResponseDefinitions.php" } + controllerFiles
            val entityDeclarations = declarations { path -> path.startsWith("lib/Db/") && '/' !in path.removePrefix("lib/Db/") }
            val capabilityDeclarations = declarations { path ->
                path == "lib/Capabilities.php" ||
                    (path.startsWith("lib/Capabilities/") && '/' !in path.removePrefix("lib/Capabilities/"))
            }
            return StaticPackageEvidence(
                controllers = controllers,
                attributeRoutes = attributeResults.flatMap(StaticAttributeRoutes::routes).take(MAX_STATIC_ROUTE_COUNT),
                skippedCustomRootRoutes = attributeResults.sumOf(StaticAttributeRoutes::skippedCustomRootRoutes),
                unreadableAttributeRoutes = attributeResults.sumOf(StaticAttributeRoutes::unreadableRoutes),
                aliasesByClass = visibleAliases(typeFiles.map { it.second }),
                entities = readEntitySerializations(entityDeclarations.map { it.second }),
                capabilities = readCapabilityFacts(capabilityDeclarations.map { it.second }),
                supplementaryTruncated = supplementaryTruncated,
            )
        }
    }
}

internal data class StaticMethodEvidence(
    val policy: StaticOperationPolicy,
    val parameterTypes: Map<String, PsalmType>,
    val returnType: PsalmType?,
    val nativeReturnClass: String?,
    val schemas: PsalmSchemaContext,
)

/**
 * Aliases each class may use: its own `@psalm-type` declarations and its `@psalm-import-type`
 * imports. Every alias is expanded in the scope that declared it, and class names inside it are
 * fully qualified there, so an imported alias never resolves against the importing file.
 */
private fun visibleAliases(declarations: List<PhpClassDeclaration>): Map<String, Map<String, PsalmType>> {
    val byClass = declarations.associateBy(PhpClassDeclaration::qualifiedName)
    val own = declarations.associate { declaration ->
        val local = declaration.docComment?.typeAliases().orEmpty()
        declaration.qualifiedName to local.mapNotNull { (name, type) ->
            runCatching { name to type.expand(local, declaration, AliasBudget(), depth = 0, stack = listOf(name)) }.getOrNull()
        }.toMap()
    }
    return declarations.associate { declaration ->
        val imported = declaration.docComment?.typeImports().orEmpty().mapNotNull { import ->
            val origin = declaration.resolveClass(import.fromClass)
            if (byClass[origin] == null) return@mapNotNull null
            own[origin]?.get(import.name)?.let { import.alias to it }
        }
        declaration.qualifiedName to (own[declaration.qualifiedName].orEmpty() + imported)
    }
}

private class AliasBudget(var nodes: Int = 0)

private fun PsalmType.expand(
    aliases: Map<String, PsalmType>,
    scope: PhpClassDeclaration,
    budget: AliasBudget,
    depth: Int,
    stack: List<String>,
): PsalmType {
    budget.nodes += 1
    check(budget.nodes <= MAX_ALIAS_EXPANSION_NODES && depth <= MAX_ALIAS_EXPANSION_DEPTH) { "Alias expansion is too large." }
    return when (this) {
        is PsalmType.Named -> when {
            arguments.isNotEmpty() -> copy(arguments = arguments.map { it.expand(aliases, scope, budget, depth + 1, stack) })
            name in aliases -> {
                check(name !in stack) { "Recursive psalm alias." }
                aliases.getValue(name).expand(aliases, scope, budget, depth + 1, stack + name)
            }
            name.first().isUpperCase() -> PsalmType.Named("\\" + scope.resolveClass(name))
            else -> this
        }
        is PsalmType.Union -> PsalmType.Union(members.map { it.expand(aliases, scope, budget, depth + 1, stack) })
        is PsalmType.ListOf -> PsalmType.ListOf(item.expand(aliases, scope, budget, depth + 1, stack))
        is PsalmType.Shape -> PsalmType.Shape(entries.map { it.copy(type = it.type.expand(aliases, scope, budget, depth + 1, stack)) })
        is PsalmType.ClassConstant -> PsalmType.ClassConstant("\\" + scope.resolveClass(className), constant)
        else -> this
    }
}

/** The class named by a native return type such as `: Item` or `: ?Item`; null for builtins and unions. */
private fun nativeReturnClass(declaration: PhpClassDeclaration, method: PhpMethodDeclaration): String? {
    val tokens = declaration.tokens
    val open = (method.nameIndex + 1 until tokens.size).firstOrNull { (tokens[it] as? PhpToken.Symbol)?.value == '(' } ?: return null
    var depth = 0
    var close = -1
    for (index in open until tokens.size) {
        when ((tokens[index] as? PhpToken.Symbol)?.value) {
            '(' -> depth += 1
            ')' -> {
                depth -= 1
                if (depth == 0) {
                    close = index
                    break
                }
            }
        }
    }
    if (close < 0 || (tokens.getOrNull(close + 1) as? PhpToken.Symbol)?.value != ':') return null
    val name = StringBuilder()
    var index = close + 2
    if ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '?') index += 1
    while (index < tokens.size) {
        when (val token = tokens[index]) {
            is PhpToken.Word -> name.append(token.value)
            is PhpToken.Symbol -> when (token.value) {
                '\\' -> name.append('\\')
                '{', ';' -> break
                else -> return null
            }
            else -> return null
        }
        index += 1
    }
    val raw = name.toString().takeIf(String::isNotEmpty) ?: return null
    if (raw.lowercase() in NATIVE_BUILTIN_TYPES) return null
    return declaration.resolveClass(raw)
}

/** OpenAPI responses declared by the method, or null when nothing typed and successful is stated. */
internal fun StaticMethodEvidence.typedResponses(): JSONObject? {
    val declared = returnType?.let(schemas::declaredResponses)
    if (declared != null) {
        val responses = JSONObject()
        declared.forEach { response ->
            // An unresolved data type says nothing about the body; that status stays untyped.
            val schema = schemas.schema(response.data)?.takeIf { !it.has(UNRESOLVED_TYPE_EXTENSION) } ?: return@forEach
            response.statuses.forEach { status -> responses.put(status.toString(), jsonResponse(schema, status)) }
        }
        return responses.takeIf { it.keySet().any { status -> status.startsWith('2') } }
    }
    val direct = returnType?.let(schemas::schema)?.takeIf { it.length() > 0 && !it.has(UNRESOLVED_TYPE_EXTENSION) }
        ?: nativeReturnClass?.let { schemas.schema(PsalmType.Named("\\" + it)) }?.takeIf { !it.has(UNRESOLVED_TYPE_EXTENSION) }
        ?: return null
    return JSONObject().put("200", jsonResponse(direct, 200))
}

private fun jsonResponse(schema: JSONObject, status: Int): JSONObject = JSONObject()
    .put("description", "Declared $status response")
    .put("content", JSONObject().put("application/json", JSONObject().put("schema", schema)))

/**
 * Narrows a native parameter schema with the method's psalm type when the two are compatible:
 * enums, ranges, item types, and object shapes. An incompatible or unreadable psalm type is
 * ignored. Union read parameters keep their native schema.
 */
internal fun StaticMethodEvidence.refinedSchema(parameter: StaticPhpParameter, base: JSONObject): JSONObject {
    if (parameter.readUnionTypes.isNotEmpty()) return base
    val declared = parameterTypes[parameter.name]?.let(schemas::schema) ?: return base
    val declaredType = declared.optString("type")
    val compatible = when (parameter.type) {
        "int" -> declaredType == "integer"
        "float" -> declaredType == "number" || declaredType == "integer"
        "string" -> declaredType == "string"
        "bool" -> declaredType == "boolean"
        "array" -> declaredType == "array" || declaredType == "object"
        else -> false
    }
    if (!compatible) return base
    // Nullable list and map inputs have no editor representation yet, so nullability is kept only
    // for scalars; the evidence list still records the declared parameter.
    if (!parameter.nullable || declaredType == "array" || declaredType == "object") declared.remove("nullable")
    return declared
}

internal const val ENTITY_SERIALIZATIONS_EXTENSION = "x-nextcloud-native-entity-serializations"
internal const val CAPABILITY_KEYS_EXTENSION = "x-nextcloud-native-capability-keys"
internal const val SUPPLEMENTARY_TRUNCATED_EXTENSION = "x-nextcloud-native-supplementary-evidence-truncated"
private val NATIVE_BUILTIN_TYPES = setOf(
    "array", "void", "mixed", "string", "int", "float", "bool", "null", "never", "static", "self", "iterable",
    "object", "callable", "false", "true",
)
private const val MAX_ALIAS_EXPANSION_NODES = 4_096
private const val MAX_ALIAS_EXPANSION_DEPTH = 16
