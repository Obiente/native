package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicAppDescriptorCompilerTest {
    @Test
    fun `specialized route constants discard stale inherited path parameters`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Deck","version":"1"},
              "servers":[{"url":"/apps/deck"}],
              "paths":{
                "/api/v1.0/boards":{
                  "parameters":[
                    {"name":"apiVersion","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"board.index",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{"type":"array","items":{"type":"object"}}}}}}
                  }
                }
              }
            }
        """.trimIndent()
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("deck", "Deck", "1"),
                endpointPolicy = EndpointPolicy(
                    "https://cloud.example.test",
                    listOf("/apps/deck"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    "/apps/deck/openapi.json",
                    Json.parseToJsonElement(document),
                    OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

        val action = descriptor.actions.single()
        assertEquals("/apps/deck/api/v1.0/boards", action.binding.path)
        assertTrue(action.binding.pathParameters.isEmpty())
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `embedded path placeholders remain declared runtime inputs`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Deck","version":"1"},
              "servers":[{"url":"/apps/deck"}],
              "paths":{
                "/api/v{apiVersion}/boards":{
                  "parameters":[
                    {"name":"apiVersion","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"board.index",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{"type":"array","items":{"type":"object"}}}}}}
                  }
                }
              }
            }
        """.trimIndent()
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("deck", "Deck", "1"),
                endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/deck")),
                advertisedOpenApi = AdvertisedOpenApi(
                    "/apps/deck/openapi.json",
                    Json.parseToJsonElement(document),
                    OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

        assertEquals(listOf("apiVersion"), descriptor.actions.single().binding.pathParameters.map { it.name })
        assertTrue(descriptor.validationErrors().isEmpty())
        assertTrue(descriptor.planDynamicNavigation().rootDestinations.isEmpty())
    }

    @Test
    fun compilesRealisticListAndCreateOperationsWithRustContractParity() {
        val descriptor = DynamicAppDescriptorCompiler().compile(input(OPEN_API))

        assertEquals("1.0", descriptor.descriptorVersion)
        assertEquals(listOf(LayoutKind.list), descriptor.layouts.map(DynamicLayout::kind))
        assertEquals(setOf(HttpMethod.GET, HttpMethod.POST), descriptor.actions.map { it.binding.method }.toSet())
        val create = descriptor.actions.single { it.binding.method == HttpMethod.POST }
        assertEquals("application/json", assertNotNull(create.binding.body).contentType)
        assertEquals(AuthKind.basic, create.binding.auth.single().kind)
        assertTrue(assertNotNull(create.binding.ocs).apiRequestHeader)
        assertEquals(listOf("description", "title"), descriptor.forms.single().fields.map(FormField::fieldId))
        assertTrue(descriptor.links.single().target == DynamicLinkTarget.FieldUrl(allowExternal = false))
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun refusesCrossOriginServerAndUnapprovedPaths() {
        val crossOrigin = OPEN_API.replace(
            "\"/ocs/v2.php/apps/tables/api/2\"",
            "\"https://attacker.example/api\"",
        )
        assertFailsWith<IllegalArgumentException> {
            DynamicAppDescriptorCompiler().compile(input(crossOrigin))
        }

        val unapproved = OPEN_API.replace(
            "\"/ocs/v2.php/apps/tables/api/2\"",
            "\"/ocs/v2.php/apps/admin/api/2\"",
        )
        assertFailsWith<IllegalArgumentException> {
            DynamicAppDescriptorCompiler().compile(input(unapproved))
        }
    }

    @Test
    fun exactGitHubTagIsDistinctLowerTrustWhileEndpointPolicyStillApplies() {
        val linkedTag = input(
            openApi = OPEN_API,
            documentUrl = "https://raw.githubusercontent.com/nextcloud/tables/v1.2.3/openapi.json",
            trust = OpenApiTrust.appStoreLinkedExactGitHubTag,
        )

        val descriptor = DynamicAppDescriptorCompiler().compile(linkedTag)

        assertTrue(
            descriptor.actions.flatMap(DynamicAction::provenance)
                .any {
                    it.kind == ProvenanceKind.appStoreLinkedSourceTag &&
                        it.detail.contains("unsigned", ignoreCase = true) &&
                        it.source.contains("/v1.2.3/")
                },
        )
        assertTrue(descriptor.validationErrors().isEmpty())

        val unapproved = OPEN_API.replace(
            "\"/ocs/v2.php/apps/tables/api/2\"",
            "\"/ocs/v2.php/apps/admin/api/2\"",
        )
        assertFailsWith<IllegalArgumentException> {
            DynamicAppDescriptorCompiler().compile(
                input(
                    openApi = unapproved,
                    documentUrl = "https://raw.githubusercontent.com/nextcloud/tables/v1.2.3/openapi.json",
                    trust = OpenApiTrust.appStoreLinkedExactGitHubTag,
                ),
            )
        }
    }

    @Test
    fun cookbookReleaseServersWithEquivalentPathBasesCompileAgainstAuthenticatedOrigin() {
        val document = """
            {
              "openapi": "3.0.1",
              "info": { "title": "Nextcloud cookbook app", "version": "0.1.3" },
              "servers": [
                { "url": "http://localhost:8000" },
                {
                  "url": "{protocol}://{server}",
                  "variables": {
                    "protocol": { "default": "https" },
                    "server": { "default": "example.com" }
                  }
                }
              ],
              "paths": {
                "/apps/cookbook/api/v1/recipes": {
                  "get": {
                    "operationId": "getAllRecipes",
                    "summary": "Get all recipes",
                    "responses": {
                      "200": {
                        "description": "Recipe stubs",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "id": { "type": "integer" },
                                  "name": { "type": "string" }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val cookbookInput = DynamicDiscoveryInput(
            app = AppIdentity("cookbook", "Cookbook", "0.11.9"),
            endpointPolicy = EndpointPolicy(
                serverOrigin = "https://cloud.example.test",
                approvedApiPrefixes = listOf("/apps/cookbook/api"),
            ),
            advertisedOpenApi = AdvertisedOpenApi(
                documentUrl = "https://raw.githubusercontent.com/nextcloud/cookbook/v0.11.9/" +
                    "docs/dev/api/0.1.3/openapi-cookbook.yaml",
                document = Json.parseToJsonElement(document),
                trust = OpenApiTrust.appStoreLinkedExactGitHubTag,
            ),
        )

        val descriptor = DynamicAppDescriptorCompiler().compile(cookbookInput)

        assertEquals("/apps/cookbook/api/v1/recipes", descriptor.actions.single().binding.path)
        assertEquals(LayoutKind.list, descriptor.layouts.single().kind)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun terminalIdentityPathOverridesPluralDetailNameWithoutBreakingFilteredCollections() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Recipes","version":"1"},
              "paths":{
                "/apps/example/recipes/{id}":{
                  "parameters":[
                    {"name":"id","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"recipeDetails",
                    "tags":["Recipes"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"id":{"type":"string"},"steps":{"type":"array","items":{"type":"string"}}}
                    }}}}}
                  }
                },
                "/apps/example/category/{category}":{
                  "parameters":[
                    {"name":"category","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"recipesInCategory",
                    "tags":["Recipes"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array","items":{"type":"object","properties":{"id":{"type":"string"}}}
                    }}}}}
                  }
                }
              }
            }
        """.trimIndent()
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("example", "Example", "1"),
                endpointPolicy = EndpointPolicy(
                    "https://cloud.example.test",
                    listOf("/apps/example"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    "/apps/example/openapi.json",
                    Json.parseToJsonElement(document),
                    OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

        assertEquals(
            ActionIntent.read,
            descriptor.actions.single { action -> action.id == "recipedetails" }.intent,
        )
        assertEquals(
            ActionIntent.list,
            descriptor.actions.single { action -> action.id == "recipesincategory" }.intent,
        )
        assertTrue(descriptor.layouts.any { layout -> layout.kind == LayoutKind.detail })
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun conflictingOpenApiServerPathBasesRemainFailClosed() {
        val conflicting = OPEN_API.replace(
            "\"servers\": [{ \"url\": \"/ocs/v2.php/apps/tables/api/2\" }]",
            """"servers": [
                { "url": "https://one.example/ocs/v2.php/apps/tables/api/2" },
                { "url": "https://two.example/ocs/v2.php/apps/tables/api/3" }
            ]""",
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            DynamicAppDescriptorCompiler().compile(
                input(
                    openApi = conflicting,
                    documentUrl = "https://raw.githubusercontent.com/nextcloud/tables/v1.2.3/openapi.json",
                    trust = OpenApiTrust.appStoreLinkedExactGitHubTag,
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("conflicting path bases"))
    }

    @Test
    fun untrustedCrossOriginEquivalentServersCannotBeRebased() {
        val crossOrigin = OPEN_API.replace(
            "\"servers\": [{ \"url\": \"/ocs/v2.php/apps/tables/api/2\" }]",
            """"servers": [
                { "url": "https://one.example/ocs/v2.php/apps/tables/api/2" },
                { "url": "https://two.example/ocs/v2.php/apps/tables/api/2/" }
            ]""",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            DynamicAppDescriptorCompiler().compile(input(crossOrigin))
        }

        assertTrue(failure.message.orEmpty().contains("rebasing"))
    }

    @Test
    fun omitsUnnamedOpenApiWriteInsteadOfInventingAnAction() {
        val withoutWriteIdentity = OPEN_API.replace("\"operationId\": \"tables.create\",", "")

        val descriptor = DynamicAppDescriptorCompiler().compile(input(withoutWriteIdentity))

        assertTrue(descriptor.actions.all { it.binding.method == HttpMethod.GET })
        assertTrue(descriptor.forms.isEmpty())
        assertTrue(descriptor.warnings.any { it.code == "ignored-unnamed-write" })
    }

    @Test
    fun exposesBodylessAndQueryDrivenMutationsAsConfirmedNativeActions() {
        val withDeleteAndQuery = OPEN_API
            .replace(
                "\"post\": {",
                """
                    "delete": {
                      "operationId": "tables.deleteAll",
                      "summary": "Delete all tables",
                      "responses": { "204": { "description": "Deleted" } }
                    },
                    "post": {
                """.trimIndent(),
            )
            .replace(
                "\"requestBody\": {",
                """
                    "parameters": [
                      { "name": "template", "in": "query", "required": true,
                        "schema": { "type": "string", "title": "Template" } }
                    ],
                    "requestBody": {
                """.trimIndent(),
            )

        val descriptor = DynamicAppDescriptorCompiler().compile(input(withDeleteAndQuery))
        val delete = descriptor.actions.single { it.id == "tables-deleteall" }
        val deleteForm = descriptor.forms.single { it.actionId == delete.id }
        assertTrue(deleteForm.fields.isEmpty())
        assertEquals(ActionRisk.destructive, delete.risk)
        assertTrue(delete.requiresConfirmation)

        val create = descriptor.actions.single { it.id == "tables-create" }
        val createForm = descriptor.forms.single { it.actionId == create.id }
        assertEquals(listOf("description", "title", "template"), createForm.fields.map { it.fieldId })
        val mappedAction = descriptor.toNativeAppSchema().actions.single { it.id == create.id }
        val mappedInput = mappedAction.inputSchema as JsonObject
        assertEquals(
            setOf("description", "title", "template"),
            (mappedInput["properties"] as JsonObject).keys,
        )
        assertEquals(
            listOf("title", "template"),
            (mappedInput["required"] as JsonArray).map { it.toString().trim('"') },
        )
        assertEquals(listOf("template"), mappedAction.binding.queryParameterNames)
        assertEquals(listOf("template"), mappedAction.binding.requiredQueryParameterNames)
        assertEquals(setOf("description", "title"), mappedAction.binding.bodyFieldNames.toSet())
        assertEquals(listOf("title"), mappedAction.binding.requiredBodyFieldNames)
        assertEquals("application/json", mappedAction.binding.bodyContentType)
    }

    @Test
    fun rejectsLegacyOpenApiAndRemoteReferences() {
        assertFailsWith<IllegalArgumentException> {
            DynamicAppDescriptorCompiler().compile(input(OPEN_API.replace("3.0.3", "2.0")))
        }
        assertFailsWith<IllegalArgumentException> {
            DynamicAppDescriptorCompiler().compile(
                input(OPEN_API.replace("#/components/schemas/TableInput", "https://attacker.example/input.json")),
            )
        }
    }

    @Test
    fun nestedCollectionRelationshipsSurviveAGenericPreferredDisplaySurface() {
        val document = Json.parseToJsonElement(
            """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Inventory", "version": "1" },
                  "paths": {
                    "/api/catalogs": {
                      "get": {
                        "operationId": "list-catalogs",
                        "tags": ["catalogs"],
                        "responses": { "200": { "description": "OK", "content": {
                          "application/json": { "schema": { "type": "array", "items": {
                            "${'$'}ref": "#/components/schemas/Catalog"
                          } } }
                        } } }
                      }
                    },
                    "/api/attributes": {
                      "get": {
                        "operationId": "list-attributes",
                        "tags": ["attributes"],
                        "responses": { "200": { "description": "OK", "content": {
                          "application/json": { "schema": { "type": "array", "items": {
                            "${'$'}ref": "#/components/schemas/Attribute"
                          } } }
                        } } }
                      }
                    },
                    "/api/catalogs/{catalogId}/attributes": {
                      "get": {
                        "operationId": "list-catalog-attributes",
                        "tags": ["attributes"],
                        "parameters": [
                          { "name": "catalogId", "in": "path", "required": true,
                            "schema": { "type": "integer" } }
                        ],
                        "responses": { "200": { "description": "OK", "content": {
                          "application/json": { "schema": { "type": "array", "items": {
                            "${'$'}ref": "#/components/schemas/Attribute"
                          } } }
                        } } }
                      }
                    },
                    "/api/entries": {
                      "get": {
                        "operationId": "list-entries",
                        "tags": ["entries"],
                        "responses": { "200": { "description": "OK", "content": {
                          "application/json": { "schema": { "type": "array", "items": {
                            "${'$'}ref": "#/components/schemas/Entry"
                          } } }
                        } } }
                      }
                    },
                    "/api/catalogs/{catalogId}/entries": {
                      "get": {
                        "operationId": "list-catalog-entries",
                        "tags": ["entries"],
                        "parameters": [
                          { "name": "catalogId", "in": "path", "required": true,
                            "schema": { "type": "integer" } }
                        ],
                        "responses": { "200": { "description": "OK", "content": {
                          "application/json": { "schema": { "type": "array", "items": {
                            "${'$'}ref": "#/components/schemas/Entry"
                          } } }
                        } } }
                      }
                    }
                  },
                  "components": { "schemas": {
                    "Catalog": { "type": "object", "properties": {
                      "id": { "type": "integer" }, "title": { "type": "string" }
                    } },
                    "Attribute": { "type": "object", "properties": {
                      "id": { "type": "integer" }, "alias": { "type": "string" },
                      "title": { "type": "string" }, "type": { "type": "string" },
                      "position": { "type": "integer" }
                    } },
                    "Entry": { "type": "object", "properties": {
                      "id": { "type": "integer" }, "values": { "type": "object" }
                    } }
                  } }
                }
            """.trimIndent(),
        )
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("inventory", "Inventory", "1"),
                endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
                advertisedOpenApi = AdvertisedOpenApi("/apps/inventory/openapi.json", document),
            ),
        )

        assertEquals("list-attributes", descriptor.layouts.single { it.id == "attributes.list" }.sourceActionId)
        assertEquals(
            setOf("list-catalog-attributes", "list-catalog-entries"),
            descriptor.links.mapNotNull { link ->
                if (link.resourceId != "catalogs") return@mapNotNull null
                (link.target as? DynamicLinkTarget.Action)?.actionId
            }.toSet(),
        )
        val composite = descriptor.toNativeAppSchema().views.single { it.id == "catalogs.table" }
        assertNotNull(composite.compositeDataGrid)
    }

    @Test
    fun successfulJsonObservationProducesGetOnlyDescriptor() {
        val observedInput = DynamicDiscoveryInput(
            app = AppIdentity("chores", "Chores", "0.9"),
            endpointPolicy = EndpointPolicy(
                "https://cloud.example.test",
                listOf("/index.php/apps/chores/api"),
            ),
            successfulReads = listOf(
                SuccessfulReadObservation(
                    operationId = "chores.list.observed",
                    path = "/index.php/apps/chores/api/v1/chores",
                    status = 200,
                    contentType = "application/json",
                    response = Json.parseToJsonElement(
                        """{"data":[{"id":1,"title":"Bins","done":false},{"id":2,"title":"Kitchen","done":true}]}""",
                    ),
                    ocs = false,
                ),
            ),
        )

        val descriptor = DynamicAppDescriptorCompiler().compile(observedInput)

        assertTrue(descriptor.actions.all { it.binding.method == HttpMethod.GET })
        assertTrue(descriptor.resources.single().fields.all(DynamicField::readOnly))
        assertTrue(descriptor.forms.isEmpty())
    }

    private fun input(
        openApi: String,
        documentUrl: String = "/apps/tables/openapi.json",
        trust: OpenApiTrust = OpenApiTrust.sameOriginAdvertisement,
    ) = DynamicDiscoveryInput(
        app = AppIdentity("tables", "Tables", "1.2.3"),
        endpointPolicy = EndpointPolicy(
            serverOrigin = "https://cloud.example.test",
            approvedApiPrefixes = listOf("/ocs/v2.php/apps/tables/api"),
        ),
        advertisedOpenApi = AdvertisedOpenApi(
            documentUrl = documentUrl,
            document = Json.parseToJsonElement(openApi),
            trust = trust,
        ),
    )

    private companion object {
        val OPEN_API = """
            {
              "openapi": "3.0.3",
              "servers": [{ "url": "/ocs/v2.php/apps/tables/api/2" }],
              "security": [{ "basicAuth": [] }],
              "paths": {
                "/tables": {
                  "get": {
                    "operationId": "tables.list",
                    "summary": "List tables",
                    "tags": ["tables"],
                    "parameters": [
                      { "name": "format", "in": "query", "schema": { "type": "string" } }
                    ],
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "ocs": {
                                  "type": "object",
                                  "properties": {
                                    "data": {
                                      "type": "array",
                                      "items": { "${'$'}ref": "#/components/schemas/Table" }
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
                  "post": {
                    "operationId": "tables.create",
                    "summary": "Create table",
                    "tags": ["tables"],
                    "requestBody": {
                      "required": true,
                      "content": {
                        "application/json": {
                          "schema": { "${'$'}ref": "#/components/schemas/TableInput" }
                        }
                      }
                    },
                    "responses": { "201": { "description": "Created" } }
                  }
                }
              },
              "components": {
                "securitySchemes": {
                  "basicAuth": { "type": "http", "scheme": "basic" }
                },
                "schemas": {
                  "Table": {
                    "type": "object",
                    "required": ["id", "title"],
                    "properties": {
                      "id": { "type": "integer", "readOnly": true },
                      "title": { "type": "string" },
                      "description": { "type": "string" },
                      "iconUrl": { "type": "string", "format": "uri" }
                    }
                  },
                  "TableInput": {
                    "type": "object",
                    "required": ["title"],
                    "properties": {
                      "title": { "type": "string" },
                      "description": { "type": "string" }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
