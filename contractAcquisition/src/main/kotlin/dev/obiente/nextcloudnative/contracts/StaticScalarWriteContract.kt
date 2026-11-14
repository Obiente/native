package dev.obiente.nextcloudnative.contracts

internal data class StaticCrudWrite(
    val bodyParameters: List<StaticPhpParameter>,
    /** Path placeholders the method reads with `$this->request->getParam()` instead of its signature. */
    val requestPathParameters: List<String> = emptyList(),
    /** The multipart file field read with `$this->request->getUploadedFile()`, if any. */
    val uploadedFileField: String? = null,
)

/**
 * Proves only conventional scalar CRUD signatures. Every route placeholder and every required
 * controller argument must have one serializable declaration. Unsupported optional arguments are
 * omitted because the request never needs to send them and PHP will use their declared defaults.
 * Every remaining supported argument becomes a closed JSON body field.
 *
 * Besides create, update, patch, and delete, two verb families are accepted for every app:
 * relationship verbs (`assign`, `unassign`, `add`, or `remove` of a related resource whose
 * identity the signature itself requires) and restore verbs (`restore`, `undoDelete`). Neither
 * extends to sending, sharing, membership, permissions, or merging, and the method name alone
 * never authorizes a write: the scalar signature must prove every input.
 *
 * Two exact request reads are accepted as declared inputs: `$this->request->getParam('x')` where
 * `x` is a route placeholder (the router merges placeholders into request parameters), and one
 * `$this->request->getUploadedFile('f')` on a POST, which makes the body multipart with file
 * field `f` and scalar text fields. Any other request access keeps the method unproven.
 */
internal fun verifiedStaticCrudWrite(
    route: StaticRoute,
    controller: StaticApiController,
    pathPlaceholders: List<String>,
): StaticCrudWrite? {
    val method = route.method.normalizedPhpName()
    val requestInputs = controller.requestInputs[method]
    if (method in controller.methodsWithRequestInput) {
        if (requestInputs == null || requestInputs.unclassified) return null
        if (requestInputs.parameters.any { name -> name !in pathPlaceholders }) return null
        if (requestInputs.uploadedFiles.size > 1) return null
        if (requestInputs.uploadedFiles.isNotEmpty() && route.verb != "POST") return null
    }
    val uploadedFileField = requestInputs?.uploadedFiles?.singleOrNull()
    val parameters = controller.parameters[method] ?: return null
    if (parameters.size > MAX_STATIC_CRUD_PARAMETERS) return null
    val relationship = method.relationshipVerb(parameters)
    val restore = method in RESTORE_METHODS
    val scalarAction = method in SCALAR_WORKFLOW_METHODS || relationship != null || restore
    val conventional = when (route.verb) {
        "POST" -> CRUD_CREATE_METHOD_PREFIXES.any(method::startsWith) || scalarAction
        "PUT" -> CRUD_UPDATE_METHOD_PREFIXES.any(method::startsWith) || scalarAction
        "PATCH" -> CRUD_PATCH_METHOD_PREFIXES.any(method::startsWith) || scalarAction
        // Removing a relationship is the only non-delete name a DELETE route may carry.
        "DELETE" -> CRUD_DELETE_METHOD_PREFIXES.any(method::startsWith) || relationship == RelationshipVerb.Remove
        else -> false
    }
    if (!conventional) return null
    // A relationship or restore always addresses an existing target named by the route.
    if ((relationship != null || restore) && pathPlaceholders.none { it.isSafeIdentityPlaceholder() }) return null

    val pathParameters = pathPlaceholders.mapNotNull { placeholder ->
        parameters.singleOrNull { parameter -> parameter.name.equals(placeholder, ignoreCase = true) }
    }
    val unmatchedPathParameters = pathPlaceholders.filter { placeholder ->
        pathParameters.none { parameter -> parameter.name.equals(placeholder, ignoreCase = true) }
    }
    if (
        unmatchedPathParameters.isNotEmpty() &&
        (!scalarAction || unmatchedPathParameters.any { !it.isSafeIdentityPlaceholder() })
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
    if (uploadedFileField != null) {
        // Multipart text fields carry scalars only, and the file field must not shadow one.
        if (bodyParameters.any { it.type == "array" || it.name == uploadedFileField }) return null
        return StaticCrudWrite(
            bodyParameters = bodyParameters,
            requestPathParameters = requestInputs?.parameters.orEmpty(),
            uploadedFileField = uploadedFileField,
        )
    }
    val requestPathParameters = requestInputs?.parameters.orEmpty()
    // A relationship whose related identity is a path segment, or a restore, needs no body.
    val bodyFree = method in BODY_FREE_SCALAR_WORKFLOW_METHODS || restore ||
        relationship != null && bodyParameters.none { it.required }
    return when (route.verb) {
        "DELETE" -> StaticCrudWrite(bodyParameters = emptyList(), requestPathParameters = requestPathParameters)
            .takeIf { bodyParameters.isEmpty() }
        "POST", "PUT", "PATCH" -> StaticCrudWrite(bodyParameters, requestPathParameters)
            .takeIf { bodyParameters.isNotEmpty() || bodyFree }
        else -> null
    }
}

private enum class RelationshipVerb { Add, Remove }

/**
 * `assignLabel($cardId, $labelId)` relates a card to a label: the verb names the related
 * resource and the signature requires that resource's identity. A name without that identity,
 * or one that names sharing, membership, permission, or ownership, is not a relationship here.
 */
private fun String.relationshipVerb(parameters: List<StaticPhpParameter>): RelationshipVerb? {
    val (prefix, verb) = RELATIONSHIP_PREFIXES.firstOrNull { (prefix, _) -> startsWith(prefix) && length > prefix.length }
        ?: return null
    val related = removePrefix(prefix)
    if (RELATIONSHIP_EXCLUDED_WORDS.any(related::contains)) return null
    val identity = related + "id"
    if (parameters.none { parameter -> parameter.required && parameter.name.normalizedPhpName() == identity }) return null
    return verb
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
 * allow-list accepted by [verifiedStaticCrudWrite], and never consults an app identifier. A
 * relationship action may also be named by its bare verb, as in `/cards/{cardId}/assign`.
 */
internal fun StaticRoute.scalarWorkflowResourceId(fullPath: String): String? {
    val methodId = method.normalizedPhpName()
    val relationshipPrefix = RELATIONSHIP_PREFIXES.firstOrNull { (prefix, _) ->
        methodId.startsWith(prefix) && methodId.length > prefix.length
    }?.first
    if (methodId !in SCALAR_WORKFLOW_METHODS && methodId !in RESTORE_METHODS && relationshipPrefix == null) return null
    val segments = fullPath.split('/').filter(String::isNotBlank)
    val actionIndex = segments.indexOfLast { segment ->
        segment.normalizedPhpName().let { it == methodId || it == relationshipPrefix }
    }
    if (actionIndex <= 0) return null
    return segments.subList(0, actionIndex).asReversed().firstOrNull { segment ->
        !segment.startsWith('{') &&
            !segment.endsWith('}') &&
            !segment.matches(Regex("v?[0-9]+(?:\\.[0-9]+)*"))
    }?.normalizedSemanticWord()?.takeIf(String::isNotBlank)
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
private val RESTORE_METHODS = setOf("restore", "undodelete", "deleteundo", "undelete")

/** Longest prefix first, so `unassign` is never read as `un` + `assign`. */
private val RELATIONSHIP_PREFIXES = listOf(
    "unassign" to RelationshipVerb.Remove,
    "assign" to RelationshipVerb.Add,
    "remove" to RelationshipVerb.Remove,
    "add" to RelationshipVerb.Add,
)

/** Relationships that grant access or change who controls a resource need their own contract. */
private val RELATIONSHIP_EXCLUDED_WORDS = setOf(
    "share", "acl", "permission", "member", "owner", "admin", "role", "group", "circle", "team",
    "invit", "access", "right",
)
private const val MAX_STATIC_CRUD_PARAMETERS = 32
