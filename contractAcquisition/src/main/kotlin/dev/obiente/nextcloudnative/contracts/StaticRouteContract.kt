package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a deliberately conservative contract from static Nextcloud route metadata.
 *
 * PHP is never executed. A small, bounded literal-array parser accepts only the subset used by
 * appinfo/routes.php, and a route is retained only when its controller statically extends a
 * supported Nextcloud controller, declares the referenced method, and either uses an API base or
 * declares a JSON-serializable response type. This includes app-domain entities while excluding
 * framework response types such as templates, redirects, downloads, and streams. Resource
 * declarations contribute only their conventional GET index/show reads. Writes are retained only
 * for previously verified settings/refresh shapes or conventional CRUD methods whose complete
 * scalar signature proves every path and closed JSON-body input. Every retained endpoint explicitly
 * declares Nextcloud's API-request header so app-password clients do not enter browser CSRF
 * middleware on otherwise valid routes.
 */
internal fun synthesizeReadOnlyRouteContract(
    appId: String,
    appVersion: String,
    files: Map<String, ByteArray>,
    supplementaryTruncated: Boolean = false,
): VerifiedPackageContract? {
    val routeFiles = files.entries.filter { (path, _) -> path.endsWith("/appinfo/routes.php") }
    if (routeFiles.size > 1) return null
    val routeFile = routeFiles.singleOrNull()
    val root = (routeFile?.key ?: files.keys.firstOrNull { path -> "/lib/Controller/" in path })
        ?.substringBefore('/') ?: return null
    if (root.isBlank()) return null
    // An app may declare routes only as controller attributes; a present but unreadable routes.php
    // still yields no contract because the route set could not be read completely.
    val manifest = routeFile?.let { parseRouteManifestDetails(it.value.decodeToString()) ?: return null }
        ?: StaticRouteManifest(emptyList())
    val evidence = StaticPackageEvidence.read(root, files, supplementaryTruncated)
    val (routeGroups, ambiguousRouteNames) = mergeStaticRouteSources(manifest.routes, evidence.attributeRoutes)
    val staticRoutes = JSONArray()
    val controllerSources = files.entries
        .asSequence()
        .filter { (path, _) ->
            path.startsWith("$root/lib/Controller/") && path.endsWith("Controller.php")
        }
        .map { (_, bytes) -> bytes.decodeToString() }
        .toList()
    val controllerSourcesByName = controllerSources.mapNotNull { source ->
        parseControllerHeader(source)?.name?.removeSuffix("Controller")?.normalizedPhpName()?.let { it to source }
    }.toMap()
    val controllerHeaders = controllerSources
        .mapNotNull(::parseControllerHeader)
        .associateBy { header -> header.name.normalizedPhpName() }
    val controllers = controllerSources
        .mapNotNull { source ->
            val header = parseControllerHeader(source) ?: return@mapNotNull null
            val trustedBase = resolveTrustedControllerBase(header, controllerHeaders) ?: return@mapNotNull null
            parseApiController(source, trustedBase)
        }
        .associateBy(StaticApiController::normalizedName)
    if (controllers.isEmpty()) return null

    val paths = JSONObject()
    val documentedVersionDefaults = routeGroups
        .flatMap { route ->
            route.requirements.mapNotNull { (name, requirement) ->
                requirement.fixedVersionPathSegmentOrNull(name)?.let { name.normalizedPhpName() to it }
            }
        }
        .groupBy(Pair<String, String>::first, Pair<String, String>::second)
        .mapValues { (_, versions) -> versions.maxWithOrNull(::compareVersionPathSegments) }
    routeGroups.take(MAX_STATIC_ROUTE_COUNT).forEach { route ->
        if (route.verb !in STATIC_ROUTE_VERBS) return@forEach
        val controller = controllers[route.controller.normalizedPhpName()] ?: return@forEach
        var routePath = route.url
        route.requirements.forEach { (name, requirement) ->
            val fixed = requirement.fixedRequirementPathSegmentOrNull(name)
            if (fixed != null) {
                routePath = routePath.replace("{$name}", fixed)
            }
        }
        routePath.pathPlaceholders()
            ?.filter(String::isVersionPlaceholder)
            ?.forEach { name ->
                documentedVersionDefaults[name.normalizedPhpName()]?.let { version ->
                    routePath = routePath.replace("{$name}", version)
                }
            }
        if (!routePath.isSafeStaticRoutePath()) return@forEach
        val prefix = if (route.ocs) "/ocs/v2.php/apps/$appId" else "/apps/$appId"
        val fullPath = (prefix + routePath).replaceDoubleSlashes()
        val fullPathPlaceholders = fullPath.pathPlaceholders() ?: return@forEach
        val methodEvidence = evidence.method(route.controller, route.method)
        val operationId = listOf(
            if (route.ocs) "ocs" else "route",
            route.controller,
            route.method + route.postfix.orEmpty(),
        ).joinToString(".") { it.normalizedPhpName() }
        val declaredParameters = controller.parameters[route.method.normalizedPhpName()]
        val bodyCandidates = declaredParameters?.filterNot { parameter ->
            fullPathPlaceholders.any { it.equals(parameter.name, ignoreCase = true) }
        }
        val mergeHint = route.verb in MERGE_HINT_VERBS && bodyCandidates?.declaresNullMergeHint() == true
        val routeEvidence = controller.let { readable ->
            staticRouteEvidence(
                operationId = operationId,
                route = route,
                fullPath = fullPath,
                placeholders = fullPathPlaceholders,
                parameters = declaredParameters,
                signatureComplete = readable.completeParameters[route.method.normalizedPhpName()] != null,
                requestInputs = readable.requestInputs[route.method.normalizedPhpName()],
                evidence = methodEvidence,
                mergeHint = mergeHint,
            ).also { entry -> if (staticRoutes.length() < MAX_STATIC_ROUTE_COUNT) staticRoutes.put(entry) }
        }
        val verifiedExactWrite = verifiedChoresWrite(
            appId = appId,
            appVersion = appVersion,
            route = route,
            fullPath = fullPath,
            controller = controller,
            controllerSource = controllerSourcesByName[route.controller.normalizedPhpName()],
        ) ?: verifiedBudgetAccountWrite(
            appId = appId,
            appVersion = appVersion,
            route = route,
            fullPath = fullPath,
            controller = controller,
            controllerSource = controllerSourcesByName[route.controller.normalizedPhpName()],
        ) ?: verifiedMusicPlaylistWrite(
            appId = appId,
            appVersion = appVersion,
            route = route,
            fullPath = fullPath,
            controller = controller,
            controllerSource = controllerSourcesByName[route.controller.normalizedPhpName()],
        )
        val verifiedChoresResponseSchema = verifiedChoresResponseSchema(
            appId = appId,
            appVersion = appVersion,
            route = route,
            fullPath = fullPath,
            controller = controller,
            controllerSource = controllerSourcesByName[route.controller.normalizedPhpName()],
        )
        if (
            route.method.normalizedPhpName() !in controller.methods &&
            verifiedExactWrite == null
        ) {
            return@forEach
        }
        val editableSettingsWrite = route.verb in EDITABLE_SETTINGS_VERBS &&
            fullPath.isEditableSettingsPath() &&
            fullPath.pathPlaceholders()?.isEmpty() == true &&
            routeGroups.any { candidate ->
                candidate.verb == "GET" && candidate.ocs == route.ocs && candidate.url == route.url
            }
        val settingsSetter = if (
            route.verb in EDITABLE_SETTINGS_VERBS &&
            fullPath.pathPlaceholders()?.isEmpty() == true
        ) {
            controller.singleParameters[route.method.normalizedPhpName()]?.let { parameter ->
                val settingsRead = routeGroups.firstOrNull { candidate ->
                    candidate.verb == "GET" &&
                        candidate.ocs == route.ocs &&
                        candidate.controller.normalizedPhpName() == route.controller.normalizedPhpName() &&
                        candidate.url.isAncestorSettingsPathOf(route.url)
                }
                settingsRead?.let { read ->
                    val fieldId = route.method.settingsFieldId() ?: return@let null
                    StaticSettingsSetter(
                        resourceId = read.url.substringAfterLast('/').normalizedSemanticWord(),
                        fieldId = fieldId,
                        parameter = parameter,
                    )
                }
            }
        } else {
            null
        }
        val operationalRefreshWrite = route.verb == "POST" &&
            route.method.normalizedPhpName() in OPERATIONAL_REFRESH_METHODS &&
            fullPathPlaceholders.isNotEmpty() &&
            controller.parameters[route.method.normalizedPhpName()]?.let { declared ->
                declared.filter(StaticPhpParameter::required).all { parameter ->
                    fullPathPlaceholders.any { placeholder -> placeholder.equals(parameter.name, ignoreCase = true) }
                }
            } == true
        // Resource writes are recorded as route evidence only; resources publish their reads.
        if (route.resourceAction && route.verb != "GET") return@forEach
        val crudWrite = if (
            route.verb != "GET" &&
            !editableSettingsWrite &&
            settingsSetter == null &&
            !operationalRefreshWrite
        ) {
            verifiedStaticCrudWrite(route, controller, fullPathPlaceholders)
                // Uploads stay route evidence until the write path can verify a stored file.
                ?.takeIf { write -> write.uploadedFileField == null && methodEvidence.allowsDynamicWrite() }
        } else {
            null
        }
        if (
            route.verb != "GET" &&
            !editableSettingsWrite &&
            settingsSetter == null &&
            !operationalRefreshWrite &&
            crudWrite == null && verifiedExactWrite == null
        ) {
            return@forEach
        }

        val routeParameters = if (route.verb == "GET") controller.readParameters else controller.parameters
        val parameters = JSONArray()
        val pathParameterNames = fullPathPlaceholders
        pathParameterNames.forEach { name ->
            val declaredParameter = routeParameters[route.method.normalizedPhpName()]
                ?.firstOrNull { parameter -> parameter.name.equals(name, ignoreCase = true) }
            parameters.put(
                staticPathParameter(
                    name = name,
                    route = route,
                    declared = declaredParameter,
                    evidence = methodEvidence,
                    requestParameter = crudWrite?.requestPathParameters?.contains(name) == true,
                ),
            )
        }
        if (route.verb == "GET" || operationalRefreshWrite) {
            routeParameters[route.method.normalizedPhpName()].orEmpty()
                .filterNot { parameter ->
                    pathParameterNames.any { pathName -> pathName.equals(parameter.name, ignoreCase = true) }
                }
                .forEach { parameter ->
                    parameters.put(
                        JSONObject()
                            .put("name", parameter.name)
                            .put("in", "query")
                            .put("required", parameter.required)
                            .put(
                                "schema",
                                methodEvidence?.refinedSchema(parameter, parameter.toOpenApiSchema())
                                    ?: parameter.toOpenApiSchema(),
                            ),
                    )
                }
        }
        parameters.put(
            JSONObject()
                .put("name", "OCS-APIRequest")
                .put("in", "header")
                .put("required", true)
                .put("schema", JSONObject().put("type", "boolean").put("default", true)),
        )
        val collection = route.verb == "GET" && !fullPath.isStaticSingletonPath() && (
            route.method.normalizedPhpName() in COLLECTION_READ_METHODS ||
                fullPath.isLikelyPluralCollectionPath()
            )
        val responseSchema = verifiedChoresResponseSchema ?: run {
            val itemSchema = JSONObject().put("type", "object").put("additionalProperties", true)
            if (collection) {
                JSONObject().put("type", "array").put("items", itemSchema)
            } else {
                itemSchema
            }
        }
        val operation = JSONObject()
            .put("operationId", operationId)
            .put(
                "summary",
                verifiedExactWrite?.label
                    ?: settingsSetter?.fieldId?.let { "Change ${it.humanizedPhpName()}" }
                    ?: route.method.humanizedPhpName(),
            )
            .put("parameters", parameters)
            .put(
                "responses",
                JSONObject().put(
                    "200",
                    JSONObject()
                        .put("description", "Successful JSON response; fields are learned from runtime data")
                        .put(
                            "content",
                            JSONObject().put(
                                "application/json",
                                JSONObject().put("schema", responseSchema),
                            ),
                        ),
                ),
            )
        val typedResponses = if (verifiedChoresResponseSchema == null) methodEvidence?.typedResponses() else null
        typedResponses?.let { operation.put("responses", it) }
        operation.putStaticOperationFacts(route, methodEvidence, mergeHint)
        verifiedRecordCursorField(
            appId = appId,
            appVersion = appVersion,
            fullPath = fullPath,
            operationId = operationId,
        )?.let { fieldId -> operation.put(RECORD_CURSOR_FIELD_EXTENSION, fieldId) }
        if (operationalRefreshWrite) {
            operation.put(OPERATIONAL_ACTION_EXTENSION, "refresh")
        }
        if (crudWrite != null || verifiedExactWrite != null) {
            operation.put(VERIFIED_CRUD_EXTENSION, true)
            (verifiedExactWrite?.resourceId ?: route.scalarWorkflowResourceId(fullPath))?.let { resourceId ->
                operation.put(RESOURCE_ID_EXTENSION, resourceId)
            }
        }
        if (editableSettingsWrite) {
            val bodySchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", true)
                .put("x-nextcloud-native-observed-settings-body", true)
            operation.put(
                "requestBody",
                JSONObject()
                    .put("required", true)
                    .put(
                        "content",
                        JSONObject().put(
                            "application/json",
                            JSONObject().put("schema", bodySchema),
                        ),
                    ),
            )
        } else if (settingsSetter != null) {
            val propertySchema = settingsSetter.parameter.toOpenApiSchema()
                .put(SETTINGS_WIRE_NAME_EXTENSION, settingsSetter.parameter.name)
                .put("title", settingsSetter.fieldId.humanizedPhpName())
            if (settingsSetter.parameter.type == "array") {
                propertySchema.put("format", SETTINGS_STRING_LIST_FORMAT)
            }
            val bodySchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put(
                    "properties",
                    JSONObject().put(settingsSetter.fieldId, propertySchema),
                )
                .put("required", JSONArray().put(settingsSetter.fieldId))
            operation
                .put(RESOURCE_ID_EXTENSION, settingsSetter.resourceId)
                .put(
                    "requestBody",
                    JSONObject()
                        .put("required", true)
                        .put(
                            "content",
                            JSONObject().put(
                                "application/json",
                                JSONObject().put("schema", bodySchema),
                            ),
                        ),
                )
        } else if (verifiedExactWrite?.bodySchema != null) {
            operation.put(
                "requestBody",
                JSONObject()
                    .put("required", verifiedExactWrite.required)
                    .put(
                        "content",
                        JSONObject().put(
                            "application/json",
                            JSONObject().put("schema", verifiedExactWrite.bodySchema),
                        ),
                    ),
            )
        } else if (crudWrite?.uploadedFileField != null) {
            operation.put("requestBody", staticMultipartRequestBody(crudWrite, methodEvidence))
        } else if (crudWrite?.bodyParameters?.isNotEmpty() == true) {
            val properties = JSONObject()
            val required = JSONArray()
            crudWrite.bodyParameters.forEach { parameter ->
                val base = parameter.toVerifiedCrudBodySchema(route)
                properties.put(
                    parameter.name,
                    if (base.has("x-nextcloud-native-boolean-map")) base else staticBodyProperty(parameter, base, methodEvidence),
                )
                if (parameter.required) required.put(parameter.name)
            }
            val bodySchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", properties)
            if (required.length() > 0) bodySchema.put("required", required)
            operation.put(
                "requestBody",
                JSONObject()
                    .put("required", crudWrite.bodyParameters.any(StaticPhpParameter::required))
                    .put(
                        "content",
                        JSONObject().put(
                            "application/json",
                            JSONObject().put("schema", bodySchema),
                        ),
                    ),
            )
        }
        val pathItem = paths.optJSONObject(fullPath) ?: JSONObject()
        if (pathItem.has(route.verb.lowercase())) return@forEach
        pathItem.put(route.verb.lowercase(), operation)
        paths.put(fullPath, pathItem)
        routeEvidence?.put("published", true)
    }
    // Android's platform org.json.JSONObject does not expose the newer isEmpty() API.
    // length() is available on every supported Android version and on the JVM test library.
    if (paths.length() == 0) return null
    val contract = JSONObject()
        .put("openapi", "3.0.3")
        .put(
            "info",
            JSONObject()
                .put("title", "$appId verified read-only routes")
                .put("version", appVersion),
        )
        .put("x-nextcloud-native-contract-kind", "verified-read-only-routes")
        .put("paths", paths)
        .put(STATIC_EVIDENCE_FORMAT_EXTENSION, STATIC_EVIDENCE_FORMAT)
        .put(STATIC_ROUTES_EXTENSION, staticRoutes)
    val skipped = JSONObject()
    (manifest.skippedCustomRootRoutes + evidence.skippedCustomRootRoutes).takeIf { it > 0 }
        ?.let { skipped.put("customRoot", it) }
    evidence.unreadableAttributeRoutes.takeIf { it > 0 }?.let { skipped.put("unreadableAttribute", it) }
    ambiguousRouteNames.takeIf { it > 0 }?.let { skipped.put("ambiguousName", it) }
    if (skipped.length() > 0) contract.put(SKIPPED_ROUTES_EXTENSION, skipped)
    evidence.putDocumentFacts(contract)
    return VerifiedPackageContract(
        appId = appId,
        appVersion = appVersion,
        specFile = if (routeFile != null) "appinfo/routes.php" else "lib/Controller",
        document = contract.toString(),
        contractKind = VerifiedContractKind.VerifiedReadRoutes,
    )
}

internal const val VERIFIED_READ_ROUTE_EXTENSION = "x-nextcloud-native-verified-read-route"
internal const val VERIFIED_STATIC_WRITE_EXTENSION = "x-nextcloud-native-verified-static-write"
internal const val FALLBACK_FOR_OPERATION_EXTENSION = "x-nextcloud-native-fallback-for-operation-id"
internal const val READ_FALLBACKS_EXTENSION = "x-nextcloud-native-read-fallback-operation-ids"
internal const val RECORD_CURSOR_FIELD_EXTENSION = "x-nextcloud-native-record-cursor-field"
internal const val OPERATIONAL_ACTION_EXTENSION = "x-nextcloud-native-operational-action"
internal const val VERIFIED_CRUD_EXTENSION = "x-nextcloud-native-verified-crud"

private fun verifiedRecordCursorField(
    appId: String,
    appVersion: String,
    fullPath: String,
    operationId: String,
): String? = "dateInt".takeIf {
    appId == "mail" &&
        appVersion == "5.10.9" &&
        fullPath == "/apps/mail/api/messages" &&
        operationId == "route.messages.index"
}

private data class StaticSettingsSetter(
    val resourceId: String,
    val fieldId: String,
    val parameter: StaticPhpParameter,
)

internal fun String.normalizedPhpName(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.humanizedPhpName(): String = buildString(length + 4) {
    this@humanizedPhpName.forEachIndexed { index, character ->
        if (index > 0 && character.isUpperCase()) append(' ')
        append(if (index == 0) character.uppercaseChar() else character)
    }
}

private fun String.replaceDoubleSlashes(): String {
    var value = this
    while ("//" in value) value = value.replace("//", "/")
    return value
}

private fun String.isSafeFixedPathSegment(): Boolean =
    isNotBlank() && length <= 64 && all { character ->
        character.isLetterOrDigit() || character == '.' || character == '_' || character == '-' || character == '+'
    }

private fun String.fixedRequirementPathSegmentOrNull(parameterName: String): String? =
    takeIf(String::isSafeFixedPathSegment)
        ?: fixedVersionPathSegmentOrNull(parameterName)

private fun String.fixedVersionPathSegmentOrNull(parameterName: String): String? {
    if (!parameterName.isVersionPlaceholder()) return null
    val bounded = trim()
        .removePrefix("^")
        .removeSuffix("$")
        .let { value ->
            if (value.startsWith('(') && value.endsWith(')')) value.substring(1, value.lastIndex) else value
        }
    val versions = bounded.split('|')
    if (versions.isEmpty() || versions.any { candidate ->
            !candidate.matches(Regex("v?[0-9]+(?:\\.[0-9]+){0,3}"))
        }
    ) {
        return null
    }
    return versions.maxWithOrNull(::compareVersionPathSegments)
}

private fun String.isVersionPlaceholder(): Boolean {
    val normalized = normalizedPhpName()
    return normalized == "version" || normalized.endsWith("apiversion")
}

private fun compareVersionPathSegments(left: String, right: String): Int {
    val leftParts = left.removePrefix("v").split('.').mapNotNull(String::toIntOrNull)
    val rightParts = right.removePrefix("v").split('.').mapNotNull(String::toIntOrNull)
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val compared = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (compared != 0) return compared
    }
    return left.compareTo(right)
}

private fun String.isSafeStaticRoutePath(): Boolean {
    if (!startsWith('/') || startsWith("//") || length > 512) return false
    if ('\\' in this || '?' in this || '#' in this || '%' in this) return false
    val placeholders = pathPlaceholders() ?: return false
    if (placeholders.size > 12) return false
    return split('/').none { segment -> segment == "." || segment == ".." }
}

private fun String.pathPlaceholders(): List<String>? {
    val names = mutableListOf<String>()
    var index = 0
    while (index < length) {
        if (this[index] == '}') return null
        if (this[index] != '{') {
            index += 1
            continue
        }
        val end = indexOf('}', index + 1)
        if (end < 0) return null
        val name = substring(index + 1, end)
        if (name.isBlank() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
        if ('{' in name || name in names) return null
        names += name
        index = end + 1
    }
    return names
}

private val COLLECTION_READ_METHODS = setOf(
    "index",
    "list",
    "getall",
    "findall",
    "search",
    "archived",
    "deleted",
    "getarchived",
    "upcomingcards",
)
private val STATIC_SINGLETON_PATH_NAMES = setOf(
    "capabilities",
    "config",
    "configuration",
    "details",
    "metadata",
    "preferences",
    "profile",
    "scanstate",
    "settings",
    "status",
)

private fun String.isLikelyPluralCollectionPath(): Boolean {
    if (pathPlaceholders()?.isNotEmpty() != false) return false
    val segment = substringAfterLast('/').normalizedSemanticWord()
    if (segment.isBlank() || segment in STATIC_SINGLETON_PATH_NAMES) return false
    return segment.semanticSingular() != segment
}

private fun String.isStaticSingletonPath(): Boolean =
    substringAfterLast('/').normalizedSemanticWord() in STATIC_SINGLETON_PATH_NAMES

private fun String.isEditableSettingsPath(): Boolean =
    substringAfterLast('/').normalizedSemanticWord() in setOf("config", "configuration", "preferences", "settings")

private fun String.isAncestorSettingsPathOf(descendant: String): Boolean =
    isEditableSettingsPath() && descendant.startsWith(trimEnd('/') + "/")

private fun String.settingsFieldId(): String? {
    val words = split(Regex("(?=[A-Z])|[_-]+"))
        .filter(String::isNotBlank)
        .toMutableList()
    while (words.firstOrNull()?.lowercase() in SETTINGS_METHOD_PREFIXES) words.removeAt(0)
    if (words.isEmpty()) return null
    val first = words.first().lowercase()
    val rest = words.drop(1).joinToString("") { word -> word.replaceFirstChar(Char::uppercaseChar) }
    val candidate = first + rest
    return candidate.takeIf {
        it.length <= 64 && it.all(Char::isLetterOrDigit) && !it.isSensitiveSettingField()
    }
}

private fun String.isSensitiveSettingField(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return normalized.contains("password") || normalized.contains("secret") ||
        normalized.contains("credential") || normalized.endsWith("token") ||
        normalized.contains("privatekey")
}

/**
 * A `setFlags(array $flags)` controller accepts a JSON object whose keys are flag names and whose
 * values are booleans. Treating that associative PHP array as a list makes verified Mail-style
 * state actions impossible to bind correctly. This narrow semantic shape is reusable for any
 * signed app exposing the same exact controller contract and does not infer arbitrary map writes.
 */
private fun StaticPhpParameter.toVerifiedCrudBodySchema(route: StaticRoute): JSONObject =
    if (
        type == "array" &&
        name.normalizedPhpName() == "flags" &&
        route.method.normalizedPhpName() in setOf("setflags", "updateflags")
    ) {
        JSONObject()
            .put("type", "object")
            .put("additionalProperties", JSONObject().put("type", "boolean"))
            .put("x-nextcloud-native-boolean-map", true)
    } else {
        toOpenApiSchema()
    }

private val SETTINGS_METHOD_PREFIXES = setOf("enable", "save", "set", "update", "user")
private val OPERATIONAL_REFRESH_METHODS = setOf("refresh", "sync")
private val STATIC_ROUTE_VERBS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
private val EDITABLE_SETTINGS_VERBS = setOf("POST", "PUT", "PATCH")
private val MERGE_HINT_VERBS = setOf("PUT", "PATCH")
private const val RESOURCE_ID_EXTENSION = "x-nextcloud-native-resource-id"
private const val SETTINGS_WIRE_NAME_EXTENSION = "x-nextcloud-native-wire-name"
private const val SETTINGS_STRING_LIST_FORMAT = "nextcloud-string-list"
internal const val MAX_STATIC_ROUTE_COUNT = 256
internal const val MAX_PHP_LITERAL_DEPTH = 16
internal const val MAX_PHP_LITERAL_NODES = 20_000
