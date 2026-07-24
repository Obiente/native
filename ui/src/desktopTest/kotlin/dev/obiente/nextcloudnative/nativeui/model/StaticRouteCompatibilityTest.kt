package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticRouteCompatibilityTest {
    @Test
    fun deckStyleStaticRoutesCompileIntoNativeReadOnlySurfaces() {
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("deck", "Deck", "1.18.2"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf(
                        "/apps/deck",
                        "/ocs/v1.php/apps/deck",
                        "/ocs/v2.php/apps/deck",
                        "/index.php/apps/deck",
                    ),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/deck#appinfo/routes.php",
                    document = Json.parseToJsonElement(deckStyleReadContract),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )

        val boardsList = descriptor.actions.single { action ->
            action.binding.path == "/apps/deck/boards"
        }
        val boardDetail = descriptor.actions.single { action ->
            action.binding.path == "/apps/deck/boards/{boardId}"
        }
        assertEquals(HttpMethod.GET, boardsList.binding.method)
        assertEquals(ActionRisk.readOnly, boardsList.risk)
        assertEquals(HttpMethod.GET, boardDetail.binding.method)
        assertEquals(ActionRisk.readOnly, boardDetail.risk)
        assertEquals(3, descriptor.actions.size)
        assertTrue(descriptor.actions.all { action -> action.binding.method == HttpMethod.GET })
        assertTrue(descriptor.forms.isEmpty())
        assertTrue(descriptor.layouts.any { layout ->
            layout.sourceActionId == boardsList.id && layout.kind == LayoutKind.list
        })
        assertTrue(descriptor.layouts.any { layout ->
            layout.sourceActionId == boardDetail.id && layout.kind == LayoutKind.detail
        })
        assertTrue(
            descriptor.actions.flatMap(DynamicAction::provenance).any { source ->
                source.detail.contains("Derived read-only endpoints from verified static routes")
            },
        )
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun mergedContractKeepsVerifiedRouteAsHiddenRuntimeFallback() {
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("mail", "Mail", "5.10.9"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/mail"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/mail#openapi.json",
                    document = Json.parseToJsonElement(mergedReadFallbackContract),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )

        val preferred = descriptor.actions.single { it.binding.path == "/apps/mail/api/accounts" }
        val fallback = descriptor.actions.single { it.binding.path == "/apps/mail/accounts" }
        assertEquals(listOf(fallback.id), preferred.fallbackActionIds)
        assertTrue(fallback.fallbackOnly)
        assertTrue(fallback.binding.apiRequestHeader)
        assertTrue(descriptor.layouts.none { it.sourceActionId == fallback.id })
        assertTrue(descriptor.links.none { (it.target as? DynamicLinkTarget.Action)?.actionId == fallback.id })
        assertTrue(descriptor.toNativeAppSchema().actions.none { it.id == fallback.id })
        assertEquals(
            NativeComponent.mailbox,
            descriptor.toNativeAppSchema().views.single { it.sourceActionId == preferred.id }.component,
        )
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun verifiedStaticCrudCompilesIntoClosedConfirmedForms() {
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("example", "Example", "1.0.0"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/example"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/example#appinfo/routes.php",
                    document = Json.parseToJsonElement(verifiedCrudContract),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )

        val create = descriptor.actions.single { action -> action.binding.method == HttpMethod.POST }
        val update = descriptor.actions.single { action -> action.binding.method == HttpMethod.PUT }
        val patch = descriptor.actions.single { action -> action.binding.method == HttpMethod.PATCH }
        val delete = descriptor.actions.single { action -> action.binding.method == HttpMethod.DELETE }

        assertTrue(listOf(create, update, patch, delete).all(DynamicAction::requiresConfirmation))
        assertEquals(ActionRisk.mutating, create.risk)
        assertEquals(ActionRisk.mutating, update.risk)
        assertEquals(ActionRisk.mutating, patch.risk)
        assertEquals(ActionRisk.destructive, delete.risk)
        assertEquals(
            setOf("title", "enabled"),
            descriptor.forms.single { form -> form.actionId == update.id }
                .fields
                .mapTo(linkedSetOf(), FormField::fieldId),
        )
        assertEquals(
            setOf("title"),
            descriptor.forms.single { form -> form.actionId == patch.id }
                .fields
                .mapTo(linkedSetOf(), FormField::fieldId),
        )
        assertTrue(descriptor.forms.single { form -> form.actionId == delete.id }.fields.isEmpty())
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun musicReadCollectionsBecomeReusableMediaLibrarySurfaces() {
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("music", "Music", "3.1.1"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/music"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/music#appinfo/routes.php",
                    document = Json.parseToJsonElement(musicReadContract),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )

        val schema = descriptor.toNativeAppSchema()
        assertTrue(schema.views.isNotEmpty())
        assertTrue(schema.views.filterNot { it.resourceId == "settings" }
            .all { view -> view.component == NativeComponent.mediaLibrary })
        assertEquals(NativeComponent.detail, schema.views.single { it.resourceId == "settings" }.component)
        assertTrue(descriptor.planDynamicNavigation().rootDestinations.any { it.resourceId == "settings" })
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    private val deckStyleReadContract = """
        {
          "openapi": "3.0.3",
          "info": { "title": "deck verified read-only routes", "version": "1.18.2" },
          "x-nextcloud-native-contract-kind": "verified-read-only-routes",
          "paths": {
            "/apps/deck/boards": {
              "get": {
                "operationId": "route.board.index",
                "responses": {
                  "200": {
                    "description": "Successful JSON response",
                    "content": {
                      "application/json": {
                        "schema": { "type": "array", "items": { "type": "object" } }
                      }
                    }
                  }
                }
              }
            },
            "/apps/deck/boards/{boardId}": {
              "get": {
                "operationId": "route.board.read",
                "parameters": [
                  { "name": "boardId", "in": "path", "required": true, "schema": { "type": "string" } }
                ],
                "responses": {
                  "200": {
                    "description": "Successful JSON response",
                    "content": {
                      "application/json": { "schema": { "type": "object" } }
                    }
                  }
                }
              }
            },
            "/apps/deck/stacks/{boardId}": {
              "get": {
                "operationId": "route.stack.index",
                "parameters": [
                  { "name": "boardId", "in": "path", "required": true, "schema": { "type": "string" } }
                ],
                "responses": {
                  "200": {
                    "description": "Successful JSON response",
                    "content": {
                      "application/json": {
                        "schema": { "type": "array", "items": { "type": "object" } }
                      }
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private val mergedReadFallbackContract = """
        {
          "openapi": "3.0.3",
          "info": { "title": "Mail", "version": "5.10.9" },
          "x-nextcloud-native-contract-kind": "openapi-with-verified-read-routes",
          "x-nextcloud-native-verified-read-route-count": 1,
          "paths": {
            "/apps/mail/api/accounts": {
              "get": {
                "operationId": "account_api-list",
                "x-nextcloud-native-read-fallback-operation-ids": ["route.accounts.index"],
                "responses": {
                  "200": {
                    "description": "Accounts",
                    "content": { "application/json": { "schema": { "type": "array", "items": { "type": "object" } } } }
                  }
                }
              }
            },
            "/apps/mail/accounts": {
              "get": {
                "operationId": "route.accounts.index",
                "x-nextcloud-native-verified-read-route": true,
                "x-nextcloud-native-fallback-for-operation-id": "account_api-list",
                "parameters": [
                  { "name": "OCS-APIRequest", "in": "header", "required": true, "schema": { "type": "boolean", "default": true } }
                ],
                "responses": {
                  "200": {
                    "description": "Accounts",
                    "content": { "application/json": { "schema": { "type": "array", "items": { "type": "object" } } } }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private val verifiedCrudContract = """
        {
          "openapi": "3.0.3",
          "info": { "title": "verified routes", "version": "1.0.0" },
          "x-nextcloud-native-contract-kind": "verified-read-only-routes",
          "paths": {
            "/apps/example/items": {
              "post": {
                "operationId": "route.item.create",
                "x-nextcloud-native-verified-crud": true,
                "requestBody": { "required": true, "content": { "application/json": { "schema": {
                  "type": "object", "additionalProperties": false,
                  "properties": {
                    "title": { "type": "string" },
                    "labels": { "type": "array", "items": { "type": "string" } }
                  },
                  "required": ["title"]
                } } } },
                "responses": { "200": { "description": "Created" } }
              }
            },
            "/apps/example/items/{id}": {
              "parameters": [
                { "name": "id", "in": "path", "required": true, "schema": { "type": "integer" } }
              ],
              "put": {
                "operationId": "route.item.update",
                "x-nextcloud-native-verified-crud": true,
                "requestBody": { "required": true, "content": { "application/json": { "schema": {
                  "type": "object", "additionalProperties": false,
                  "properties": {
                    "title": { "type": "string" },
                    "enabled": { "type": "boolean" }
                  },
                  "required": ["title", "enabled"]
                } } } },
                "responses": { "200": { "description": "Updated" } }
              },
              "patch": {
                "operationId": "route.item.patch",
                "x-nextcloud-native-verified-crud": true,
                "requestBody": { "required": false, "content": { "application/json": { "schema": {
                  "type": "object", "additionalProperties": false,
                  "properties": { "title": { "type": "string" } }
                } } } },
                "responses": { "200": { "description": "Patched" } }
              },
              "delete": {
                "operationId": "route.item.delete",
                "x-nextcloud-native-verified-crud": true,
                "responses": { "200": { "description": "Deleted" } }
              }
            }
          }
        }
    """.trimIndent()

    private val musicReadContract = """
        {
          "openapi": "3.0.3",
          "info": { "title": "music verified read-only routes", "version": "3.1.1" },
          "x-nextcloud-native-contract-kind": "verified-read-only-routes",
          "paths": {
            "/apps/music/api/artists": {
              "get": {
                "operationId": "route.shivaapi.artists",
                "responses": { "200": { "description": "Artists", "content": {
                  "application/json": { "schema": { "type": "array", "items": { "type": "object" } } }
                } } }
              }
            },
            "/apps/music/api/albums": {
              "get": {
                "operationId": "route.shivaapi.albums",
                "responses": { "200": { "description": "Albums", "content": {
                  "application/json": { "schema": { "type": "array", "items": { "type": "object" } } }
                } } }
              }
            },
            "/apps/music/api/tracks": {
              "get": {
                "operationId": "route.shivaapi.tracks",
                "responses": { "200": { "description": "Tracks", "content": {
                  "application/json": { "schema": { "type": "array", "items": { "type": "object" } } }
                } } }
              }
            },
            "/apps/music/api/settings": {
              "get": {
                "operationId": "route.setting.getall",
                "responses": { "200": { "description": "Settings", "content": {
                  "application/json": { "schema": { "type": "array", "items": { "type": "object" } } }
                } } }
              }
            }
          }
        }
    """.trimIndent()
}
