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
        val state = KotlinCompilerState(input, document, source)
        if (sanitized.ignoredCount > 0) {
            state.warnings += DynamicWarning(
                code = "opaque-external-schema-reference",
                message = "Ignored ${sanitized.ignoredCount} external OpenAPI schema references; endpoints remain available without inferred fields.",
            )
        }

        paths.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEach { (openApiPath, itemElement) ->
            require(openApiPath.startsWith('/') && !openApiPath.startsWith("//")) {
                "Invalid OpenAPI path: $openApiPath"
            }
            val path = combinePaths(serverBase, openApiPath)
            require(path.isApproved(input.endpointPolicy)) { "Unapproved OpenAPI path: $path" }
            val pathItem = itemElement as? JsonObject ?: return@forEach
            val inheritedParameters = pathItem["parameters"] as? JsonArray
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
                state.addOperation(
                    path = path,
                    method = method,
                    operation = operation,
                    operationId = advertisedOperationId ?: "get-${path.stableId()}",
                    inheritedParameters = inheritedParameters,
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

private class KotlinCompilerState(
    private val input: DynamicDiscoveryInput,
    private val document: JsonObject,
    private val source: Provenance,
) {
    private val resources = linkedMapOf<String, KotlinResourceBuilder>()
    private val actions = linkedMapOf<String, DynamicAction>()
    private val layoutSeeds = linkedMapOf<String, KotlinLayoutSeed>()
    private val forms = linkedMapOf<String, DynamicForm>()
    private val permissions = linkedMapOf<String, PermissionSpec>()
    private val operationActionIds = linkedMapOf<String, String>()
    private val fallbackOperationIds = linkedMapOf<String, List<String>>()
    val warnings = mutableListOf<DynamicWarning>()

    fun addOperation(
        path: String,
        method: HttpMethod,
        operation: JsonObject,
        operationId: String,
        inheritedParameters: JsonArray?,
    ) {
        val actionId = uniqueId(actions.keys, operationId.stableId())
        operationActionIds.putIfAbsent(operationId, actionId)
        val fallbackForOperationId = operation.string(READ_FALLBACK_FOR_OPERATION_EXTENSION)
        val readFallbackOperationIds = operation.stringArray(READ_FALLBACK_OPERATION_IDS_EXTENSION).orEmpty()
        if (readFallbackOperationIds.isNotEmpty()) {
            fallbackOperationIds[actionId] = readFallbackOperationIds
        }
        val filteredCollectionResourceId =
            semanticFilteredCollectionResourceId(operation, path, operationId, method)
        val resourceId = resourceId(
            operation = operation,
            path = path,
            operationId = operationId,
            method = method,
            filteredCollectionResourceId = filteredCollectionResourceId,
        )
        val response = responseSchema(operation)
        val binaryRead = method == HttpMethod.GET && operation.hasSuccessfulBinaryResponse()
        val (itemSchema, responseCollection) = responseItemSchema(response)
        val collection = responseCollection || filteredCollectionResourceId != null
        val resource = resources.getOrPut(resourceId) { KotlinResourceBuilder(resourceId) }
        resource.collection = resource.collection || collection
        itemSchema?.let { resource.mergeFields(fieldsFromSchema(it)) }

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
        )
        val label = operation.string("summary") ?: operationId.humanize()
        val body = semanticActionBody(
            method = method,
            path = boundPath,
            operationId = operationId,
            label = label,
            declared = body(operation),
        )
        val auth = auth(operation)
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
        val action = DynamicAction(
            id = actionId,
            label = label,
            resourceId = resourceId,
            intent = intent(method, boundPath, operationId, collection),
            risk = when (method) {
                HttpMethod.GET -> ActionRisk.readOnly
                HttpMethod.DELETE -> ActionRisk.destructive
                else -> ActionRisk.mutating
            },
            requiresConfirmation = method != HttpMethod.GET,
            binding = DynamicHttpBinding(
                method = method,
                path = boundPath,
                pathParameters = pathParameters,
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
            permissionIds = permissionIds,
            confidence = Confidence.high,
            provenance = listOf(source),
        )
        actions[actionId] = action

        if (method == HttpMethod.GET) {
            if (fallbackForOperationId == null && !binaryRead) {
                val kind = if (collection) LayoutKind.list else LayoutKind.detail
                val layoutId = if (kind == LayoutKind.list && filteredCollectionResourceId != null) {
                    "$resourceId.${kind.name}.${operationId.stableId()}"
                } else {
                    "$resourceId.${kind.name}"
                }
                layoutPreference(resourceId, boundPath, operationId, kind, pathParameters)?.let { preference ->
                    val candidate = KotlinLayoutSeed(
                        id = layoutId,
                        title = resourceId.humanize(),
                        resourceId = resourceId,
                        kind = kind,
                        sourceActionId = actionId,
                        preference = preference,
                        semanticFamily = resourceId.surfaceFamily(),
                        alternate = isAlternateSurface(resourceId, boundPath, operationId),
                    )
                    val current = layoutSeeds[layoutId]
                    if (current == null || candidate.isPreferredTo(current)) layoutSeeds[layoutId] = candidate
                }
            }
        } else {
            val ocsFormatParameter = action.binding.ocs?.formatQueryParameter
            val bodyFields = body?.schema?.let(::formFields).orEmpty()
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
        val completedResources = resources.values.map(KotlinResourceBuilder::finish)
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
                val field = resolveLocal(element) as? JsonObject ?: return@mapNotNull null
                DynamicField(
                    id = id,
                    label = field.string("title") ?: id.humanize(),
                    kind = fieldKind(id, field),
                    required = id in required,
                    readOnly = field.boolean("readOnly") ?: false,
                    nullable = field.boolean("nullable") ?: false,
                    multiple = field.string("type") == "array",
                    format = field.string("format"),
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
        val request = operation["requestBody"]?.let(::resolveLocal) as? JsonObject ?: return null
        val content = request.objectValue("content") ?: return null
        val contentType = listOf(
            "application/json",
            "application/x-www-form-urlencoded",
            "multipart/form-data",
        ).firstOrNull(content::containsKey) ?: content.keys.firstOrNull() ?: return null
        val media = content[contentType] as? JsonObject ?: return null
        val schema = media["schema"]?.let(::resolveLocal)?.withDynamicFormFormats() ?: return null
        return HttpBody(
            contentType = contentType,
            required = request.boolean("required") ?: false,
            schema = schema,
        )
    }

    private fun JsonElement.withDynamicFormFormats(): JsonElement {
        val schema = this as? JsonObject ?: return this
        val properties = schema["properties"] as? JsonObject ?: return schema
        val formattedProperties = JsonObject(
            properties.mapValues { (_, property) ->
                val propertySchema = property as? JsonObject ?: return@mapValues property
                val type = propertySchema.string("type")
                val itemType = (propertySchema["items"] as? JsonObject)?.string("type")
                if (type == "array" && itemType == "string") {
                    JsonObject(propertySchema + ("format" to JsonPrimitive(DYNAMIC_STRING_ARRAY_FORMAT)))
                } else {
                    property
                }
            },
        )
        return JsonObject(schema + ("properties" to formattedProperties))
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
    ): Pair<String, List<HttpParameter>> {
        if (!collection || parameters.size != 1) return path to parameters
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

    private fun formFields(schemaElement: JsonElement): List<FormField>? {
        val schema = resolveLocal(schemaElement) as? JsonObject ?: return null
        val required = (schema["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .toSet()
        val properties = schema.objectValue("properties") ?: return null
        return properties.entries.sortedBy(Map.Entry<String, JsonElement>::key).mapNotNull { (id, element) ->
            val field = resolveLocal(element) as? JsonObject ?: return@mapNotNull null
            if (field.boolean("readOnly") == true) return@mapNotNull null
            FormField(
                fieldId = id,
                label = field.string("title") ?: id.humanize(),
                kind = fieldKind(id, field),
                required = id in required,
                format = field.string("format"),
                enumValues = field.stringArray("enum"),
            )
        }
    }

    private fun formField(parameter: HttpParameter): FormField? {
        val schema = resolveLocal(parameter.schema) as? JsonObject ?: return null
        return FormField(
            fieldId = parameter.name,
            label = schema.string("title") ?: parameter.name.humanize(),
            kind = fieldKind(parameter.name, schema),
            required = parameter.required,
            format = schema.string("format"),
            enumValues = schema.stringArray("enum"),
        )
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
        discovered.forEach { fields.putIfAbsent(it.id, it) }
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

/**
 * Binary reads are valid API capabilities, but they are not record/detail layouts. Their bytes are
 * consumed by native artwork/media loaders instead of being sent through the JSON record parser.
 */
private fun JsonObject.hasSuccessfulBinaryResponse(): Boolean {
    val responseContent = objectValue("responses")
        ?.entries
        ?.filter { (status, _) -> status.startsWith('2') }
        ?.mapNotNull { (_, response) -> (response as? JsonObject)?.objectValue("content") }
        .orEmpty()
    if (responseContent.isEmpty()) return false
    val contentTypes = responseContent.flatMap { it.keys }.map { it.substringBefore(';').lowercase() }
    return contentTypes.isNotEmpty() &&
        contentTypes.none { type -> type.contains("json") } &&
        contentTypes.all { type ->
            type.startsWith("image/") ||
                type.startsWith("audio/") ||
                type.startsWith("video/") ||
                type == "application/octet-stream"
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
    val taxonomyFilter = path.stableId().let { stablePath ->
        ("category" in stablePath || "tag" in stablePath || "keyword" in stablePath) &&
            path.pathPlaceholders().isNotEmpty()
    }
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

private fun semanticActionBody(
    method: HttpMethod,
    path: String,
    operationId: String,
    label: String,
    declared: HttpBody?,
): HttpBody? {
    if (method == HttpMethod.GET || method == HttpMethod.DELETE) return declared
    val declaredProperties = (declared?.schema as? JsonObject)
        ?.get("properties") as? JsonObject
    if (!declaredProperties.isNullOrEmpty()) return declared

    val semantics = "$operationId $label $path".lowercase()
    val recipeAction = "recipe" in semantics
    if (!recipeAction) return declared

    val properties = when {
        "import" in semantics && ("url" in semantics || "website" in semantics) -> JsonObject(
            mapOf(
                "url" to semanticStringSchema(
                    title = "Recipe URL",
                    description = "Link to a webpage containing a recipe",
                    format = "uri",
                ),
            ),
        )
        method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH) &&
            listOf("create", "new", "update", "edit").any(semantics::contains) -> recipeEditProperties()
        else -> return declared
    }
    val required = if ("url" in properties) listOf("url") else listOf("name")
    return HttpBody(
        contentType = declared?.contentType ?: "application/json",
        required = declared?.required ?: true,
        schema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to properties,
                "required" to JsonArray(required.map(::JsonPrimitive)),
            ),
        ),
    )
}

private fun recipeEditProperties(): JsonObject = JsonObject(
    mapOf(
        "name" to semanticStringSchema("Recipe name"),
        "description" to semanticStringSchema("Description"),
        "recipeYield" to JsonObject(
            mapOf(
                "type" to JsonPrimitive("integer"),
                "title" to JsonPrimitive("Servings"),
                "minimum" to JsonPrimitive(1),
            ),
        ),
        "recipeCategory" to semanticStringSchema("Category"),
        "keywords" to semanticStringSchema("Tags", "Separate tags with commas"),
        "prepTime" to semanticStringSchema("Preparation time", "For example: PT20M"),
        "cookTime" to semanticStringSchema("Cooking time", "For example: PT45M"),
        "recipeIngredient" to semanticStringArraySchema("Ingredients"),
        "recipeInstructions" to semanticStringArraySchema("Instructions"),
        "tool" to semanticStringArraySchema("Tools"),
    ),
)

private fun semanticStringSchema(
    title: String,
    description: String? = null,
    format: String? = null,
): JsonObject = JsonObject(
    buildMap {
        put("type", JsonPrimitive("string"))
        put("title", JsonPrimitive(title))
        description?.let { put("description", JsonPrimitive(it)) }
        format?.let { put("format", JsonPrimitive(it)) }
    },
)

private fun semanticStringArraySchema(title: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("array"),
        "title" to JsonPrimitive(title),
        "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        "format" to JsonPrimitive(DYNAMIC_STRING_ARRAY_FORMAT),
    ),
)

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
    val parentVariants = parentSegment.semanticBaseVariants()
    return resources.firstOrNull { resource ->
        resource.id.semanticBaseVariants().intersect(parentVariants).isNotEmpty()
    }
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
    val singular = when {
        normalized.endsWith("ies") && normalized.length > 3 -> normalized.dropLast(3) + "y"
        normalized.endsWith("ches") || normalized.endsWith("shes") -> normalized.dropLast(2)
        normalized.endsWith("ses") || normalized.endsWith("xes") || normalized.endsWith("zes") -> normalized.dropLast(2)
        normalized.endsWith('s') && !normalized.endsWith("ss") -> normalized.dropLast(1)
        else -> normalized
    }
    return setOf(normalized, singular)
}

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

private fun intent(
    method: HttpMethod,
    path: String,
    operationId: String,
    collection: Boolean,
): ActionIntent = when {
    method == HttpMethod.GET && path.endsWithIdentityPlaceholder() -> ActionIntent.read
    method == HttpMethod.GET && collection -> ActionIntent.list
    method == HttpMethod.GET && operationId.looksLikeCollectionReadOperation() -> ActionIntent.list
    method == HttpMethod.GET && path.contains('{') -> ActionIntent.read
    method == HttpMethod.GET -> ActionIntent.read
    method == HttpMethod.POST -> ActionIntent.create
    method == HttpMethod.DELETE -> ActionIntent.delete
    else -> ActionIntent.update
}

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

private fun String.humanize(): String = replace('-', ' ').replace('_', ' ').replace('.', ' ')
    .split(' ')
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.stringArray(key: String): List<String>? = (get(key) as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull
}
