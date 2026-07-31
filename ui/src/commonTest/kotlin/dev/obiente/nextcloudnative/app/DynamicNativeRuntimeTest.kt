package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.AuthKind
import dev.obiente.nextcloudnative.nativeui.model.AuthRequirement
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicField
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicResource
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpBody
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.OcsMetadata
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.Provenance
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredScalarKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import dev.obiente.nextcloudnative.nativeui.runtime.actionBindingValues
import dev.obiente.nextcloudnative.nativeui.runtime.safeActionBindingValues
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicNativeRuntimeTest {
    @Test
    fun `nested collection read qualifies a generic parent identity from its declared response`() {
        val action = readAction().copy(
            resourceId = "categories",
            responseFieldIds = listOf("id", "houseId", "name"),
            binding = readAction().binding.copy(
                path = "/apps/example/api/houses/{id}/categories",
                pathParameters = listOf(
                    HttpParameter(
                        name = "id",
                        required = true,
                        schema = JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                        source = ParameterSource.runtimeContext,
                    ),
                ),
            ),
        )

        val context = dynamicReadBindingContext(
            action = action,
            values = mapOf("id" to "house-7"),
            runtimeContext = emptyMap(),
        )

        assertEquals(mapOf("houseId" to "house-7"), context)
        val category = NativeRecord(
            id = "category-11",
            values = mapOf(
                "id" to "category-11",
                "houseId" to "house-7",
                "name" to "Fruit",
            ),
            bindingContext = context,
        )
        assertEquals("category-11", category.actionBindingValues()["id"])
        assertEquals("house-7", category.actionBindingValues()["houseId"])
        assertNull(
            category.copy(
                values = category.values + ("houseId" to "different-house"),
            ).safeActionBindingValues(),
        )
    }

    @Test
    fun `generic identity is not requalified without exact nested response evidence`() {
        val base = readAction()
        val parameter = HttpParameter(
            name = "id",
            required = true,
            schema = JsonObject(mapOf("type" to JsonPrimitive("string"))),
            source = ParameterSource.runtimeContext,
        )
        val terminal = base.copy(
            responseFieldIds = listOf("id", "houseId"),
            binding = base.binding.copy(
                path = "/apps/example/api/categories/{id}",
                pathParameters = listOf(parameter),
            ),
        )
        val undeclaredParent = terminal.copy(
            responseFieldIds = listOf("id", "name"),
            binding = terminal.binding.copy(path = "/apps/example/api/houses/{id}/categories"),
        )
        val ambiguousParent = terminal.copy(
            responseFieldIds = listOf("id", "houseId", "housesId"),
            binding = terminal.binding.copy(path = "/apps/example/api/houses/{id}/categories"),
        )
        val unrelatedNestedResource = terminal.copy(
            resourceId = "items",
            binding = terminal.binding.copy(path = "/apps/example/api/houses/{id}/categories"),
        )

        listOf(terminal, undeclaredParent, ambiguousParent, unrelatedNestedResource).forEach { action ->
            assertEquals(
                mapOf("id" to "value-7"),
                dynamicReadBindingContext(
                    action = action,
                    values = mapOf("id" to "value-7"),
                    runtimeContext = emptyMap(),
                ),
            )
        }
    }

    @Test
    fun `last known contracts allow reads but fail closed for mutations`() {
        assertTrue(DynamicContractVersionStatus.LastKnownReadOnly.allows(readAction().risk))
        assertFalse(DynamicContractVersionStatus.LastKnownReadOnly.allows(createAction().risk))
        assertTrue(DynamicContractVersionStatus.VerifiedCurrent.allows(createAction().risk))
    }

    @Test
    fun `declared OCS mutation accepts explicit successful metadata`() {
        val result = response(
            """{"ocs":{"meta":{"status":"ok","statuscode":100,"message":"OK"},"data":{"id":7}}}""",
        ).toDynamicActionExecutionResult(createAction())

        assertEquals(
            "Create item completed.",
            assertIs<NativeActionExecutionResult.Success>(result).message,
        )
    }

    @Test
    fun `declared OCS mutation rejects HTTP 200 failure with sanitized message`() {
        val result = response(
            """{"ocs":{"meta":{"status":"failure","statuscode":997,"message":"Current user is not\nauthorized"},"data":[]}}""",
        ).toDynamicActionExecutionResult(createAction())
        val failure = assertIs<NativeActionExecutionResult.Failure>(result)

        assertEquals(
            "The server rejected Create item: Current user is not authorized.",
            failure.message,
        )
        assertEquals(NativeActionFailureOutcome.Rejected, failure.outcome)
    }

    @Test
    fun `declared OCS mutation rejects a non success status code`() {
        val result = response(
            """{"ocs":{"meta":{"status":"ok","statuscode":403,"message":"Permission denied"},"data":[]}}""",
        ).toDynamicActionExecutionResult(createAction())

        assertEquals(
            "The server rejected Create item: Permission denied.",
            assertIs<NativeActionExecutionResult.Failure>(result).message,
        )
    }

    @Test
    fun `declared OCS mutation fails closed on contradictory metadata`() {
        val result = response(
            """{"ocs":{"meta":{"status":"failure","statuscode":100,"message":"Contradictory result"},"data":[]}}""",
        ).toDynamicActionExecutionResult(createAction())

        assertIs<NativeActionExecutionResult.Failure>(result)
    }

    @Test
    fun `declared OCS mutation fails closed on malformed metadata without exposing its body`() {
        val secretBody = """{"ocs":{"meta":{"status":"ok","message":"token=must-not-leak"},"data":[]}}"""
        val result = response(secretBody).toDynamicActionExecutionResult(createAction())
        val failure = assertIs<NativeActionExecutionResult.Failure>(result)
        val message = failure.message

        assertEquals(
            "The server returned invalid OCS metadata for Create item.",
            message,
        )
        assertEquals(NativeActionFailureOutcome.Unknown, failure.outcome)
        assertFalse(message.contains("must-not-leak"))
    }

    @Test
    fun `mutation HTTP failures distinguish rejection from an unknown outcome`() {
        val rejected = response(
            body = """{"error":"conflict"}""",
            status = 409,
        ).toDynamicActionExecutionResult(createAction())
        val unavailable = response(
            body = """{"error":"unavailable"}""",
            status = 503,
        ).toDynamicActionExecutionResult(createAction())

        assertEquals(
            NativeActionFailureOutcome.Rejected,
            assertIs<NativeActionExecutionResult.Failure>(rejected).outcome,
        )
        assertEquals(
            NativeActionFailureOutcome.Unknown,
            assertIs<NativeActionExecutionResult.Failure>(unavailable).outcome,
        )
    }

    @Test
    fun `ordinary non OCS mutation preserves HTTP 2xx success behavior`() {
        val action = createAction().copy(
            binding = createAction().binding.copy(ocs = null),
        )
        val result = response("not an OCS envelope", contentType = "text/plain")
            .toDynamicActionExecutionResult(action)

        assertEquals(
            "Create item completed.",
            assertIs<NativeActionExecutionResult.Success>(result).message,
        )
    }

    @Test
    fun dynamicArtworkRequestsStayInsideTheActiveAppNamespace() {
        val request = dynamicAppAssetRequest(
            "music",
            "/apps/music/api/albums/42/cover?size=thumb",
        ) ?: error("Expected a safe artwork request")

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/apps/music/api/albums/42/cover", request.relativePath)
        assertEquals(mapOf("size" to "thumb"), request.queryParameters)
        val recipeImage = dynamicAppAssetRequest(
            "cookbook",
            "/index.php/apps/cookbook/recipe/123/image?size=full",
        ) ?: error("Expected a safe authenticated recipe image request")
        assertEquals("/index.php/apps/cookbook/recipe/123/image", recipeImage.relativePath)
        assertEquals(mapOf("size" to "full"), recipeImage.queryParameters)
        assertNull(dynamicAppAssetRequest("music", "/apps/mail/api/avatar"))
        assertNull(dynamicAppAssetRequest("music", "/apps/music/../files/secret"))
        assertNull(dynamicAppAssetRequest("music", "https://example.test/cover.jpg"))
        assertNull(dynamicAppAssetRequest("music", "/apps/music/cover?token=secret%20value"))
    }

    @Test
    fun navigationHrefProvidesCanonicalAppStoreIdWithoutHardcodedAliases() {
        assertEquals(
            "richdocuments",
            NextcloudAppEntry(
                id = "office",
                name = "Office",
                href = "/index.php/apps/richdocuments/",
            ).canonicalAppStoreId(),
        )
        assertEquals(
            "mail",
            NextcloudAppEntry(
                id = "mail",
                name = "Mail",
                href = "https://cloud.example.test/apps/mail/",
            ).canonicalAppStoreId(),
        )
    }

    @Test
    fun unsafeNavigationHrefCannotChangeCanonicalAppStoreId() {
        assertEquals(
            "office",
            NextcloudAppEntry("office", "Office", "/apps/../richdocuments").canonicalAppStoreId(),
        )
    }

    @Test
    fun discoversOpenApiThroughOfficialAuthenticatedViewerPathWithoutTrustingContentType() = runBlocking {
        val requests = mutableListOf<NextcloudApiRequest>()
        val discovery = discoverDynamicAppDescriptor(
            serverUrl = "https://cloud.example.test",
            app = NextcloudAppEntry("example", "Example", null),
            execute = { request ->
                requests += request
                when (request.relativePath) {
                    "/index.php/apps/ocs_api_viewer/apps" -> response(
                        """[{"id":"example","name":"Example","version":"1.0","specs":["example"]}]""",
                    )
                    "/index.php/apps/ocs_api_viewer/apps/example" -> response(
                        DISCOVERY_OPEN_API,
                        contentType = "text/html; charset=UTF-8",
                    )
                    else -> response("not found", status = 404, contentType = "text/plain")
                }
            },
        )

        assertEquals(DynamicDescriptorAcquisition.OcsApiViewer, discovery.acquisition)
        assertEquals("/index.php/apps/ocs_api_viewer/apps/example", discovery.sourcePath)
        assertEquals(
            listOf(
                "/index.php/apps/ocs_api_viewer/apps",
                "/index.php/apps/ocs_api_viewer/apps/example",
            ),
            requests.map(NextcloudApiRequest::relativePath),
        )
        assertTrue(requests.all { it.method == NextcloudApiMethod.GET })
        assertTrue(discovery.descriptor.actions.isNotEmpty())
        assertTrue(
            discovery.descriptor.actions.flatMap(DynamicAction::provenance)
                .any { it.kind == ProvenanceKind.advertisedOpenApi && it.source.contains("ocs_api_viewer") },
        )
    }

    @Test
    fun missingViewerFallsBackToStaticAssetWithClearDiagnostic() = runBlocking {
        val requests = mutableListOf<String>()
        val discovery = discoverDynamicAppDescriptor(
            serverUrl = "https://cloud.example.test",
            app = NextcloudAppEntry("example", "Example", null),
            execute = { request ->
                requests += request.relativePath
                when (request.relativePath) {
                    "/index.php/apps/ocs_api_viewer/apps" -> response("Not found", 404, "text/html")
                    "/apps/example/openapi.json" -> response(DISCOVERY_OPEN_API)
                    else -> response("Not found", 404, "text/html")
                }
            },
        )

        assertEquals(DynamicDescriptorAcquisition.StaticAppAsset, discovery.acquisition)
        assertEquals("/apps/example/openapi.json", discovery.sourcePath)
        assertTrue(discovery.diagnostics.any { it.contains("not installed or enabled") })
        assertEquals("/index.php/apps/ocs_api_viewer/apps", requests.first())
    }

    @Test
    fun missingViewerAndSpecsReturnsHonestMetadataFallbackDiagnostics() = runBlocking {
        val discovery = discoverDynamicAppDescriptor(
            serverUrl = "https://cloud.example.test",
            app = NextcloudAppEntry("example", "Example", null),
            execute = { request ->
                if (request.relativePath == "/index.php/apps/ocs_api_viewer/apps") {
                    response("Not found", 404, "text/html")
                } else {
                    response("Not found", 404, "text/html")
                }
            },
        )

        assertEquals(DynamicDescriptorAcquisition.MetadataFallback, discovery.acquisition)
        assertNull(discovery.sourcePath)
        assertTrue(discovery.diagnostics.any { it.contains("not installed or enabled") })
        assertTrue(discovery.diagnostics.any { it.contains("No valid static OpenAPI") })
        assertTrue(discovery.descriptor.actions.isEmpty())
    }

    @Test
    fun acquiredContractPreservesTypedMergedReadFallbackKind() {
        val acquired = AcquiredOpenApiContract(
            appId = "example",
            appVersion = "1.2.3",
            contractVersion = "1.2.3",
            specFile = "openapi.json",
            document = "{}",
            packageUrl = "https://apps.nextcloud.com/packages/example",
            sourceUrl = "https://apps.nextcloud.com/packages/example#openapi.json",
            sourceKind = AcquiredOpenApiContractSourceKind.SignedAppPackage,
            contractKind = AcquiredContractKind.OpenApiWithVerifiedReadRoutes,
        )

        assertEquals(AcquiredContractKind.OpenApiWithVerifiedReadRoutes, acquired.contractKind)
    }

    @Test
    fun bindsOnlyDeclaredInputsAndUsesRequestSchemaTypes() {
        val action = createAction()
        val request = buildDynamicApiRequest(
            descriptor = descriptor(action),
            action = action,
            values = mapOf(
                "projectId" to "project 7",
                "limit" to "25",
                "title" to "Shared list",
                "count" to "4",
                "enabled" to "true",
                "undeclared" to "must-not-leak",
            ),
            runtimeContext = mapOf("owner" to "alice"),
        )

        assertEquals(NextcloudApiMethod.POST, request.method)
        assertEquals("/ocs/v2.php/apps/example/api/projects/project%207/items", request.relativePath)
        assertEquals(mapOf("limit" to "25", "owner" to "alice", "format" to "json"), request.queryParameters)
        assertEquals("application/json", request.contentType)
        assertTrue(request.ocsApiRequest)
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject
        assertEquals(setOf("title", "count", "enabled"), body.keys)
        assertEquals(JsonPrimitive("Shared list"), body["title"])
        assertEquals(JsonPrimitive(4), body["count"])
        assertEquals(JsonPrimitive(true), body["enabled"])
        assertFalse("undeclared" in body)
    }

    @Test
    fun `pantry checklist create binds house path separately from its JSON body`() {
        val action = DynamicAction(
            id = "checklist-create-list",
            label = "Create a checklist in a house",
            resourceId = "lists",
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            binding = DynamicHttpBinding(
                method = HttpMethod.POST,
                path = "/ocs/v2.php/apps/pantry/api/houses/{houseId}/lists",
                pathParameters = listOf(
                    HttpParameter(
                        name = "houseId",
                        required = true,
                        schema = json.parseToJsonElement("""{"type":"integer","format":"int64"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
                body = HttpBody(
                    contentType = "application/json",
                    required = true,
                    schema = json.parseToJsonElement(
                        """
                        {
                          "type": "object",
                          "required": ["name"],
                          "properties": {
                            "name": {"type": "string"},
                            "description": {"type": "string", "nullable": true},
                            "icon": {"type": "string", "nullable": true},
                            "color": {"type": "string", "nullable": true}
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
                auth = listOf(AuthRequirement("basicAuth", AuthKind.basic)),
                ocs = OcsMetadata(
                    apiRequestHeader = true,
                    responseDataPointer = "/ocs/data",
                    responseMetaPointer = "/ocs/meta",
                    formatQueryParameter = "format",
                ),
            ),
            confidence = Confidence.high,
            provenance = listOf(
                Provenance(
                    kind = ProvenanceKind.verifiedAppPackage,
                    source = "pantry-0.23.0",
                    detail = "Verified signed Pantry OpenAPI contract",
                ),
            ),
        )
        val descriptor = descriptor(action).copy(
            app = AppIdentity("pantry", "Pantry", "0.23.0"),
            endpointPolicy = EndpointPolicy(
                serverOrigin = "https://cloud.example.test",
                approvedApiPrefixes = listOf("/ocs/v2.php/apps/pantry/api"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor = descriptor,
            action = action,
            values = mapOf(
                "houseId" to "7",
                "name" to "Groceries",
                "description" to "Weekly shop",
                "undeclared" to "must-not-leak",
            ),
        )

        assertEquals("/ocs/v2.php/apps/pantry/api/houses/7/lists", request.relativePath)
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject
        assertEquals(setOf("name", "description"), body.keys)
        assertEquals(JsonPrimitive("Groceries"), body["name"])
        assertEquals(JsonPrimitive("Weekly shop"), body["description"])
        assertFalse("houseId" in body)
        assertFalse("undeclared" in body)
    }

    @Test
    fun `blank optional nullable strings are omitted without erasing intentional empty strings`() {
        val action = createAction(
            bodySchema =
                """
                {
                  "type": "object",
                  "required": ["title"],
                  "properties": {
                    "title": {"type": "string"},
                    "content": {"type": "string"},
                    "color": {"type": "string", "nullable": true}
                  }
                }
                """.trimIndent(),
        )
        val descriptor = descriptor(action)
        val sharedValues = mapOf(
            "projectId" to "7",
            "limit" to "25",
            "title" to "Sandbox note",
            "content" to "",
            "color" to "",
        )
        val request = buildDynamicApiRequest(
            descriptor = descriptor,
            action = action,
            values = sharedValues,
            runtimeContext = mapOf("owner" to "sandbox"),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(setOf("title", "content"), body.keys)
        assertEquals(JsonPrimitive("Sandbox note"), body["title"])
        assertEquals(JsonPrimitive(""), body["content"])
        assertFalse("color" in body)

        val coloredRequest = buildDynamicApiRequest(
            descriptor = descriptor,
            action = action,
            values = sharedValues + ("color" to "#A1B2C3"),
            runtimeContext = mapOf("owner" to "sandbox"),
        )
        val coloredBody = json.parseToJsonElement(
            requireNotNull(coloredRequest.body).decodeToString(),
        ) as JsonObject
        assertEquals(JsonPrimitive("#A1B2C3"), coloredBody["color"])

        assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor = descriptor,
                action = action,
                values = sharedValues + ("title" to ""),
                runtimeContext = mapOf("owner" to "sandbox"),
            )
        }
    }

    @Test
    fun `openapi 31 nullable string union also omits a blank optional value`() {
        val action = createAction(
            bodySchema =
                """
                {
                  "type": "object",
                  "required": ["title"],
                  "properties": {
                    "title": {"type": "string"},
                    "color": {"type": ["string", "null"]}
                  }
                }
                """.trimIndent(),
        )
        val request = buildDynamicApiRequest(
            descriptor = descriptor(action),
            action = action,
            values = mapOf(
                "projectId" to "7",
                "limit" to "25",
                "title" to "Sandbox note",
                "color" to "",
            ),
            runtimeContext = mapOf("owner" to "sandbox"),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(setOf("title"), body.keys)
    }

    @Test
    fun `collection reads request a useful initial page when a typed limit is optional`() {
        val action = readAction().copy(
            binding = readAction().binding.copy(
                queryParameters = listOf(
                    HttpParameter(
                        name = "limit",
                        required = false,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.userInput,
                    ),
                ),
            ),
        )

        val request = buildDynamicApiRequest(descriptor(action), action, values = emptyMap())

        assertEquals("50", request.queryParameters["limit"])
    }

    @Test
    fun `initial collection page size honors declared defaults and inclusive bounds`() {
        data class Case(
            val name: String,
            val schema: String,
            val expected: String?,
        )

        listOf(
            Case(
                name = "limit",
                schema = """{"type":"integer","default":12,"minimum":1,"maximum":20}""",
                expected = "12",
            ),
            Case(
                name = "pageSize",
                schema = """{"type":"integer","maximum":20}""",
                expected = "20",
            ),
            Case(
                name = "perPage",
                schema = """{"type":"number","minimum":75,"maximum":100}""",
                expected = "75",
            ),
            Case(
                name = "maxResults",
                schema = """{"type":"integer","minimum":501}""",
                expected = null,
            ),
            Case(
                name = "limit",
                schema = """{"type":"integer","default":25,"maximum":20}""",
                expected = null,
            ),
            Case(
                name = "limit",
                schema = """{"type":"number","default":12.5,"minimum":1,"maximum":20}""",
                expected = null,
            ),
        ).forEach { case ->
            val action = readAction().copy(
                binding = readAction().binding.copy(
                    queryParameters = listOf(
                        HttpParameter(
                            name = case.name,
                            required = false,
                            schema = json.parseToJsonElement(case.schema),
                            source = ParameterSource.userInput,
                        ),
                    ),
                ),
            )

            val request = buildDynamicApiRequest(descriptor(action), action, values = emptyMap())

            assertEquals(case.expected, request.queryParameters[case.name], case.toString())
        }
    }

    @Test
    fun `pagination expectation uses the same schema derived page size as the initial request`() {
        fun action(pageSizeSchema: String): DynamicAction = readAction().copy(
            binding = readAction().binding.copy(
                queryParameters = listOf(
                    HttpParameter(
                        name = "page_size",
                        required = false,
                        schema = json.parseToJsonElement(pageSizeSchema),
                        source = ParameterSource.userInput,
                    ),
                    HttpParameter(
                        name = "page",
                        required = false,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.userInput,
                    ),
                ),
            ),
        )

        val bounded = action("""{"type":"integer","default":18,"minimum":5,"maximum":20}""")
        assertEquals(
            "18",
            buildDynamicApiRequest(descriptor(bounded), bounded, values = emptyMap())
                .queryParameters["page_size"],
        )
        assertEquals(18, requireNotNull(bounded.dynamicPaginationSpec()).expectedPageSize)

        val unsafe = action("""{"type":"integer","minimum":501}""")
        assertNull(
            buildDynamicApiRequest(descriptor(unsafe), unsafe, values = emptyMap())
                .queryParameters["page_size"],
        )
        assertNull(requireNotNull(unsafe.dynamicPaginationSpec()).expectedPageSize)
    }

    @Test
    fun `typed optional page parameter enables reusable page number pagination`() {
        val action = readAction().copy(
            binding = readAction().binding.copy(
                queryParameters = listOf(
                    HttpParameter(
                        name = "page_size",
                        required = false,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.userInput,
                    ),
                    HttpParameter(
                        name = "page",
                        required = false,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.userInput,
                    ),
                ),
            ),
        )

        val pagination = requireNotNull(action.dynamicPaginationSpec())

        assertEquals(DynamicPaginationMode.PageNumber, pagination.mode)
        assertEquals("page", pagination.parameterName)
        assertEquals(50, pagination.expectedPageSize)
        assertEquals("2", pagination.nextValue(nextPageNumber = 2, loadedRecordCount = 50))
        assertTrue(pagination.canContinue(lastPageSize = 50))
        assertFalse(pagination.canContinue(lastPageSize = 12))
    }

    @Test
    fun `offset pagination uses loaded count and cursor needs a declared record value`() {
        fun integerParameter(name: String, required: Boolean = false) = HttpParameter(
            name = name,
            required = required,
            schema = json.parseToJsonElement("""{"type":"integer"}"""),
            source = ParameterSource.userInput,
        )
        val offsetAction = readAction().copy(
            binding = readAction().binding.copy(queryParameters = listOf(integerParameter("offset"))),
        )
        val offset = requireNotNull(offsetAction.dynamicPaginationSpec())

        assertEquals(DynamicPaginationMode.Offset, offset.mode)
        assertEquals("75", offset.nextValue(nextPageNumber = 3, loadedRecordCount = 75))
        assertTrue(offset.canContinue(lastPageSize = 20))
        val cursor = requireNotNull(
            readAction().copy(
                binding = readAction().binding.copy(queryParameters = listOf(integerParameter("cursor"))),
            ).dynamicPaginationSpec(),
        )
        assertEquals(DynamicPaginationMode.RecordCursor, cursor.mode)
        assertNull(cursor.nextValue(2, 50, listOf(NativeRecord("1", mapOf("id" to "1")))))
        assertEquals(
            "1721734567",
            cursor.nextValue(
                2,
                50,
                listOf(NativeRecord("1", emptyMap(), displayValues = mapOf("dateInt" to "1721734567"))),
            ),
        )
        assertNull(
            readAction().copy(
                binding = readAction().binding.copy(queryParameters = listOf(integerParameter("page", required = true))),
            ).dynamicPaginationSpec(),
        )
        assertNull(
            readAction().copy(intent = ActionIntent.update).copy(
                binding = readAction().binding.copy(queryParameters = listOf(integerParameter("page"))),
            ).dynamicPaginationSpec(),
        )
    }

    @Test
    fun `verified observed settings body accepts safe fields but never credential names`() {
        val bodySchema = json.parseToJsonElement(
            """{"type":"object","additionalProperties":true,"x-nextcloud-native-observed-settings-body":true}""",
        )
        val action = readAction().copy(
            id = "config.update",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.POST,
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified settings route"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf("folder" to "Recipes", "print_image" to "true", "password" to "hidden"),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(setOf("folder", "print_image"), body.keys)
        assertEquals(JsonPrimitive("true"), body["print_image"])
    }

    @Test
    fun `verified observed settings preserve scalar json types supplied by the native form`() {
        val bodySchema = json.parseToJsonElement(
            """{"type":"object","additionalProperties":true,"x-nextcloud-native-observed-settings-body":true}""",
        )
        val inputSchema = json.parseToJsonElement(
            """{
              "properties":{
                "folder":{"type":"string"},
                "update_interval":{"type":"integer"},
                "print_image":{"type":"boolean"}
              }
            }""",
        )
        val action = readAction().copy(
            id = "config.update",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.POST,
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified settings route"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf(
                "folder" to "Cookbook",
                "update_interval" to "900",
                "print_image" to "true",
            ),
            observedInputSchema = inputSchema,
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(JsonPrimitive("Cookbook"), body["folder"])
        assertEquals(JsonPrimitive(900), body["update_interval"])
        assertEquals(JsonPrimitive(true), body["print_image"])
    }

    @Test
    fun `contract declared object fields serialize reconstructed row data as json objects`() {
        fun requestFor(propertySchema: String): JsonObject {
            val bodySchema = json.parseToJsonElement(
                """{
                  "type":"object",
                  "properties":{"data":$propertySchema},
                  "required":["data"]
                }""",
            )
            val action = readAction().copy(
                id = "rows.update",
                resourceId = "rows",
                intent = ActionIntent.update,
                risk = ActionRisk.mutating,
                binding = readAction().binding.copy(
                    method = HttpMethod.PUT,
                    path = "/ocs/v2.php/apps/example/api/rows/{rowId}",
                    pathParameters = listOf(
                        HttpParameter(
                            "rowId",
                            true,
                            json.parseToJsonElement("""{"type":"integer"}"""),
                            ParameterSource.resourceField,
                        ),
                    ),
                    body = HttpBody("application/json", true, bodySchema),
                ),
                provenance = listOf(
                    Provenance(
                        ProvenanceKind.advertisedOpenApi,
                        "/apps/example/openapi.json",
                        "Declared row update",
                    ),
                ),
            )
            val request = buildDynamicApiRequest(
                descriptor(action),
                action,
                values = mapOf(
                    "rowId" to "7",
                    "data" to """{"42":{"columnId":42,"value":15.75,"future":"preserved"}}""",
                ),
            )
            return json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject
        }

        val direct = requestFor("""{"type":"object","additionalProperties":true}""")
        val oneOf = requestFor("""{"oneOf":[{"type":"string"},{"type":"object"}]}""")

        assertTrue(direct["data"] is JsonObject)
        assertEquals(direct["data"], oneOf["data"])
        assertEquals(
            JsonPrimitive("preserved"),
            ((direct.getValue("data") as JsonObject).getValue("42") as JsonObject)["future"],
        )
    }

    @Test
    fun `undeclared json-looking strings remain strings`() {
        val bodySchema = json.parseToJsonElement(
            """{"type":"object","properties":{"data":{"type":"string"}},"required":["data"]}""",
        )
        val action = readAction().copy(
            id = "rows.update.string",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.PUT,
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(
                    ProvenanceKind.advertisedOpenApi,
                    "/apps/example/openapi.json",
                    "Declared string update",
                ),
            ),
        )
        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf("data" to """{"42":{"value":15.75}}"""),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(JsonPrimitive("""{"42":{"value":15.75}}"""), body["data"])
    }

    @Test
    fun `verified settings setter maps native field to wire value and serializes a text list`() {
        val bodySchema = json.parseToJsonElement(
            """{
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "excludedPaths":{
                  "type":"array",
                  "items":{"type":"string"},
                  "format":"nextcloud-string-list",
                  "x-nextcloud-native-wire-name":"value"
                }
              },
              "required":["excludedPaths"]
            }""",
        )
        val action = readAction().copy(
            id = "settings.excluded-paths",
            resourceId = "settings",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.POST,
                path = "/ocs/v2.php/apps/example/api/settings/user/exclude_paths",
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified settings setter"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf("excludedPaths" to "Music/Archive\nShared\nMusic/Archive"),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(setOf("value"), body.keys)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("Music/Archive"), JsonPrimitive("Shared"))),
            body["value"],
        )
    }

    @Test
    fun `exact declared integer arrays encode bounded JSON numbers and omit blank optional values`() {
        val bodySchema = json.parseToJsonElement(
            """{
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "requiredIds":{
                  "type":"array",
                  "items":{"type":"integer"},
                  "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
                },
                "optionalIds":{
                  "type":"array",
                  "items":{"type":"integer"},
                  "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
                }
              },
              "required":["requiredIds"]
            }""",
        )
        val action = readAction().copy(
            id = "assignments.update",
            resourceId = "assignments",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/assignments",
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified assignment setter"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf(
                "requiredIds" to "[7,-2,7,9223372036854775807]",
                "optionalIds" to "   ",
                "undeclaredIds" to "[99]",
            ),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(setOf("requiredIds"), body.keys)
        assertEquals(
            JsonArray(
                listOf(
                    JsonPrimitive(7),
                    JsonPrimitive(-2),
                    JsonPrimitive(7),
                    JsonPrimitive(Long.MAX_VALUE),
                ),
            ),
            body["requiredIds"],
        )
    }

    @Test
    fun `form encoded arrays fail closed while scalar siblings remain supported`() {
        val bodySchema = json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "ids":{
                  "type":"array",
                  "items":{"type":"integer"},
                  "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
                },
                "label":{"type":"string"}
              }
            }""",
        )
        val action = readAction().copy(
            id = "assignments.form.update",
            resourceId = "assignments",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.POST,
                path = "/ocs/v2.php/apps/example/api/assignments",
                body = HttpBody("application/x-www-form-urlencoded", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified assignment setter"),
            ),
        )

        val scalarRequest = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf("label" to "One"),
        )
        assertEquals("label=One", requireNotNull(scalarRequest.body).decodeToString())

        assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor(action),
                action,
                values = mapOf("ids" to "[1,2]", "label" to "One"),
            )
        }
    }

    @Test
    fun `integer array encoding enforces every supported declared constraint`() {
        val bodySchema = json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "ids":{
                  "type":"array",
                  "items":{
                    "type":"integer",
                    "minimum":2,
                    "exclusiveMaximum":12,
                    "multipleOf":2
                  },
                  "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT",
                  "minItems":2,
                  "maxItems":3,
                  "uniqueItems":true
                }
              },
              "required":["ids"]
            }""",
        )
        val action = readAction().copy(
            id = "assignments.update",
            resourceId = "assignments",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/assignments",
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified assignment setter"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf("ids" to "[2,4,10]"),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject
        assertEquals(
            JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(4), JsonPrimitive(10))),
            body["ids"],
        )

        listOf(
            "[]",
            "[2]",
            "[2,4,6,8]",
            "[2,2]",
            "[0,2]",
            "[2,12]",
            "[2,3]",
        ).forEach { value ->
            assertFailsWith<IllegalStateException>(value) {
                buildDynamicApiRequest(descriptor(action), action, values = mapOf("ids" to value))
            }
        }
    }

    @Test
    fun `blank optional typed scalars are omitted while blank strings remain explicit`() {
        val bodySchema = json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "categoryId":{"type":"integer","nullable":true},
                "amount":{"type":"number","nullable":true},
                "enabled":{"type":"boolean"},
                "description":{"type":"string"}
              }
            }""",
        )
        val action = readAction().copy(
            id = "entries.create",
            resourceId = "entries",
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.POST,
                path = "/ocs/v2.php/apps/example/api/entries",
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified entry create"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf(
                "categoryId" to "",
                "amount" to " ",
                "enabled" to "",
                "description" to "",
            ),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(setOf("description"), body.keys)
        assertEquals(JsonPrimitive(""), body["description"])
    }

    @Test
    fun `integer array encoding rejects malformed wrong-type overflowing and oversized inputs`() {
        val bodySchema = json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "ids":{
                  "type":"array",
                  "items":{"type":"integer"},
                  "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
                }
              },
              "required":["ids"]
            }""",
        )
        val action = readAction().copy(
            id = "assignments.update",
            resourceId = "assignments",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.PUT,
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified assignment setter"),
            ),
        )
        val tooMany = (0..256).joinToString(prefix = "[", postfix = "]")
        listOf(
            "1\n2",
            """[1,"2"]""",
            "[1.5]",
            "[true]",
            """[{"id":1}]""",
            "[9223372036854775808]",
            tooMany,
        ).forEach { value ->
            assertFailsWith<IllegalStateException>(value) {
                buildDynamicApiRequest(descriptor(action), action, values = mapOf("ids" to value))
            }
        }
    }

    @Test
    fun `integer-looking arrays without an exact declared integer format fail closed`() {
        fun requestFor(propertySchema: String) {
            val bodySchema = json.parseToJsonElement(
                """{"type":"object","properties":{"ids":$propertySchema},"required":["ids"]}""",
            )
            val action = readAction().copy(
                id = "assignments.update",
                resourceId = "assignments",
                intent = ActionIntent.update,
                risk = ActionRisk.mutating,
                binding = readAction().binding.copy(
                    method = HttpMethod.PUT,
                    body = HttpBody("application/json", true, bodySchema),
                ),
                provenance = listOf(
                    Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified assignment setter"),
                ),
            )
            assertFailsWith<IllegalStateException> {
                buildDynamicApiRequest(descriptor(action), action, values = mapOf("ids" to "[1,2]"))
            }
        }

        requestFor("""{"type":"array","items":{"type":"integer"}}""")
        requestFor(
            """{
              "type":"array",
              "items":{"type":"string"},
              "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
            }""",
        )
        requestFor("""{"type":"array","items":{"type":"number"}}""")
        requestFor("""{"type":"array"}""")
        requestFor(
            """{
              "type":"array",
              "items":{"type":"integer"},
              "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT",
              "contains":{"const":1}
            }""",
        )
        requestFor(
            """{
              "type":"array",
              "items":{"type":"integer","enum":[1,2]},
              "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
            }""",
        )
    }

    @Test
    fun `verified observed settings serialize only bounded boolean maps as objects`() {
        val bodySchema = json.parseToJsonElement(
            """{"type":"object","additionalProperties":true,"x-nextcloud-native-observed-settings-body":true}""",
        )
        val action = readAction().copy(
            id = "config.update",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.POST,
                body = HttpBody("application/json", true, bodySchema),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified settings route"),
            ),
        )

        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf(
                "visibleInfoBlocks" to """{"preparation-time":true,"tools":false}""",
                "notes" to """{"arbitrary":"text"}""",
            ),
        )
        val body = json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(
            json.parseToJsonElement("""{"preparation-time":true,"tools":false}"""),
            body["visibleInfoBlocks"],
        )
        assertEquals(JsonPrimitive("""{"arbitrary":"text"}"""), body["notes"])
    }

    @Test
    fun `selected record id cannot supply an unrelated resource identifier`() {
        val action = createAction()

        val failure = assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor = descriptor(action),
                action = action,
                values = mapOf(
                    "id" to "project 7",
                    "limit" to "25",
                    "title" to "Shared list",
                ),
                runtimeContext = mapOf("owner" to "alice"),
            )
        }

        assertEquals("projectId is required.", failure.message)
    }

    @Test
    fun rejectsMissingRequiredBodyInputAndMultiSegmentPathInput() {
        val action = createAction()
        val descriptor = descriptor(action)

        val missingBody = assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor,
                action,
                values = mapOf("projectId" to "7", "limit" to "10"),
                runtimeContext = mapOf("owner" to "alice"),
            )
        }
        assertEquals("title is required.", missingBody.message)

        val unsafePath = assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor,
                action,
                values = mapOf(
                    "projectId" to "../admin",
                    "limit" to "10",
                    "title" to "Nope",
                ),
                runtimeContext = mapOf("owner" to "alice"),
            )
        }
        assertTrue(unsafePath.message.orEmpty().contains("single safe path segment"))
    }

    @Test
    fun emptyPropertySchemaDoesNotForwardArbitraryValues() {
        val action = createAction(
            bodySchema = """{"type":"object","properties":{}}""",
        )
        val request = buildDynamicApiRequest(
            descriptor(action),
            action,
            values = mapOf(
                "projectId" to "7",
                "limit" to "10",
                "undeclared" to "must-not-leak",
            ),
            runtimeContext = mapOf("owner" to "alice"),
        )

        assertEquals(JsonObject(emptyMap()), json.parseToJsonElement(requireNotNull(request.body).decodeToString()))
    }

    @Test
    fun `verified CRUD requests bind declared paths and never forward undeclared body values`() {
        val update = createAction(
            bodySchema = """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "properties":{
                    "title":{"type":"string"},
                    "enabled":{"type":"boolean"}
                  },
                  "required":["title","enabled"]
                }
            """.trimIndent(),
        ).copy(
            id = "items.update",
            intent = ActionIntent.update,
            requiresConfirmation = true,
            binding = createAction().binding.copy(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/items/{id}",
                pathParameters = listOf(
                    HttpParameter(
                        name = "id",
                        required = true,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
                queryParameters = emptyList(),
                body = HttpBody(
                    contentType = "application/json",
                    required = true,
                    schema = json.parseToJsonElement(
                        """
                            {
                              "type":"object",
                              "additionalProperties":false,
                              "properties":{
                                "title":{"type":"string"},
                                "enabled":{"type":"boolean"}
                              },
                              "required":["title","enabled"]
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
            provenance = listOf(
                Provenance(ProvenanceKind.verifiedAppPackage, "signed-package", "Verified CRUD route"),
            ),
        )

        val updateRequest = buildDynamicApiRequest(
            descriptor(update),
            update,
            values = mapOf(
                "id" to "73",
                "title" to "Roadmap",
                "enabled" to "true",
                "password" to "must-not-leak",
                "undeclared" to "must-not-leak",
            ),
        )
        val updateBody = json.parseToJsonElement(
            requireNotNull(updateRequest.body).decodeToString(),
        ) as JsonObject

        assertEquals(NextcloudApiMethod.PUT, updateRequest.method)
        assertEquals("/ocs/v2.php/apps/example/api/items/73", updateRequest.relativePath)
        assertEquals(setOf("title", "enabled"), updateBody.keys)
        assertEquals(JsonPrimitive(true), updateBody["enabled"])

        val delete = update.copy(
            id = "items.delete",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
            binding = update.binding.copy(method = HttpMethod.DELETE, body = null),
        )
        val deleteRequest = buildDynamicApiRequest(
            descriptor(delete),
            delete,
            values = mapOf("id" to "73", "reason" to "must-not-leak"),
        )

        assertEquals(NextcloudApiMethod.DELETE, deleteRequest.method)
        assertEquals("/ocs/v2.php/apps/example/api/items/73", deleteRequest.relativePath)
        assertNull(deleteRequest.body)
    }

    @Test
    fun rejectsAuthenticationSchemesTheTransportCannotProvide() {
        val action = createAction(
            auth = listOf(AuthRequirement("bearerAuth", AuthKind.bearer)),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor(action),
                action,
                values = mapOf("projectId" to "7", "limit" to "10", "title" to "Nope"),
                runtimeContext = mapOf("owner" to "alice"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("authentication scheme"))
    }

    @Test
    fun parsesSuccessfulOcsDataAndJsonPointerEscapes() {
        val action = readAction(
            dataPointer = "/payload/a~1b/~0items",
            metaPointer = "/payload/meta",
        )
        val records = parseDynamicRecords(
            action,
            response(
                """
                {
                  "payload": {
                    "meta": {"status":"ok","statuscode":100,"message":"OK"},
                    "a/b": {"~items":[
                      {"id":12,"title":"One","done":false},
                      {"uuid":"second","title":"Two","note":null}
                    ]}
                  }
                }
                """.trimIndent(),
            ),
            declaredFieldIds = setOf("id", "uuid", "title", "done", "note"),
        )

        assertEquals(listOf("12", "second"), records.map { it.id })
        assertEquals("One", records[0].values["title"])
        assertEquals("false", records[0].values["done"])
        assertNull(records[1].values["note"])
    }

    @Test
    fun `neutral object map collection gains bounded display-only scalar fields`() {
        val records = parseDynamicRecords(
            readAction(),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": {
                      "stable-a": {
                        "displayName":"Primary",
                        "unread":true,
                        "total":4,
                        "lastSync":"2026-07-22T21:10:00Z",
                        "password":"must-not-display",
                        "nested":{"private":"value"},
                        "blob":"${"A".repeat(120)}"
                      },
                      "stable-b": {
                        "displayName":"Archive",
                        "unread":false,
                        "total":9
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("stable-a", "stable-b"), records.map(NativeRecord::id))
        assertTrue(records.all { it.values.isEmpty() })
        assertTrue(records.none(NativeRecord::actionSafeIdentity))
        assertEquals("Redacted", records.first().displayValues["password"])
        assertEquals("1 field", records.first().displayValues["nested"])
        assertEquals("Binary data", records.first().displayValues["blob"])
        assertEquals(
            setOf("displayName", "unread", "total", "lastSync", "nested"),
            records.first().ephemeralFields.map(FieldSpec::id).toSet(),
        )
        assertEquals(FieldKind.boolean, records.first().ephemeralFields.single { it.id == "unread" }.kind)
        assertEquals(FieldKind.integer, records.first().ephemeralFields.single { it.id == "total" }.kind)
        assertEquals(FieldKind.dateTime, records.first().ephemeralFields.single { it.id == "lastSync" }.kind)
        assertEquals(FieldKind.objectValue, records.first().ephemeralFields.single { it.id == "nested" }.kind)
        assertTrue(records.flatMap(NativeRecord::ephemeralFields).all(FieldSpec::readOnly))
    }

    @Test
    fun `single named array list envelopes become native rows without mutation-safe observed ids`() {
        val action = readAction().copy(
            binding = readAction().binding.copy(ocs = null),
        )
        val records = parseDynamicRecords(
            action,
            response(
                """
                {
                  "worklog": [
                    {
                      "id": "observed-1",
                      "member": "member-a",
                      "work_time": "2026-07-23T11:00:00Z",
                      "name": "Completed task",
                      "points": 2
                    },
                    {
                      "id": "observed-2",
                      "member": "member-b",
                      "work_time": "2026-07-23T12:00:00Z",
                      "name": "Another task",
                      "points": 3
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("observed-1", "observed-2"), records.map(NativeRecord::id))
        assertTrue(records.none(NativeRecord::actionSafeIdentity))
        assertEquals(listOf("Completed task", "Another task"), records.map { it.displayValues["name"] })
    }

    @Test
    fun `empty single named array list envelope stays an empty collection`() {
        val records = parseDynamicRecords(
            readAction().copy(binding = readAction().binding.copy(ocs = null)),
            response("""{"items":[]}"""),
        )

        assertTrue(records.isEmpty())
    }

    @Test
    fun `collection array with cursor and count metadata becomes native rows`() {
        val records = parseDynamicRecords(
            readAction().copy(binding = readAction().binding.copy(ocs = null)),
            response(
                """
                {
                  "entries": [
                    {"id":"row-1","title":"First"},
                    {"id":"row-2","title":"Second"}
                  ],
                  "cursor": 25,
                  "total": 2,
                  "isPaginated": true,
                  "name": "Results"
                }
                """.trimIndent(),
            ),
            declaredFieldIds = setOf("id", "title"),
        )

        assertEquals(listOf("row-1", "row-2"), records.map(NativeRecord::id))
        assertEquals(listOf("First", "Second"), records.map { it.values["title"] })
    }

    @Test
    fun `empty collection array with metadata remains truly empty`() {
        val records = parseDynamicRecords(
            readAction().copy(binding = readAction().binding.copy(ocs = null)),
            response(
                """
                {
                  "entries": [],
                  "cursor": null,
                  "isPaginated": false,
                  "name": "Results",
                  "pagination": {"page":1,"pageCount":0}
                }
                """.trimIndent(),
            ),
        )

        assertTrue(records.isEmpty())
    }

    @Test
    fun `declared collection unwraps beside a parallel scalar id index`() {
        val action = readAction().copy(
            id = "bills.list",
            label = "List bills",
            resourceId = "bills",
        )
        val records = parseDynamicRecords(
            action,
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": {
                      "bills": [
                        {"id": 41, "what": "Train", "amount": 12.5},
                        {"id": 42, "what": "Hotel", "amount": 80}
                      ],
                      "allBillIds": [40, 41, 42],
                      "nb_bills": 2,
                      "timestamp": 1784825274
                    }
                  }
                }
                """.trimIndent(),
            ),
            declaredFieldIds = setOf("id", "what", "amount"),
        )

        assertEquals(listOf("41", "42"), records.map(NativeRecord::id))
        assertEquals(listOf("Train", "Hotel"), records.map { it.values["what"] })
    }

    @Test
    fun `ambiguous arrays and ordinary nested attachments are not flattened`() {
        val action = readAction().copy(binding = readAction().binding.copy(ocs = null))
        val ambiguous = parseDynamicRecords(
            action,
            response("""{"items":[],"results":[],"total":0}"""),
        )
        val ordinary = parseDynamicRecords(
            action,
            response("""{"title":"Message","attachments":[{"id":"file-1"}]}"""),
        )

        assertEquals(listOf("record"), ambiguous.map(NativeRecord::id))
        assertEquals(listOf("record"), ordinary.map(NativeRecord::id))
        assertEquals("Message", ordinary.single().displayValues["title"])
        assertTrue("attachments" in ordinary.single().structuredValues)
    }

    @Test
    fun `observed authentication-shaped digest is redacted even when declared`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": [{
                      "id": 7,
                      "description": "Native client",
                      "hash": "${"a4".repeat(32)}",
                      "keys": [{
                        "id": 8,
                        "description": "Nested client",
                        "digest": "${"4b".repeat(32)}"
                      }]
                    }]
                  }
                }
                """.trimIndent(),
            ),
            declaredFieldIds = setOf("id", "description", "hash", "keys"),
        ).single()

        assertFalse("hash" in record.values)
        assertEquals("Redacted", record.displayValues["hash"])
        assertFalse(record.ephemeralFields.any { it.id == "hash" })
        val nested = (record.structuredValues["keys"] as NativeStructuredValue.ListValue)
            .items.single() as NativeStructuredValue.ObjectValue
        assertEquals(setOf("id", "description"), nested.entries.mapTo(linkedSetOf()) { it.key })
    }

    @Test
    fun `sparse array uses backing database identity instead of opaque protocol id for read navigation`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": [
                      {"databaseId":73,"id":"opaque-protocol-id","name":"Primary"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        ).single()

        assertEquals("73", record.id)
        assertEquals("opaque-protocol-id", record.displayValues["id"])
        assertEquals("Primary", record.displayValues["name"])
        assertFalse(record.actionSafeIdentity)

        val childAction = readAction().copy(
            id = "entries.children",
            resourceId = "entries",
            binding = readAction().binding.copy(
                path = "/ocs/v2.php/apps/example/api/entries/{entryId}/children",
                pathParameters = listOf(
                    HttpParameter(
                        name = "entryId",
                        required = true,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
            ),
        )
        val request = buildDynamicApiRequest(
            descriptor = descriptor(childAction),
            action = childAction,
            values = mapOf("entryId" to record.id),
        )

        assertEquals("/ocs/v2.php/apps/example/api/entries/73/children", request.relativePath)
    }

    @Test
    fun `declared database identity outranks a declared protocol id`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": [
                      {"databaseId":73,"id":"1","name":"Primary"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
            declaredFieldIds = setOf("databaseId", "id", "name"),
        ).single()

        assertEquals("73", record.id)
        assertTrue(record.actionSafeIdentity)
        assertEquals("1", record.values["id"])
        assertEquals("73", record.actionBindingValues()["id"])
    }

    @Test
    fun `observed database identity outranks a declared protocol id for read navigation`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": [
                      {"databaseId":73,"id":"opaque-protocol-id","name":"Primary"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
            declaredFieldIds = setOf("id", "name"),
        ).single()

        assertEquals("73", record.id)
        assertFalse(record.actionSafeIdentity)
        assertEquals("opaque-protocol-id", record.values["id"])
    }

    @Test
    fun `object map key remains read identity when values contain display identifiers`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": {
                      "stable-map-key": {
                        "databaseId":73,
                        "id":"opaque-protocol-id",
                        "name":"Primary"
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        ).single()

        assertEquals("stable-map-key", record.id)
        assertFalse(record.actionSafeIdentity)
    }

    @Test
    fun `declared arrays and objects retain bounded safe structure for native detail`() {
        val record = parseDynamicRecords(
            readAction().copy(intent = ActionIntent.read),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data": {
                      "id":"guide-7",
                      "title":"Replace a wheel",
                      "supplies":["Jack","Wrench"],
                      "instructions":["Secure the vehicle","Raise the vehicle"],
                      "facts":{"duration":"25 min","difficulty":"Medium","password":"hidden"}
                    }
                  }
                }
                """.trimIndent(),
            ),
            declaredFieldIds = setOf("id", "title", "supplies", "instructions", "facts"),
        ).single()

        val supplies = record.structuredValues["supplies"] as NativeStructuredValue.ListValue
        val instructions = record.structuredValues["instructions"] as NativeStructuredValue.ListValue
        val facts = record.structuredValues["facts"] as NativeStructuredValue.ObjectValue

        assertEquals(listOf("Jack", "Wrench"), supplies.items.map { (it as NativeStructuredValue.Scalar).value })
        assertEquals(
            listOf("Secure the vehicle", "Raise the vehicle"),
            instructions.items.map { (it as NativeStructuredValue.Scalar).value },
        )
        assertEquals(listOf("duration", "difficulty"), facts.entries.map { it.key })
        assertTrue(facts.entries.all { (it.value as NativeStructuredValue.Scalar).kind == NativeStructuredScalarKind.string })
        assertFalse("password" in record.structuredValues)
        assertFalse("supplies" in record.actionBindingValues())
        assertFalse("instructions" in record.actionBindingValues())
        assertFalse("facts" in record.actionBindingValues())
        assertEquals("guide-7", record.actionBindingValues()["id"])
        assertEquals("2 items", record.displayValues["instructions"])
        assertEquals("2 fields", record.displayValues["facts"])
    }

    @Test
    fun `safe observed nested values remain display only for sparse contracts`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[{"id":7,"artist":{"id":2,"name":"Ada"},"files":{"path":"song.flac","token":"hidden"}}]}}""",
            ),
        ).single()

        val artist = record.structuredValues["artist"] as NativeStructuredValue.ObjectValue
        val files = record.structuredValues["files"] as NativeStructuredValue.ObjectValue
        assertEquals("Ada", (artist.entries.single { it.key == "name" }.value as NativeStructuredValue.Scalar).value)
        assertFalse(files.entries.any { it.key == "token" })
        assertFalse("artist" in record.actionBindingValues())
        assertTrue(record.ephemeralFields.any { it.id == "artist" && it.kind == FieldKind.objectValue })
    }

    @Test
    fun `bounded structures retain valid mime keyed representation maps`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """
                {
                  "ocs": {
                    "meta": {"status":"ok","statuscode":100},
                    "data":[{
                      "id":7,
                      "title":"Track",
                      "artist":{"name":"Artist"},
                      "files":{
                        "audio/mpeg":"/index.php/apps/library/api/files/91/download",
                        "not/a/mime":"discarded",
                        "token":"hidden"
                      }
                    }]
                  }
                }
                """.trimIndent(),
            ),
        ).single()

        val files = record.structuredValues["files"] as NativeStructuredValue.ObjectValue
        assertEquals(listOf("audio/mpeg"), files.entries.map { it.key })
        assertEquals(
            "/index.php/apps/library/api/files/91/download",
            (files.entries.single().value as NativeStructuredValue.Scalar).value,
        )
    }

    @Test
    fun `sparse contracts retain bounded semantic message bodies for inert native rendering`() {
        val messageBody = "Readable message body. ".repeat(600)
        val record = parseDynamicRecords(
            readAction().copy(intent = ActionIntent.read),
            response(
                """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"databaseId":42,"subject":"Hello","body":${JsonPrimitive(messageBody)},"unrelated":"${"x".repeat(700)}"}}}""",
            ),
        ).single()

        assertEquals(messageBody, record.displayValues["body"])
        assertEquals(FieldKind.longText, record.ephemeralFields.single { it.id == "body" }.kind)
        assertEquals("Binary data", record.displayValues["unrelated"])
        assertFalse("body" in record.actionBindingValues())
    }

    @Test
    fun `structured arrays are truncated by explicit per-container bounds`() {
        val items = (1..200).joinToString(",") { index -> "\"Item $index\"" }
        val record = parseDynamicRecords(
            readAction().copy(intent = ActionIntent.read),
            response(
                """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"id":"bounded","items":[$items]}}}""",
            ),
            declaredFieldIds = setOf("id", "items"),
        ).single()

        val structured = record.structuredValues["items"] as NativeStructuredValue.ListValue
        assertEquals(128, structured.items.size)
        assertEquals(72, structured.omittedItems)
        assertEquals("Item 1", (structured.items.first() as NativeStructuredValue.Scalar).value)
        assertEquals("Item 128", (structured.items.last() as NativeStructuredValue.Scalar).value)
    }

    @Test
    fun `observed account identifier and token cannot satisfy a declared query`() {
        val record = parseDynamicRecords(
            readAction(),
            response(
                """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[{"id":"row-1","name":"Visible","accountId":"untrusted","token":"secret"}]}}""",
            ),
            declaredFieldIds = setOf("name"),
        ).single()
        val action = readAction().copy(
            id = "mailboxes.list",
            resourceId = "mailboxes",
            binding = readAction().binding.copy(
                queryParameters = listOf(
                    HttpParameter(
                        name = "accountId",
                        required = true,
                        schema = json.parseToJsonElement("""{"type":"string"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
            ),
        )

        assertFalse("accountId" in record.values)
        assertFalse("token" in record.values)
        assertEquals("Redacted", record.displayValues["token"])
        assertFalse(record.actionSafeIdentity)
        val failure = assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor(action),
                action,
                values = record.actionBindingValues(),
            )
        }
        assertEquals("accountId is required.", failure.message)
    }

    @Test
    fun treatsSlashPointerAsAnEmptyPropertyName() {
        val action = readAction(dataPointer = "/", metaPointer = "/meta")
        val records = parseDynamicRecords(
            action,
            response(
                """{"meta":{"status":"ok","statuscode":200},"root":{"id":"wrong"},"":{"id":"empty-key"}}""",
            ),
        )

        assertEquals("empty-key", records.single().id)
    }

    @Test
    fun rejectsOcsFailureEvenWhenHttpStatusIsSuccessful() {
        val action = readAction()

        val failure = assertFailsWith<IllegalStateException> {
            parseDynamicRecords(
                action,
                response(
                    """
                    {"ocs":{"meta":{"status":"failure","statuscode":997,"message":"Current user is not authorized"},"data":[]}}
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("Current user is not authorized", failure.message)
    }

    @Test
    fun rejectsNonJsonAndMalformedJsonPointers() {
        val action = readAction()
        val nonJson = assertFailsWith<IllegalStateException> {
            parseDynamicRecords(action, response("<html/>", contentType = "text/html"))
        }
        assertTrue(nonJson.message.orEmpty().contains("did not return JSON"))

        val malformedPointerAction = readAction(dataPointer = "/ocs/~2data")
        val malformed = assertFailsWith<IllegalStateException> {
            parseDynamicRecords(
                malformedPointerAction,
                response("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[]}}"""),
            )
        }
        assertTrue(malformed.message.orEmpty().contains("JSON pointer is invalid"))
    }

    @Test
    fun `malformed and invalid utf8 dynamic payloads fail as bounded screen errors`() {
        val malformedBodies = listOf("", "{", "[1,", """{"unterminated":"""")
        malformedBodies.forEach { body ->
            val failure = assertFailsWith<IllegalStateException> {
                parseDynamicRecords(
                    readAction().copy(binding = readAction().binding.copy(ocs = null)),
                    response(body),
                )
            }
            assertEquals("The dynamic endpoint returned malformed JSON.", failure.message)
        }

        val invalidUtf8 = NextcloudApiResponse(
            status = 200,
            body = byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xc3.toByte(), '}'.code.toByte()),
            contentType = "application/json",
            etag = null,
        )
        val utf8Failure = assertFailsWith<IllegalStateException> {
            parseDynamicRecords(
                readAction().copy(binding = readAction().binding.copy(ocs = null)),
                invalidUtf8,
            )
        }
        assertEquals("The dynamic endpoint returned invalid UTF-8 JSON.", utf8Failure.message)
    }

    @Test
    fun `deep JSON is rejected before recursive parsing while brackets inside strings stay inert`() {
        val deeplyNested = """{"child":""".repeat(65) + "null" + "}".repeat(65)
        val failure = assertFailsWith<IllegalStateException> {
            parseDynamicRecords(
                readAction().copy(binding = readAction().binding.copy(ocs = null)),
                response(deeplyNested),
            )
        }
        assertEquals("The dynamic endpoint returned JSON nested too deeply.", failure.message)

        val safe = parseDynamicRecords(
            readAction().copy(binding = readAction().binding.copy(ocs = null)),
            response("""{"id":"safe","value":"${"{[\\\"]}".repeat(80)}"}"""),
            declaredFieldIds = setOf("id", "value"),
        ).single()
        assertEquals("safe", safe.id)
    }

    @Test
    fun `oversized dynamic JSON is rejected before materialization`() {
        val response = NextcloudApiResponse(
            status = 200,
            body = ByteArray(DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES.toInt() + 1) { ' '.code.toByte() },
            contentType = "application/json",
            etag = null,
        )

        val failure = assertFailsWith<IllegalStateException> {
            parseDynamicRecords(
                readAction().copy(binding = readAction().binding.copy(ocs = null)),
                response,
            )
        }
        assertEquals("The dynamic endpoint returned too much JSON.", failure.message)
    }

    @Test
    fun `null heavy and wide records remain bounded inert native data`() {
        val nullFields = (1..500).joinToString(",") { index -> "\"null$index\":null" }
        val scalarFields = (1..500).joinToString(",") { index -> "\"field$index\":\"value-$index\"" }
        val record = parseDynamicRecords(
            readAction().copy(binding = readAction().binding.copy(ocs = null)),
            response("""{"id":"wide",$nullFields,$scalarFields}"""),
            declaredFieldIds = (1..500).mapTo(mutableSetOf()) { "field$it" } + "id",
        ).single()

        assertEquals("wide", record.id)
        assertTrue(record.values.size <= 128)
        assertTrue(record.displayValues.size <= 128)
        assertTrue(record.ephemeralFields.size <= 24)
        assertTrue(record.structuredValues.isEmpty())
    }

    @Test
    fun `large and duplicate collection identities cannot crash keyed native lists`() {
        val rows = (0 until MAX_DYNAMIC_NATIVE_RECORDS + 200).joinToString(",") { index ->
            val id = if (index < 3) "duplicate" else "row-$index"
            """{"id":"$id","position":$index}"""
        }
        val records = parseDynamicRecords(
            readAction().copy(binding = readAction().binding.copy(ocs = null)),
            response("[$rows]"),
            declaredFieldIds = setOf("id", "position"),
        )

        assertTrue(records.size <= MAX_DYNAMIC_NATIVE_RECORDS)
        assertEquals(records.size, records.map(NativeRecord::id).distinct().size)
        assertEquals("0", records.first().values["position"])
    }

    @Test
    fun `oversized object map keys degrade to bounded read only identities`() {
        val longKey = "x".repeat(2_000)
        val records = parseDynamicRecords(
            readAction().copy(binding = readAction().binding.copy(ocs = null)),
            response("""{"$longKey":{"title":"Visible"},"second":{"title":"Second"}}"""),
        )

        assertEquals(2, records.size)
        assertTrue(records.all { it.id.length <= 256 })
        assertEquals(records.size, records.map(NativeRecord::id).distinct().size)
        assertTrue(records.none(NativeRecord::actionSafeIdentity))
    }

    @Test
    fun `write-only resource identity fields cannot authorize records from a read response`() = runBlocking {
        val read = readAction().copy(responseFieldIds = listOf("title"))
        val base = descriptor(read)
        val descriptor = base.copy(
            resources = listOf(
                base.resources.single().copy(
                    fields = listOf(
                        DynamicField(
                            id = "id",
                            label = "ID",
                            kind = FieldKind.string,
                            required = true,
                            readOnly = false,
                            nullable = false,
                            multiple = false,
                            confidence = Confidence.high,
                        ),
                        DynamicField(
                            id = "title",
                            label = "Title",
                            kind = FieldKind.string,
                            required = true,
                            readOnly = false,
                            nullable = false,
                            multiple = false,
                            confidence = Confidence.high,
                        ),
                    ),
                ),
            ),
        )

        val record = executeDynamicReadWithFallback(descriptor, read.id) {
            response(
                """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[{"id":"write-only","title":"Visible"}]}}""",
            )
        }.single()

        assertEquals("write-only", record.id)
        assertEquals("Visible", record.values["title"])
        assertFalse(record.actionSafeIdentity)
    }

    @Test
    fun preferredReadUsesHiddenFallbackOnlyAfterFailureOrEmptyResult() = runBlocking {
        val fallback = readAction().copy(
            id = "items.list.fallback",
            binding = readAction().binding.copy(path = "/ocs/v2.php/apps/example/api/items-fallback"),
            fallbackOnly = true,
        )
        val preferred = readAction().copy(fallbackActionIds = listOf(fallback.id))
        val descriptor = descriptor(preferred).copy(actions = listOf(preferred, fallback))

        suspend fun load(primary: NextcloudApiResponse): Pair<List<String>, List<NativeRecord>> {
            val attempted = mutableListOf<String>()
            val records = executeDynamicReadWithFallback(descriptor, preferred.id) { action ->
                attempted += action.id
                if (action.id == preferred.id) {
                    primary
                } else {
                    response(
                        """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[{"id":"fallback-1"}]}}""",
                    )
                }
            }
            return attempted to records
        }

        val failed = load(response("not found", status = 404, contentType = "text/plain"))
        assertEquals(listOf(preferred.id, fallback.id), failed.first)
        assertEquals("fallback-1", failed.second.single().id)

        val empty = load(response("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[]}}"""))
        assertEquals(listOf(preferred.id, fallback.id), empty.first)
        assertEquals("fallback-1", empty.second.single().id)

        val populated = load(
            response("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[{"id":"primary-1"}]}}"""),
        )
        assertEquals(listOf(preferred.id), populated.first)
        assertEquals("primary-1", populated.second.single().id)
    }

    @Test
    fun `fallback transport retains preferred collection parsing semantics`() = runBlocking {
        val fallback = readAction().copy(
            id = "items.read.fallback",
            label = "Read fallback payload",
            resourceId = "fallback-payload",
            intent = ActionIntent.read,
            binding = readAction().binding.copy(path = "/ocs/v2.php/apps/example/api/items-fallback"),
            fallbackOnly = true,
        )
        val preferred = readAction().copy(fallbackActionIds = listOf(fallback.id))
        val descriptor = descriptor(preferred).copy(actions = listOf(preferred, fallback))

        val records = executeDynamicReadWithFallback(descriptor, preferred.id) { action ->
            if (action.id == preferred.id) {
                response("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[]}""")
            } else {
                response(
                    """
                    {
                      "ocs": {
                        "meta": {"status":"ok","statuscode":100},
                        "data": {
                          "items": [{"id":"one"},{"id":"two"}],
                          "allItemIds": ["one","two"],
                          "total": 2
                        }
                      }
                    }
                    """.trimIndent(),
                )
            }
        }

        assertEquals(listOf("one", "two"), records.map(NativeRecord::id))
    }

    @Test
    fun `verified fallback exposes a useful bounded server error instead of the primary 500`() = runBlocking {
        val fallback = readAction().copy(
            id = "messages.list.legacy",
            binding = readAction().binding.copy(
                path = "/ocs/v2.php/apps/example/api/messages-legacy",
                ocs = null,
            ),
            fallbackOnly = true,
        )
        val preferred = readAction().copy(fallbackActionIds = listOf(fallback.id))
        val descriptor = descriptor(preferred).copy(actions = listOf(preferred, fallback))

        val failure = assertFailsWith<IllegalStateException> {
            executeDynamicReadWithFallback(descriptor, preferred.id) { action ->
                if (action.id == preferred.id) {
                    response(
                        """{"ocs":{"meta":{"status":"failure","statuscode":996,"message":"Internal Server Error"},"data":[]}}""",
                        status = 500,
                    )
                } else {
                    response("""{"status":"fail","data":{"message":"mailbox 4 is not cached"}}""", status = 400)
                }
            }
        }

        assertTrue(failure.message.orEmpty().contains("has not been synchronized"))
        assertFalse(failure.message.orEmpty().contains("mailbox 4"))
        assertFalse(failure.message.orEmpty().contains("HTTP 500"))
    }

    @Test
    fun `verified fallback carries an equivalent parent id from path to renamed query`() {
        val fallback = readAction().copy(
            id = "messages.list.legacy",
            fallbackOnly = true,
            binding = readAction().binding.copy(
                path = "/ocs/v2.php/apps/example/api/messages",
                pathParameters = emptyList(),
                queryParameters = listOf(
                    HttpParameter(
                        name = "mailboxId",
                        required = true,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
            ),
        )
        val preferred = readAction().copy(
            fallbackActionIds = listOf(fallback.id),
            binding = readAction().binding.copy(
                path = "/ocs/v2.php/apps/example/api/mailboxes/{id}/messages",
                pathParameters = listOf(
                    HttpParameter(
                        name = "id",
                        required = true,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
            ),
        )

        assertEquals(
            mapOf("id" to "73", "mailboxId" to "73"),
            remapReadFallbackValues(preferred, fallback, mapOf("id" to "73")),
        )
    }

    @Test
    fun `typed object rows are canonicalized and exact get recovery is executable`() {
        val provenance = listOf(
            Provenance(
                ProvenanceKind.verifiedAppPackage,
                "signed package",
                "Verified signed contract",
            ),
        )
        val pathParameter = HttpParameter(
            name = "itemId",
            required = true,
            schema = json.parseToJsonElement("""{"type":"integer"}"""),
            source = ParameterSource.resourceField,
        )
        val path = "/ocs/v2.php/apps/example/api/items/{itemId}/share"
        val read = readAction().copy(
            id = "item-shares.read",
            intent = ActionIntent.read,
            binding = readAction().binding.copy(
                path = path,
                pathParameters = listOf(pathParameter),
                queryParameters = emptyList(),
                body = null,
            ),
            provenance = provenance,
        )
        val bodySchema = json.parseToJsonElement(
            """
            {
              "type":"object",
              "required":["shares"],
              "properties":{
                "shares":{
                  "type":"array",
                  "format":"$DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT",
                  "minItems":1,
                  "maxItems":4,
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["uid","permission"],
                    "properties":{
                      "uid":{"type":"string","minLength":1},
                      "permission":{"type":"string","enum":["view","edit"]}
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val replacement = read.copy(
            id = "item-share",
            label = "Share item",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            resultRecoveryActionId = read.id,
            binding = read.binding.copy(
                method = HttpMethod.PUT,
                body = HttpBody("application/json", true, bodySchema),
            ),
        )
        val descriptor = descriptor(replacement).copy(actions = listOf(read, replacement))

        val request = buildDynamicApiRequest(
            descriptor,
            replacement,
            values = mapOf(
                "itemId" to "7",
                "shares" to """[ { "permission":"edit", "uid":"alice" } ]""",
            ),
        )
        assertEquals(
            """{"shares":[{"uid":"alice","permission":"edit"}]}""",
            requireNotNull(request.body).decodeToString(),
        )
        assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor,
                replacement,
                values = mapOf(
                    "itemId" to "7",
                    "shares" to """[{"uid":"alice","permission":"owner"}]""",
                ),
            )
        }

        val recovery = buildDynamicResultRecoveryRequest(
            descriptor = descriptor,
            mutation = replacement,
            values = mapOf("itemId" to "7"),
        )
        assertEquals("/ocs/v2.php/apps/example/api/items/7/share", recovery?.relativePath)
        assertEquals(NextcloudApiMethod.GET, recovery?.method)
    }

    @Test
    fun `signed operational sync route becomes a bounded native recovery request`() {
        val sync = readAction().copy(
            id = "mailboxes.sync",
            resourceId = "sync",
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            binding = readAction().binding.copy(
                method = HttpMethod.POST,
                path = "/apps/example/api/mailboxes/{id}/sync",
                pathParameters = listOf(
                    HttpParameter(
                        name = "id",
                        required = true,
                        schema = json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
                queryParameters = listOf(
                    HttpParameter(
                        name = "init",
                        required = false,
                        schema = json.parseToJsonElement("""{"type":"boolean"}"""),
                        source = ParameterSource.userInput,
                    ),
                ),
                ocs = null,
            ),
            provenance = listOf(
                Provenance(
                    ProvenanceKind.verifiedAppPackage,
                    "signed package",
                    "Verified static operational route",
                ),
            ),
        )
        val descriptor = descriptor(sync).copy(
            endpointPolicy = descriptor(sync).endpointPolicy.copy(
                approvedApiPrefixes = listOf("/apps/example/api"),
            ),
        )

        val request = buildDynamicRefreshRecoveryRequest(descriptor, mapOf("id" to "73"))

        assertEquals("/apps/example/api/mailboxes/73/sync", request?.relativePath)
        assertEquals(mapOf("init" to "true"), request?.queryParameters)
        assertTrue(request?.ocsApiRequest == true)
        assertTrue(NextcloudApiResponse(409, byteArrayOf(), null, null).acceptedDynamicRefresh())
    }

    private fun descriptor(action: DynamicAction) = DynamicAppDescriptor(
        descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
        app = AppIdentity("example", "Example", "1.0"),
        endpointPolicy = EndpointPolicy(
            serverOrigin = "https://cloud.example.test",
            approvedApiPrefixes = listOf("/ocs/v2.php/apps/example/api"),
        ),
        resources = listOf(
            DynamicResource(
                id = action.resourceId,
                label = action.resourceId,
                collection = true,
                confidence = Confidence.high,
            ),
        ),
        actions = listOf(action),
    )

    private fun createAction(
        bodySchema: String = REQUEST_BODY_SCHEMA,
        auth: List<AuthRequirement> = listOf(AuthRequirement("basicAuth", AuthKind.basic)),
    ) = DynamicAction(
        id = "items.create",
        label = "Create item",
        resourceId = "items",
        intent = ActionIntent.create,
        risk = ActionRisk.mutating,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.POST,
            path = "/ocs/v2.php/apps/example/api/projects/{projectId}/items",
            pathParameters = listOf(
                HttpParameter(
                    name = "projectId",
                    required = true,
                    schema = json.parseToJsonElement("""{"type":"string"}"""),
                    source = ParameterSource.resourceField,
                ),
            ),
            queryParameters = listOf(
                HttpParameter(
                    name = "limit",
                    required = true,
                    schema = json.parseToJsonElement("""{"type":"integer"}"""),
                    source = ParameterSource.userInput,
                ),
                HttpParameter(
                    name = "owner",
                    required = true,
                    schema = json.parseToJsonElement("""{"type":"string"}"""),
                    source = ParameterSource.runtimeContext,
                ),
            ),
            body = HttpBody(
                contentType = "application/json",
                required = true,
                schema = json.parseToJsonElement(bodySchema),
            ),
            auth = auth,
            ocs = OcsMetadata(
                apiRequestHeader = true,
                responseDataPointer = "/ocs/data",
                responseMetaPointer = "/ocs/meta",
                formatQueryParameter = "format",
            ),
        ),
        confidence = Confidence.high,
        provenance = listOf(
            Provenance(
                kind = ProvenanceKind.advertisedOpenApi,
                source = "/apps/example/openapi.json",
                detail = "Declared operation",
            ),
        ),
    )

    private fun readAction(
        dataPointer: String = "/ocs/data",
        metaPointer: String = "/ocs/meta",
    ) = DynamicAction(
        id = "items.list",
        label = "List items",
        resourceId = "items",
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.GET,
            path = "/ocs/v2.php/apps/example/api/items",
            auth = listOf(AuthRequirement("basicAuth", AuthKind.basic)),
            ocs = OcsMetadata(
                apiRequestHeader = true,
                responseDataPointer = dataPointer,
                responseMetaPointer = metaPointer,
                formatQueryParameter = "format",
            ),
        ),
        confidence = Confidence.high,
    )

    private fun response(
        body: String,
        status: Int = 200,
        contentType: String? = "application/json; charset=utf-8",
    ) = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = contentType,
        etag = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
        const val DISCOVERY_OPEN_API = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Example API","version":"1.0"},
              "servers":[{"url":"/ocs/v2.php/apps/example/api"}],
              "security":[{"basic_auth":[]}],
              "paths":{
                "/items":{
                  "get":{
                    "operationId":"example.items.list",
                    "summary":"List items",
                    "responses":{
                      "200":{
                        "description":"OK",
                        "content":{
                          "application/json":{
                            "schema":{
                              "type":"array",
                              "items":{
                                "type":"object",
                                "properties":{
                                  "id":{"type":"integer","readOnly":true},
                                  "title":{"type":"string"}
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              },
              "components":{
                "securitySchemes":{
                  "basic_auth":{"type":"http","scheme":"basic"}
                }
              }
            }
        """
        const val REQUEST_BODY_SCHEMA = """
            {
              "type":"object",
              "required":["title"],
              "properties":{
                "title":{"type":"string"},
                "count":{"type":"integer"},
                "enabled":{"type":"boolean"}
              }
            }
        """
    }
}
