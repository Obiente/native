package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `fallback labels humanize camel case and acronym identifiers`() {
        val openApi = OPEN_API.replace(
            """
            "TableInput": {
              "type": "object",
              "required": ["title"],
              "properties": {
                "title": { "type": "string" },
                "description": { "type": "string" }
              }
            }
            """.trimIndent().prependIndent("      "),
            """
            "TableInput": {
              "type": "object",
              "required": ["title"],
              "properties": {
                "title": { "type": "string" },
                "description": { "type": "string" },
                "sortOrder": { "type": "integer" },
                "deleteOnDoneDefault": { "type": "boolean" },
                "iconURLValue": { "type": "string" }
              }
            }
            """.trimIndent().prependIndent("      "),
        )

        val descriptor = DynamicAppDescriptorCompiler().compile(input(openApi))

        val labels = descriptor.forms.single().fields.associate { field -> field.fieldId to field.label }
        assertEquals("Sort Order", labels["sortOrder"])
        assertEquals("Delete On Done Default", labels["deleteOnDoneDefault"])
        assertEquals("Icon URL Value", labels["iconURLValue"])
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `collection writes inherit the resource identity of the get on the same route`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Pantry-shaped contract","version":"1"},
              "paths":{
                "/ocs/v2.php/apps/example/api/houses/{houseId}/lists":{
                  "get":{
                    "operationId":"checklist-index-lists",
                    "tags":["Lists"],
                    "parameters":[
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{
                        "id":{"type":"integer"},
                        "name":{"type":"string"}
                      }}
                    }}}}}
                  },
                  "post":{
                    "operationId":"checklist-create-list",
                    "tags":["Checklist"],
                    "parameters":[
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{
                      "type":"object",
                      "required":["name"],
                      "properties":{"name":{"type":"string"}}
                    }}}},
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"id":{"type":"integer"},"name":{"type":"string"}}
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
                    listOf("/ocs/v2.php/apps/example"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    "/ocs/v2.php/apps/example/openapi.json",
                    Json.parseToJsonElement(document),
                    OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

        val list = descriptor.actions.single { it.id == "checklist-index-lists" }
        val create = descriptor.actions.single { it.id == "checklist-create-list" }
        assertEquals("lists", list.resourceId)
        assertEquals(list.resourceId, create.resourceId)
        assertEquals(ActionIntent.create, create.intent)
        assertEquals(listOf("houseId"), create.binding.pathParameters.map { it.name })
        assertEquals("lists", descriptor.forms.single { it.actionId == create.id }.resourceId)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `record mutations inherit the resource identity of the get on the same route`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Controller-tagged records","version":"1"},
              "paths":{
                "/ocs/v2.php/apps/example/api/houses/{houseId}/lists/{listId}":{
                  "get":{
                    "operationId":"checklist-show-list",
                    "tags":["Lists"],
                    "parameters":[
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}},
                      {"name":"listId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"id":{"type":"integer"},"name":{"type":"string"}}
                    }}}}}
                  },
                  "patch":{
                    "operationId":"checklist-update-list",
                    "tags":["Checklist"],
                    "parameters":[
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}},
                      {"name":"listId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"name":{"type":"string"}}
                    }}}},
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"id":{"type":"integer"},"name":{"type":"string"}}
                    }}}}}
                  },
                  "delete":{
                    "operationId":"checklist-delete-list",
                    "tags":["Checklist"],
                    "parameters":[
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}},
                      {"name":"listId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"204":{"description":"Deleted"}}
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
                    listOf("/ocs/v2.php/apps/example"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    "/ocs/v2.php/apps/example/openapi.json",
                    Json.parseToJsonElement(document),
                    OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

        val show = descriptor.actions.single { it.id == "checklist-show-list" }
        val update = descriptor.actions.single { it.id == "checklist-update-list" }
        val delete = descriptor.actions.single { it.id == "checklist-delete-list" }
        assertEquals("lists", show.resourceId)
        assertEquals(show.resourceId, update.resourceId)
        assertEquals(show.resourceId, delete.resourceId)
        assertEquals(ActionIntent.update, update.intent)
        assertEquals(ActionIntent.delete, delete.intent)
        assertEquals("lists", descriptor.forms.single { it.actionId == update.id }.resourceId)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `state collections and their transitions retain the parent collection resource`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"State collections","version":"1"},
              "paths":{
                "/api/teams/{teamId}/entries":{
                  "get":{
                    "operationId":"entry-index",
                    "tags":["Entries"],
                    "parameters":[
                      {"name":"teamId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{
                        "id":{"type":"integer"},
                        "name":{"type":"string"},
                        "deletedAt":{"type":"string","nullable":true}
                      }}
                    }}}}}
                  }
                },
                "/api/teams/{teamId}/entries/trash":{
                  "get":{
                    "operationId":"entry-index-deleted",
                    "tags":["Trash entries"],
                    "parameters":[
                      {"name":"teamId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{
                        "id":{"type":"integer"},
                        "name":{"type":"string"},
                        "deletedAt":{"type":"string","nullable":true}
                      }}
                    }}}}}
                  }
                },
                "/api/teams/{teamId}/entries/{entryId}/restore":{
                  "post":{
                    "operationId":"entry-restore",
                    "tags":["Entries"],
                    "parameters":[
                      {"name":"teamId","in":"path","required":true,"schema":{"type":"integer"}},
                      {"name":"entryId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"Restored"}}
                  }
                },
                "/api/teams/{teamId}/entries/{entryId}/permanent":{
                  "delete":{
                    "operationId":"entry-delete-permanently",
                    "tags":["Entries"],
                    "parameters":[
                      {"name":"teamId","in":"path","required":true,"schema":{"type":"integer"}},
                      {"name":"entryId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"204":{"description":"Deleted"}}
                  }
                }
              }
            }
        """.trimIndent()
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("example", "Example", "1"),
                endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
                advertisedOpenApi = AdvertisedOpenApi(
                    "/api/openapi.json",
                    Json.parseToJsonElement(document),
                    OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

        val active = descriptor.actions.single { it.id == "entry-index" }
        val trash = descriptor.actions.single { it.id == "entry-index-deleted" }
        listOf(
            "entry-restore",
            "entry-delete-permanently",
        ).forEach { actionId ->
            assertEquals(active.resourceId, descriptor.actions.single { it.id == actionId }.resourceId)
        }
        assertEquals(active.resourceId, trash.resourceId)
        val activeLayout = descriptor.layouts.single { it.sourceActionId == active.id }
        val trashLayout = descriptor.layouts.single { it.sourceActionId == trash.id }
        assertNotEquals(activeLayout.id, trashLayout.id)
        assertEquals("Entries", activeLayout.title)
        assertEquals("Trash", trashLayout.title)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `command routes require proven read resource ownership`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Command ownership","version":"1"},
              "paths":{
                "/apps/example/api/tasks":{
                  "get":{
                    "operationId":"tasks-list",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{"id":{"type":"integer"},"done":{"type":"boolean"}}}
                    }}}}}
                  }
                },
                "/apps/example/api/tasks/{taskId}/toggle":{
                  "parameters":[{"name":"taskId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{
                    "operationId":"task-toggle",
                    "summary":"Toggle task done status",
                    "responses":{"200":{"description":"OK"}}
                  }
                },
                "/apps/example/api/projects":{
                  "get":{
                    "operationId":"projects-list",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{"id":{"type":"integer"},"name":{"type":"string"}}}
                    }}}}}
                  }
                },
                "/apps/example/api/projects/automation/{automationId}/toggle":{
                  "parameters":[{"name":"automationId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{
                    "operationId":"automation-toggle",
                    "summary":"Toggle automation done status",
                    "responses":{"200":{"description":"OK"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        val actions = descriptor.actions.associateBy(DynamicAction::id)
        val tasks = assertNotNull(actions["tasks-list"])
        val taskToggle = assertNotNull(actions["task-toggle"])
        val projects = assertNotNull(actions["projects-list"])
        val unrelatedToggle = assertNotNull(actions["automation-toggle"])

        assertEquals(ActionEffect.toggle, taskToggle.effect)
        assertEquals(tasks.resourceId, taskToggle.resourceId)
        assertEquals(ActionEffect.toggle, unrelatedToggle.effect)
        assertNotEquals(projects.resourceId, unrelatedToggle.resourceId)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `bare toggle vocabulary does not imply completion semantics`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Generic toggle","version":"1"},
              "paths":{
                "/apps/example/api/switches":{
                  "get":{
                    "operationId":"switches-list",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{"id":{"type":"integer"},"enabled":{"type":"boolean"}}}
                    }}}}}
                  }
                },
                "/apps/example/api/switches/{switchId}/toggle":{
                  "parameters":[{"name":"switchId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{
                    "operationId":"switch-toggle",
                    "summary":"Toggle switch",
                    "responses":{"200":{"description":"OK"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        val toggle = descriptor.actions.single { action -> action.id == "switch-toggle" }

        assertEquals(ActionEffect.execute, toggle.effect)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `destructive post vocabulary always requires confirmation`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Destructive commands","version":"1"},
              "paths":{
                "/apps/example/api/records/{recordId}/remove":{
                  "parameters":[{"name":"recordId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{
                    "operationId":"record-remove",
                    "summary":"Remove record",
                    "responses":{"200":{"description":"Removed"}}
                  }
                },
                "/apps/example/api/artifacts/{artifactId}/destroy":{
                  "parameters":[{"name":"artifactId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{
                    "operationId":"artifact-destroy",
                    "summary":"Destroy artifact",
                    "responses":{"200":{"description":"Destroyed"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        listOf("record-remove", "artifact-destroy").forEach { actionId ->
            val action = descriptor.actions.single { candidate -> candidate.id == actionId }
            assertEquals(ActionRisk.destructive, action.risk, actionId)
            assertTrue(action.requiresConfirmation, actionId)
        }
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
    fun `recipe actions recover useful forms when an external schema is unavailable`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Recipes","version":"1"},
              "paths":{
                "/apps/example/api/v1/recipes":{
                  "get":{
                    "operationId":"getAllRecipes",
                    "summary":"Get all recipes",
                    "tags":["Recipes"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array","items":{"type":"object","properties":{"id":{"type":"string"}}}
                    }}}}}
                  },
                  "post":{
                    "operationId":"newRecipe",
                    "summary":"Create a new recipe",
                    "tags":["Recipes"],
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{}}}},
                    "responses":{"201":{"description":"Created"}}
                  }
                },
                "/apps/example/api/v1/import":{
                  "post":{
                    "operationId":"import",
                    "summary":"Import a recipe using schema.org metadata from a website URL",
                    "tags":["Recipes"],
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{}}}},
                    "responses":{"201":{"description":"Created"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        val create = descriptor.actions.single { it.id == "newrecipe" }
        val import = descriptor.actions.single { it.id == "import" }

        assertEquals(
            listOf(
                "cookTime",
                "description",
                "keywords",
                "name",
                "prepTime",
                "recipeCategory",
                "recipeIngredient",
                "recipeInstructions",
                "recipeYield",
                "tool",
            ),
            descriptor.forms.single { it.actionId == create.id }.fields.map(FormField::fieldId),
        )
        assertEquals(
            listOf("url"),
            descriptor.forms.single { it.actionId == import.id }.fields.map(FormField::fieldId),
        )
        assertTrue((assertNotNull(create.binding.body).schema as JsonObject).containsKey("properties"))
        assertTrue((assertNotNull(import.binding.body).schema as JsonObject).containsKey("properties"))
        assertEquals("recipes", import.resourceId)
        assertEquals(
            setOf("import", "newrecipe"),
            descriptor.planDynamicNavigation().rootFormActions.map { it.actionId }.toSet(),
        )
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `recipe action recovery preserves undeclared and non object bodies`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Recipes","version":"1"},
              "paths":{
                "/apps/example/api/v1/recipes/draft":{
                  "post":{
                    "operationId":"createRecipeDraft",
                    "summary":"Create a recipe draft",
                    "tags":["Recipes"],
                    "responses":{"201":{"description":"Created"}}
                  }
                },
                "/apps/example/api/v1/recipes/batch":{
                  "post":{
                    "operationId":"createRecipeBatch",
                    "summary":"Create recipes in a batch",
                    "tags":["Recipes"],
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{
                      "type":"array","items":{"type":"string"}
                    }}}},
                    "responses":{"201":{"description":"Created"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))

        assertNull(descriptor.actions.single { it.id == "createrecipedraft" }.binding.body)
        assertEquals(
            "array",
            (assertNotNull(
                descriptor.actions.single { it.id == "createrecipebatch" }.binding.body,
            ).schema as JsonObject)["type"]?.jsonPrimitive?.content,
        )
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `taxonomy filter responses use the filtered subject resource`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Recipes","version":"1"},
              "paths":{
                "/apps/example/api/v1/categories":{
                  "get":{
                    "operationId":"getAllCategories",
                    "summary":"Get all categories",
                    "tags":["Categories"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array","items":{"type":"string"}
                    }}}}}
                  }
                },
                "/apps/example/api/v1/keywords":{
                  "get":{
                    "operationId":"getAllKeywords",
                    "summary":"Get all keywords",
                    "tags":["Tags"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array","items":{"type":"string"}
                    }}}}}
                  }
                },
                "/apps/example/api/v1/categories/{category}":{
                  "parameters":[
                    {"name":"category","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"recipesInCategory",
                    "summary":"Get all recipes in a category",
                    "tags":["Categories"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{}}}}}
                  }
                },
                "/apps/example/api/v1/tags/{keywords}":{
                  "parameters":[
                    {"name":"keywords","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"recipesWithKeyword",
                    "summary":"Get all recipes with a keyword",
                    "tags":["Tags"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{}}}}}
                  }
                },
                "/apps/example/api/v1/labels/{label}":{
                  "parameters":[
                    {"name":"label","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"recipesWithLabel",
                    "summary":"Get all recipes with a label",
                    "tags":["Labels"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{}}}}}
                  }
                },
                "/apps/example/api/v1/staging/{id}":{
                  "parameters":[
                    {"name":"id","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"recipesInStaging",
                    "summary":"Get a recipe from staging",
                    "tags":["Staging"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{}}}}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))

        assertEquals(
            setOf("recipes"),
            descriptor.actions
                .filter { it.id in setOf("recipesincategory", "recipeswithkeyword", "recipeswithlabel") }
                .map(DynamicAction::resourceId)
                .toSet(),
        )
        assertEquals(
            setOf("categories.recipes.collection", "keywords.recipes.collection"),
            descriptor.links.map(DynamicLink::id).toSet(),
        )
        assertEquals(
            setOf("recipesincategory", "recipeswithkeyword", "recipeswithlabel"),
            descriptor.layouts
                .filter { it.resourceId == "recipes" }
                .mapNotNull(DynamicLayout::sourceActionId)
                .toSet(),
        )
        assertEquals(
            "staging",
            descriptor.actions.single { it.id == "recipesinstaging" }.resourceId,
        )
        val keywordRecipes = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "keywords",
                recordId = "sweet",
                actionSafeIdentity = false,
            ),
        ).contextualChildDestinations.single()
        assertEquals("recipeswithkeyword", keywordRecipes.actionId)
        assertEquals(mapOf("keywords" to "sweet"), keywordRecipes.pathParameterValues)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `nested plural routes and verified request fields enrich generic collection semantics`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Example","version":"1"},
              "paths":{
                "/apps/example/api/houses":{
                  "get":{
                    "operationId":"house-index",
                    "tags":["house"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array","items":{"type":"object","properties":{"id":{"type":"integer"}}}
                    }}}}}
                  }
                },
                "/apps/example/api/houses/{houseId}":{
                  "parameters":[
                    {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}}
                  ],
                  "get":{
                    "operationId":"house-show",
                    "tags":["house"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object","properties":{"id":{"type":"integer"}}
                    }}}}}
                  }
                },
                "/apps/example/api/houses/{houseId}/lists":{
                  "parameters":[
                    {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}}
                  ],
                  "get":{
                    "operationId":"list-index",
                    "tags":["lists"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array","items":{"type":"object","properties":{
                        "id":{"type":"integer"},
                        "name":{"type":"string"},
                        "color":{}
                      }}
                    }}}}}
                  },
                  "post":{
                    "operationId":"list-create",
                    "tags":["lists"],
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{
                      "type":"object",
                      "required":["name"],
                      "properties":{"name":{"type":"string"},"color":{"type":"string"}}
                    }}}},
                    "responses":{"201":{"description":"Created"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        val house = descriptor.actions.single { action -> action.id == "house-index" }
        val lists = descriptor.actions.single { action -> action.id == "list-index" }
        val listResource = descriptor.resources.single { resource -> resource.id == lists.resourceId }

        assertEquals(house.resourceId, descriptor.actions.single { it.id == "house-show" }.resourceId)
        assertEquals(FieldKind.string, listResource.fields.single { field -> field.id == "color" }.kind)
        assertTrue(
            descriptor.links.any { link ->
                link.resourceId == house.resourceId &&
                    (link.target as? DynamicLinkTarget.Action)?.actionId == lists.id
            },
            "links=${descriptor.links}",
        )
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `binary artwork reads are not exposed as JSON detail views`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Recipes","version":"1"},
              "paths":{
                "/apps/example/api/v1/recipes/{id}/image":{
                  "parameters":[
                    {"name":"id","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "get":{
                    "operationId":"getRecipeImage",
                    "summary":"Get the recipe image",
                    "tags":["Recipes"],
                    "responses":{"200":{"description":"Image","content":{
                      "image/jpeg":{"schema":{"type":"string","format":"binary"}}
                    }}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))

        assertEquals(listOf("getrecipeimage"), descriptor.actions.map(DynamicAction::id))
        assertTrue(descriptor.layouts.isEmpty())
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
    fun `generic discovery withholds credential generation mutations`() {
        val credentialWrite = OPEN_API.replace(
            "\"operationId\": \"tables.create\"",
            "\"operationId\": \"settings.createUserKey\"",
        )

        val descriptor = DynamicAppDescriptorCompiler().compile(input(credentialWrite))

        assertTrue(descriptor.actions.all { action -> action.binding.method == HttpMethod.GET })
        assertTrue(descriptor.forms.isEmpty())
        assertTrue(
            descriptor.warnings.any { warning ->
                warning.code == "ignored-sensitive-credential-write"
            },
        )
    }

    @Test
    fun `generic discovery withholds ambiguous result mutations from every semantic source`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Ambiguous results","version":"1"},
              "paths":{
                "/apps/example/api/records":{
                  "get":{
                    "operationId":"records-list",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{"id":{"type":"integer"},"name":{"type":"string"}}}
                    }}}}}
                  }
                },
                "/apps/example/api/messages":{
                  "post":{
                    "operationId":"messages.sendNow",
                    "summary":"Dispatch notification",
                    "responses":{"202":{"description":"Accepted"}}
                  }
                },
                "/apps/example/api/records/{recordId}/collaboration":{
                  "parameters":[{"name":"recordId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{
                    "operationId":"collaboration-create",
                    "summary":"Share record",
                    "responses":{"201":{"description":"Created"}}
                  }
                },
                "/apps/example/api/records/{recordId}/merge":{
                  "parameters":[{"name":"recordId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{
                    "operationId":"records-combine",
                    "summary":"Combine records",
                    "responses":{"200":{"description":"Combined"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))

        assertEquals(listOf("records-list"), descriptor.actions.map(DynamicAction::id))
        assertEquals(
            3,
            descriptor.warnings.count { warning ->
                warning.code == "ignored-ambiguous-result-write"
            },
        )
        assertTrue(descriptor.forms.isEmpty())
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `ambiguous result vocabulary uses exact words and does not hide safe neighbors or reads`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Safe semantic boundaries","version":"1"},
              "paths":{
                "/apps/example/api/share":{
                  "get":{
                    "operationId":"share-read",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"enabled":{"type":"boolean"}}
                    }}}}}
                  }
                },
                "/apps/example/api/senders":{
                  "post":{
                    "operationId":"sender-create",
                    "summary":"Create sender",
                    "responses":{"201":{"description":"Created"}}
                  }
                },
                "/apps/example/api/preferences/shared":{
                  "patch":{
                    "operationId":"shared-preferences-update",
                    "summary":"Update shared preferences",
                    "responses":{"200":{"description":"Updated"}}
                  }
                },
                "/apps/example/api/mergeable/{recordId}":{
                  "parameters":[{"name":"recordId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "put":{
                    "operationId":"mergeable-record-update",
                    "summary":"Update mergeable record",
                    "responses":{"200":{"description":"Updated"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))

        assertEquals(
            setOf(
                "mergeable-record-update",
                "sender-create",
                "share-read",
                "shared-preferences-update",
            ),
            descriptor.actions.map(DynamicAction::id).toSet(),
        )
        assertTrue(
            descriptor.warnings.none { warning ->
                warning.code == "ignored-ambiguous-result-write"
            },
        )
        assertTrue(descriptor.validationErrors().isEmpty())
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
    fun `only exact declared integer arrays receive the generic integer array format`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Scalar array contract","version":"1"},
              "paths":{
                "/apps/example/api/assignments":{
                  "post":{
                    "operationId":"assignments-update",
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{
                        "integerIds":{"type":"array","items":{"type":"integer"}},
                        "textIds":{"type":"array","items":{"type":"string"}},
                        "decimalIds":{"type":"array","items":{"type":"number"}},
                        "objectIds":{"type":"array","items":{"type":"object"}},
                        "mixedIds":{"type":"array","items":{"oneOf":[
                          {"type":"integer"},{"type":"string"}
                        ]}},
                        "untypedIds":{"type":"array"}
                      }
                    }}}},
                    "responses":{"200":{"description":"Updated"}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        val action = descriptor.actions.single { it.id == "assignments-update" }
        val fields = descriptor.forms.single { it.actionId == action.id }.fields.associateBy(FormField::fieldId)
        val bodyProperties = ((requireNotNull(action.binding.body).schema as JsonObject)["properties"] as JsonObject)

        assertEquals(DYNAMIC_INTEGER_ARRAY_FORMAT, fields.getValue("integerIds").format)
        assertEquals(DYNAMIC_STRING_ARRAY_FORMAT, fields.getValue("textIds").format)
        assertNull(fields.getValue("decimalIds").format)
        assertNull(fields.getValue("objectIds").format)
        assertNull(fields.getValue("mixedIds").format)
        assertNull(fields.getValue("untypedIds").format)
        assertTrue(bodyProperties["integerIds"].isExactDynamicIntegerArraySchema())
        assertFalse(bodyProperties["textIds"].isExactDynamicIntegerArraySchema())

        val nativeField = descriptor.toNativeAppSchema()
            .resources
            .single { resource -> resource.id == action.resourceId }
            .fields
            .single { field -> field.id == "integerIds" }
        assertEquals(FieldKind.integer, nativeField.kind)
        assertEquals(DYNAMIC_INTEGER_ARRAY_FORMAT, nativeField.format)
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

    @Test
    fun `root and parent scoped singleton reads retain distinct layouts`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Scoped settings","version":"1"},
              "paths":{
                "/apps/example/api/preferences":{
                  "get":{
                    "operationId":"preferences-user",
                    "tags":["Preferences"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"theme":{"type":"string"}}
                    }}}}}
                  }
                },
                "/apps/example/api/workspaces/{workspaceId}/preferences":{
                  "parameters":[
                    {"name":"workspaceId","in":"path","required":true,"schema":{"type":"integer"}}
                  ],
                  "get":{
                    "operationId":"preferences-workspace",
                    "tags":["Preferences"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"notifications":{"type":"boolean"}}
                    }}}}}
                  }
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        val preferenceLayouts = descriptor.layouts.filter { layout ->
            layout.resourceId == descriptor.actions.single { action ->
                action.id == "preferences-user"
            }.resourceId
        }

        assertEquals(2, preferenceLayouts.size)
        assertEquals(
            setOf("preferences-user", "preferences-workspace"),
            preferenceLayouts.mapNotNull(DynamicLayout::sourceActionId).toSet(),
        )
        assertEquals(2, preferenceLayouts.map(DynamicLayout::id).distinct().size)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `write effects come from contract semantics instead of the HTTP method`() {
        val document = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Semantic actions","version":"1"},
              "paths":{
                "/apps/example/api/widgets":{
                  "get":{
                    "operationId":"widgets-list",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{"id":{"type":"integer"},"name":{"type":"string"}}}
                    }}}}}
                  },
                  "post":{
                    "operationId":"widgets-create",
                    "summary":"Create widget",
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{
                      "type":"object",
                      "required":["name"],
                      "properties":{"name":{"type":"string"}}
                    }}}},
                    "responses":{"201":{"description":"Created"}}
                  },
                  "patch":{
                    "operationId":"widgets-update",
                    "summary":"Update widgets",
                    "responses":{"200":{"description":"Updated"}}
                  }
                },
                "/apps/example/api/widgets/reorder":{
                  "post":{"operationId":"widgets-reorder","summary":"Reorder widgets","responses":{"200":{"description":"OK"}}}
                },
                "/apps/example/api/widgets/trash":{
                  "delete":{"operationId":"widgets-empty-trash","summary":"Empty trash","responses":{"204":{"description":"Empty"}}}
                },
                "/apps/example/api/widgets/{widgetId}":{
                  "parameters":[{"name":"widgetId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "get":{
                    "operationId":"widget-show",
                    "tags":["WidgetDetails"],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"id":{"type":"integer"},"name":{"type":"string"}}
                    }}}}}
                  }
                },
                "/apps/example/api/widgets/{widgetId}/toggle":{
                  "parameters":[{"name":"widgetId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{"operationId":"widget-toggle","summary":"Toggle widget done status","responses":{"200":{"description":"OK"}}}
                },
                "/apps/example/api/widgets/{widgetId}/restore":{
                  "parameters":[{"name":"widgetId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{"operationId":"widget-restore","summary":"Restore widget","responses":{"200":{"description":"OK"}}}
                },
                "/apps/example/api/widgets/{widgetId}/archive":{
                  "parameters":[{"name":"widgetId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{"operationId":"widget-archive","summary":"Archive widget","responses":{"200":{"description":"OK"}}}
                },
                "/apps/example/api/widgets/{widgetId}/unarchive":{
                  "parameters":[{"name":"widgetId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{"operationId":"widget-unarchive","summary":"Unarchive widget","responses":{"200":{"description":"OK"}}}
                },
                "/apps/example/api/widgets/{widgetId}/copy":{
                  "parameters":[{"name":"widgetId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{"operationId":"widget-copy","summary":"Copy widget","responses":{"201":{"description":"Copied"}}}
                },
                "/apps/example/api/widgets/{widgetId}/permanent":{
                  "parameters":[{"name":"widgetId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "delete":{"operationId":"widget-delete-permanently","summary":"Permanently delete widget","responses":{"204":{"description":"Deleted"}}}
                },
                "/apps/example/api/widgets/batch/delete":{
                  "post":{"operationId":"widgets-batch-delete","summary":"Delete selected widgets","responses":{"200":{"description":"OK"}}}
                },
                "/apps/example/api/widgets/upload":{
                  "post":{"operationId":"widgets-upload","summary":"Upload widgets","responses":{"200":{"description":"OK"}}}
                },
                "/apps/example/api/workspaces/{workspaceId}":{
                  "parameters":[{"name":"workspaceId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "get":{
                    "operationId":"workspace-show",
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"object",
                      "properties":{"id":{"type":"integer"},"name":{"type":"string"}}
                    }}}}}
                  }
                },
                "/apps/example/api/workspaces/{workspaceId}/leave":{
                  "parameters":[{"name":"workspaceId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "post":{"operationId":"workspace-leave","summary":"Leave workspace","responses":{"200":{"description":"OK"}}}
                }
              }
            }
        """.trimIndent()

        val descriptor = DynamicAppDescriptorCompiler().compile(exampleInput(document))
        val actions = descriptor.actions.associateBy(DynamicAction::id)

        fun assertAction(
            id: String,
            effect: ActionEffect,
            intent: ActionIntent,
            risk: ActionRisk,
            requiresConfirmation: Boolean,
        ) {
            val action = assertNotNull(actions[id])
            assertEquals(effect, action.effect, id)
            assertEquals(intent, action.intent, id)
            assertEquals(risk, action.risk, id)
            assertEquals(requiresConfirmation, action.requiresConfirmation, id)
        }

        assertAction("widgets-create", ActionEffect.create, ActionIntent.create, ActionRisk.mutating, false)
        assertAction("widgets-update", ActionEffect.update, ActionIntent.update, ActionRisk.mutating, false)
        assertAction("widgets-reorder", ActionEffect.reorder, ActionIntent.execute, ActionRisk.mutating, false)
        assertAction("widget-toggle", ActionEffect.toggle, ActionIntent.execute, ActionRisk.mutating, false)
        assertAction("widget-restore", ActionEffect.restore, ActionIntent.execute, ActionRisk.mutating, false)
        assertAction("widget-archive", ActionEffect.archive, ActionIntent.execute, ActionRisk.mutating, false)
        assertAction("widget-unarchive", ActionEffect.unarchive, ActionIntent.execute, ActionRisk.mutating, false)
        assertAction("widget-copy", ActionEffect.copy, ActionIntent.execute, ActionRisk.mutating, false)
        assertAction(
            "widget-delete-permanently",
            ActionEffect.permanentDelete,
            ActionIntent.delete,
            ActionRisk.destructive,
            true,
        )
        assertAction(
            "widgets-empty-trash",
            ActionEffect.empty,
            ActionIntent.delete,
            ActionRisk.destructive,
            true,
        )
        assertAction(
            "widgets-batch-delete",
            ActionEffect.batch,
            ActionIntent.execute,
            ActionRisk.destructive,
            true,
        )
        assertAction("widgets-upload", ActionEffect.upload, ActionIntent.execute, ActionRisk.mutating, false)
        assertAction("workspace-leave", ActionEffect.leave, ActionIntent.execute, ActionRisk.destructive, true)
        assertEquals("widgets", actions.getValue("widget-show").resourceId)
        assertTrue(
            actions.values
                .filter { action -> action.id.startsWith("widget") }
                .all { action -> action.resourceId == "widgets" },
        )
        assertEquals(actions.getValue("workspace-show").resourceId, actions.getValue("workspace-leave").resourceId)
        assertTrue(
            descriptor.resources.none { resource ->
                resource.id in setOf(
                    "archive",
                    "batch",
                    "copy",
                    "leave",
                    "permanent",
                    "reorder",
                    "restore",
                    "toggle",
                    "unarchive",
                    "upload",
                )
            },
        )
        assertTrue(descriptor.validationErrors().isEmpty())
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

    private fun exampleInput(openApi: String) = DynamicDiscoveryInput(
        app = AppIdentity("example", "Example", "1"),
        endpointPolicy = EndpointPolicy(
            serverOrigin = "https://cloud.example.test",
            approvedApiPrefixes = listOf("/apps/example/api"),
        ),
        advertisedOpenApi = AdvertisedOpenApi(
            documentUrl = "/apps/example/openapi.json",
            document = Json.parseToJsonElement(openApi),
            trust = OpenApiTrust.sameOriginAdvertisement,
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
