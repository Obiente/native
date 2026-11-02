package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Package facts attached to one synthesized OpenAPI operation. These are E3 evidence for the
 * descriptor compiler and later admission decisions; attaching them never publishes a write.
 */
internal fun JSONObject.putStaticOperationFacts(
    route: StaticRoute,
    evidence: StaticMethodEvidence?,
    mergeHint: Boolean,
) {
    put(ROUTE_SOURCE_EXTENSION, route.source.wireName)
    evidence?.policy?.let { put(POLICY_EXTENSION, it.toJson()) }
    if (mergeHint) put(MERGE_HINT_EXTENSION, MERGE_HINT_KIND)
    route.postfix?.takeIf(String::isSafeFactValue)?.let { put(ROUTE_POSTFIX_EXTENSION, it) }
}

internal val StaticRouteSource.wireName: String
    get() = when (this) {
        StaticRouteSource.RouteFile -> "routes-file"
        StaticRouteSource.Attribute -> "attribute"
    }

/** Path parameter schema with the route's requirement, default, and hidden-input facts. */
internal fun staticPathParameter(
    name: String,
    route: StaticRoute,
    declared: StaticPhpParameter?,
    evidence: StaticMethodEvidence?,
    requestParameter: Boolean,
): JSONObject {
    val schema = declared?.let { parameter ->
        val base = parameter.toOpenApiSchema()
        evidence?.refinedSchema(parameter, base)?.also { it.remove("nullable") } ?: base
    } ?: JSONObject().put("type", "string")
    return JSONObject()
        .put("name", name)
        .put("in", "path")
        .put("required", true)
        .put("schema", schema)
        .also { parameter ->
            route.requirements[name]?.takeIf(String::isSafeFactValue)?.let { parameter.put(ROUTE_REQUIREMENT_EXTENSION, it) }
            // Recorded, never bound: binding a default would hide a user-selectable identity.
            route.defaults[name]?.takeIf { it.length <= 256 }?.let { parameter.put(ROUTE_DEFAULT_EXTENSION, it) }
            if (requestParameter) parameter.put(REQUEST_INPUT_EXTENSION, "request-parameter")
        }
}

/** A JSON body or multipart text property: the native type narrowed by psalm, nullability, default. */
internal fun staticBodyProperty(
    parameter: StaticPhpParameter,
    base: JSONObject,
    evidence: StaticMethodEvidence?,
): JSONObject {
    val schema = evidence?.refinedSchema(parameter, base) ?: base
    val type = schema.optString("type")
    if (parameter.nullable && type != "array" && type != "object" && type.isNotEmpty()) schema.put("nullable", true)
    parameter.jsonDefault()?.let { schema.put("default", it) }
    return schema
}

/** A multipart body with the uploaded file field and scalar text fields, as the method reads them. */
internal fun staticMultipartRequestBody(write: StaticCrudWrite, evidence: StaticMethodEvidence?): JSONObject {
    val file = requireNotNull(write.uploadedFileField)
    val properties = JSONObject()
    val required = JSONArray()
    write.bodyParameters.forEach { parameter ->
        properties.put(parameter.name, staticBodyProperty(parameter, parameter.toOpenApiSchema(), evidence))
        if (parameter.required) required.put(parameter.name)
    }
    properties.put(
        file,
        JSONObject().put("type", "string").put("format", "binary").put(REQUEST_INPUT_EXTENSION, "uploaded-file"),
    )
    // The method reads the file unconditionally; sending it is the purpose of the request.
    required.put(file)
    val schema = JSONObject().put("type", "object").put("properties", properties).put("required", required)
    return JSONObject()
        .put("required", true)
        .put("content", JSONObject().put("multipart/form-data", JSONObject().put("schema", schema)))
}

/**
 * One entry of the document's static route evidence: every route the package declares for a
 * readable controller method, published or not, with its signature and facts. Unpublished writes
 * stay here so a later admission step can evaluate them without re-reading the package.
 */
internal fun staticRouteEvidence(
    operationId: String,
    route: StaticRoute,
    fullPath: String,
    placeholders: List<String>,
    parameters: List<StaticPhpParameter>?,
    signatureComplete: Boolean,
    requestInputs: StaticRequestInputs?,
    evidence: StaticMethodEvidence?,
    mergeHint: Boolean,
): JSONObject = JSONObject()
    .put("operationId", operationId)
    .put("method", route.verb.lowercase())
    .put("path", fullPath)
    .put("source", route.source.wireName)
    .put("published", false)
    .put("signatureComplete", signatureComplete && parameters != null)
    .also { entry ->
        evidence?.policy?.let { entry.put("policy", it.toJson()) }
        if (mergeHint) entry.put("mergeHint", MERGE_HINT_KIND)
        requestInputs?.takeIf { !it.empty }?.let { inputs ->
            entry.put(
                "requestInputs",
                JSONObject()
                    .put("parameters", JSONArray(inputs.parameters))
                    .put("uploadedFiles", JSONArray(inputs.uploadedFiles))
                    .put("unclassified", inputs.unclassified),
            )
        }
        parameters?.let { declared ->
            entry.put(
                "parameters",
                JSONArray(
                    declared.map { parameter ->
                        val inPath = placeholders.any { it.equals(parameter.name, ignoreCase = true) }
                        JSONObject()
                            .put("name", parameter.name)
                            .put("in", if (inPath) "path" else if (route.verb == "GET") "query" else "body")
                            .put("required", parameter.required || inPath)
                            .put("nullable", parameter.nullable)
                            .put("schema", staticBodyProperty(parameter, parameter.toOpenApiSchema(), evidence))
                    },
                ),
            )
        }
    }

/**
 * Combines routes.php routes with attribute routes. The router names a route
 * `{controller}#{method}{postfix}` per group and a later route with the same name replaces an
 * earlier one, so repeated attribute names keep the last declaration. When routes.php and an
 * attribute claim the same name with a different URL or verb, the registration order is not
 * stated by the package, so both are dropped and counted. Routes from both sources that share
 * the same URL and verb but name different controller methods are dropped as well: the server
 * registers attribute routes first and dispatches to them, so publishing the file route's
 * signature would send its body to another action.
 */
internal fun mergeStaticRouteSources(fileRoutes: List<StaticRoute>, attributeRoutes: List<StaticRoute>): Pair<List<StaticRoute>, Int> {
    fun StaticRoute.routeName() = listOf(ocs.toString(), controller.normalizedPhpName(), method.normalizedPhpName(), postfix.orEmpty())
    val attributesByName = linkedMapOf<List<String>, StaticRoute>()
    attributeRoutes.forEach { route -> attributesByName.remove(route.routeName()); attributesByName[route.routeName()] = route }
    val fileNames = fileRoutes.groupBy { it.routeName() }
    var ambiguous = 0
    val conflicting = attributesByName.filter { (name, route) ->
        fileNames[name]?.any { it.url != route.url || it.verb != route.verb } == true
    }.keys
    val merged = fileRoutes.filter { route ->
        (route.routeName() !in conflicting).also { kept -> if (!kept) ambiguous += 1 }
    } + attributesByName.filterKeys { name ->
        (name !in conflicting).also { kept -> if (!kept) ambiguous += 1 }
    }.values.filter { route ->
        fileNames[route.routeName()].orEmpty().none { it.url == route.url && it.verb == route.verb }
    }
    fun StaticRoute.endpoint() = listOf(ocs.toString(), url.withAnonymousPlaceholders(), verb.uppercase())
    fun StaticRoute.action() = controller.normalizedPhpName() to method.normalizedPhpName()
    val crossSourceClashes = merged.groupBy { it.endpoint() }.filterValues { routes ->
        routes.any { it.source == StaticRouteSource.RouteFile } &&
            routes.any { it.source != StaticRouteSource.RouteFile } &&
            routes.map { it.action() }.distinct().size > 1
    }.keys
    val unambiguous = merged.filter { route ->
        (route.endpoint() !in crossSourceClashes).also { kept -> if (!kept) ambiguous += 1 }
    }
    return unambiguous to ambiguous
}

internal const val ROUTE_SOURCE_EXTENSION = "x-nextcloud-native-route-source"
internal const val POLICY_EXTENSION = "x-nextcloud-native-policy"
internal const val MERGE_HINT_EXTENSION = "x-nextcloud-native-merge-hint"
internal const val ROUTE_POSTFIX_EXTENSION = "x-nextcloud-native-route-postfix"
internal const val REQUEST_INPUT_EXTENSION = "x-nextcloud-native-request-input"
internal const val ROUTE_REQUIREMENT_EXTENSION = "x-nextcloud-native-route-requirement"
internal const val ROUTE_DEFAULT_EXTENSION = "x-nextcloud-native-route-default"
internal const val STATIC_ROUTES_EXTENSION = "x-nextcloud-native-static-routes"
internal const val SKIPPED_ROUTES_EXTENSION = "x-nextcloud-native-skipped-routes"
internal const val STATIC_EVIDENCE_FORMAT_EXTENSION = "x-nextcloud-native-static-evidence-format"

/** Bumped whenever the static evidence fields change shape. */
internal const val STATIC_EVIDENCE_FORMAT = 2
internal const val MERGE_HINT_KIND = "nullable-null-default-parameters"

/**
 * Replaces each `{name}` with `{}`; placeholder names do not change which requests a route
 * matches. A URL with unbalanced braces is returned unchanged so it never equals another route.
 */
private fun String.withAnonymousPlaceholders(): String {
    if (count { it == '{' } != count { it == '}' }) return this
    return buildString {
        var inPlaceholder = false
        for (char in this@withAnonymousPlaceholders) {
            when {
                inPlaceholder -> if (char == '}') {
                    append("{}")
                    inPlaceholder = false
                }
                char == '{' -> inPlaceholder = true
                else -> append(char)
            }
        }
    }
}
