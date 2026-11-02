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
): VerifiedPackageContract? {
    val routeFile = files.entries.singleOrNull { (path, _) -> path.endsWith("/appinfo/routes.php") }
        ?: return null
    val root = routeFile.key.substringBefore('/')
    if (root.isBlank()) return null
    val routeGroups = parseRouteManifest(routeFile.value.decodeToString()) ?: return null
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
        val crudWrite = if (
            route.verb != "GET" &&
            !editableSettingsWrite &&
            settingsSetter == null &&
            !operationalRefreshWrite
        ) {
            verifiedStaticCrudWrite(route, controller, fullPathPlaceholders)
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
                JSONObject()
                    .put("name", name)
                    .put("in", "path")
                    .put("required", true)
                    .put("schema", declaredParameter?.toOpenApiSchema() ?: JSONObject().put("type", "string")),
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
                            .put("schema", parameter.toOpenApiSchema()),
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
        val operationId = listOf(
            if (route.ocs) "ocs" else "route",
            route.controller,
            route.method,
        ).joinToString(".") { it.normalizedPhpName() }
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
        } else if (crudWrite?.bodyParameters?.isNotEmpty() == true) {
            val properties = JSONObject()
            val required = JSONArray()
            crudWrite.bodyParameters.forEach { parameter ->
                properties.put(
                    parameter.name,
                    parameter.toVerifiedCrudBodySchema(route),
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
    return VerifiedPackageContract(
        appId = appId,
        appVersion = appVersion,
        specFile = "appinfo/routes.php",
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

internal data class StaticApiController(
    val name: String,
    val methods: Set<String>,
    val singleParameters: Map<String, StaticPhpParameter>,
    val parameters: Map<String, List<StaticPhpParameter>>,
    val completeParameters: Map<String, List<StaticPhpParameter>>,
    val readParameters: Map<String, List<StaticPhpParameter>>,
    val methodsWithRequestInput: Set<String>,
) {
    val normalizedName: String = name.removeSuffix("Controller").normalizedPhpName()
}

private data class StaticSettingsSetter(
    val resourceId: String,
    val fieldId: String,
    val parameter: StaticPhpParameter,
)

private data class StaticControllerHeader(
    val name: String,
    val baseName: String,
)

private fun parseControllerHeader(source: String): StaticControllerHeader? = runCatching {
    val tokens = PhpLiteralLexer(source).tokens()
    val classIndex = tokens.indexOfFirst { token -> token is PhpToken.Word && token.value == "class" }
    val className = (tokens.getOrNull(classIndex + 1) as? PhpToken.Word)?.value ?: return null
    val extendsIndex = (classIndex + 2 until tokens.size).firstOrNull { index ->
        val token = tokens[index]
        token is PhpToken.Word && token.value == "extends"
    } ?: return null
    val openingBrace = (extendsIndex + 1 until tokens.size).firstOrNull { index ->
        val token = tokens[index]
        token is PhpToken.Symbol && token.value == '{'
    } ?: return null
    val baseName = tokens.subList(extendsIndex + 1, openingBrace)
        .filterIsInstance<PhpToken.Word>()
        .lastOrNull()
        ?.value
        ?: return null
    StaticControllerHeader(name = className, baseName = baseName)
}.getOrNull()

private fun resolveTrustedControllerBase(
    header: StaticControllerHeader,
    headers: Map<String, StaticControllerHeader>,
): String? {
    var current = header
    val visited = mutableSetOf(header.name.normalizedPhpName())
    repeat(MAX_STATIC_CONTROLLER_INHERITANCE_DEPTH) {
        if (current.baseName in STATIC_READ_CONTROLLER_BASES) return current.baseName
        val nextName = current.baseName.normalizedPhpName()
        if (!visited.add(nextName)) return null
        current = headers[nextName] ?: return null
    }
    return null
}

private fun parseApiController(
    source: String,
    trustedBase: String,
): StaticApiController? = runCatching {
    val tokens = PhpLiteralLexer(source).tokens()
    val classIndex = tokens.indexOfFirst { token -> token is PhpToken.Word && token.value == "class" }
    val className = (tokens.getOrNull(classIndex + 1) as? PhpToken.Word)?.value ?: return null
    val methodEntries = tokens.indices.mapNotNull { index ->
        val token = tokens[index]
        if (token !is PhpToken.Word || token.value != "function") return@mapNotNull null
        val nameIndex = (index + 1 until tokens.size).firstOrNull { candidate ->
            tokens[candidate] is PhpToken.Word
        } ?: return@mapNotNull null
        val methodName = (tokens[nameIndex] as PhpToken.Word).value.normalizedPhpName()
        val safe = trustedBase == "ApiController" || trustedBase == "OCSController" ||
            hasSafeJsonReturnType(tokens, nameIndex) ||
            hasOnlyDataResponseReturns(tokens, nameIndex)
        if (!safe) return@mapNotNull null
        ParsedStaticMethod(
            name = methodName,
            singleParameter = singleSerializableParameter(tokens, nameIndex),
            parameters = serializableParameters(tokens, nameIndex),
            readParameters = serializableParameters(tokens, nameIndex, allowScalarUnions = true),
            completeParameters = serializableParameters(
                tokens = tokens,
                methodNameIndex = nameIndex,
                allowUnsupportedOptional = false,
            ),
            readsRequestInput = methodBodyTokens(tokens, nameIndex)
                ?.containsRequestInputAccess() == true,
        )
    }
    StaticApiController(
        name = className,
        methods = methodEntries.mapTo(linkedSetOf(), ParsedStaticMethod::name),
        singleParameters = methodEntries.mapNotNull { method ->
            method.singleParameter?.let { method.name to it }
        }.toMap(),
        parameters = methodEntries.mapNotNull { method ->
            method.parameters?.let { method.name to it }
        }.toMap(),
        completeParameters = methodEntries.mapNotNull { method ->
            method.completeParameters?.let { method.name to it }
        }.toMap(),
        readParameters = methodEntries.mapNotNull { method ->
            method.readParameters?.let { method.name to it }
        }.toMap(),
        methodsWithRequestInput = methodEntries
            .filter(ParsedStaticMethod::readsRequestInput)
            .mapTo(linkedSetOf(), ParsedStaticMethod::name),
    )
}.getOrNull()

private data class ParsedStaticMethod(
    val name: String,
    val singleParameter: StaticPhpParameter?,
    val parameters: List<StaticPhpParameter>?,
    val completeParameters: List<StaticPhpParameter>?,
    val readParameters: List<StaticPhpParameter>?,
    val readsRequestInput: Boolean,
)

/**
 * Accepts legacy controllers which omit a return type only when every explicit return constructs
 * Nextcloud's JSON-only DataResponse family. This keeps page, redirect, download and stream
 * controllers outside the contract while supporting older apps whose API class extends the generic
 * Controller base.
 */
private fun hasOnlyDataResponseReturns(tokens: List<PhpToken>, methodNameIndex: Int): Boolean {
    val body = methodBodyTokens(tokens, methodNameIndex) ?: return false
    val returns = body.indices.filter { index ->
        (body[index] as? PhpToken.Word)?.value == "return"
    }
    if (returns.isEmpty()) return false
    return returns.all { returnIndex ->
        val end = (returnIndex + 1 until body.size).firstOrNull { index ->
            (body[index] as? PhpToken.Symbol)?.value == ';'
        } ?: return false
        body.subList(returnIndex + 1, end).constructsOnlySafeDataResponse()
    }
}

private fun List<PhpToken>.constructsOnlySafeDataResponse(): Boolean {
    val constructedTypes = indices.mapNotNull { index ->
        if ((get(index) as? PhpToken.Word)?.value != "new") return@mapNotNull null
        val openingParenthesis = (index + 1 until size).firstOrNull { candidate ->
            (get(candidate) as? PhpToken.Symbol)?.value == '('
        } ?: return@mapNotNull null
        subList(index + 1, openingParenthesis)
            .filterIsInstance<PhpToken.Word>()
            .lastOrNull()
            ?.value
    }
    return constructedTypes.isNotEmpty() &&
        constructedTypes.all { type -> type in STATIC_JSON_RESPONSE_TYPES }
}

private fun methodBodyTokens(tokens: List<PhpToken>, methodNameIndex: Int): List<PhpToken>? {
    val openingParenthesis = (methodNameIndex + 1 until tokens.size).firstOrNull { index ->
        (tokens[index] as? PhpToken.Symbol)?.value == '('
    } ?: return null
    var parenthesisDepth = 0
    var signatureEnd = -1
    for (index in openingParenthesis until tokens.size) {
        val symbol = tokens[index] as? PhpToken.Symbol ?: continue
        when (symbol.value) {
            '(' -> parenthesisDepth += 1
            ')' -> {
                parenthesisDepth -= 1
                if (parenthesisDepth == 0) {
                    signatureEnd = index
                    break
                }
            }
        }
    }
    if (signatureEnd < 0) return null
    val bodyStart = (signatureEnd + 1 until tokens.size).firstOrNull { index ->
        (tokens[index] as? PhpToken.Symbol)?.value.let { symbol -> symbol == '{' || symbol == ';' }
    } ?: return null
    if ((tokens[bodyStart] as? PhpToken.Symbol)?.value != '{') return null
    var bodyDepth = 0
    for (index in bodyStart until tokens.size) {
        val symbol = tokens[index] as? PhpToken.Symbol ?: continue
        when (symbol.value) {
            '{' -> bodyDepth += 1
            '}' -> {
                bodyDepth -= 1
                if (bodyDepth == 0) return tokens.subList(bodyStart + 1, index)
            }
        }
    }
    return null
}

private fun List<PhpToken>.containsRequestInputAccess(): Boolean {
    val words = filterIsInstance<PhpToken.Word>().map { token -> token.value.lowercase() }
    if ("request" !in words) return false
    return words.any { word -> word in STATIC_REQUEST_INPUT_ACCESSORS }
}

private fun hasSafeJsonReturnType(tokens: List<PhpToken>, methodNameIndex: Int): Boolean {
    val open = (methodNameIndex + 1 until tokens.size).firstOrNull { index ->
        val token = tokens[index]
        token is PhpToken.Symbol && token.value == '('
    } ?: return false
    var depth = 0
    var close = -1
    for (index in open until tokens.size) {
        val token = tokens[index]
        if (token is PhpToken.Symbol) {
            if (token.value == '(') depth += 1
            if (token.value == ')') {
                depth -= 1
                if (depth == 0) {
                    close = index
                    break
                }
            }
        }
    }
    if (close < 0) return false
    val bodyStart = (close + 1 until tokens.size).firstOrNull { index ->
        val token = tokens[index]
        token is PhpToken.Symbol && (token.value == '{' || token.value == ';')
    } ?: return false
    val signature = tokens.subList(close + 1, bodyStart)
    if (signature.none { token -> token is PhpToken.Symbol && token.value == ':' }) return false
    val returnTypes = signature.filterIsInstance<PhpToken.Word>()
        .map { token -> token.value }
        .filterNot { value -> value == "null" }
    return returnTypes.isNotEmpty() && returnTypes.all(String::isSafeStaticJsonReturnType)
}

private fun String.isSafeStaticJsonReturnType(): Boolean {
    if (this in STATIC_JSON_RETURN_TYPES) return true
    if (isEmpty() || length > 128 || !first().isUpperCase()) return false
    if (!all { character -> character.isLetterOrDigit() || character == '_' }) return false
    // Nextcloud page, redirect, download, stream, and other transport response objects are not
    // native data contracts. App-domain entities such as Deck's Card are serialized by the
    // framework and are useful safe GET results.
    return !endsWith("Response") && this !in STATIC_NON_DATA_RETURN_TYPES
}

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

private val STATIC_READ_CONTROLLER_BASES = setOf("ApiController", "OCSController", "Controller")
private val STATIC_JSON_RETURN_TYPES = setOf("array", "DataResponse", "JSONResponse", "JsonResponse")
private val STATIC_JSON_RESPONSE_TYPES = setOf("DataResponse", "JSONResponse", "JsonResponse")
private val STATIC_NON_DATA_RETURN_TYPES = setOf("Response", "IResponse")
private val STATIC_REQUEST_INPUT_ACCESSORS = setOf(
    "post", "put", "patch", "delete", "getparam", "getparams", "getuploadedfile",
)
private val SETTINGS_METHOD_PREFIXES = setOf("enable", "save", "set", "update", "user")
private val OPERATIONAL_REFRESH_METHODS = setOf("refresh", "sync")
private val STATIC_ROUTE_VERBS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
private val EDITABLE_SETTINGS_VERBS = setOf("POST", "PUT", "PATCH")
private const val RESOURCE_ID_EXTENSION = "x-nextcloud-native-resource-id"
private const val SETTINGS_WIRE_NAME_EXTENSION = "x-nextcloud-native-wire-name"
private const val SETTINGS_STRING_LIST_FORMAT = "nextcloud-string-list"
internal const val MAX_STATIC_ROUTE_COUNT = 256
private const val MAX_STATIC_CONTROLLER_INHERITANCE_DEPTH = 8
internal const val MAX_PHP_LITERAL_DEPTH = 16
internal const val MAX_PHP_LITERAL_NODES = 20_000
