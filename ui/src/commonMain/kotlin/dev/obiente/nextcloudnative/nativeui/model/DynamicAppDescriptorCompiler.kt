package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val READ_FALLBACK_OPERATION_IDS_EXTENSION =
    "x-nextcloud-native-read-fallback-operation-ids"
private const val READ_FALLBACK_FOR_OPERATION_EXTENSION =
    "x-nextcloud-native-fallback-for-operation-id"
private const val DESCRIPTION_INFERRED_MULTIPART_EXTENSION =
    "x-nextcloud-native-description-inferred-multipart"
private const val MAX_MULTIPART_DESCRIPTION_CHARACTERS = 2_048
private const val MAX_INFERRED_MULTIPART_TEXT_FIELDS = 16
private const val MAX_SIGNED_DESCRIPTION_ENUM_CHARACTERS = 2_048
private const val MAX_SIGNED_DESCRIPTION_ENUM_FIELD_LENGTH = 128
private const val MAX_SIGNED_DESCRIPTION_ENUM_VALUES = 16
private const val MAX_SIGNED_DESCRIPTION_ENUM_VALUE_LENGTH = 128

private val INFERRED_MULTIPART_SCALAR_TYPES = setOf("string", "integer", "number", "boolean")
private val EDITABLE_DYNAMIC_STRING_ARRAY_SCHEMA_KEYS = setOf(
    "\$comment",
    "default",
    "deprecated",
    "description",
    "example",
    "examples",
    "items",
    "nullable",
    "readOnly",
    "title",
    "type",
    "writeOnly",
)
private val EDITABLE_DYNAMIC_STRING_ARRAY_ITEM_SCHEMA_KEYS = setOf(
    "\$comment",
    "deprecated",
    "description",
    "example",
    "examples",
    "nullable",
    "readOnly",
    "title",
    "type",
    "writeOnly",
)
private val FIELD_COMPOSITION_ANNOTATION_KEYS = setOf(
    "default",
    "deprecated",
    "description",
    "example",
    "examples",
    "nullable",
    "readOnly",
    "title",
    "writeOnly",
)
private val MULTIPART_FILE_FIELD_DESCRIPTION = Regex(
    """\bmultipart/form-data\b.{0,320}?\b(image|photo|audio|video|binary|file)\s+file\b.{0,160}?\bfield\s+named\s+\*\*([A-Za-z][A-Za-z0-9_.-]{0,63})\*\*""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ADDITIONAL_MULTIPART_FIELDS_DESCRIPTION = Regex(
    """\boptional\s+([A-Za-z][A-Za-z0-9_.-]*(?:\s*(?:,\s*|\s+and\s+)[A-Za-z][A-Za-z0-9_.-]*)*)\s+may\s+be\s+sent\s+as\s+additional\s+form\s+fields?\b""",
    RegexOption.IGNORE_CASE,
)

private fun String.isSafeInferredMultipartFieldName(): Boolean =
    length in 1..64 &&
        first().isAsciiLetter() &&
        all { character ->
            character.isAsciiLetter() || character.isDigit() || character in "_.-"
        }

private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun String.parseInferredMultipartFieldList(): List<String> =
    replace(Regex("""\s+and\s+""", RegexOption.IGNORE_CASE), ",")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

/** Common Kotlin compiler used until the Rust compiler is linked into each platform app. */
class DynamicAppDescriptorCompiler {
    fun compile(input: DynamicDiscoveryInput): DynamicAppDescriptor {
        require(input.endpointPolicy.serverOrigin.isSafeHttpOrigin()) { "Invalid server origin" }
        require(input.endpointPolicy.approvedApiPrefixes.isNotEmpty()) { "No approved API prefix" }
        require(input.endpointPolicy.approvedApiPrefixes.all(String::isSafeRelativePath)) {
            "Invalid approved API prefix"
        }
        val advertised = input.advertisedOpenApi
            ?: return if (input.successfulReads.isEmpty()) {
                metadataDescriptor(input).requireValid()
            } else {
                compileObservedReads(input).requireValid()
            }
        require(
            advertised.trust == OpenApiTrust.nextcloudSignedAppPackage ||
                advertised.trust == OpenApiTrust.nextcloudSignedCompatibleAppPackage ||
                advertised.trust == OpenApiTrust.appStoreLinkedExactGitHubTag ||
                advertised.trust == OpenApiTrust.appStoreLinkedCompatibleGitHubTag ||
                advertised.documentUrl.isSameOriginDocument(input.endpointPolicy.serverOrigin),
        ) {
            "Cross-origin OpenAPI advertisement"
        }
        val advertisedDocument = advertised.document as? JsonObject ?: error("OpenAPI document must be an object")
        require(advertisedDocument.string("openapi")?.startsWith("3.") == true) {
            "Only OpenAPI 3.x is supported"
        }
        val sanitized = if (advertised.trust == OpenApiTrust.sameOriginAdvertisement) {
            ExternalReferenceSanitization(advertisedDocument, 0)
        } else {
            sanitizeExternalSchemaReferences(advertisedDocument)
        }
        val document = sanitized.document
        val derivedReadOnlyRoutes =
            document.string("x-nextcloud-native-contract-kind") == "verified-read-only-routes"
        val paths = document.objectValue("paths") ?: error("OpenAPI paths must be an object")
        val serverBase = openApiServerBase(
            document = document,
            origin = input.endpointPolicy.serverOrigin,
            allowTrustedRebase = advertised.trust != OpenApiTrust.sameOriginAdvertisement,
        )
        val source = Provenance(
            kind = when (advertised.trust) {
                OpenApiTrust.nextcloudSignedAppPackage -> ProvenanceKind.verifiedAppPackage
                OpenApiTrust.nextcloudSignedCompatibleAppPackage -> ProvenanceKind.verifiedAppPackage
                OpenApiTrust.appStoreLinkedExactGitHubTag -> ProvenanceKind.appStoreLinkedSourceTag
                OpenApiTrust.appStoreLinkedCompatibleGitHubTag -> ProvenanceKind.appStoreLinkedSourceTag
                OpenApiTrust.sameOriginAdvertisement -> ProvenanceKind.advertisedOpenApi
            },
            source = advertised.documentUrl,
            detail = when (advertised.trust) {
                OpenApiTrust.nextcloudSignedAppPackage ->
                    if (derivedReadOnlyRoutes) {
                        "Derived read-only endpoints from verified static routes and API controller metadata in a Nextcloud App Store package"
                    } else {
                        "Imported OpenAPI ${document.string("openapi")} from a verified Nextcloud App Store package"
                    }
                OpenApiTrust.nextcloudSignedCompatibleAppPackage ->
                    if (derivedReadOnlyRoutes) {
                        "Derived read-only endpoints from verified static routes and API controller metadata in a patch-compatible Nextcloud App Store package"
                    } else {
                        "Imported OpenAPI ${document.string("openapi")} from a verified patch-compatible Nextcloud App Store package"
                    }
                OpenApiTrust.appStoreLinkedExactGitHubTag ->
                    if (derivedReadOnlyRoutes) {
                        "Derived read-only endpoints from static routes and API controller metadata in the exact App Store-linked source tag"
                    } else {
                        "Imported unsigned OpenAPI ${document.string("openapi")} from the exact GitHub release tag linked by Nextcloud App Store metadata"
                    }
                OpenApiTrust.appStoreLinkedCompatibleGitHubTag ->
                    if (derivedReadOnlyRoutes) {
                        "Derived read-only endpoints from static routes and API controller metadata in a patch-compatible App Store-linked source tag"
                    } else {
                        "Imported unsigned OpenAPI ${document.string("openapi")} from a patch-compatible GitHub release tag linked by Nextcloud App Store metadata"
                    }
                OpenApiTrust.sameOriginAdvertisement ->
                    "Imported advertised OpenAPI ${document.string("openapi")}"
            },
        )
        val state = KotlinCompilerState(
            input = input,
            document = document,
            source = source,
            allowSignedDescriptionMultipartInference =
                advertised.trust == OpenApiTrust.nextcloudSignedAppPackage ||
                    advertised.trust == OpenApiTrust.nextcloudSignedCompatibleAppPackage,
            allowSignedDescriptionEnumInference =
                advertised.trust == OpenApiTrust.nextcloudSignedAppPackage ||
                    advertised.trust == OpenApiTrust.nextcloudSignedCompatibleAppPackage,
        )
        if (sanitized.ignoredCount > 0) {
            state.warnings += DynamicWarning(
                code = "opaque-external-schema-reference",
                message = "Ignored ${sanitized.ignoredCount} external OpenAPI schema references; endpoints remain available without inferred fields.",
            )
        }
        val inferredReadRouteResourceIdentities = paths.entries.mapNotNull { (openApiPath, itemElement) ->
            val path = combinePaths(serverBase, openApiPath)
            val pathItem = itemElement as? JsonObject ?: return@mapNotNull null
            state.routeResourceIdentity(path, pathItem)?.let { identity -> path to identity }
        }.toMap()
        val readRouteResourceIdentities = inferredReadRouteResourceIdentities.mapValues { (path, identity) ->
            (
                path.collectionRouteForTerminalIdentity()
                    ?: path.collectionRouteForTerminalState()
                )
                ?.let(inferredReadRouteResourceIdentities::get)
                ?.takeIf { parent -> parent.collection }
                ?.let { parent -> identity.copy(resourceId = parent.resourceId) }
                ?: identity
        }

        paths.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEach { (openApiPath, itemElement) ->
            require(openApiPath.startsWith('/') && !openApiPath.startsWith("//")) {
                "Invalid OpenAPI path: $openApiPath"
            }
            val path = combinePaths(serverBase, openApiPath)
            require(path.isApproved(input.endpointPolicy)) { "Unapproved OpenAPI path: $path" }
            val pathItem = itemElement as? JsonObject ?: return@forEach
            val inheritedParameters = pathItem["parameters"] as? JsonArray
            val routeResourceIdentity = readRouteResourceIdentities[path]
            pathItem.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEach operationLoop@{ (methodName, operationElement) ->
                val method = methodName.toHttpMethod() ?: return@operationLoop
                val operation = operationElement as? JsonObject ?: return@operationLoop
                val advertisedOperationId = operation.string("operationId")?.takeIf(String::isNotBlank)
                if (method != HttpMethod.GET && advertisedOperationId == null) {
                    state.warnings += DynamicWarning(
                        code = "ignored-unnamed-write",
                        message = "Ignored documented $methodName $path because it has no operationId",
                    )
                    return@operationLoop
                }
                val operationId = advertisedOperationId ?: "get-${path.stableId()}"
                if (
                    method != HttpMethod.GET &&
                    operation.isSensitiveCredentialMutation(path, operationId)
                ) {
                    state.warnings += DynamicWarning(
                        code = "ignored-sensitive-credential-write",
                        message = "Ignored documented $methodName $path because generic credential mutations require a dedicated trusted workflow.",
                    )
                    return@operationLoop
                }
                if (
                    method != HttpMethod.GET &&
                    operation.requiresAmbiguousResultRecoveryPolicy(path, operationId)
                ) {
                    if (state.exactIdempotentResultRecoveryActionId(path, method) == null) {
                        state.warnings += DynamicWarning(
                            code = "ignored-ambiguous-result-write",
                            message = "Ignored documented $methodName $path because generic send, share, and merge mutations require an exact verified read recovery surface.",
                        )
                        return@operationLoop
                    }
                }
                state.addOperation(
                    path = path,
                    method = method,
                    operation = operation,
                    operationId = operationId,
                    inheritedParameters = inheritedParameters,
                    routeResourceIdentity = routeResourceIdentity,
                    readRouteResourceIdentities = readRouteResourceIdentities,
                    resultRecoveryActionId = state.exactIdempotentResultRecoveryActionId(path, method),
                )
            }
        }
        return state.finish().requireValid()
    }
}

private data class ExternalReferenceSanitization(
    val document: JsonObject,
    val ignoredCount: Int,
)

private fun sanitizeExternalSchemaReferences(document: JsonObject): ExternalReferenceSanitization {
    var ignored = 0
    fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::sanitize))
        is JsonObject -> {
            val reference = element["${'$'}ref"] as? JsonPrimitive
            if (reference?.contentOrNull?.startsWith("#/") == false) {
                ignored += 1
                JsonObject(element.filterKeys { key -> key != "${'$'}ref" }.mapValues { (_, value) -> sanitize(value) })
            } else {
                JsonObject(element.mapValues { (_, value) -> sanitize(value) })
            }
        }
        else -> element
    }
    return ExternalReferenceSanitization(sanitize(document) as JsonObject, ignored)
}

private fun compileObservedReads(input: DynamicDiscoveryInput): DynamicAppDescriptor {
    val resources = linkedMapOf<String, KotlinObservedResource>()
    val actions = mutableListOf<DynamicAction>()
    val layouts = linkedMapOf<String, DynamicLayout>()
    val permissions = linkedMapOf<String, PermissionSpec>()
    val links = mutableListOf<DynamicLink>()

    input.successfulReads.forEach { observation ->
        require(observation.status in 200..299 && observation.contentType.lowercase().contains("json")) {
            "Observation is not a successful JSON response: ${observation.path}"
        }
        require(observation.path.isApproved(input.endpointPolicy)) {
            "Unapproved observed endpoint: ${observation.path}"
        }
        val source = Provenance(
            kind = ProvenanceKind.successfulReadObservation,
            source = observation.path,
            detail = "Inferred only read-only structure from HTTP ${observation.status} ${observation.contentType}",
        )
        val payload = observedPayload(observation)
        val collection = payload is JsonArray
        val resourceId = observation.path.observedResourceId()
        val discoveredFields = observedFields(payload, source)
        val resource = resources.getOrPut(resourceId) { KotlinObservedResource(resourceId) }
        resource.collection = resource.collection || collection
        resource.merge(discoveredFields, source)

        val sessionPermissionId = "auth.nextcloud-session"
        permissions.putIfAbsent(
            sessionPermissionId,
            PermissionSpec(
                id = sessionPermissionId,
                label = "Nextcloud session authentication",
                kind = PermissionKind.authenticatedSession,
                state = PermissionState.required,
                confidence = Confidence.medium,
                provenance = source,
            ),
        )
        observation.permissionIds.forEach { permissionId ->
            permissions.putIfAbsent(
                permissionId,
                PermissionSpec(
                    id = permissionId,
                    label = permissionId.humanize(),
                    kind = PermissionKind.resourceAcl,
                    state = PermissionState.unknown,
                    confidence = Confidence.medium,
                    provenance = source,
                ),
            )
        }
        val query = observation.queryParameters.map { parameter ->
            HttpParameter(
                name = parameter.name,
                required = parameter.required,
                schema = parameter.schema,
                source = ParameterSource.userInput,
            )
        }.sortedBy(HttpParameter::name)
        val actionId = uniqueId(
            actions.mapTo(mutableSetOf(), DynamicAction::id),
            (observation.operationId ?: "observed.get.${observation.path.stableId()}").stableId(),
        )
        actions += DynamicAction(
            id = actionId,
            label = observation.label ?: "Read ${resourceId.humanize()}",
            resourceId = resourceId,
            intent = if (collection) ActionIntent.list else ActionIntent.read,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            binding = DynamicHttpBinding(
                method = HttpMethod.GET,
                path = observation.path,
                queryParameters = query,
                auth = listOf(AuthRequirement("nextcloud-session", AuthKind.nextcloudSession)),
                ocs = if (observation.ocs) ocsMetadata(observation.path, query) else null,
            ),
            responseFieldIds = discoveredFields.map(DynamicField::id),
            permissionIds = (listOf(sessionPermissionId) + observation.permissionIds).distinct().sorted(),
            confidence = Confidence.medium,
            provenance = listOf(source),
        )
        val kind = if (collection) LayoutKind.list else LayoutKind.detail
        val layoutId = "$resourceId.${kind.name}"
        layouts.putIfAbsent(
            layoutId,
            DynamicLayout(
                id = layoutId,
                title = resourceId.humanize(),
                resourceId = resourceId,
                kind = kind,
                fields = discoveredFields.mapIndexed { index, field ->
                    LayoutField(field.id, field.layoutRole(index), index < 5)
                },
                sourceActionId = actionId,
                confidence = Confidence.medium,
                provenance = listOf(source),
            ),
        )
        discoveredFields.filter { it.format == "uri" }.forEach { field ->
            links += DynamicLink(
                id = "$resourceId.${field.id}.link",
                label = field.label,
                resourceId = resourceId,
                sourceFieldId = field.id,
                target = DynamicLinkTarget.FieldUrl(allowExternal = false),
                confidence = Confidence.medium,
                provenance = listOf(source),
            )
        }
    }
    return DynamicAppDescriptor(
        descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
        app = input.app,
        endpointPolicy = input.endpointPolicy,
        capabilities = input.capabilities,
        permissions = permissions.values.sortedBy(PermissionSpec::id),
        resources = resources.values.map(KotlinObservedResource::finish).sortedBy(DynamicResource::id),
        layouts = layouts.values.sortedBy(DynamicLayout::id),
        links = links.distinctBy(DynamicLink::id).sortedBy(DynamicLink::id),
        forms = emptyList(),
        actions = actions.sortedBy(DynamicAction::id),
    )
}

private data class KotlinObservedResource(
    val id: String,
    var collection: Boolean = false,
    val fields: MutableMap<String, DynamicField> = linkedMapOf(),
    val provenance: MutableList<Provenance> = mutableListOf(),
) {
    fun merge(discovered: List<DynamicField>, source: Provenance) {
        discovered.forEach { fields.putIfAbsent(it.id, it) }
        if (source !in provenance) provenance += source
    }

    fun finish() = DynamicResource(
        id = id,
        label = id.humanize(),
        collection = collection,
        fields = fields.values.sortedBy(DynamicField::id),
        confidence = Confidence.medium,
        provenance = provenance,
    )
}

private fun observedPayload(observation: SuccessfulReadObservation): JsonElement {
    val response = observation.response
    if (observation.ocs) {
        ((response as? JsonObject)?.get("ocs") as? JsonObject)?.get("data")?.let { return it }
    }
    val objectValue = response as? JsonObject
    return objectValue?.takeIf { it.size == 1 }?.get("data") ?: response
}

private fun observedFields(payload: JsonElement, source: Provenance): List<DynamicField> {
    val samples = when (payload) {
        is JsonArray -> payload.mapNotNull { it as? JsonObject }.take(64)
        is JsonObject -> listOf(payload)
        else -> emptyList()
    }
    if (samples.isEmpty()) return emptyList()
    return samples.flatMap(JsonObject::keys).distinct().sorted().take(128).map { id ->
        val values = samples.mapNotNull { it[id] }
        val nonNull = values.filterNot { it.toString() == "null" }
        val flattened = nonNull.flatMap { value -> (value as? JsonArray)?.toList() ?: listOf(value) }
        val kind = observedKind(id, flattened)
        val format = flattened
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .takeIf { strings ->
                strings.isNotEmpty() && strings.all { it.startsWith("https://") || it.startsWith("http://") }
            }
            ?.let { "uri" }
            ?: id.takeIf { it.lowercase().contains("date") && flattened.isNotEmpty() }?.let { "date-time" }
        DynamicField(
            id = id,
            label = id.humanize(),
            kind = kind,
            required = nonNull.size == samples.size,
            readOnly = true,
            nullable = nonNull.size < samples.size,
            multiple = values.any { it is JsonArray },
            format = format,
            confidence = Confidence.medium,
            provenance = listOf(source),
        )
    }
}

private fun observedKind(id: String, values: List<JsonElement>): FieldKind {
    if (values.isEmpty()) return FieldKind.unknown
    return when {
        values.all { (it as? JsonPrimitive)?.booleanOrNull != null } -> FieldKind.boolean
        values.all { element ->
            (element as? JsonPrimitive)?.contentOrNull?.toLongOrNull() != null
        } -> FieldKind.integer
        values.all { element ->
            (element as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() != null
        } -> FieldKind.decimal
        values.all { it is JsonPrimitive && it.isString } -> fieldKind(id, JsonObject(mapOf("type" to JsonPrimitive("string"))))
        values.all { it is JsonObject || it is JsonArray } -> FieldKind.objectValue
        else -> FieldKind.unknown
    }
}

private fun String.observedResourceId(): String = split('/').asReversed().firstOrNull { segment ->
    segment.isNotBlank() && !segment.startsWith('{') && !segment.matches(Regex("v[0-9]+"))
}?.stableId()?.takeIf(String::isNotBlank) ?: "resource"

private data class KotlinRouteResourceIdentity(
    val resourceId: String,
    val collection: Boolean,
)

private fun String.collectionRouteForTerminalIdentity(): String? {
    val segments = trimEnd('/').split('/')
    val terminal = segments.lastOrNull() ?: return null
    if (!terminal.startsWith('{') || !terminal.endsWith('}')) return null
    return segments.dropLast(1).joinToString("/").ifBlank { "/" }
}

private fun String.collectionRouteForTerminalState(): String? {
    val segments = trimEnd('/').split('/')
    if (terminalCollectionState() == null) return null
    return segments.dropLast(1).joinToString("/").ifBlank { "/" }
}

private fun recordImagePreviewRouteResourceIdentity(
    path: String,
    readRouteResourceIdentities: Map<String, KotlinRouteResourceIdentity>,
): KotlinRouteResourceIdentity? {
    val segments = path.trim('/').split('/').filter(String::isNotBlank)
    if (segments.size < 3 || segments.last().stableId() !in RECORD_IMAGE_PREVIEW_TERMINALS) return null
    val recordSegment = segments[segments.lastIndex - 1]
    if (!recordSegment.startsWith('{') || !recordSegment.endsWith('}')) return null
    val collectionPath = "/" + segments.dropLast(2).joinToString("/")
    return readRouteResourceIdentities.entries
        .filter { (candidatePath, identity) ->
            identity.collection && candidatePath.trimEnd('/') == collectionPath
        }
        .map { entry -> entry.value }
        .distinct()
        .singleOrNull()
}

private val RECORD_IMAGE_PREVIEW_TERMINALS = setOf("image", "preview", "thumbnail")

private val TRUSTED_RECORD_IMAGE_PREVIEW_PROVENANCE = setOf(
    ProvenanceKind.verifiedAppPackage,
    ProvenanceKind.appStoreLinkedSourceTag,
)

private fun String.isDeclaredRecordImageContentType(): Boolean =
    this == "*/*" || this == "application/octet-stream" || startsWith("image/")

private fun DynamicAction.hasResolvableRecordImageShape(resource: KotlinResourceBuilder): Boolean {
    val segments = binding.path.trim('/').split('/').filter(String::isNotBlank)
    val recordToken = segments.getOrNull(segments.lastIndex - 1) ?: return false
    if (!recordToken.startsWith('{') || !recordToken.endsWith('}')) return false
    val recordParameterName = recordToken.removePrefix("{").removeSuffix("}")
    if (binding.pathParameters.none { parameter -> parameter.name == recordParameterName }) return false
    val recordParameterStem = recordParameterName
        .takeIf { name -> name.endsWith("Id", ignoreCase = true) && name.length > 2 }
        ?.dropLast(2)
    if (
        !recordParameterName.equals("id", ignoreCase = true) &&
        recordParameterStem?.semanticBaseVariants()
            ?.intersect(resource.id.semanticBaseVariants())
            ?.isNotEmpty() != true
    ) {
        return false
    }
    return resource.fields.values.any { field ->
        field.id.lowercase() in setOf("id", "databaseid", "uuid", "token")
    }
}

private fun String.terminalCollectionState(): String? =
    trimEnd('/')
        .substringAfterLast('/')
        .stableId()
        .takeIf(COLLECTION_STATE_ROUTE_WORDS::contains)

/**
 * Command routes such as `/{id}/toggle`, `/trash/{id}/restore`, and `/batch/move`
 * describe an effect on an existing resource rather than a new resource named after the terminal
 * route segment. Match their normalized route back to a verified JSON GET route only when that
 * route proves ownership: either the normalized routes are equal, or a collection read owns the
 * single terminal item identity targeted by the command.
 *
 * This deliberately depends on route shape plus the already-derived semantic effect. It does not
 * know an app ID, operation ID, entity name, or server-specific parameter name. If equally strong
 * candidates disagree about the resource, the action remains unbound instead of guessing.
 */
private fun transitionRouteResourceIdentity(
    path: String,
    effect: ActionEffect,
    readRouteResourceIdentities: Map<String, KotlinRouteResourceIdentity>,
): KotlinRouteResourceIdentity? {
    val targetRoute = path.transitionResourceRoute(effect) ?: return null
    val candidates = readRouteResourceIdentities.mapNotNull { (readPath, identity) ->
        val candidateRoute = readPath.readResourceRoute()
        val exact = candidateRoute == targetRoute
        val collectionItem =
            identity.collection &&
                targetRoute == candidateRoute + "{}"
        if (!exact && !collectionItem) return@mapNotNull null
        val stateSegmentCount = readPath.routeSegments().count { segment ->
            segment.stableId() in COLLECTION_STATE_ROUTE_WORDS
        }
        KotlinTransitionRouteCandidate(
            identity = identity,
            exact = exact,
            routeSegmentCount = candidateRoute.size,
            stateSegmentCount = stateSegmentCount,
        )
    }
    val bestScore = candidates.maxOfOrNull(KotlinTransitionRouteCandidate::score) ?: return null
    val best = candidates.filter { candidate -> candidate.score == bestScore }
    val resourceIds = best.map { candidate -> candidate.identity.resourceId }.distinct()
    if (resourceIds.size != 1) return null
    return best.first().identity
}

private data class KotlinTransitionRouteCandidate(
    val identity: KotlinRouteResourceIdentity,
    val exact: Boolean,
    val routeSegmentCount: Int,
    val stateSegmentCount: Int,
) {
    val score: Int
        get() =
            (if (exact) 1_000_000 else 0) +
                routeSegmentCount * 100 -
                stateSegmentCount
}

private fun String.transitionResourceRoute(effect: ActionEffect): List<String>? {
    val effectWords = when (effect) {
        ActionEffect.toggle -> TOGGLE_WORDS
        ActionEffect.archive -> setOf("archive", "archived")
        ActionEffect.unarchive -> setOf("archive", "archived", "unarchive")
        ActionEffect.restore -> setOf("restore")
        ActionEffect.move -> setOf("move")
        ActionEffect.copy -> setOf("copy", "duplicate")
        ActionEffect.reorder -> REORDER_WORDS
        ActionEffect.batch -> return routeSegments()
            .takeWhile { segment -> segment.stableId() != "batch" }
            .toNormalizedResourceRoute()
            .takeIf(List<String>::isNotEmpty)
        ActionEffect.upload -> setOf("upload", "import", "image")
        ActionEffect.leave -> setOf("leave")
        ActionEffect.clear -> setOf("clear", "image")
        ActionEffect.permanentDelete -> PERMANENT_DELETE_WORDS + setOf("delete")
        ActionEffect.empty -> setOf("empty")
        ActionEffect.unspecified,
        ActionEffect.list,
        ActionEffect.read,
        ActionEffect.create,
        ActionEffect.update,
        ActionEffect.delete,
        ActionEffect.assign,
        ActionEffect.execute,
        -> return null
    }
    return routeSegments()
        .filterNot { segment ->
            val word = segment.stableId()
            word in effectWords || word in COLLECTION_STATE_ROUTE_WORDS
        }
        .toNormalizedResourceRoute()
        .takeIf(List<String>::isNotEmpty)
}

private fun String.readResourceRoute(): List<String> = routeSegments()
    .filterNot { segment -> segment.stableId() in COLLECTION_STATE_ROUTE_WORDS }
    .toNormalizedResourceRoute()

private fun String.routeSegments(): List<String> = trim('/')
    .split('/')
    .filter(String::isNotBlank)

private fun List<String>.toNormalizedResourceRoute(): List<String> = map { segment ->
    if (segment.startsWith('{') && segment.endsWith('}')) "{}" else segment.stableId()
}

private val COLLECTION_STATE_ROUTE_WORDS = setOf(
    "archive",
    "archived",
    "deleted",
    "trash",
)

private class KotlinCompilerState(
    private val input: DynamicDiscoveryInput,
    private val document: JsonObject,
    private val source: Provenance,
    private val allowSignedDescriptionMultipartInference: Boolean,
    private val allowSignedDescriptionEnumInference: Boolean,
) {
    private val resources = linkedMapOf<String, KotlinResourceBuilder>()
    private val actions = linkedMapOf<String, DynamicAction>()
    private val layoutSeeds = linkedMapOf<String, KotlinLayoutSeed>()
    private val forms = linkedMapOf<String, DynamicForm>()
    private val permissions = linkedMapOf<String, PermissionSpec>()
    private val operationActionIds = linkedMapOf<String, String>()
    private val fallbackOperationIds = linkedMapOf<String, List<String>>()
    private val readActionIdsByExactContractPath = linkedMapOf<String, MutableList<String>>()
    private val recordImagePreviewCandidates = linkedMapOf<String, MutableList<DynamicRecordImagePreviewSpec>>()
    val warnings = mutableListOf<DynamicWarning>()

    /**
     * An ambiguous semantic write is recoverable only when PUT makes the operation idempotent and a
     * trusted, already-compiled GET reads the exact same route without additional required query
     * input. The returned action ID is retained as executable recovery evidence.
     */
    fun exactIdempotentResultRecoveryActionId(
        path: String,
        method: HttpMethod,
    ): String? {
        if (
            method != HttpMethod.PUT ||
            source.kind !in setOf(
                ProvenanceKind.verifiedAppPackage,
                ProvenanceKind.appStoreLinkedSourceTag,
            )
        ) {
            return null
        }
        return readActionIdsByExactContractPath[path]
            .orEmpty()
            .mapNotNull(actions::get)
            .singleOrNull { candidate ->
                candidate.binding.method == HttpMethod.GET &&
                candidate.binding.body == null &&
                candidate.binding.queryParameters.none(HttpParameter::required) &&
                candidate.intent in setOf(ActionIntent.read, ActionIntent.list) &&
                candidate.risk == ActionRisk.readOnly &&
                !candidate.fallbackOnly &&
                candidate.provenance.any { provenance ->
                    provenance.kind in setOf(
                        ProvenanceKind.verifiedAppPackage,
                        ProvenanceKind.appStoreLinkedSourceTag,
                    )
                }
            }
            ?.id
    }

    /**
     * One REST route is one resource even when its read and write operations use different tags.
     * OpenAPI generators commonly group GET and mutations by controller instead of by returned
     * record type. Anchor the route family to its JSON GET while retaining whether that response is
     * a collection so creates normalize parent bindings without turning detail routes into lists.
     */
    fun routeResourceIdentity(path: String, pathItem: JsonObject): KotlinRouteResourceIdentity? {
        val operation = pathItem["get"] as? JsonObject ?: return null
        if (hasSuccessfulBinaryResponse(operation)) return null
        val operationId = operation.string("operationId")
            ?.takeIf(String::isNotBlank)
            ?: "get-${path.stableId()}"
        val filteredCollectionResourceId =
            semanticFilteredCollectionResourceId(operation, path, operationId, HttpMethod.GET)
        val (itemSchema, responseCollection) = responseItemSchema(responseSchema(operation))
        if (itemSchema == null && filteredCollectionResourceId == null) return null
        val resourceId = resourceId(
            operation = operation,
            path = path,
            operationId = operationId,
            method = HttpMethod.GET,
            filteredCollectionResourceId = filteredCollectionResourceId,
        )
        return KotlinRouteResourceIdentity(
            resourceId = resourceId,
            collection = responseCollection || filteredCollectionResourceId != null,
        )
    }

    fun addOperation(
        path: String,
        method: HttpMethod,
        operation: JsonObject,
        operationId: String,
        inheritedParameters: JsonArray?,
        routeResourceIdentity: KotlinRouteResourceIdentity?,
        readRouteResourceIdentities: Map<String, KotlinRouteResourceIdentity>,
        resultRecoveryActionId: String?,
    ) {
        val declaredBody = body(operation)
        if ("requestBody" in operation && declaredBody == null) {
            return
        }
        if (declaredBody?.isUnsupportedOptionalFileMultipartBody() == true) {
            return
        }
        val actionId = uniqueId(actions.keys, operationId.stableId())
        operationActionIds.putIfAbsent(operationId, actionId)
        val fallbackForOperationId = operation.string(READ_FALLBACK_FOR_OPERATION_EXTENSION)
        val readFallbackOperationIds = operation.stringArray(READ_FALLBACK_OPERATION_IDS_EXTENSION).orEmpty()
        if (readFallbackOperationIds.isNotEmpty()) {
            fallbackOperationIds[actionId] = readFallbackOperationIds
        }
        val filteredCollectionResourceId =
            semanticFilteredCollectionResourceId(operation, path, operationId, method)
        val inferredResourceId = resourceId(
            operation = operation,
            path = path,
            operationId = operationId,
            method = method,
            filteredCollectionResourceId = filteredCollectionResourceId,
        )
        val response = responseSchema(operation)
        val binaryResponseContentTypes = if (method == HttpMethod.GET) {
            successfulBinaryResponseContentTypes(operation)
        } else {
            null
        }
        val binaryRead = binaryResponseContentTypes != null
        val recordImagePreviewIdentity = if (binaryRead) {
            recordImagePreviewRouteResourceIdentity(path, readRouteResourceIdentities)
        } else {
            null
        }
        val (itemSchema, responseCollection) = responseItemSchema(response)
        val preliminaryCollection =
            responseCollection ||
                filteredCollectionResourceId != null ||
                routeResourceIdentity?.collection == true
        val label = operation.string("summary") ?: operationId.humanize()
        val effect = actionEffect(
            method = method,
            path = path,
            operationId = operationId,
            label = label,
            collection = preliminaryCollection,
        )
        val semanticRouteResourceIdentity = transitionRouteResourceIdentity(
            path = path,
            effect = effect,
            readRouteResourceIdentities = readRouteResourceIdentities,
        )
            ?: routeResourceIdentity
        val collection = preliminaryCollection || semanticRouteResourceIdentity?.collection == true
        val resourceId =
            recordImagePreviewIdentity?.resourceId
                ?: filteredCollectionResourceId
                ?: semanticRouteResourceIdentity?.resourceId
                ?: inferredResourceId
        val resource = resources.getOrPut(resourceId) { KotlinResourceBuilder(resourceId) }
        resource.collection = resource.collection || collection
        val responseFields = itemSchema?.let(::fieldsFromSchema).orEmpty()
        resource.mergeFields(responseFields)

        val (documentedPathParameters, queryParameters) = parameters(
            inheritedParameters,
            operation["parameters"] as? JsonArray,
        )
        // Merged contracts can specialize a route requirement such as `{apiVersion}` to `1.0`
        // while retaining the original path-item parameter. It is no longer a runtime input once
        // the placeholder is absent, so normalize it away and keep strict descriptor validation.
        val actualPathParameterNames = path.pathPlaceholders()
        val applicablePathParameters = documentedPathParameters.filter { parameter ->
            parameter.name in actualPathParameterNames
        }
        val (defaultBoundPath, defaultPathParameters) = bindDocumentedPathDefaults(path, applicablePathParameters)
        val (boundPath, pathParameters) = normalizeCollectionParentIdentifier(
            defaultBoundPath,
            defaultPathParameters,
            collection,
            identifierUsedOutsidePath = method != HttpMethod.GET ||
                queryParameters.any { it.name.equals("id", ignoreCase = true) } ||
                responseFields.any { it.id.equals("id", ignoreCase = true) } ||
                (declaredBody?.schema as? JsonObject)?.let(::fieldsFromSchema).orEmpty()
                    .any { it.id.equals("id", ignoreCase = true) },
        )
        val body = declaredBody
        (body?.schema as? JsonObject)?.let { bodySchema ->
            resource.mergeFields(fieldsFromSchema(bodySchema))
        }
        val auth = auth(operation)
        val risk = actionRisk(
            method = method,
            effect = effect,
            path = boundPath,
            operationId = operationId,
            label = label,
        )
        val permissionIds = auth.map { requirement ->
            val permissionId = "auth.${requirement.scheme.stableId()}"
            permissions.putIfAbsent(
                permissionId,
                PermissionSpec(
                    id = permissionId,
                    label = "${requirement.scheme.humanize()} authentication",
                    kind = if (requirement.scopes.isEmpty()) {
                        PermissionKind.authenticatedSession
                    } else {
                        PermissionKind.apiScope
                    },
                    state = PermissionState.required,
                    confidence = Confidence.high,
                    provenance = source,
                ),
            )
            permissionId
        }
        // The exact contract path used by an idempotent replacement may have been normalized on
        // its collection GET (for example `{entityId}` -> `{id}`). Once the original contract path
        // has proven the recovery relationship, use that GET's executable path binding for the PUT
        // as well. This keeps validation and post-write recovery exact without guessing an alias at
        // runtime.
        val recoveryPathBinding = resultRecoveryActionId
            ?.let(actions::get)
            ?.binding
        val action = DynamicAction(
            id = actionId,
            label = label,
            resourceId = resourceId,
            intent = effect.toActionIntent(),
            risk = risk,
            requiresConfirmation = risk == ActionRisk.destructive,
            binding = DynamicHttpBinding(
                method = method,
                path = recoveryPathBinding?.path ?: boundPath,
                pathParameters = recoveryPathBinding?.pathParameters ?: pathParameters,
                queryParameters = queryParameters,
                body = body,
                auth = auth,
                apiRequestHeader = hasApiRequestHeader(
                    inheritedParameters,
                    operation["parameters"] as? JsonArray,
                ),
                ocs = ocsMetadata(path, queryParameters),
            ),
            fallbackOnly = fallbackForOperationId != null,
            responseFieldIds = if (method == HttpMethod.GET) {
                responseFields.map(DynamicField::id)
            } else {
                emptyList()
            },
            permissionIds = permissionIds,
            confidence = Confidence.high,
            provenance = listOf(source),
            effect = effect,
            resultRecoveryActionId = resultRecoveryActionId,
        )
        actions[actionId] = action
        if (
            recordImagePreviewIdentity != null &&
            source.kind in TRUSTED_RECORD_IMAGE_PREVIEW_PROVENANCE &&
            binaryResponseContentTypes != null &&
            binaryResponseContentTypes.all(String::isDeclaredRecordImageContentType) &&
            action.binding.body == null &&
            action.binding.queryParameters.none(HttpParameter::required) &&
            action.hasResolvableRecordImageShape(resource)
        ) {
            recordImagePreviewCandidates.getOrPut(resourceId) { mutableListOf() } +=
                DynamicRecordImagePreviewSpec(
                    actionId = actionId,
                    declaredContentTypes = binaryResponseContentTypes,
                )
        }
        if (method == HttpMethod.GET) {
            readActionIdsByExactContractPath.getOrPut(path) { mutableListOf() } += actionId
        }

        if (method == HttpMethod.GET) {
            if (fallbackForOperationId == null && !binaryRead) {
                val kind = if (collection) LayoutKind.list else LayoutKind.detail
                val collectionState = path.terminalCollectionState()
                val layoutId = if (
                    (
                        kind == LayoutKind.list &&
                            (
                                filteredCollectionResourceId != null ||
                                    collectionState != null ||
                                    pathParameters.count { parameter ->
                                        parameter.name.isIdentityParameterName()
                                    } > 1
                                )
                        ) ||
                    (
                        kind == LayoutKind.detail &&
                            pathParameters.isNotEmpty() &&
                            resourceId.isScopedSingletonSurface()
                        )
                ) {
                    "$resourceId.${kind.name}.${operationId.stableId()}"
                } else {
                    "$resourceId.${kind.name}"
                }
                layoutPreference(resourceId, boundPath, operationId, kind, pathParameters)?.let { preference ->
                    val candidate = KotlinLayoutSeed(
                        id = layoutId,
                        title = collectionState?.humanize() ?: resourceId.humanize(),
                        resourceId = resourceId,
                        kind = kind,
                        sourceActionId = actionId,
                        preference = preference,
                        semanticFamily = resourceId.surfaceFamily(),
                        alternate = collectionState == null &&
                            isAlternateSurface(resourceId, boundPath, operationId),
                    )
                    val current = layoutSeeds[layoutId]
                    if (current == null || candidate.isPreferredTo(current)) layoutSeeds[layoutId] = candidate
                }
            }
        } else {
            val ocsFormatParameter = action.binding.ocs?.formatQueryParameter
            val bodyFields = body?.let(::formFields).orEmpty()
            val queryFields = queryParameters
                .filter { parameter ->
                    parameter.source == ParameterSource.userInput && parameter.name != ocsFormatParameter
                }
                .mapNotNull(::formField)
            forms["$actionId.form"] = DynamicForm(
                id = "$actionId.form",
                title = label,
                resourceId = resourceId,
                actionId = actionId,
                fields = (bodyFields + queryFields).distinctBy(FormField::fieldId),
                confidence = Confidence.high,
                provenance = listOf(source),
            )
        }
    }

    fun finish(): DynamicAppDescriptor {
        val completedActions = actions.mapValues { (actionId, action) ->
            action.copy(
                fallbackActionIds = fallbackOperationIds[actionId]
                    .orEmpty()
                    .mapNotNull(operationActionIds::get)
                    .filter { fallbackId -> fallbackId != actionId }
                    .distinct(),
            )
        }
        val completedResources = resources.values.map(KotlinResourceBuilder::finish).map { resource ->
            val preview = recordImagePreviewCandidates[resource.id]
                .orEmpty()
                .distinct()
                .singleOrNull()
            resource.copy(recordImagePreview = preview)
        }
        val preferredLayoutSeeds = layoutSeeds.values.filter { candidate ->
            !candidate.alternate || layoutSeeds.values.none { other ->
                !other.alternate &&
                    other.kind == candidate.kind &&
                    other.semanticFamily == candidate.semanticFamily &&
                    other.preference > candidate.preference
            }
        }
        val layouts = preferredLayoutSeeds.mapNotNull { seed ->
            completedResources.firstOrNull { it.id == seed.resourceId }?.let(seed::finish)
        }
        val fieldLinks = completedResources.flatMap { resource ->
            resource.fields.filter { it.format == "uri" }.map { field ->
                DynamicLink(
                    id = "${resource.id}.${field.id}.link",
                    label = field.label,
                    resourceId = resource.id,
                    sourceFieldId = field.id,
                    target = DynamicLinkTarget.FieldUrl(allowExternal = false),
                    confidence = field.confidence,
                    provenance = field.provenance,
                )
            }
        }
        // A display layout is one chosen surface per resource, but it is not the relationship
        // graph. Full contracts commonly advertise a convenient generic collection endpoint as
        // the display layout alongside more specific parent/child endpoints. Derive hierarchy
        // from every safe collection read, then retain the best route for each parent/child pair.
        // This keeps navigation relationships available without multiplying equivalent routes.
        val hierarchyLinks = completedActions.values
            .asSequence()
            .filter { action ->
                !action.fallbackOnly &&
                    action.binding.method == HttpMethod.GET &&
                    action.intent == ActionIntent.list &&
                    action.risk == ActionRisk.readOnly
            }
            .mapNotNull { action ->
                val child = completedResources.firstOrNull { it.id == action.resourceId }
                    ?: return@mapNotNull null
                val parent = action.navigationParent(child.id, completedResources)
                    ?: return@mapNotNull null
                if (parent.id.semanticBaseVariants().intersect(child.id.semanticBaseVariants()).isNotEmpty()) {
                    return@mapNotNull null
                }
                val preference = layoutPreference(
                    resourceId = child.id,
                    path = action.binding.path,
                    operationId = action.id,
                    kind = LayoutKind.list,
                    pathParameters = action.binding.pathParameters,
                ) ?: return@mapNotNull null
                HierarchyReadCandidate(parent, child, action, preference)
            }
            .groupBy { candidate -> candidate.parent.id to candidate.child.id }
            .values
            .mapNotNull { candidates ->
                val candidate = candidates.sortedWith(
                    compareByDescending<HierarchyReadCandidate>(HierarchyReadCandidate::preference)
                        .thenBy { it.action.id },
                ).firstOrNull() ?: return@mapNotNull null
                val identity = candidate.parent.fields
                    .filter { field -> field.id.lowercase() in setOf("databaseid", "id", "uuid", "token") }
                    .minByOrNull { field ->
                        when (field.id.lowercase()) {
                            "databaseid" -> 0
                            "id" -> 1
                            "uuid" -> 2
                            else -> 3
                        }
                    }
                val sourceFieldId = identity?.id ?: "id".takeIf { candidate.parent.collection }
                    ?: return@mapNotNull null
                DynamicLink(
                    id = "${candidate.parent.id}.${candidate.child.id}.collection",
                    label = candidate.child.label,
                    resourceId = candidate.parent.id,
                    sourceFieldId = sourceFieldId,
                    target = DynamicLinkTarget.Action(candidate.action.id),
                    confidence = if (identity == null) Confidence.medium else Confidence.high,
                    provenance = candidate.action.provenance,
                )
            }
        return DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = input.app,
            endpointPolicy = input.endpointPolicy,
            capabilities = input.capabilities,
            permissions = permissions.values.sortedBy(PermissionSpec::id),
            resources = completedResources.sortedBy(DynamicResource::id),
            layouts = layouts.sortedBy(DynamicLayout::id),
            links = (fieldLinks + hierarchyLinks).distinctBy(DynamicLink::id).sortedBy(DynamicLink::id),
            forms = forms.values.sortedBy(DynamicForm::id),
            actions = completedActions.values.sortedBy(DynamicAction::id),
            warnings = warnings,
        )
    }

    private fun responseSchema(operation: JsonObject): JsonElement? {
        val response = operation.objectValue("responses")
            ?.entries
            ?.sortedBy(Map.Entry<String, JsonElement>::key)
            ?.firstOrNull { it.key.startsWith('2') }
            ?.value
            ?.let(::resolveLocal) as? JsonObject
        return response?.objectValue("content")
            ?.get("application/json")
            ?.let { it as? JsonObject }
            ?.get("schema")
            ?.let(::resolveLocal)
    }

    /**
     * Exact binary response schemas remain media capabilities even when an OpenAPI generator emits
     * the wildcard content type. Local references are resolved before classification so those
     * bytes cannot accidentally enter the JSON record parser.
     */
    private fun hasSuccessfulBinaryResponse(operation: JsonObject): Boolean =
        successfulBinaryResponseContentTypes(operation) != null

    private fun successfulBinaryResponseContentTypes(operation: JsonObject): List<String>? {
        val responseMedia = operation.objectValue("responses")
            ?.entries
            ?.filter { (status, _) -> status.startsWith('2') }
            ?.mapNotNull { (_, responseElement) ->
                (resolveLocal(responseElement) as? JsonObject)?.objectValue("content")
            }
            ?.flatMap { content -> content.entries }
            .orEmpty()
        if (responseMedia.isEmpty()) return null
        val accepted = responseMedia.mapNotNull { (declaredType, mediaElement) ->
            val type = declaredType.substringBefore(';').trim().lowercase()
            if (type.contains("json")) return@mapNotNull null
            val media = resolveLocal(mediaElement) as? JsonObject
            val schema = media?.get("schema")?.let(::resolveLocal) as? JsonObject
            val exactBinarySchema = schema?.let { declared ->
                declared.string("type") == "string" && declared.string("format") == "binary"
            } == true
            type.takeIf {
                exactBinarySchema ||
                type.startsWith("image/") ||
                type.startsWith("audio/") ||
                type.startsWith("video/") ||
                type == "application/octet-stream"
            }
        }
        return accepted.distinct().sorted().takeIf { it.size == responseMedia.size }
    }

    private fun responseItemSchema(schema: JsonElement?): Pair<JsonObject?, Boolean> {
        val value = schema?.let(::resolveLocal) as? JsonObject ?: return null to false
        if (value.string("type") == "array") {
            return (value["items"]?.let(::resolveLocal) as? JsonObject) to true
        }
        val properties = value.objectValue("properties")
        listOf("ocs", "data").forEach { key ->
            properties?.get(key)?.let { nested ->
                val result = responseItemSchema(nested)
                if (result.first != null) return result
            }
        }
        if (properties.orEmpty().keys.any { it.lowercase() in setOf("id", "uuid", "token") }) {
            return value to false
        }
        val additionalProperties = value["additionalProperties"]
            ?.takeUnless { it is JsonPrimitive && it.booleanOrNull == false }
            ?.let(::resolveLocal) as? JsonObject
        if (additionalProperties != null) return additionalProperties to true
        val directCollections = properties.orEmpty().values.mapNotNull { nested ->
            val candidate = resolveLocal(nested) as? JsonObject ?: return@mapNotNull null
            if (candidate.string("type") != "array") return@mapNotNull null
            val item = candidate["items"]?.let(::resolveLocal) as? JsonObject ?: return@mapNotNull null
            item.takeIf {
                it.string("type") == "object" || it.objectValue("properties") != null || it["allOf"] is JsonArray
            }
        }
        if (directCollections.size == 1) return directCollections.single() to true
        return value.takeIf {
            it.string("type") == "object" || properties != null || it["allOf"] is JsonArray
        } to false
    }

    private fun fieldsFromSchema(schema: JsonObject): List<DynamicField> {
        val resolved = resolveLocal(schema) as? JsonObject ?: return emptyList()
        val inheritedFields = (resolved["allOf"] as? JsonArray)
            .orEmpty()
            .mapNotNull { resolveLocal(it) as? JsonObject }
            .flatMap(::fieldsFromSchema)
        val required = (resolved["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .toSet()
        val directFields = resolved.objectValue("properties")
            .orEmpty()
            .entries
            .sortedBy(Map.Entry<String, JsonElement>::key)
            .mapNotNull { (id, element) ->
                val field = resolveFieldSchema(element) as? JsonObject ?: return@mapNotNull null
                DynamicField(
                    id = id,
                    label = field.string("title") ?: id.humanize(),
                    kind = fieldKind(id, field),
                    required = id in required,
                    readOnly = field.boolean("readOnly") ?: false,
                    nullable = field.boolean("nullable") ?: false,
                    multiple = field.string("type") == "array",
                    format = field.dynamicEditorFormat(),
                    enumValues = field.stringArray("enum"),
                    confidence = Confidence.high,
                    provenance = listOf(source),
                )
            }
        return (inheritedFields + directFields).distinctBy(DynamicField::id)
    }

    private fun parameters(
        inherited: JsonArray?,
        operation: JsonArray?,
    ): Pair<List<HttpParameter>, List<HttpParameter>> {
        val path = linkedMapOf<String, HttpParameter>()
        val query = linkedMapOf<String, HttpParameter>()
        inherited.orEmpty().plus(operation.orEmpty()).forEach { element ->
            val parameter = resolveLocal(element) as? JsonObject ?: return@forEach
            val name = parameter.string("name") ?: return@forEach
            val location = parameter.string("in") ?: return@forEach
            val compiled = HttpParameter(
                name = name,
                required = parameter.boolean("required") ?: (location == "path"),
                schema = parameter["schema"]?.let(::resolveLocal) ?: JsonObject(emptyMap()),
                source = if (location == "path") ParameterSource.resourceField else ParameterSource.userInput,
            )
            when (location) {
                "path" -> path[name] = compiled
                "query" -> query[name] = compiled
            }
        }
        return path.values.sortedBy(HttpParameter::name) to query.values.sortedBy(HttpParameter::name)
    }

    private fun hasApiRequestHeader(inherited: JsonArray?, operation: JsonArray?): Boolean =
        inherited.orEmpty().plus(operation.orEmpty()).any { element ->
            val parameter = resolveLocal(element) as? JsonObject ?: return@any false
            parameter.string("in") == "header" &&
                parameter.string("name")?.equals("OCS-APIRequest", ignoreCase = true) == true
        }

    private fun body(operation: JsonObject): HttpBody? {
        val request = operation["requestBody"]?.let(::resolveLocal) as? JsonObject
        val content = request?.objectValue("content")
        inferredSignedDescriptionMultipartBody(operation, request, content)?.let { return it }
        request ?: return null
        content ?: return null
        val contentType = listOf(
            "multipart/form-data",
            "application/json",
            "application/x-www-form-urlencoded",
        ).firstOrNull { candidate ->
            val media = content[candidate] as? JsonObject ?: return@firstOrNull false
            val schema = media["schema"]?.let(::resolveLocal)
            candidate != "multipart/form-data" || isExactDynamicMultipartSchema(schema)
        } ?: content.keys.firstOrNull() ?: return null
        val media = content[contentType] as? JsonObject ?: return null
        val declaredSchema = media["schema"]?.let(::resolveLocal) ?: return null
        // The generic array editors produce a typed JSON value. Form and multipart array
        // serialization depends on exact media encoding/style/explode metadata that the descriptor
        // does not retain, so those bodies must not acquire a JSON-oriented editor format.
        val jsonBody = contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)
        if (jsonBody && declaredSchema.hasUnnormalizableReadOnlyRepeatableObjectProperty()) return null
        if (jsonBody && declaredSchema.hasUnsupportedDynamicStringArrayProperty()) return null
        val schema = if (jsonBody) {
            declaredSchema.withDynamicFormFormats()
        } else {
            declaredSchema
        }
        return HttpBody(
            contentType = contentType,
            required = request.boolean("required") ?: false,
            schema = schema,
        )
    }

    private fun JsonElement.hasUnnormalizableReadOnlyRepeatableObjectProperty(): Boolean {
        val objectSchema = this as? JsonObject ?: return false
        val properties = objectSchema.objectValue("properties") ?: return false
        return properties.values.any { element ->
            val array = resolveFieldSchema(element) as? JsonObject ?: return@any false
            if (array.string("type") != "array") return@any false
            val item = array["items"]?.let(::resolveLocal) as? JsonObject ?: return@any false
            if (item.string("type") != "object") return@any false
            val nestedProperties = item.objectValue("properties") ?: return@any false
            val containsReadOnly = nestedProperties.values.any nestedField@{ nestedElement ->
                val nested = resolveFieldSchema(nestedElement) as? JsonObject ?: return@nestedField false
                nested.boolean("readOnly") == true
            }
            containsReadOnly && normalizeRepeatableObjectArraySchema(array) == null
        }
    }

    private fun JsonElement.hasUnsupportedDynamicStringArrayProperty(): Boolean {
        val objectSchema = this as? JsonObject ?: return false
        val properties = objectSchema.objectValue("properties") ?: return false
        return properties.values.any { element ->
            val array = resolveFieldSchema(element) as? JsonObject ?: return@any false
            val item = array["items"]?.let(::resolveLocal) as? JsonObject ?: return@any false
            array.string("type") == "array" &&
                item.string("type") == "string" &&
                !array.isExactEditableDynamicStringArraySchema(item)
        }
    }

    private fun JsonObject.isExactEditableDynamicStringArraySchema(item: JsonObject): Boolean =
        keys.all(EDITABLE_DYNAMIC_STRING_ARRAY_SCHEMA_KEYS::contains) &&
            item.keys.all(EDITABLE_DYNAMIC_STRING_ARRAY_ITEM_SCHEMA_KEYS::contains) &&
            ("nullable" !in this || boolean("nullable") == false) &&
            ("nullable" !in item || item.boolean("nullable") == false)

    private fun inferredSignedDescriptionMultipartBody(
        operation: JsonObject,
        request: JsonObject?,
        content: JsonObject?,
    ): HttpBody? {
        if (!allowSignedDescriptionMultipartInference) return null
        val description = operation.string("description")
            ?.takeIf { it.length in 1..MAX_MULTIPART_DESCRIPTION_CHARACTERS }
            ?: return null
        val fileMatches = MULTIPART_FILE_FIELD_DESCRIPTION.findAll(description).toList()
        if (fileMatches.size != 1) return null
        val fileFieldName = fileMatches.single().groupValues[2]
        if (!fileFieldName.isSafeInferredMultipartFieldName()) return null
        val declaredJson = content?.entries
            ?.singleOrNull { (type, _) ->
                type.substringBefore(';').trim().equals("application/json", ignoreCase = true)
            }
            ?.value as? JsonObject
        if (content != null && (content.size != 1 || declaredJson == null)) return null
        val declaredJsonSchema = declaredJson
            ?.get("schema")
            ?.let(::resolveLocal) as? JsonObject
        val declaredProperties = declaredJsonSchema?.objectValue("properties").orEmpty()
        if (fileFieldName in declaredProperties) return null

        val additionalMatches = ADDITIONAL_MULTIPART_FIELDS_DESCRIPTION.findAll(description).toList()
        if (additionalMatches.size > 1) return null
        val additionalFields = additionalMatches.singleOrNull()
            ?.groupValues
            ?.get(1)
            ?.parseInferredMultipartFieldList()
            ?: emptyList()
        if (additionalFields.size > MAX_INFERRED_MULTIPART_TEXT_FIELDS) return null
        if (
            additionalFields.any { !it.isSafeInferredMultipartFieldName() } ||
            additionalFields.distinct().size != additionalFields.size
        ) {
            return null
        }

        if (declaredJsonSchema == null) {
            if (request != null || content != null || additionalFields.isNotEmpty()) return null
        } else {
            if (declaredJsonSchema.string("type") != "object") return null
            if (additionalFields.toSet() != declaredProperties.keys) return null
            if (declaredProperties.values.any { element ->
                    val property = resolveLocal(element) as? JsonObject ?: return@any true
                    property.string("type") !in INFERRED_MULTIPART_SCALAR_TYPES ||
                        property.string("format") == "binary"
                }
            ) {
                return null
            }
        }

        val mediaWord = fileMatches.single().groupValues[1].lowercase()
        val fileProperty = buildMap<String, JsonElement> {
            put("type", JsonPrimitive("string"))
            put("format", JsonPrimitive("binary"))
            when (mediaWord) {
                "image", "photo" -> put("contentMediaType", JsonPrimitive("image/*"))
                "audio" -> put("contentMediaType", JsonPrimitive("audio/*"))
                "video" -> put("contentMediaType", JsonPrimitive("video/*"))
            }
            put(DESCRIPTION_INFERRED_MULTIPART_EXTENSION, JsonPrimitive(true))
        }
        val properties = JsonObject(
            declaredProperties + (fileFieldName to JsonObject(fileProperty)),
        )
        val declaredRequired = (declaredJsonSchema?.get("required") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter(declaredProperties::containsKey)
        val schema = JsonObject(
            buildMap {
                put("type", JsonPrimitive("object"))
                put("properties", properties)
                put("required", JsonArray((declaredRequired + fileFieldName).distinct().map(::JsonPrimitive)))
                put(DESCRIPTION_INFERRED_MULTIPART_EXTENSION, JsonPrimitive(true))
            },
        )
        return HttpBody(
            contentType = "multipart/form-data",
            required = request?.boolean("required") ?: true,
            schema = schema,
        )
    }

    private fun isExactDynamicMultipartSchema(element: JsonElement?): Boolean {
        val schema = element as? JsonObject ?: return false
        if (schema.string("type") != "object") return false
        val properties = schema.objectValue("properties") ?: return false
        if (properties.isEmpty() || properties.size > MAX_INFERRED_MULTIPART_TEXT_FIELDS + 1) return false
        var binaryFieldName: String? = null
        properties.forEach { (name, propertyElement) ->
            if (!name.isSafeInferredMultipartFieldName()) return false
            val property = resolveLocal(propertyElement) as? JsonObject ?: return false
            if (property.string("type") == "string" && property.string("format") == "binary") {
                if (binaryFieldName != null) return false
                binaryFieldName = name
            } else if (property.string("type") !in INFERRED_MULTIPART_SCALAR_TYPES) {
                return false
            }
        }
        val required = (schema["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val exactBinaryFieldName = binaryFieldName ?: return false
        return exactBinaryFieldName in required
    }

    private fun HttpBody.isUnsupportedOptionalFileMultipartBody(): Boolean {
        if (!contentType.substringBefore(';').trim().equals("multipart/form-data", ignoreCase = true)) {
            return false
        }
        val objectSchema = schema as? JsonObject ?: return false
        val properties = objectSchema.objectValue("properties") ?: return false
        val binaryFields = properties.entries.filter { (_, element) ->
            val property = resolveLocal(element) as? JsonObject ?: return@filter false
            property.string("type") == "string" && property.string("format") == "binary"
        }
        if (binaryFields.size != 1) return false
        val required = (objectSchema["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return binaryFields.single().key !in required
    }

    private fun JsonElement.withDynamicFormFormats(): JsonElement {
        val schema = this as? JsonObject ?: return this
        val properties = schema["properties"] as? JsonObject ?: return schema
        val formattedProperties = JsonObject(
            properties.mapValues { (_, property) ->
                val propertySchema = resolveFieldSchema(property) as? JsonObject ?: return@mapValues property
                val type = propertySchema.string("type")
                val itemType = (propertySchema["items"]?.let(::resolveLocal) as? JsonObject)?.string("type")
                when {
                    type == "array" && itemType == "string" &&
                        propertySchema.isExactEditableDynamicStringArraySchema(
                            propertySchema["items"]?.let(::resolveLocal) as? JsonObject
                                ?: return@mapValues property,
                        ) ->
                        JsonObject(propertySchema + ("format" to JsonPrimitive(DYNAMIC_STRING_ARRAY_FORMAT)))
                    type == "array" && itemType == "integer" ->
                        JsonObject(propertySchema + ("format" to JsonPrimitive(DYNAMIC_INTEGER_ARRAY_FORMAT)))
                    type == "array" && itemType == "object" ->
                        normalizeRepeatableObjectArraySchema(propertySchema) ?: propertySchema
                    type == "array" -> propertySchema
                    else -> property
                }
            },
        )
        return JsonObject(schema + ("properties" to formattedProperties))
    }

    private fun normalizeRepeatableObjectArraySchema(array: JsonObject): JsonObject? {
        val item = array["items"]?.let(::resolveLocal) as? JsonObject ?: return null
        if (item.string("type") != "object") return null
        val properties = item.objectValue("properties") ?: return null
        val description = array.string("description")
        val readOnlyFieldIds = mutableSetOf<String>()
        val normalizedProperties = linkedMapOf<String, JsonElement>()
        properties.forEach { (fieldId, element) ->
            val scalar = resolveFieldSchema(element) as? JsonObject ?: return null
            if (scalar.boolean("readOnly") == true) {
                readOnlyFieldIds += fieldId
                return@forEach
            }
            val recoveredEnum = if (
                allowSignedDescriptionEnumInference &&
                scalar.string("type") == "string" &&
                scalar["enum"] == null
            ) {
                description?.exactSignedDescriptionEnum(fieldId)
            } else {
                null
            }
            normalizedProperties[fieldId] = if (recoveredEnum == null) {
                scalar
            } else {
                JsonObject(
                    scalar + (
                        DESCRIPTION_ENUM_EXTENSION to
                            JsonArray(recoveredEnum.map(::JsonPrimitive))
                        ),
                )
            }
        }
        if (normalizedProperties.isEmpty()) return null
        val normalizedItemValues = item.toMutableMap().apply {
            put("properties", JsonObject(normalizedProperties))
            val required = item["required"] as? JsonArray
            if (required != null && readOnlyFieldIds.isNotEmpty()) {
                put(
                    "required",
                    JsonArray(
                        required.filterNot { element ->
                            val fieldId = (element as? JsonPrimitive)
                                ?.takeIf(JsonPrimitive::isString)
                                ?.contentOrNull
                            fieldId != null && fieldId in readOnlyFieldIds
                        },
                    ),
                )
            }
        }
        val normalizedItem = JsonObject(normalizedItemValues)
        val normalized = JsonObject(
            array +
                ("items" to normalizedItem) +
                ("format" to JsonPrimitive(DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT)),
        )
        return normalized.takeIf { it.repeatableObjectInputSpec() != null }
    }

    private fun bindDocumentedPathDefaults(
        path: String,
        parameters: List<HttpParameter>,
    ): Pair<String, List<HttpParameter>> {
        var boundPath = path
        val remaining = parameters.filter { parameter ->
            val schema = parameter.schema as? JsonObject
            val default = (schema?.get("default") as? JsonPrimitive)?.contentOrNull
                ?: (schema?.get("enum") as? JsonArray)
                    ?.singleOrNull()
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
            val safeDefault = default?.takeIf(String::isSafeDocumentedPathSegment)
                ?: return@filter true
            boundPath = boundPath.replace("{${parameter.name}}", safeDefault)
            false
        }
        return boundPath to remaining
    }

    private fun normalizeCollectionParentIdentifier(
        path: String,
        parameters: List<HttpParameter>,
        collection: Boolean,
        identifierUsedOutsidePath: Boolean = false,
    ): Pair<String, List<HttpParameter>> {
        if (!collection || parameters.size != 1 || identifierUsedOutsidePath) return path to parameters
        val parameter = parameters.single()
        if (parameter.name.equals("id", ignoreCase = true) || !parameter.name.endsWith("Id", ignoreCase = true)) {
            return path to parameters
        }
        val placeholder = "{${parameter.name}}"
        val parameterIndex = path.split('/').indexOf(placeholder)
        if (parameterIndex <= 0 || parameterIndex >= path.split('/').lastIndex) return path to parameters
        return path.replace(placeholder, "{id}") to listOf(parameter.copy(name = "id"))
    }

    private fun auth(operation: JsonObject): List<AuthRequirement> {
        val security = (operation["security"] ?: document["security"]) as? JsonArray
        val schemes = document.objectValue("components")?.objectValue("securitySchemes")
        if (security == null) {
            return listOf(AuthRequirement("nextcloud-session", AuthKind.nextcloudSession))
        }
        if (security.isEmpty()) return emptyList()
        val alternatives = security.mapNotNull { requirementElement ->
            val requirement = requirementElement as? JsonObject ?: return@mapNotNull null
            val result = linkedMapOf<String, AuthRequirement>()
            requirement.forEach { (name, scopesElement) ->
                val definition = schemes?.get(name)?.let(::resolveLocal) as? JsonObject
                result[name] = AuthRequirement(
                    scheme = name,
                    kind = authKind(definition),
                    scopes = (scopesElement as? JsonArray)
                        .orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                )
            }
            result.values.sortedBy(AuthRequirement::scheme)
        }
        return alternatives.minWithOrNull(
            compareBy<List<AuthRequirement>>(
                { requirements -> requirements.maxOfOrNull { it.kind.transportPreference() } ?: 0 },
                List<AuthRequirement>::size,
                { requirements -> requirements.joinToString(",", transform = AuthRequirement::scheme) },
            ),
        ).orEmpty()
    }

    private fun formFields(body: HttpBody): List<FormField>? {
        val schema = resolveLocal(body.schema) as? JsonObject ?: return null
        val supportsTypedArrays = body.contentType.substringBefore(';').trim()
            .equals("application/json", ignoreCase = true)
        val required = (schema["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .toSet()
        val properties = schema.objectValue("properties") ?: return null
        return properties.entries.sortedBy(Map.Entry<String, JsonElement>::key).mapNotNull { (id, element) ->
            val field = resolveFieldSchema(element) as? JsonObject ?: return@mapNotNull null
            if (field.boolean("readOnly") == true) return@mapNotNull null
            if (field.string("type") == "array" && !supportsTypedArrays) return@mapNotNull null
            val repeatableObjectInput = field.repeatableObjectInputSpec()
            val enumValues = field.stringArray("enum")
            val enumLabels = field.nativeEnumLabels(enumValues)
            if (ENUM_LABELS_EXTENSION in field && enumLabels == null) return@mapNotNull null
            FormField(
                fieldId = id,
                label = field.string("title") ?: id.humanize(),
                kind = fieldKind(id, field),
                required = id in required,
                format = repeatableObjectInput
                    ?.let { DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT }
                    ?: field.dynamicEditorFormat(),
                enumValues = enumValues,
                enumLabels = enumLabels,
                repeatableObjectInput = repeatableObjectInput,
            )
        }
    }

    private fun formField(parameter: HttpParameter): FormField? {
        val schema = resolveFieldSchema(parameter.schema) as? JsonObject ?: return null
        val enumValues = schema.stringArray("enum")
        val enumLabels = schema.nativeEnumLabels(enumValues)
        if (ENUM_LABELS_EXTENSION in schema && enumLabels == null) return null
        return FormField(
            fieldId = parameter.name,
            label = schema.string("title") ?: parameter.name.humanize(),
            kind = fieldKind(parameter.name, schema),
            required = parameter.required,
            format = schema.string("format"),
            enumValues = enumValues,
            enumLabels = enumLabels,
        )
    }

    /**
     * Resolves the exact scalar/field shape behind a single-member OpenAPI `allOf` wrapper.
     *
     * OpenAPI generators commonly express a nullable enum property as
     * `{ "nullable": true, "allOf": [{ "$ref": "..." }] }`. This is not an enum union: one
     * referenced schema supplies the value shape while the wrapper supplies annotations. Multiple
     * members or structural wrapper keywords remain unresolved so downstream UI inference fails
     * closed instead of inventing a combined type or option set.
     */
    private fun resolveFieldSchema(element: JsonElement, depth: Int = 0): JsonElement {
        require(depth <= 24) { "OpenAPI field composition depth exceeded" }
        val resolved = resolveLocal(element) as? JsonObject ?: return resolveLocal(element)
        val allOf = resolved["allOf"] as? JsonArray ?: return resolved
        val member = allOf.singleOrNull() ?: return resolved
        val wrapper = resolved.filterKeys { key -> key != "allOf" }
        if (wrapper.keys.any { key -> key !in FIELD_COMPOSITION_ANNOTATION_KEYS }) return resolved
        val inherited = resolveFieldSchema(member, depth + 1) as? JsonObject ?: return resolved
        if ("allOf" in inherited) return resolved
        return JsonObject(inherited + wrapper)
    }

    private fun resolveLocal(element: JsonElement, depth: Int = 0): JsonElement {
        require(depth <= 24) { "OpenAPI reference depth exceeded" }
        val objectValue = element as? JsonObject ?: return element
        val reference = objectValue.string("${'$'}ref") ?: return element
        require(reference.startsWith("#/")) { "Only local OpenAPI references are supported" }
        var target: JsonElement = document
        reference.removePrefix("#").split('/').filter(String::isNotEmpty).forEach { token ->
            val key = token.replace("~1", "/").replace("~0", "~")
            target = (target as? JsonObject)?.get(key) ?: error("Invalid OpenAPI reference: $reference")
        }
        return resolveLocal(target, depth + 1)
    }
}

private data class HierarchyReadCandidate(
    val parent: DynamicResource,
    val child: DynamicResource,
    val action: DynamicAction,
    val preference: Int,
)

private data class KotlinResourceBuilder(
    val id: String,
    var collection: Boolean = false,
    val fields: MutableMap<String, DynamicField> = linkedMapOf(),
) {
    fun mergeFields(discovered: List<DynamicField>) {
        discovered.forEach { candidate ->
            val current = fields[candidate.id]
            fields[candidate.id] = when {
                current == null -> candidate
                current.kind == FieldKind.unknown && candidate.kind != FieldKind.unknown ->
                    candidate.copy(
                        required = current.required || candidate.required,
                        readOnly = current.readOnly,
                        provenance = (current.provenance + candidate.provenance).distinct(),
                    )
                current.format == null && candidate.format != null ->
                    current.copy(
                        format = candidate.format,
                        provenance = (current.provenance + candidate.provenance).distinct(),
                    )
                else -> current
            }
        }
    }

    fun finish(): DynamicResource = DynamicResource(
        id = id,
        label = id.humanize(),
        collection = collection,
        fields = fields.values.sortedBy(DynamicField::id),
        confidence = Confidence.high,
        provenance = fields.values.flatMap(DynamicField::provenance).distinct(),
    )
}

private data class KotlinLayoutSeed(
    val id: String,
    val title: String,
    val resourceId: String,
    val kind: LayoutKind,
    val sourceActionId: String,
    val preference: Int,
    val semanticFamily: String,
    val alternate: Boolean,
) {
    fun isPreferredTo(other: KotlinLayoutSeed): Boolean =
        preference > other.preference || preference == other.preference && sourceActionId < other.sourceActionId

    fun finish(resource: DynamicResource): DynamicLayout = DynamicLayout(
        id = id,
        title = title,
        resourceId = resourceId,
        kind = kind,
        fields = resource.fields.mapIndexed { index, field ->
            LayoutField(
                fieldId = field.id,
                role = field.layoutRole(index),
                visible = index < if (kind == LayoutKind.list) 5 else 32,
            )
        },
        sourceActionId = sourceActionId,
        confidence = Confidence.high,
        provenance = resource.provenance,
    )
}

private fun metadataDescriptor(input: DynamicDiscoveryInput): DynamicAppDescriptor {
    val provenance = Provenance(
        ProvenanceKind.appMetadata,
        input.app.id,
        "Installed app identity only",
    )
    val fields = listOf("id" to "App ID", "name" to "Name", "version" to "Version").map { (id, label) ->
        DynamicField(
            id = id,
            label = label,
            kind = FieldKind.string,
            required = true,
            readOnly = true,
            nullable = false,
            multiple = false,
            confidence = Confidence.verified,
            provenance = listOf(provenance),
        )
    }
    return DynamicAppDescriptor(
        descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
        app = input.app,
        endpointPolicy = input.endpointPolicy,
        capabilities = input.capabilities,
        resources = listOf(
            DynamicResource(
                id = "app-metadata",
                label = "App metadata",
                collection = false,
                fields = fields,
                confidence = Confidence.low,
                provenance = listOf(provenance),
            ),
        ),
        layouts = listOf(
            DynamicLayout(
                id = "app-metadata.detail",
                title = input.app.name,
                resourceId = "app-metadata",
                kind = LayoutKind.detail,
                fields = fields.map { LayoutField(it.id, LayoutFieldRole.metadata, true) },
                confidence = Confidence.low,
                provenance = listOf(provenance),
            ),
        ),
        warnings = listOf(
            DynamicWarning(
                "metadata-only",
                "No advertised OpenAPI was available; no endpoint actions were created",
            ),
        ),
    )
}

private data class NormalizedOpenApiServer(
    val pathBase: String,
    val requiresTrustedRebase: Boolean,
    val original: String,
)

private fun openApiServerBase(
    document: JsonObject,
    origin: String,
    allowTrustedRebase: Boolean,
): String {
    val servers = (document["servers"] as? JsonArray)
        .orEmpty()
        .mapNotNull { (it as? JsonObject)?.string("url") }
        .distinct()
    if (servers.isEmpty()) return ""
    if (servers.size == 1) {
        val value = servers.single()
        require(!value.contains('{') && !value.contains('?') && !value.contains('#')) {
            "Templated or qualified OpenAPI server URL is unsupported: $value"
        }
        return normalizeOpenApiServer(value, origin).also { normalized ->
            require(!normalized.requiresTrustedRebase) { "Cross-origin OpenAPI server: $value" }
        }.pathBase
    }

    val normalized = servers.map { value -> normalizeOpenApiServer(value, origin) }
    val pathBases = normalized.map(NormalizedOpenApiServer::pathBase).distinct().sorted()
    require(pathBases.size == 1) {
        "OpenAPI server entries resolve to conflicting path bases: ${pathBases.joinToString()}"
    }
    val rebased = normalized.filter(NormalizedOpenApiServer::requiresTrustedRebase)
    require(rebased.isEmpty() || allowTrustedRebase) {
        "OpenAPI server entries require cross-origin or templated rebasing: " +
            rebased.joinToString { server -> server.original }
    }
    return pathBases.single()
}

private fun normalizeOpenApiServer(value: String, origin: String): NormalizedOpenApiServer {
    require(value.isNotBlank() && !value.contains('?') && !value.contains('#') && !value.contains('\\')) {
        "OpenAPI server URL is unsupported: $value"
    }
    if (value.startsWith('/') && !value.startsWith("//")) {
        require(!value.contains('{') && value.isSafeRelativePath()) {
            "OpenAPI server path is unsupported: $value"
        }
        return NormalizedOpenApiServer(value.trimEnd('/'), false, value)
    }

    val separator = value.indexOf("://")
    require(separator > 0) { "OpenAPI server URL is unsupported: $value" }
    val authorityStart = separator + 3
    val pathStart = value.indexOf('/', authorityStart)
    val authority = if (pathStart < 0) value.substring(authorityStart) else value.substring(authorityStart, pathStart)
    require(authority.isNotBlank() && !authority.contains('@') && authority.none(Char::isWhitespace)) {
        "OpenAPI server authority is unsupported: $value"
    }
    val path = if (pathStart < 0) "" else value.substring(pathStart)
    require(!path.contains('{') && (path.isEmpty() || path.isSafeRelativePath())) {
        "OpenAPI server path is unsupported: $value"
    }
    val declaredOrigin = value.substring(0, authorityStart) + authority
    val sameOrigin = !declaredOrigin.contains('{') &&
        declaredOrigin.trimEnd('/').equals(origin.trimEnd('/'), ignoreCase = true)
    return NormalizedOpenApiServer(path.trimEnd('/'), !sameOrigin, value)
}

private fun combinePaths(base: String, path: String): String =
    "${base.trimEnd('/')}/${path.trimStart('/')}"

private fun String.isApproved(policy: EndpointPolicy): Boolean =
    isSafeRelativePath() && policy.approvedApiPrefixes.any(::matchesPrefix)

private fun String.isSameOriginDocument(origin: String): Boolean = when {
    startsWith('/') -> !startsWith("//") && !contains('\\')
    else -> startsWith(origin.trimEnd('/') + "/")
}

private fun String.toHttpMethod(): HttpMethod? = when (lowercase()) {
    "get" -> HttpMethod.GET
    "post" -> HttpMethod.POST
    "put" -> HttpMethod.PUT
    "patch" -> HttpMethod.PATCH
    "delete" -> HttpMethod.DELETE
    else -> null
}

private fun resourceId(
    operation: JsonObject,
    path: String,
    operationId: String,
    method: HttpMethod,
    filteredCollectionResourceId: String? =
        semanticFilteredCollectionResourceId(operation, path, operationId, method),
): String {
    operation.string(RESOURCE_ID_EXTENSION)
        ?.stableId()
        ?.takeIf { it.isNotBlank() && it.length <= 64 }
        ?.let { return it }
    filteredCollectionResourceId?.let { return it }
    val rawTag = (operation["tags"] as? JsonArray)
        ?.firstOrNull()
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
    val tagResource = rawTag?.semanticTagResourceId()
    val pathResource = path.semanticPathResourceId()
    if (
        method != HttpMethod.GET &&
        pathResource in setOf("import", "export") &&
        tagResource != null &&
        operation.referencesSemanticResource(tagResource, operationId)
    ) {
        return tagResource
    }
    if (tagResource in ROOT_RESOURCE_WORDS && pathResource == null) return "overview"
    if (tagResource == null) {
        return pathResource ?: operationId.split('.', '_', '-').firstOrNull {
            it !in setOf("get", "list", "create", "update", "delete")
        }?.stableId()?.takeIf(String::isNotBlank)
        ?: "resource"
    }
    if (pathResource == null) return tagResource
    if (tagResource.semanticBaseVariants().intersect(pathResource.semanticBaseVariants()).isNotEmpty()) {
        val scopes = (rawTag.orEmpty().stableId().split('-') + path.stableId().split('-'))
            .filter { it in ALTERNATE_SURFACE_WORDS }
            .distinct()
        return (scopes + pathResource).joinToString("-")
    }
    if (operationId.provesResource(pathResource)) return pathResource
    return tagResource
}

private fun JsonObject.referencesSemanticResource(resourceId: String, operationId: String): Boolean {
    val evidence = listOfNotNull(
        operationId,
        string("summary"),
        string("description"),
    ).joinToString(" ").stableId()
    return resourceId.semanticBaseVariants().any { variant ->
        variant.length >= 4 && evidence.contains(variant)
    }
}

private fun JsonObject.isSensitiveCredentialMutation(path: String, operationId: String): Boolean {
    val semantics = listOfNotNull(
        operationId,
        string("summary"),
        string("description"),
        path,
    ).joinToString(" ").lowercase()
    val credentialTarget = SENSITIVE_CREDENTIAL_CONCEPTS.any(semantics::contains)
    val credentialMutation = SENSITIVE_CREDENTIAL_MUTATION_CONCEPTS.any(semantics::contains)
    return credentialTarget && credentialMutation
}

private val SENSITIVE_CREDENTIAL_CONCEPTS = setOf(
    "api key",
    "apikey",
    "credential",
    "password",
    "secret",
    "token",
    "userkey",
    "/keys",
    "/key/",
)

private val SENSITIVE_CREDENTIAL_MUTATION_CONCEPTS = setOf(
    "create",
    "generate",
    "issue",
    "reset",
    "rotate",
)

/**
 * Some mutations produce externally visible or non-repeatable effects whose result cannot be
 * recovered safely by the generic request pipeline after a timeout or disconnect. Keep those
 * operations out of generic discovery until the operation declares a dedicated ambiguous-result
 * policy. Exact semantic words avoid withholding unrelated concepts such as senders, shared
 * preferences, or mergeable records.
 */
private fun JsonObject.requiresAmbiguousResultRecoveryPolicy(
    path: String,
    operationId: String,
): Boolean {
    val words = actionSemanticWords(
        path = path,
        operationId = operationId,
        label = string("summary").orEmpty(),
    )
    return words.any(AMBIGUOUS_RESULT_MUTATION_WORDS::contains)
}

private val AMBIGUOUS_RESULT_MUTATION_WORDS = setOf(
    "merge",
    "send",
    "share",
)

/**
 * Recovers only the narrow signed prose shape `field ('first' or 'second')`.
 *
 * The field name must occur exactly once as a complete word, the parenthesized expression must
 * immediately follow it, and every alternative must be a quoted bounded scalar. General prose,
 * examples, comma lists, and unquoted words remain non-authoritative.
 */
private fun String.exactSignedDescriptionEnum(fieldId: String): List<String>? {
    if (
        length !in 1..MAX_SIGNED_DESCRIPTION_ENUM_CHARACTERS ||
        fieldId.isBlank() ||
        fieldId.length > MAX_SIGNED_DESCRIPTION_ENUM_FIELD_LENGTH
    ) {
        return null
    }
    val occurrences = indices.filter { index ->
        index + fieldId.length <= length &&
            regionMatches(index, fieldId, 0, fieldId.length, ignoreCase = true) &&
            (index == 0 || !this[index - 1].isLetterOrDigit()) &&
            (index + fieldId.length == length || !this[index + fieldId.length].isLetterOrDigit())
    }
    val start = occurrences.singleOrNull() ?: return null
    val tail = substring(start + fieldId.length).trimStart()
    if (!tail.startsWith('(')) return null
    val close = tail.indexOf(')')
    if (close <= 1) return null
    val expression = tail.substring(1, close)
    val tokens = expression.split(Regex("""\s+or\s+""", RegexOption.IGNORE_CASE))
    if (tokens.size !in 2..MAX_SIGNED_DESCRIPTION_ENUM_VALUES) return null
    val values = tokens.mapNotNull { token ->
        val quoted = token.trim()
        if (
            quoted.length < 3 ||
            quoted.first() !in setOf('\'', '"') ||
            quoted.last() != quoted.first()
        ) {
            return@mapNotNull null
        }
        quoted.substring(1, quoted.lastIndex).takeIf { value ->
            value.length in 1..MAX_SIGNED_DESCRIPTION_ENUM_VALUE_LENGTH &&
                value.all { character ->
                    character.isLetterOrDigit() || character in setOf('-', '_', '.', ' ')
                }
        }
    }
    return values.takeIf {
        it.size == tokens.size &&
            it.distinct().size == it.size
    }
}

/**
 * Filter endpoints often use a taxonomy noun in their route even though their response is a
 * collection of the filtered subject. Prefer the subject proven by operation metadata so records
 * open with the subject's renderer and detail actions instead of as raw category/tag records.
 */
private fun semanticFilteredCollectionResourceId(
    operation: JsonObject,
    path: String,
    operationId: String,
    method: HttpMethod,
): String? {
    if (method != HttpMethod.GET) return null
    val taxonomyFilter =
        path.hasAnySemanticConcept(SEMANTIC_TAXONOMY_CONCEPTS) &&
            path.pathPlaceholders().isNotEmpty()
    if (!taxonomyFilter) return null
    val operationText = listOfNotNull(
        operationId,
        operation.string("summary"),
        operation.string("description"),
    ).joinToString(" ").lowercase()
    return when {
        "recipe" in operationText -> "recipes"
        else -> null
    }
}

private const val RESOURCE_ID_EXTENSION = "x-nextcloud-native-resource-id"

private fun String.provesResource(resourceId: String): Boolean {
    val resourceVariants = resourceId.semanticBaseVariants()
    val operationId = stableId()
    val exactWord = operationId.split('-')
        .asSequence()
        .filter { it !in NON_RESOURCE_OPERATION_WORDS }
        .any { word -> word.resourceNameVariants().intersect(resourceVariants).isNotEmpty() }
    if (exactWord) return true

    // PHP controller methods are commonly camelCase before route manifests collapse them into an
    // operation ID such as `route-account-getbalancehistory`. Preserve a distinct nested resource
    // when its path name (`balance-history`) is still proven by that compact method token. Without
    // this check, the controller tag wins and creates an Account -> Account navigation loop.
    val compactOperationId = operationId.filter(Char::isLetterOrDigit)
    return resourceVariants
        .map { variant -> variant.filter(Char::isLetterOrDigit) }
        .filter { compact -> compact.length >= 5 }
        .any(compactOperationId::contains)
}

private fun String.semanticTagResourceId(): String? {
    val words = stableId().split('-').mapNotNull { word ->
        when {
            word.matches(Regex("api[0-9]*")) -> null
            word.matches(Regex("v[0-9]+")) -> null
            word.endsWith("ocs") && word.length > 3 -> word.removeSuffix("ocs")
            else -> word
        }
    }
    return words.joinToString("-").takeIf(String::isNotBlank)
}

private fun String.semanticPathResourceId(): String? {
    val literalSegments = split('/')
        .filter { it.isNotBlank() && !(it.startsWith('{') && it.endsWith('}')) }
        .map(String::stableId)
    if (literalSegments.lastOrNull() in ROOT_RESOURCE_WORDS) return null
    return literalSegments.asReversed().asSequence()
        .filter { it !in NON_RESOURCE_PATH_SEGMENTS && !it.matches(Regex("[0-9]+")) }
        .map { segment ->
            segment.split('-')
                .filter { it !in ALTERNATE_SURFACE_WORDS }
                .joinToString("-")
        }
        .firstOrNull(String::isNotBlank)
}

private fun String.semanticBaseVariants(): Set<String> {
    val base = stableId().split('-')
        .filter { it !in SURFACE_SCOPE_WORDS }
        .joinToString("-")
    return base.resourceNameVariants()
}

private val ROOT_RESOURCE_WORDS = setOf("default", "general", "index", "init", "root")

private val NON_RESOURCE_PATH_SEGMENTS = ROOT_RESOURCE_WORDS + setOf(
    "display-mode",
    "scheme",
    "simple",
    "transfer",
)

private val NON_RESOURCE_OPERATION_WORDS = setOf(
    "add",
    "api",
    "create",
    "delete",
    "get",
    "index",
    "list",
    "read",
    "remove",
    "show",
    "update",
)

private fun DynamicAction.navigationParent(
    childResourceId: String,
    resources: List<DynamicResource>,
): DynamicResource? = binding.path.hierarchyParent(childResourceId, resources)
    ?: (binding.pathParameters + binding.queryParameters)
        .asSequence()
        .filter(HttpParameter::required)
        .map { parameter -> parameter.name.stableId() }
        .mapNotNull { stem ->
            val variants = stem.semanticBaseVariants()
            resources.firstOrNull { resource ->
                resource.collection &&
                    resource.id.semanticBaseVariants().intersect(variants).isNotEmpty() &&
                    resource.id.semanticBaseVariants().intersect(childResourceId.semanticBaseVariants()).isEmpty()
            }
        }
        .firstOrNull()
    ?: (binding.pathParameters + binding.queryParameters)
        .asSequence()
        .filter(HttpParameter::required)
        .mapNotNull { parameter -> parameter.name.identityResourceStem() }
        .mapNotNull { stem ->
            val variants = stem.semanticBaseVariants()
            resources.firstOrNull { resource ->
                resource.id.semanticBaseVariants().intersect(variants).isNotEmpty() &&
                    resource.id.semanticBaseVariants().intersect(childResourceId.semanticBaseVariants()).isEmpty()
            }
        }
        .firstOrNull()

private fun String.identityResourceStem(): String? = takeIf {
    length > 2 && endsWith("Id", ignoreCase = true)
}?.dropLast(2)?.takeIf(String::isNotBlank)

private fun String.isIdentityParameterName(): Boolean =
    equals("id", ignoreCase = true) || identityResourceStem() != null

private fun String.hierarchyParent(
    childResourceId: String,
    resources: List<DynamicResource>,
): DynamicResource? {
    val segments = split('/').filter(String::isNotBlank)
    val childIndex = segments.indexOfLast { segment ->
        !segment.startsWith('{') &&
            segment.stableId().semanticBaseVariants()
                .intersect(childResourceId.semanticBaseVariants()).isNotEmpty()
    }
    if (childIndex <= 1) return null
    val parameterIndex = (childIndex - 1 downTo 0).firstOrNull { index ->
        segments[index].startsWith('{') && segments[index].endsWith('}')
    } ?: return null
    val parentSegment = segments.take(parameterIndex).asReversed().firstOrNull { segment ->
        !segment.startsWith('{') && segment.stableId() !in NON_RESOURCE_PATH_SEGMENTS
    } ?: return null
    val normalizedParent = parentSegment.stableId()
    val parentVariants = normalizedParent.semanticBaseVariants()
    return resources
        .asSequence()
        .filter { resource ->
            resource.id.semanticBaseVariants().intersect(parentVariants).isNotEmpty()
        }
        .sortedWith(
            compareByDescending<DynamicResource> { resource -> resource.id == normalizedParent }
                .thenByDescending(DynamicResource::collection)
                .thenBy(DynamicResource::id),
        )
        .firstOrNull()
}

private fun layoutPreference(
    resourceId: String,
    path: String,
    operationId: String,
    kind: LayoutKind,
    pathParameters: List<HttpParameter>,
): Int? {
    val literalSegments = path.split('/')
        .filter { it.isNotBlank() && !(it.startsWith('{') && it.endsWith('}')) }
        .map(String::stableId)
    val words = (literalSegments + operationId.stableId())
        .flatMap { it.split('-') }
        .filter(String::isNotBlank)
        .toSet()
    if (words.any { it in NON_SURFACE_OPERATION_WORDS }) return null

    val resourceNames = resourceId.resourceNameVariants()
    val matchingIndex = literalSegments.indexOfLast { it in resourceNames }
    var preference = when {
        literalSegments.lastOrNull() in resourceNames -> 100
        matchingIndex >= 0 -> 60
        words.any { it in resourceNames } -> 20
        else -> 0
    }
    if (matchingIndex >= 0) {
        preference -= (literalSegments.lastIndex - matchingIndex) * 12
    }
    if (words.any { it in ALTERNATE_SURFACE_WORDS }) preference -= 40
    if ("local" in words) preference += 10
    if (kind == LayoutKind.list && ("list" in words || "all" in words)) preference += 5
    if (kind == LayoutKind.detail && path.substringAfterLast('/').let { it.startsWith('{') && it.endsWith('}') }) {
        preference += 5
    }
    preference -= pathParameters.count { parameter ->
        ((parameter.schema as? JsonObject)?.get("enum") as? JsonArray)?.size?.let { it > 1 } == true
    } * 30
    preference -= if (kind == LayoutKind.list) {
        // A collection with no required path context is a usable root. Prefer it over an otherwise
        // equivalent versioned/parent-scoped operation, which remains available for navigation.
        pathParameters.size * 8
    } else {
        (pathParameters.size - 1).coerceAtLeast(0) * 3
    }
    return preference
}

private fun String.resourceNameVariants(): Set<String> {
    val normalized = stableId()
    return buildSet {
        add(normalized)
        when {
            normalized.endsWith("ies") && normalized.length > 3 -> add(normalized.dropLast(3) + "y")
            normalized.endsWith("ches") || normalized.endsWith("shes") -> add(normalized.dropLast(2))
            normalized.endsWith("ses") || normalized.endsWith("xes") || normalized.endsWith("zes") -> {
                // Both forms are linguistically valid: boxes -> box, while houses -> house.
                // Retain both bounded candidates and let the declared resource set disambiguate.
                add(normalized.dropLast(2))
                add(normalized.dropLast(1))
            }
            normalized.endsWith('s') && !normalized.endsWith("ss") -> add(normalized.dropLast(1))
        }
    }
}

private fun String.isScopedSingletonSurface(): Boolean =
    semanticBaseVariants().any(SCOPED_SINGLETON_SURFACES::contains)

private val SCOPED_SINGLETON_SURFACES = setOf(
    "capability",
    "config",
    "configuration",
    "preference",
    "prefs",
    "profile",
    "setting",
    "status",
)

private fun String.surfaceFamily(): String = stableId()
    .split('-')
    .filter { it !in SURFACE_SCOPE_WORDS }
    .joinToString("-")
    .ifBlank { stableId() }

private fun isAlternateSurface(resourceId: String, path: String, operationId: String): Boolean =
    (resourceId.stableId().split('-') + path.stableId().split('-') + operationId.stableId().split('-'))
        .any { it in ALTERNATE_SURFACE_WORDS }

private val NON_SURFACE_OPERATION_WORDS = setOf(
    "export",
    "health",
    "healthcheck",
    "import",
    "ping",
    "probe",
)

private val ALTERNATE_SURFACE_WORDS = setOf(
    "federated",
    "federation",
    "public",
    "remote",
)

private val SURFACE_SCOPE_WORDS = ALTERNATE_SURFACE_WORDS + setOf("api", "local", "shared")

private fun actionEffect(
    method: HttpMethod,
    path: String,
    operationId: String,
    label: String,
    collection: Boolean,
): ActionEffect {
    if (method == HttpMethod.GET) {
        return when {
            path.endsWithIdentityPlaceholder() -> ActionEffect.read
            collection -> ActionEffect.list
            operationId.looksLikeCollectionReadOperation() -> ActionEffect.list
            path.contains('{') -> ActionEffect.read
            else -> ActionEffect.read
        }
    }

    val words = actionSemanticWords(path, operationId, label)
    return when {
        "empty" in words -> ActionEffect.empty
        words.any { it in PERMANENT_DELETE_WORDS } -> ActionEffect.permanentDelete
        words.any { it in REORDER_WORDS } -> ActionEffect.reorder
        "batch" in words -> ActionEffect.batch
        "restore" in words -> ActionEffect.restore
        "unarchive" in words -> ActionEffect.unarchive
        "archive" in words -> ActionEffect.archive
        words.any { it in COMPLETION_TRANSITION_WORDS } ||
            ("toggle" in words && words.any { it in COMPLETION_STATE_WORDS }) -> ActionEffect.toggle
        "move" in words -> ActionEffect.move
        "copy" in words || "duplicate" in words -> ActionEffect.copy
        "upload" in words || "import" in words -> ActionEffect.upload
        "assign" in words || "replace" in words -> ActionEffect.assign
        "leave" in words -> ActionEffect.leave
        "clear" in words -> ActionEffect.clear
        method == HttpMethod.DELETE -> ActionEffect.delete
        words.any { it in CREATE_WORDS } -> ActionEffect.create
        method == HttpMethod.PUT || method == HttpMethod.PATCH -> ActionEffect.update
        else -> ActionEffect.execute
    }
}

private fun actionRisk(
    method: HttpMethod,
    effect: ActionEffect,
    path: String,
    operationId: String,
    label: String,
): ActionRisk {
    if (method == HttpMethod.GET) return ActionRisk.readOnly
    if (
        effect in setOf(
            ActionEffect.delete,
            ActionEffect.permanentDelete,
            ActionEffect.empty,
            ActionEffect.leave,
            ActionEffect.clear,
        )
    ) {
        return ActionRisk.destructive
    }
    val words = actionSemanticWords(path, operationId, label)
    return if (words.any { it in DESTRUCTIVE_ACTION_WORDS }) {
        ActionRisk.destructive
    } else {
        ActionRisk.mutating
    }
}

private fun ActionEffect.toActionIntent(): ActionIntent = when (this) {
    ActionEffect.list -> ActionIntent.list
    ActionEffect.read -> ActionIntent.read
    ActionEffect.create -> ActionIntent.create
    ActionEffect.update,
    ActionEffect.assign,
    -> ActionIntent.update
    ActionEffect.delete,
    ActionEffect.permanentDelete,
    ActionEffect.empty,
    ActionEffect.clear,
    -> ActionIntent.delete
    ActionEffect.unspecified,
    ActionEffect.toggle,
    ActionEffect.archive,
    ActionEffect.unarchive,
    ActionEffect.restore,
    ActionEffect.move,
    ActionEffect.copy,
    ActionEffect.reorder,
    ActionEffect.batch,
    ActionEffect.upload,
    ActionEffect.leave,
    ActionEffect.execute,
    -> ActionIntent.execute
}

private fun actionSemanticWords(
    path: String,
    operationId: String,
    label: String,
): Set<String> = sequenceOf(path, operationId.humanize(), label)
    .flatMap { value -> value.stableId().split('-').asSequence() }
    .filter(String::isNotBlank)
    .toSet()

private val CREATE_WORDS = setOf("add", "create", "new")
private val TOGGLE_WORDS = setOf("toggle", "complete", "reopen")
private val COMPLETION_TRANSITION_WORDS = setOf("complete", "reopen")
private val COMPLETION_STATE_WORDS = setOf(
    "checked",
    "complete",
    "completed",
    "completion",
    "done",
)
private val REORDER_WORDS = setOf("reorder", "reposition", "sort")
private val PERMANENT_DELETE_WORDS = setOf("permanent", "permanently", "purge")
private val DESTRUCTIVE_ACTION_WORDS = setOf(
    "clear",
    "delete",
    "destroy",
    "empty",
    "permanent",
    "permanently",
    "purge",
    "remove",
)

/**
 * A terminal identity placeholder proves an item read even when a plural operation name such as
 * `recipeDetails` resembles a collection controller. Non-identity filters such as
 * `/category/{category}` remain eligible for collection classification from their response.
 */
private fun String.endsWithIdentityPlaceholder(): Boolean {
    val segment = trimEnd('/').substringAfterLast('/')
    if (!segment.startsWith('{') || !segment.endsWith('}') || segment.length <= 2) return false
    val name = segment.substring(1, segment.lastIndex)
    return name.lowercase() in setOf("id", "uuid", "token") ||
        name.endsWith("Id") ||
        name.endsWith("ID") ||
        name.endsWith("_id") ||
        name.endsWith("-id")
}

/**
 * Recognizes conventional collection controller names even when a sparse static contract has no
 * response schema. Parent-scoped routes such as `getItems(parentId)` otherwise look like detail
 * reads solely because their path contains a parent placeholder.
 */
private fun String.looksLikeCollectionReadOperation(): Boolean {
    val compact = lowercase().filter(Char::isLetterOrDigit)
    if ("list" in compact || "findall" in compact || "getall" in compact) return true
    val target = compact.substringAfterLast("get", missingDelimiterValue = compact)
    if (target.endsWith("history") || target.endsWith("log") || target.endsWith("feed")) return true
    if (!target.endsWith('s') || target.endsWith("ss")) return false
    return COLLECTION_SINGLETON_SUFFIXES.none(target::endsWith)
}

private val COLLECTION_SINGLETON_SUFFIXES = setOf(
    "capabilities", "preferences", "settings", "status",
)

private fun authKind(definition: JsonObject?): AuthKind = when {
    definition?.string("type") == "http" && definition.string("scheme") == "basic" -> AuthKind.basic
    definition?.string("type") == "http" && definition.string("scheme") == "bearer" -> AuthKind.bearer
    definition?.string("type") == "apiKey" && definition.string("in") == "cookie" -> AuthKind.cookie
    definition?.string("type") == "apiKey" -> AuthKind.apiKey
    definition?.string("type") == "oauth2" -> AuthKind.oAuth2
    definition?.string("type") == "openIdConnect" -> AuthKind.openIdConnect
    else -> AuthKind.nextcloudSession
}

private fun AuthKind.transportPreference(): Int = when (this) {
    AuthKind.nextcloudSession -> 0
    AuthKind.basic -> 1
    AuthKind.bearer -> 2
    AuthKind.cookie -> 3
    AuthKind.apiKey -> 4
    AuthKind.oAuth2 -> 5
    AuthKind.openIdConnect -> 6
}

private fun String.isSafeDocumentedPathSegment(): Boolean =
    isNotBlank() && this != "." && this != ".." && all { character ->
        character.isLetterOrDigit() && character.code < 128 || character in "-._~"
    }

private fun ocsMetadata(path: String, query: List<HttpParameter>): OcsMetadata? =
    if (path.contains("/ocs/") || path.contains("/ocs/v1.php/") || path.contains("/ocs/v2.php/")) {
        OcsMetadata(
            apiRequestHeader = true,
            responseDataPointer = "/ocs/data",
            responseMetaPointer = "/ocs/meta",
            formatQueryParameter = query.firstOrNull { it.name == "format" }?.name,
        )
    } else {
        null
    }

private fun fieldKind(id: String, schema: JsonObject): FieldKind {
    if (schema["enum"] is JsonArray) return FieldKind.enumeration
    val value = if (schema.string("type") == "array") schema["items"] as? JsonObject ?: schema else schema
    val lowerId = id.lowercase()
    return when {
        value.string("type") == "string" && value.string("format") == "binary" -> FieldKind.file
        value.string("type") == "string" && value.string("format") == "date" -> FieldKind.date
        value.string("type") == "string" && value.string("format") == "date-time" -> FieldKind.dateTime
        value.string("type") == "integer" -> FieldKind.integer
        value.string("type") == "number" -> FieldKind.decimal
        value.string("type") == "boolean" -> FieldKind.boolean
        value.string("type") == "object" || value.string("type") == "array" -> FieldKind.objectValue
        value.string("type") == "string" && lowerId.filter(Char::isLetterOrDigit) in
            setOf("date", "duedate", "startdate", "enddate") -> FieldKind.date
        value.string("type") == "string" && lowerId.filter(Char::isLetterOrDigit) in
            setOf("datetime", "createdat", "updatedat", "modifiedat") -> FieldKind.dateTime
        lowerId.contains("currency") -> FieldKind.currency
        lowerId.contains("image") || lowerId.contains("preview") || lowerId.contains("thumbnail") -> FieldKind.image
        lowerId.contains("file") || lowerId.contains("mime") -> FieldKind.file
        lowerId == "user" || lowerId.endsWith("userid") || lowerId.endsWith("user_id") -> FieldKind.userReference
        lowerId.contains("description") || lowerId.contains("message") || lowerId.contains("content") -> FieldKind.longText
        value.string("type") == "string" -> FieldKind.string
        else -> FieldKind.unknown
    }
}

private fun JsonObject.dynamicEditorFormat(): String? {
    val format = string("format")
    if (format != "binary") return format
    return string("contentMediaType")
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isSafeDynamicUploadMimeFilter)
        ?: format
}

private fun String.isSafeDynamicUploadMimeFilter(): Boolean {
    if (length !in 3..160 || count { it == '/' } != 1) return false
    val type = substringBefore('/')
    val subtype = substringAfter('/')
    if (type.isEmpty() || subtype.isEmpty() || type == "*") return false
    fun String.safeToken(): Boolean = all { character ->
        character.isAsciiLetter() || character.isDigit() || character in "!#$&+-.^_"
    }
    return type.safeToken() && (subtype == "*" || subtype.safeToken())
}

private fun DynamicField.layoutRole(index: Int): LayoutFieldRole = when {
    kind == FieldKind.image -> LayoutFieldRole.image
    id.lowercase() in setOf("id", "uuid", "token") -> LayoutFieldRole.identity
    id.lowercase() in setOf("title", "name", "displayname", "subject") -> LayoutFieldRole.title
    index == 0 -> LayoutFieldRole.title
    index == 1 -> LayoutFieldRole.subtitle
    kind == FieldKind.longText -> LayoutFieldRole.body
    else -> LayoutFieldRole.metadata
}

private fun uniqueId(existing: Set<String>, requested: String): String {
    if (requested !in existing) return requested
    var suffix = 2
    while ("$requested-$suffix" in existing) suffix += 1
    return "$requested-$suffix"
}

private fun String.stableId(): String = buildString {
    var separator = false
    this@stableId.forEach { character ->
        if (character.isLetterOrDigit() && character.code < 128) {
            append(character.lowercaseChar())
            separator = false
        } else if (!separator && isNotEmpty()) {
            append('-')
            separator = true
        }
    }
}.trim('-')

private fun String.humanize(): String = buildString(length + 4) {
    this@humanize.forEachIndexed { index, character ->
        val previous = this@humanize.getOrNull(index - 1)
        val next = this@humanize.getOrNull(index + 1)
        when {
            character == '-' || character == '_' || character == '.' || character.isWhitespace() -> {
                if (isNotEmpty() && last() != ' ') append(' ')
            }
            character.isUpperCase() && (
                previous?.isLowerCase() == true ||
                    previous?.isDigit() == true ||
                    (previous?.isUpperCase() == true && next?.isLowerCase() == true)
                ) -> {
                if (isNotEmpty() && last() != ' ') append(' ')
                append(character)
            }
            else -> append(character)
        }
    }
}
    .split(' ')
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.stringArray(key: String): List<String>? = (get(key) as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.nativeEnumLabels(enumValues: List<String>?): Map<String, String>? =
    (get(ENUM_LABELS_EXTENSION) as? JsonObject)?.entries
        ?.mapNotNull { (wireValue, labelElement) ->
            (labelElement as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf { label -> label.isNotBlank() && label.length <= MAX_DYNAMIC_ENUM_LABEL_LENGTH }
                ?.let { label -> wireValue to label }
        }
        ?.toMap()
        ?.takeIf { labels -> enumValues != null && labels.keys == enumValues.toSet() }
