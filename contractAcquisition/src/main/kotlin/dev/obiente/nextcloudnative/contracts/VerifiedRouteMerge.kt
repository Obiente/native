package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

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
    val annotatedOperations = copyStaticFacts(paths, routePaths)
    val copiedDocumentFacts = STATIC_DOCUMENT_FACT_EXTENSIONS.count { key ->
        routeDocument.has(key).also { present -> if (present) document.put(key, routeDocument.get(key)) }
    }
    val preferredCollections = paths.keys().asSequence().sorted().mapNotNull { path ->
        val pathItem = paths.optJSONObject(path) ?: return@mapNotNull null
        val operation = pathItem.optJSONObject("get") ?: return@mapNotNull null
        if (!operation.isCollectionRead(document)) return@mapNotNull null
        val operationId = operation.stringValue("operationId")?.takeIf(String::isNotBlank)
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
                ?.takeIf { operation -> operation.booleanValue(VERIFIED_CRUD_EXTENSION, absent = false, wrongType = false) }
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
            ?.takeIf { operation -> operation.stringValue(OPERATIONAL_ACTION_EXTENSION) == "refresh" }
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
                val fallbackOperationId = importedOperation.stringValue("operationId").orEmpty()
                if (fallbackOperationId.isNotBlank()) {
                    val fallbacks = preferred.operation.optJSONArray(READ_FALLBACKS_EXTENSION) ?: JSONArray().also {
                        preferred.operation.put(READ_FALLBACKS_EXTENSION, it)
                    }
                    if ((0 until fallbacks.length()).none { index ->
                            fallbacks.opt(index) == fallbackOperationId
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

    if (addedRoutes == 0) {
        if (annotatedOperations == 0 && copiedDocumentFacts == 0) return openApi
        return openApi.copy(document = document.toString())
    }
    document.put("x-nextcloud-native-contract-kind", "openapi-with-verified-read-routes")
    document.put("x-nextcloud-native-verified-read-route-count", addedRoutes - importedStaticWrites)
    document.put("x-nextcloud-native-linked-read-fallback-count", linkedFallbacks)
    document.put("x-nextcloud-native-verified-static-write-count", importedStaticWrites)
    return openApi.copy(
        document = document.toString(),
        contractKind = VerifiedContractKind.OpenApiWithVerifiedReadRoutes,
    )
}

/**
 * Adds the package's static facts (route source, policy, merge hint) to advertised operations at
 * the same path and method. Advertised OpenAPI states admin, password, and CORS rules only as
 * prose, so these facts never replace a declared field; an operation that already carries a fact
 * keeps it.
 */
private fun copyStaticFacts(paths: JSONObject, routePaths: JSONObject): Int {
    var annotated = 0
    routePaths.keys().asSequence().sorted().forEach { path ->
        val routeItem = routePaths.optJSONObject(path) ?: return@forEach
        val target = paths.optJSONObject(path) ?: return@forEach
        HTTP_OPERATION_METHODS.forEach { method ->
            val routeOperation = routeItem.optJSONObject(method) ?: return@forEach
            val targetOperation = target.optJSONObject(method) ?: return@forEach
            var changed = false
            STATIC_OPERATION_FACT_EXTENSIONS.forEach { key ->
                if (routeOperation.has(key) && !targetOperation.has(key)) {
                    targetOperation.put(key, routeOperation.get(key))
                    changed = true
                }
            }
            if (changed) annotated += 1
        }
    }
    return annotated
}

private val HTTP_OPERATION_METHODS = listOf("get", "post", "put", "patch", "delete")
private val STATIC_OPERATION_FACT_EXTENSIONS = listOf(ROUTE_SOURCE_EXTENSION, POLICY_EXTENSION, MERGE_HINT_EXTENSION)
private val STATIC_DOCUMENT_FACT_EXTENSIONS = listOf(
    STATIC_EVIDENCE_FORMAT_EXTENSION,
    STATIC_ROUTES_EXTENSION,
    SKIPPED_ROUTES_EXTENSION,
    ENTITY_SERIALIZATIONS_EXTENSION,
    CAPABILITY_KEYS_EXTENSION,
    SUPPLEMENTARY_TRUNCATED_EXTENSION,
)

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
                val location = parameter.stringValue("in")
                if (location != "path" && location != "query") return@repeat
                if (!parameter.booleanValue("required", absent = location == "path", wrongType = true)) return@repeat
                val name = parameter.stringValue("name").orEmpty().normalizedSemanticWord()
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
    val reference = stringValue("${'$'}ref")?.takeIf { it.startsWith("#/") }
    if (reference != null && visitedReferences.add(reference)) {
        val resolved = document.resolveLocalObject(reference)
        if (resolved?.collectionShape(document, visitedReferences, depth + 1) == true) return true
    }
    if (stringValue("type") == "array") return true
    if (stringValue("type") == "object" && opt("additionalProperties") is JSONObject) return true
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

internal fun String.normalizedSemanticWord(): String = lowercase().filter(Char::isLetterOrDigit)

internal fun String.semanticSingular(): String = when {
    endsWith("ies") && length > 3 -> dropLast(3) + "y"
    endsWith("ches") || endsWith("shes") -> dropLast(2)
    endsWith("ses") || endsWith("xes") || endsWith("zes") -> dropLast(2)
    endsWith('s') && length > 1 -> dropLast(1)
    else -> this
}

private val SEMANTIC_PATH_NOISE = setOf(
    "api", "apps", "indexphp", "ocs", "ocsv1php", "ocsv2php", "list", "index", "getall", "findall",
)

private val VERIFIED_STATIC_WRITE_METHODS = setOf("post", "put", "patch", "delete")
