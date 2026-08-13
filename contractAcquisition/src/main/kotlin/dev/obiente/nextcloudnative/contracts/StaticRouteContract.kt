package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
        val verifiedChoresWrite = verifiedChoresWrite(
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
            verifiedChoresWrite == null
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
            crudWrite == null && verifiedChoresWrite == null
        ) {
            return@forEach
        }

        val parameters = JSONArray()
        val pathParameterNames = fullPathPlaceholders
        pathParameterNames.forEach { name ->
            val declaredParameter = controller.parameters[route.method.normalizedPhpName()]
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
            controller.parameters[route.method.normalizedPhpName()].orEmpty()
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
                verifiedChoresWrite?.label
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
        if (operationalRefreshWrite) {
            operation.put(OPERATIONAL_ACTION_EXTENSION, "refresh")
        }
        if (crudWrite != null || verifiedChoresWrite != null) {
            operation.put(VERIFIED_CRUD_EXTENSION, true)
            (verifiedChoresWrite?.resourceId ?: route.scalarWorkflowResourceId(fullPath))?.let { resourceId ->
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
        } else if (verifiedChoresWrite?.bodySchema != null) {
            operation.put(
                "requestBody",
                JSONObject()
                    .put("required", verifiedChoresWrite.required)
                    .put(
                        "content",
                        JSONObject().put(
                            "application/json",
                            JSONObject().put("schema", verifiedChoresWrite.bodySchema),
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

/**
 * Retains the typed OpenAPI contract as the preferred surface and adds independently verified GET
 * routes from the same signed package. Equivalent collection reads are linked explicitly so a
 * caller can retry the verified route after an empty or failed preferred response without exposing
 * a duplicate primary action. Writes are imported only when the static verifier proved their
 * complete scalar CRUD signature, or when they are narrowly verified, body-free sync or refresh
 * operations used to recover a stale server-side collection.
 */
internal fun mergeOpenApiWithVerifiedReadRoutes(
    openApi: VerifiedPackageContract,
    verifiedReadRoutes: VerifiedPackageContract,
): VerifiedPackageContract {
    require(openApi.contractKind == VerifiedContractKind.OpenApi)
    require(verifiedReadRoutes.contractKind == VerifiedContractKind.VerifiedReadRoutes)
    require(openApi.appId == verifiedReadRoutes.appId && openApi.appVersion == verifiedReadRoutes.appVersion)

    val document = JSONObject(openApi.document)
    val paths = document.getJSONObject("paths")
    val routeDocument = JSONObject(verifiedReadRoutes.document)
    val routePaths = routeDocument.getJSONObject("paths")
    val preferredCollections = paths.keys().asSequence().sorted().mapNotNull { path ->
        val pathItem = paths.optJSONObject(path) ?: return@mapNotNull null
        val operation = pathItem.optJSONObject("get") ?: return@mapNotNull null
        if (!operation.isCollectionRead(document)) return@mapNotNull null
        val operationId = operation.optString("operationId").takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        CollectionReadContract(
            operationId = operationId,
            semanticResource = path.semanticCollectionResource() ?: return@mapNotNull null,
            requiredInputs = requiredReadInputs(pathItem, operation),
            operation = operation,
        )
    }.toList()
    var addedRoutes = 0
    var linkedFallbacks = 0
    var importedStaticWrites = 0

    routePaths.keys().asSequence().sorted().forEach { path ->
        val routePathItem = routePaths.optJSONObject(path) ?: return@forEach
        VERIFIED_STATIC_WRITE_METHODS.forEach writeMethod@ { method ->
            val routeWrite = routePathItem.optJSONObject(method)
                ?.takeIf { operation -> operation.optBoolean(VERIFIED_CRUD_EXTENSION) }
                ?: return@writeMethod
            val targetPathItem = paths.optJSONObject(path) ?: JSONObject().also { paths.put(path, it) }
            if (targetPathItem.optJSONObject(method) == null) {
                targetPathItem.put(
                    method,
                    JSONObject(routeWrite.toString()).put(VERIFIED_STATIC_WRITE_EXTENSION, true),
                )
                importedStaticWrites += 1
                addedRoutes += 1
            }
        }
        val routeOperationalPost = routePathItem.optJSONObject("post")
            ?.takeIf { operation -> operation.optString(OPERATIONAL_ACTION_EXTENSION) == "refresh" }
        if (routeOperationalPost != null) {
            val targetPathItem = paths.optJSONObject(path) ?: JSONObject().also { paths.put(path, it) }
            if (targetPathItem.optJSONObject("post") == null) {
                targetPathItem.put(
                    "post",
                    JSONObject(routeOperationalPost.toString()).put(VERIFIED_READ_ROUTE_EXTENSION, true),
                )
                addedRoutes += 1
            }
        }
        val routeOperation = routePathItem.optJSONObject("get") ?: return@forEach
        val existingPathItem = paths.optJSONObject(path)
        if (existingPathItem?.optJSONObject("get") != null) return@forEach

        val importedOperation = JSONObject(routeOperation.toString())
            .put(VERIFIED_READ_ROUTE_EXTENSION, true)
        val semanticResource = path.semanticCollectionResource()
        if (routeOperation.isCollectionRead(routeDocument) && semanticResource != null) {
            val requiredInputs = requiredReadInputs(routePathItem, routeOperation)
            preferredCollections.firstOrNull { preferred ->
                preferred.semanticResource == semanticResource &&
                    preferred.requiredInputs.sameSemanticInputs(requiredInputs)
            }?.let { preferred ->
                importedOperation.put(FALLBACK_FOR_OPERATION_EXTENSION, preferred.operationId)
                val fallbackOperationId = importedOperation.optString("operationId")
                if (fallbackOperationId.isNotBlank()) {
                    val fallbacks = preferred.operation.optJSONArray(READ_FALLBACKS_EXTENSION) ?: JSONArray().also {
                        preferred.operation.put(READ_FALLBACKS_EXTENSION, it)
                    }
                    if ((0 until fallbacks.length()).none { index ->
                            fallbacks.optString(index) == fallbackOperationId
                        }
                    ) {
                        fallbacks.put(fallbackOperationId)
                        linkedFallbacks += 1
                    }
                }
            }
        }
        val targetPathItem = existingPathItem ?: JSONObject().also { paths.put(path, it) }
        targetPathItem.put("get", importedOperation)
        addedRoutes += 1
    }

    if (addedRoutes == 0) return openApi
    document.put("x-nextcloud-native-contract-kind", "openapi-with-verified-read-routes")
    document.put("x-nextcloud-native-verified-read-route-count", addedRoutes - importedStaticWrites)
    document.put("x-nextcloud-native-linked-read-fallback-count", linkedFallbacks)
    document.put("x-nextcloud-native-verified-static-write-count", importedStaticWrites)
    return openApi.copy(
        document = document.toString(),
        contractKind = VerifiedContractKind.OpenApiWithVerifiedReadRoutes,
    )
}

private data class CollectionReadContract(
    val operationId: String,
    val semanticResource: String,
    val requiredInputs: Set<String>,
    val operation: JSONObject,
)

private fun requiredReadInputs(pathItem: JSONObject, operation: JSONObject): Set<String> = buildSet {
    sequenceOf(pathItem.optJSONArray("parameters"), operation.optJSONArray("parameters"))
        .filterNotNull()
        .forEach { parameters ->
            repeat(parameters.length()) { index ->
                val parameter = parameters.optJSONObject(index) ?: return@repeat
                val location = parameter.optString("in")
                if (location != "path" && location != "query") return@repeat
                if (!parameter.optBoolean("required", location == "path")) return@repeat
                val name = parameter.optString("name").normalizedSemanticWord()
                if (name.isNotBlank()) add("$location:$name")
            }
        }
}

/**
 * A verified legacy route may move the same required input between a path segment and a query
 * parameter. The runtime still binds the exact declared location, so fallback equivalence is based
 * on the normalized input names while preserving both contracts unchanged.
 */
private fun Set<String>.sameSemanticInputs(other: Set<String>): Boolean {
    val left = mapTo(linkedSetOf()) { input -> input.substringAfter(':') }
    val right = other.mapTo(linkedSetOf()) { input -> input.substringAfter(':') }
    if (left == right) return true
    if (left.size != 1 || right.size != 1) return false
    val leftName = left.single()
    val rightName = right.single()
    return (leftName == "id" && rightName.endsWith("id")) ||
        (rightName == "id" && leftName.endsWith("id"))
}

private fun JSONObject.isCollectionRead(document: JSONObject): Boolean {
    val responses = optJSONObject("responses") ?: return false
    val response = responses.keys().asSequence()
        .filter { status -> status == "200" || status.matches(Regex("2[0-9Xx]{2}")) }
        .sorted()
        .mapNotNull(responses::optJSONObject)
        .firstOrNull() ?: return false
    val content = response.optJSONObject("content") ?: return false
    val media = content.optJSONObject("application/json")
        ?: content.keys().asSequence().sorted().mapNotNull(content::optJSONObject).firstOrNull()
        ?: return false
    val schema = media.optJSONObject("schema") ?: return false
    return schema.collectionShape(document, mutableSetOf(), 0)
}

private fun JSONObject.collectionShape(
    document: JSONObject,
    visitedReferences: MutableSet<String>,
    depth: Int,
): Boolean {
    if (depth > 8) return false
    val reference = optString("${'$'}ref").takeIf { it.startsWith("#/") }
    if (reference != null && visitedReferences.add(reference)) {
        val resolved = document.resolveLocalObject(reference)
        if (resolved?.collectionShape(document, visitedReferences, depth + 1) == true) return true
    }
    if (optString("type") == "array") return true
    if (optString("type") == "object" && opt("additionalProperties") is JSONObject) return true
    val properties = optJSONObject("properties")
    listOf("ocs", "data").forEach { name ->
        if (properties?.optJSONObject(name)?.collectionShape(document, visitedReferences, depth + 1) == true) {
            return true
        }
    }
    listOf("allOf", "oneOf", "anyOf").forEach { keyword ->
        val branches = optJSONArray(keyword) ?: return@forEach
        repeat(branches.length()) { index ->
            if (branches.optJSONObject(index)?.collectionShape(document, visitedReferences, depth + 1) == true) {
                return true
            }
        }
    }
    return false
}

private fun JSONObject.resolveLocalObject(reference: String): JSONObject? {
    var current: Any = this
    reference.removePrefix("#/").split('/').forEach { raw ->
        val key = raw.replace("~1", "/").replace("~0", "~")
        current = (current as? JSONObject)?.opt(key) ?: return null
    }
    return current as? JSONObject
}

private fun String.semanticCollectionResource(): String? {
    val candidates = split('/').asSequence()
        .filter(String::isNotBlank)
        .filterNot { segment -> segment.startsWith('{') && segment.endsWith('}') }
        .map { segment -> segment.normalizedSemanticWord() }
        .filter(String::isNotBlank)
        .filterNot { segment ->
            segment in SEMANTIC_PATH_NOISE || segment.matches(Regex("v?[0-9]+"))
        }
        .toList()
    return candidates.lastOrNull()?.semanticSingular()
}

private fun String.normalizedSemanticWord(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.semanticSingular(): String = when {
    endsWith("ies") && length > 3 -> dropLast(3) + "y"
    endsWith("ches") || endsWith("shes") -> dropLast(2)
    endsWith("ses") || endsWith("xes") || endsWith("zes") -> dropLast(2)
    endsWith('s') && length > 1 -> dropLast(1)
    else -> this
}

internal const val VERIFIED_READ_ROUTE_EXTENSION = "x-nextcloud-native-verified-read-route"
internal const val VERIFIED_STATIC_WRITE_EXTENSION = "x-nextcloud-native-verified-static-write"
internal const val FALLBACK_FOR_OPERATION_EXTENSION = "x-nextcloud-native-fallback-for-operation-id"
internal const val READ_FALLBACKS_EXTENSION = "x-nextcloud-native-read-fallback-operation-ids"
internal const val OPERATIONAL_ACTION_EXTENSION = "x-nextcloud-native-operational-action"
internal const val VERIFIED_CRUD_EXTENSION = "x-nextcloud-native-verified-crud"

private val SEMANTIC_PATH_NOISE = setOf(
    "api", "apps", "indexphp", "ocs", "ocsv1php", "ocsv2php", "list", "index", "getall", "findall",
)

private data class StaticRoute(
    val controller: String,
    val method: String,
    val url: String,
    val verb: String,
    val ocs: Boolean,
    val requirements: Map<String, String>,
)

private data class StaticApiController(
    val name: String,
    val methods: Set<String>,
    val singleParameters: Map<String, StaticPhpParameter>,
    val parameters: Map<String, List<StaticPhpParameter>>,
    val completeParameters: Map<String, List<StaticPhpParameter>>,
    val methodsWithRequestInput: Set<String>,
) {
    val normalizedName: String = name.removeSuffix("Controller").normalizedPhpName()
}

private data class StaticPhpParameter(
    val name: String,
    val type: String,
    val required: Boolean = true,
)

private data class StaticSettingsSetter(
    val resourceId: String,
    val fieldId: String,
    val parameter: StaticPhpParameter,
)

private data class StaticCrudWrite(
    val bodyParameters: List<StaticPhpParameter>,
)

private data class VerifiedChoresWrite(
    val label: String,
    val resourceId: String,
    val bodySchema: JSONObject?,
    val required: Boolean = true,
)

private fun isVerifiedChoresController(
    appId: String,
    appVersion: String,
    controller: StaticApiController,
    controllerSource: String?,
): Boolean =
    appId == "chores" && appVersion == "0.1.0" &&
        controller.normalizedName == "api" && controllerSource != null &&
        controllerSource.sha256() == CHORES_0_1_0_API_CONTROLLER_SHA256

/**
 * Declares the exact identities and invitation bindings serialized by the pinned Chores
 * controller. These response shapes are coupled to the same signed-source digest as the write
 * adapter so record values can authorize mutations only while both sides of the contract match.
 */
private fun verifiedChoresResponseSchema(
    appId: String,
    appVersion: String,
    route: StaticRoute,
    fullPath: String,
    controller: StaticApiController,
    controllerSource: String?,
): JSONObject? {
    if (route.verb != "GET" || !isVerifiedChoresController(
            appId = appId,
            appVersion = appVersion,
            controller = controller,
            controllerSource = controllerSource,
        )
    ) {
        return null
    }
    val inviteSchema = closedObjectSchema(
        properties = mapOf(
            "inviteId" to stringSchema(title = "Invitation"),
            "teamId" to integerSchema(title = "Team"),
            "teamName" to stringSchema(title = "Team name"),
            "userId" to stringSchema(title = "User"),
        ),
        required = listOf("inviteId", "teamId", "teamName", "userId"),
    )
    return when (fullPath) {
        "/apps/chores/api/v1.0/team",
        "/apps/chores/api/v1.0/account/team",
        -> closedObjectSchema(
            properties = mapOf(
                "id" to integerSchema(title = "Team"),
                "name" to stringSchema(title = "Team name"),
                "owner" to stringSchema(title = "Owner"),
                "members" to JSONObject()
                    .put("type", "array")
                    .put(
                        "items",
                        closedObjectSchema(
                            properties = mapOf(
                                "team_id" to integerSchema(title = "Team"),
                                "member" to stringSchema(title = "Member"),
                                "displayName" to stringSchema(title = "Display name"),
                                "points" to integerSchema(title = "Points"),
                            ),
                            required = listOf("team_id", "member", "displayName", "points"),
                        ),
                    ),
                "invites" to JSONObject().put("type", "array").put("items", inviteSchema),
            ),
            required = listOf("id", "name", "owner", "members", "invites"),
        )
        "/apps/chores/api/v1.0/account/invites" ->
            JSONObject().put("type", "array").put("items", inviteSchema)
        "/apps/chores/api/v1.0/team/{teamId}/chores" ->
            JSONObject().put(
                "type",
                "array",
            ).put(
                "items",
                closedObjectSchema(
                    properties = mapOf(
                        "id" to integerSchema(title = "Chore"),
                        "name" to stringSchema(title = "Chore name"),
                        "assignee" to nullableStringSchema(title = "Assignee"),
                        "points" to integerSchema(title = "Points"),
                        "due" to stringSchema(title = "Due", format = "date-time"),
                        "repeat" to choresRepeatScheduleSchema(),
                    ),
                    required = listOf("id", "name", "points", "due", "repeat"),
                ),
            )
        else -> null
    }
}

/**
 * Imports the mutation shapes from the exact signed Chores 0.1.0 controller audited alongside its
 * web client. These routes hide their JSON inputs behind IRequest, so the general scalar verifier
 * correctly rejects them. Pinning the controller digest keeps this exception fail-closed: a future
 * Chores release must be audited before its changed write contract becomes callable.
 */
private fun verifiedChoresWrite(
    appId: String,
    appVersion: String,
    route: StaticRoute,
    fullPath: String,
    controller: StaticApiController,
    controllerSource: String?,
): VerifiedChoresWrite? {
    if (!isVerifiedChoresController(appId, appVersion, controller, controllerSource)) {
        return null
    }
    return when (route.verb to fullPath) {
        "POST" to "/apps/chores/api/v1.0/team" -> VerifiedChoresWrite(
            label = "Create team",
            resourceId = "team",
            bodySchema = closedObjectSchema(
                properties = mapOf("name" to stringSchema(title = "Team name")),
                required = listOf("name"),
            ),
        )
        "POST" to "/apps/chores/api/v1.0/team/{teamId}/invites" -> VerifiedChoresWrite(
            label = "Invite member",
            resourceId = "team",
            bodySchema = closedObjectSchema(
                properties = mapOf("userId" to stringSchema(title = "Nextcloud user")),
                required = listOf("userId"),
            ),
        )
        "POST" to "/apps/chores/api/v1.0/account/invites/accept" -> VerifiedChoresWrite(
            label = "Accept invitation",
            resourceId = "invitations",
            bodySchema = closedObjectSchema(
                properties = mapOf("teamId" to JSONObject().put("type", "integer").put("title", "Team")),
                required = listOf("teamId"),
            ),
        )
        "POST" to "/apps/chores/api/v1.0/team/{teamId}/chores" -> VerifiedChoresWrite(
            label = "Add chore",
            resourceId = "chores",
            bodySchema = closedObjectSchema(
                properties = mapOf(
                    "chores" to JSONObject()
                        .put("type", "array")
                        .put("format", "nextcloud-repeatable-object-array")
                        .put("minItems", 1)
                        .put("maxItems", 1)
                        .put(
                            "items",
                            closedObjectSchema(
                                properties = mapOf(
                                    "name" to stringSchema(title = "Chore name"),
                                    "points" to JSONObject().put("type", "integer").put("minimum", 1)
                                        .put("maximum", 6).put("title", "Points (1-6)"),
                                    "due" to stringSchema(title = "Due", format = "date-time"),
                                    "repeat" to choresRepeatScheduleSchema(),
                                ),
                                required = listOf("name", "points", "due", "repeat"),
                            ),
                        ),
                ),
                required = listOf("chores"),
            ),
        )
        "PATCH" to "/apps/chores/api/v1.0/team/{teamId}/chores/{choreId}" -> VerifiedChoresWrite(
            label = "Edit chore",
            resourceId = "chores",
            bodySchema = closedObjectSchema(
                properties = mapOf(
                    "name" to stringSchema(title = "Chore name"),
                    "assignee" to stringSchema(title = "Assignee"),
                    "points" to JSONObject().put("type", "integer").put("minimum", 0).put("title", "Points"),
                    "due" to stringSchema(title = "Due", format = "date-time"),
                    "repeat" to choresRepeatScheduleSchema(),
                ),
                required = emptyList(),
            ),
            required = false,
        )
        "POST" to "/apps/chores/api/v1.0/team/{teamId}/work" -> VerifiedChoresWrite(
            label = "Mark as done",
            resourceId = "chores",
            bodySchema = closedObjectSchema(
                properties = mapOf(
                    "work" to JSONObject()
                        .put("type", "array")
                        .put("format", "nextcloud-repeatable-object-array")
                        .put("minItems", 1)
                        .put("maxItems", 1)
                        .put(
                            "items",
                            closedObjectSchema(
                                properties = mapOf(
                                    "id" to stringSchema(title = "Completion id", format = "uuid"),
                                    "work_time" to stringSchema(title = "Completed at", format = "date-time"),
                                    "chore_id" to JSONObject().put("type", "integer").put("title", "Chore"),
                                    "member" to stringSchema(title = "Member"),
                                ),
                                required = listOf("id", "work_time", "chore_id", "member"),
                            ),
                        ),
                ),
                required = listOf("work"),
            ),
        )
        "DELETE" to "/apps/chores/api/v1.0/team/{teamId}/members/{userIdToRemove}" ->
            VerifiedChoresWrite(
                label = "Remove member",
                resourceId = "team",
                bodySchema = null,
                required = false,
            )
        else -> null
    }
}

private fun closedObjectSchema(
    properties: Map<String, JSONObject>,
    required: List<String>,
): JSONObject = JSONObject()
    .put("type", "object")
    .put("additionalProperties", false)
    .put("properties", JSONObject(properties))
    .also { schema ->
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
    }

private fun stringSchema(title: String, format: String? = null): JSONObject = JSONObject()
    .put("type", "string")
    .put("title", title)
    .also { schema -> format?.let { schema.put("format", it) } }

private fun integerSchema(title: String): JSONObject = JSONObject()
    .put("type", "integer")
    .put("title", title)

private fun nullableStringSchema(title: String): JSONObject = JSONObject()
    .put("type", JSONArray().put("string").put("null"))
    .put("title", title)

private fun choresRepeatScheduleSchema(): JSONObject {
    val choices = linkedMapOf(
        "s:1:-" to "Does not repeat",
        "o:1" to "On demand",
        "d:1" to "Every day",
        "d:2" to "Every 2 days",
        "d:3" to "Every 3 days",
        "d:4" to "Every 4 days",
        "d:5" to "Every 5 days",
        "d:6" to "Every 6 days",
        "w:1" to "Every week",
        "w:2" to "Every 2 weeks",
        "w:3" to "Every 3 weeks",
        "w:4" to "Every 4 weeks",
        "m:1" to "Every month",
        "m:2" to "Every 2 months",
        "m:3" to "Every 3 months",
        "m:4" to "Every 4 months",
        "m:6" to "Every 6 months",
        "m:12" to "Every year",
    )
    return stringSchema(title = "Repeat")
        .put("enum", JSONArray(choices.keys))
        .put("x-nextcloud-native-enum-labels", JSONObject(choices))
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val CHORES_0_1_0_API_CONTROLLER_SHA256 =
    "146286dcb68bddd025e0a47e7edc134fbc94f0e9f594e9030663bb0f217f3cc6"

/**
 * Proves only conventional scalar CRUD signatures. Every route placeholder and every required
 * controller argument must have one serializable declaration. Unsupported optional arguments are
 * omitted because the request never needs to send them and PHP will use their declared defaults.
 * Every remaining supported argument becomes a closed JSON body field.
 */
private fun verifiedStaticCrudWrite(
    route: StaticRoute,
    controller: StaticApiController,
    pathPlaceholders: List<String>,
): StaticCrudWrite? {
    val method = route.method.normalizedPhpName()
    if (method in controller.methodsWithRequestInput) return null
    val conventional = when (route.verb) {
        "POST" -> CRUD_CREATE_METHOD_PREFIXES.any(method::startsWith) || method in SCALAR_WORKFLOW_METHODS
        "PUT" -> CRUD_UPDATE_METHOD_PREFIXES.any(method::startsWith) || method in SCALAR_WORKFLOW_METHODS
        "PATCH" -> CRUD_PATCH_METHOD_PREFIXES.any(method::startsWith) || method in SCALAR_WORKFLOW_METHODS
        "DELETE" -> CRUD_DELETE_METHOD_PREFIXES.any(method::startsWith)
        else -> false
    }
    if (!conventional) return null

    val parameters = controller.parameters[method] ?: return null
    if (parameters.size > MAX_STATIC_CRUD_PARAMETERS) return null
    val pathParameters = pathPlaceholders.mapNotNull { placeholder ->
        parameters.singleOrNull { parameter -> parameter.name.equals(placeholder, ignoreCase = true) }
    }
    val unmatchedPathParameters = pathPlaceholders.filter { placeholder ->
        pathParameters.none { parameter -> parameter.name.equals(placeholder, ignoreCase = true) }
    }
    if (
        unmatchedPathParameters.isNotEmpty() &&
        (method !in SCALAR_WORKFLOW_METHODS || unmatchedPathParameters.any { !it.isSafeIdentityPlaceholder() })
    ) {
        return null
    }
    if (pathParameters.distinctBy { parameter -> parameter.name.lowercase() }.size != pathParameters.size) {
        return null
    }
    val bodyParameters = parameters.filterNot { parameter ->
        pathPlaceholders.any { placeholder -> placeholder.equals(parameter.name, ignoreCase = true) }
    }
    if (bodyParameters.any { parameter -> parameter.name.isSensitiveWriteField() }) return null
    return when (route.verb) {
        "DELETE" -> StaticCrudWrite(bodyParameters = emptyList()).takeIf { bodyParameters.isEmpty() }
        "POST", "PUT", "PATCH" -> StaticCrudWrite(bodyParameters).takeIf {
            bodyParameters.isNotEmpty() || method in BODY_FREE_SCALAR_WORKFLOW_METHODS
        }
        else -> null
    }
}

private fun String.isSafeIdentityPlaceholder(): Boolean {
    val normalized = normalizedPhpName()
    return normalized == "id" || normalized.endsWith("id")
}

/**
 * A scalar workflow route commonly appends its action after the target identity, for example
 * `/cards/{cardId}/reorder`. Without an explicit resource hint an OpenAPI consumer would infer
 * `reorder` as the resource and disconnect the action from the card records it mutates.
 *
 * This derives the target from route shape only. It is deliberately limited to the same small
 * allow-list accepted by [verifiedStaticCrudWrite], and never consults an app identifier.
 */
private fun StaticRoute.scalarWorkflowResourceId(fullPath: String): String? {
    val methodId = method.normalizedPhpName()
    if (methodId !in SCALAR_WORKFLOW_METHODS) return null
    val segments = fullPath.split('/').filter(String::isNotBlank)
    val actionIndex = segments.indexOfLast { segment -> segment.normalizedPhpName() == methodId }
    if (actionIndex <= 0) return null
    return segments.subList(0, actionIndex).asReversed().firstOrNull { segment ->
        !segment.startsWith('{') &&
            !segment.endsWith('}') &&
            !segment.matches(Regex("v?[0-9]+(?:\\.[0-9]+)*"))
    }?.normalizedSemanticWord()?.takeIf(String::isNotBlank)
}

private sealed interface PhpLiteral {
    data class Text(val value: String) : PhpLiteral
    data class Sequence(val entries: List<Entry>) : PhpLiteral
    data object Null : PhpLiteral
    data object Unsupported : PhpLiteral

    data class Entry(val key: String?, val value: PhpLiteral)
}

private sealed interface PhpToken {
    data class Word(val value: String) : PhpToken
    data class Text(val value: String) : PhpToken
    data class Symbol(val value: Char) : PhpToken
    data object Arrow : PhpToken
}

private fun parseRouteManifest(source: String): List<StaticRoute>? = runCatching {
    val tokens = PhpLiteralLexer(source).tokens()
    val root = tokens.indices
        .filter { index -> (tokens[index] as? PhpToken.Word)?.value == "return" }
        .asReversed()
        .mapNotNull { returnIndex ->
            runCatching {
                PhpLiteralParser(tokens, returnIndex + 1).parseValue() as? PhpLiteral.Sequence
            }.getOrNull()
        }
        .firstOrNull { candidate ->
            candidate.named("routes") is PhpLiteral.Sequence ||
                candidate.named("ocs") is PhpLiteral.Sequence ||
                candidate.named("resources") is PhpLiteral.Sequence
        }
        ?: return null
    buildList {
        listOf("routes" to false, "ocs" to true).forEach { (groupName, ocs) ->
            val group = root.named(groupName) as? PhpLiteral.Sequence ?: return@forEach
            group.entries.take(MAX_STATIC_ROUTE_COUNT).forEach routeLoop@ { entry ->
                val route = entry.value as? PhpLiteral.Sequence ?: return@routeLoop
                val name = route.text("name") ?: return@routeLoop
                val separator = name.indexOf('#')
                if (separator <= 0 || separator != name.lastIndexOf('#') || separator == name.lastIndex) {
                    return@routeLoop
                }
                val url = route.text("url") ?: return@routeLoop
                val verb = route.text("verb")?.uppercase() ?: return@routeLoop
                val requirements = (route.named("requirements") as? PhpLiteral.Sequence)
                    ?.entries
                    ?.mapNotNull { requirement ->
                        val key = requirement.key ?: return@mapNotNull null
                        val value = (requirement.value as? PhpLiteral.Text)?.value ?: return@mapNotNull null
                        key to value
                    }
                    ?.toMap()
                    .orEmpty()
                add(
                    StaticRoute(
                        controller = name.substring(0, separator),
                        method = name.substring(separator + 1),
                        url = url,
                        verb = verb,
                        ocs = ocs,
                        requirements = requirements,
                    ),
                )
            }
        }
        val resources = root.named("resources") as? PhpLiteral.Sequence
        resources?.entries?.take(MAX_STATIC_ROUTE_COUNT)?.forEach resourceLoop@ { entry ->
            val controller = entry.key ?: return@resourceLoop
            val resource = entry.value as? PhpLiteral.Sequence ?: return@resourceLoop
            val url = resource.text("url") ?: return@resourceLoop
            val only = resource.optionalTextList("only") ?: return@resourceLoop
            val except = resource.optionalTextList("except") ?: return@resourceLoop
            fun enabled(method: String): Boolean =
                (only.values == null || method.normalizedPhpName() in only.values) &&
                    method.normalizedPhpName() !in except.values.orEmpty()
            if (enabled("index")) {
                add(
                    StaticRoute(
                        controller = controller,
                        method = "index",
                        url = url,
                        verb = "GET",
                        ocs = false,
                        requirements = emptyMap(),
                    ),
                )
            }
            if (enabled("show")) {
                add(
                    StaticRoute(
                        controller = controller,
                        method = "show",
                        url = "${url.trimEnd('/')}/{id}",
                        verb = "GET",
                        ocs = false,
                        requirements = emptyMap(),
                    ),
                )
            }
        }
    }
}.getOrNull()

private fun PhpLiteral.Sequence.named(name: String): PhpLiteral? =
    entries.firstOrNull { entry -> entry.key == name }?.value

private fun PhpLiteral.Sequence.text(name: String): String? = (named(name) as? PhpLiteral.Text)?.value

private data class OptionalTextList(val values: Set<String>?)

private fun PhpLiteral.Sequence.optionalTextList(name: String): OptionalTextList? {
    val value = named(name) ?: return OptionalTextList(null)
    val sequence = value as? PhpLiteral.Sequence ?: return null
    val entries = sequence.entries.map { entry ->
        (entry.value as? PhpLiteral.Text)?.value?.normalizedPhpName() ?: return null
    }
    return OptionalTextList(entries.toSet())
}

private class PhpLiteralParser(
    private val tokens: List<PhpToken>,
    private var index: Int,
) {
    private var nodes = 0

    fun parseValue(depth: Int = 0): PhpLiteral {
        check(depth <= MAX_PHP_LITERAL_DEPTH) { "The route manifest is nested too deeply." }
        nodes += 1
        check(nodes <= MAX_PHP_LITERAL_NODES) { "The route manifest contains too many values." }
        return when (val token = tokens.getOrNull(index++) ?: error("The route manifest is truncated.")) {
            is PhpToken.Text -> PhpLiteral.Text(token.value)
            is PhpToken.Word -> when {
                token.value == "null" -> PhpLiteral.Null
                (tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(' -> {
                    skipUnsupportedCall()
                    PhpLiteral.Unsupported
                }
                else -> PhpLiteral.Text(token.value)
            }
            is PhpToken.Symbol -> {
                check(token.value == '[') { "Only literal arrays are supported in route metadata." }
                parseSequence(depth + 1)
            }
            PhpToken.Arrow -> error("Unexpected array arrow in route metadata.")
        }
    }

    /**
     * Keeps a manifest parseable when an entry is produced by a PHP helper. The call is consumed
     * as opaque syntax and its route is discarded; no function name, argument, or result is
     * interpreted as trusted metadata.
     */
    private fun skipUnsupportedCall() {
        check((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(')
        var depth = 0
        while (index < tokens.size) {
            val symbol = tokens[index++] as? PhpToken.Symbol ?: continue
            when (symbol.value) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return
                    check(depth >= 0) { "The route helper call is malformed." }
                }
            }
        }
        error("The route helper call is truncated.")
    }

    private fun parseSequence(depth: Int): PhpLiteral.Sequence {
        val entries = mutableListOf<PhpLiteral.Entry>()
        while (true) {
            val next = tokens.getOrNull(index) ?: error("The route manifest array is truncated.")
            if (next is PhpToken.Symbol && next.value == ']') {
                index += 1
                return PhpLiteral.Sequence(entries)
            }
            val first = parseValue(depth)
            val entry = if (tokens.getOrNull(index) == PhpToken.Arrow) {
                index += 1
                val key = (first as? PhpLiteral.Text)?.value
                    ?: error("Route manifest keys must be literal strings.")
                PhpLiteral.Entry(key, parseValue(depth))
            } else {
                PhpLiteral.Entry(null, first)
            }
            entries += entry
            val separator = tokens.getOrNull(index)
            if (separator is PhpToken.Symbol && separator.value == ',') {
                index += 1
            } else if (separator !is PhpToken.Symbol || separator.value != ']') {
                error("Route manifest array entries must be comma separated.")
            }
        }
    }
}

private class PhpLiteralLexer(private val source: String) {
    private var index = 0
    private val result = mutableListOf<PhpToken>()

    fun tokens(): List<PhpToken> {
        check(source.length <= MAX_STATIC_PHP_SOURCE_CHARS) { "The PHP metadata file is too large." }
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index += 1
                source.startsWith("//", index) -> skipLineComment()
                source.startsWith("/*", index) -> skipBlockComment()
                source[index] == '#' -> skipLineComment()
                source.startsWith("=>", index) -> {
                    result += PhpToken.Arrow
                    index += 2
                }
                source[index] == '\'' || source[index] == '"' -> result += readText()
                source[index].isLetterOrDigit() || source[index] == '_' -> result += readWord()
                else -> {
                    result += PhpToken.Symbol(source[index])
                    index += 1
                }
            }
            check(result.size <= MAX_PHP_LITERAL_TOKENS) { "The PHP metadata has too many tokens." }
        }
        return result
    }

    private fun skipLineComment() {
        while (index < source.length && source[index] != '\n') index += 1
    }

    private fun skipBlockComment() {
        val end = source.indexOf("*/", index + 2)
        check(end >= 0) { "The PHP metadata has an unterminated comment." }
        index = end + 2
    }

    private fun readText(): PhpToken.Text {
        val quote = source[index++]
        val value = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            if (character == quote) return PhpToken.Text(value.toString())
            if (character == '\\') {
                check(index < source.length) { "The PHP string literal is truncated." }
                val escaped = source[index++]
                if (escaped == quote || escaped == '\\') {
                    value.append(escaped)
                } else {
                    // PHP single-quoted route requirements commonly contain regex escapes such
                    // as \d. Preserve unknown escapes so they can never be mistaken for a fixed
                    // literal path segment.
                    value.append('\\')
                    value.append(escaped)
                }
            } else {
                value.append(character)
            }
            check(value.length <= MAX_PHP_LITERAL_STRING_CHARS) { "A PHP string literal is too long." }
        }
        error("The PHP metadata has an unterminated string literal.")
    }

    private fun readWord(): PhpToken.Word {
        val start = index
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index += 1
        return PhpToken.Word(source.substring(start, index))
    }
}

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

/**
 * Parses only ordinary scalar PHP controller arguments. Unsupported signatures simply do not
 * contribute query parameters; no PHP source is evaluated and no default expression is used.
 */
private fun serializableParameters(
    tokens: List<PhpToken>,
    methodNameIndex: Int,
    allowUnsupportedOptional: Boolean = true,
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
        parseSerializableParameter(segment) ?: if (allowUnsupportedOptional && segment.hasOptionalDefault()) {
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

private fun parseSerializableParameter(tokens: List<PhpToken>): StaticPhpParameter? {
    if (tokens.isEmpty()) return null
    val dollarIndex = tokens.indexOfFirst { token ->
        token is PhpToken.Symbol && token.value == '$'
    }
    if (dollarIndex <= 0) return null
    if (tokens.take(dollarIndex).any { token ->
            token is PhpToken.Symbol && token.value !in setOf('?')
        }
    ) return null
    val type = (tokens.getOrNull(dollarIndex - 1) as? PhpToken.Word)?.value?.lowercase()
        ?.takeIf { it in STATIC_SERIALIZABLE_PARAMETER_TYPES }
        ?: return null
    val name = (tokens.getOrNull(dollarIndex + 1) as? PhpToken.Word)?.value
        ?.takeIf(::isSafePhpParameterName)
        ?: return null
    val remainder = tokens.drop(dollarIndex + 2)
    val required = when {
        remainder.isEmpty() -> true
        (remainder.firstOrNull() as? PhpToken.Symbol)?.value == '=' -> false
        else -> return null
    }
    return StaticPhpParameter(name = name, type = type, required = required)
}

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

private fun singleSerializableParameter(
    tokens: List<PhpToken>,
    methodNameIndex: Int,
): StaticPhpParameter? {
    val open = (methodNameIndex + 1 until tokens.size).firstOrNull { index ->
        (tokens[index] as? PhpToken.Symbol)?.value == '('
    } ?: return null
    var depth = 0
    var close = -1
    for (index in open until tokens.size) {
        val symbol = tokens[index] as? PhpToken.Symbol ?: continue
        if (symbol.value == '(') depth += 1
        if (symbol.value == ')') {
            depth -= 1
            if (depth == 0) {
                close = index
                break
            }
        }
    }
    if (close <= open + 1) return null
    val signature = tokens.subList(open + 1, close)
    if (signature.any { token ->
            token is PhpToken.Symbol && token.value in setOf(',', '=', '&', '?')
        }
    ) return null
    val dollarIndex = signature.indexOfFirst { token ->
        token is PhpToken.Symbol && token.value == '$'
    }
    if (dollarIndex <= 0) return null
    val type = (signature.getOrNull(dollarIndex - 1) as? PhpToken.Word)?.value?.lowercase()
        ?.takeIf { it in STATIC_SERIALIZABLE_PARAMETER_TYPES }
        ?: return null
    val name = (signature.getOrNull(dollarIndex + 1) as? PhpToken.Word)?.value
        ?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }
        ?: return null
    if (dollarIndex + 2 != signature.size) return null
    return StaticPhpParameter(name, type)
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

private fun String.normalizedPhpName(): String = lowercase().filter(Char::isLetterOrDigit)

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

private fun String.isSensitiveWriteField(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return normalized.contains("password") ||
        normalized.contains("passphrase") ||
        normalized.contains("secret") ||
        normalized.contains("credential") ||
        normalized.contains("privatekey") ||
        normalized.contains("apikey") ||
        normalized.contains("accesstoken") ||
        normalized.contains("refreshtoken") ||
        normalized.contains("recoverykey") ||
        normalized.contains("signature")
}

private fun StaticPhpParameter.toOpenApiSchema(): JSONObject = when (type) {
    "bool" -> JSONObject().put("type", "boolean")
    "int" -> JSONObject().put("type", "integer")
    "float" -> JSONObject().put("type", "number")
    "array" -> JSONObject()
        .put("type", "array")
        .put("items", JSONObject().put("type", "string"))
    else -> JSONObject().put("type", "string")
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
private val STATIC_SERIALIZABLE_PARAMETER_TYPES = setOf("array", "bool", "float", "int", "string")
private val SETTINGS_METHOD_PREFIXES = setOf("enable", "save", "set", "update", "user")
private val OPERATIONAL_REFRESH_METHODS = setOf("refresh", "sync")
private val STATIC_ROUTE_VERBS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
private val VERIFIED_STATIC_WRITE_METHODS = setOf("post", "put", "patch", "delete")
private val EDITABLE_SETTINGS_VERBS = setOf("POST", "PUT", "PATCH")
private val CRUD_CREATE_METHOD_PREFIXES = setOf("create", "store")
private val CRUD_UPDATE_METHOD_PREFIXES = setOf("update")
private val CRUD_PATCH_METHOD_PREFIXES = setOf("patch")
private val CRUD_DELETE_METHOD_PREFIXES = setOf("delete", "destroy")
private val SCALAR_WORKFLOW_METHODS = setOf(
    "move",
    "relocate",
    "rename",
    "reorder",
    "archive",
    "unarchive",
    "done",
    "undone",
    "setflags",
)
private val BODY_FREE_SCALAR_WORKFLOW_METHODS = setOf("archive", "unarchive", "done", "undone")
private const val RESOURCE_ID_EXTENSION = "x-nextcloud-native-resource-id"
private const val SETTINGS_WIRE_NAME_EXTENSION = "x-nextcloud-native-wire-name"
private const val SETTINGS_STRING_LIST_FORMAT = "nextcloud-string-list"
private const val MAX_STATIC_ROUTE_COUNT = 256
private const val MAX_STATIC_CRUD_PARAMETERS = 32
private const val MAX_STATIC_CONTROLLER_INHERITANCE_DEPTH = 8
private const val MAX_PHP_LITERAL_DEPTH = 16
private const val MAX_PHP_LITERAL_NODES = 20_000
private const val MAX_PHP_LITERAL_TOKENS = 80_000
private const val MAX_PHP_LITERAL_STRING_CHARS = 2_048
private const val MAX_STATIC_PHP_SOURCE_CHARS = 1_048_576
