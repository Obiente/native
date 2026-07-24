package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicMailRelationshipCompilerTest {
    @Test
    fun `required identity queries and nested collection paths form distinct navigation resources`() {
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("communications", "Communications", "1.0"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/ocs/v2.php/apps/communications"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "/apps/communications/openapi.json",
                    document = Json.parseToJsonElement(MAIL_LIKE_OPEN_API),
                    trust = OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

        assertEquals(setOf("account", "mailboxes", "messages"), descriptor.resources.map { it.id }.toSet())
        assertTrue(descriptor.resources.all(DynamicResource::collection))
        assertTrue(descriptor.resources.single { it.id == "mailboxes" }.fields.isEmpty())
        assertTrue(descriptor.resources.single { it.id == "messages" }.fields.isEmpty())
        assertFalse(descriptor.links.any { link ->
            val actionId = (link.target as? DynamicLinkTarget.Action)?.actionId ?: return@any false
            descriptor.actions.single { it.id == actionId }.resourceId == link.resourceId
        })

        val accountPlan = descriptor.planDynamicNavigation()
        assertEquals(listOf("account"), accountPlan.rootDestinations.map { it.resourceId })

        val mailboxDestination = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(resourceId = "account", recordId = "account-7"),
        ).contextualChildDestinations.single()
        assertEquals(
            mailboxDestination,
            descriptor.preferredSemanticContextualChild(
                DynamicResourceRecordContext(resourceId = "account", recordId = "account-7"),
            ),
        )
        assertEquals("mailboxes", mailboxDestination.resourceId)
        assertEquals(mapOf("accountId" to "account-7"), mailboxDestination.pathParameterValues)

        val messageDestination = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "mailboxes",
                recordId = "mailbox-9",
                parameterValues = mailboxDestination.pathParameterValues,
            ),
        ).contextualChildDestinations.single()
        assertEquals(
            messageDestination,
            descriptor.preferredSemanticContextualChild(
                DynamicResourceRecordContext(
                    resourceId = "mailboxes",
                    recordId = "mailbox-9",
                    parameterValues = mailboxDestination.pathParameterValues,
                ),
            ),
        )
        assertEquals("messages", messageDestination.resourceId)
        assertEquals("account-7", messageDestination.pathParameterValues["accountId"])
        assertEquals("mailbox-9", assertNotNull(messageDestination.pathParameterValues["id"]))
    }

    private companion object {
        val MAIL_LIKE_OPEN_API = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/ocs/v2.php/apps/communications/account/list": {
                  "get": {
                    "operationId": "account_api-list",
                    "tags": ["account_api"],
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": { "application/json": { "schema": {
                          "type": "object",
                          "properties": { "ocs": { "type": "object", "properties": {
                            "data": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Account" } }
                          } } }
                        } } }
                      }
                    }
                  }
                },
                "/ocs/v2.php/apps/communications/ocs/mailboxes": {
                  "get": {
                    "operationId": "mailboxes_api-list",
                    "tags": ["mailboxes_api"],
                    "parameters": [
                      { "name": "accountId", "in": "query", "required": true, "schema": { "type": "integer" } }
                    ],
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": { "application/json": { "schema": {
                          "type": "object",
                          "properties": { "ocs": { "type": "object", "properties": {
                            "data": { "type": "object", "additionalProperties": { "type": "object" } }
                          } } }
                        } } }
                      }
                    }
                  }
                },
                "/ocs/v2.php/apps/communications/ocs/mailboxes/{mailboxId}/messages": {
                  "get": {
                    "operationId": "mailboxes_api-list-messages",
                    "tags": ["mailboxes_api"],
                    "parameters": [
                      { "name": "mailboxId", "in": "path", "required": true, "schema": { "type": "integer" } }
                    ],
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": { "application/json": { "schema": {
                          "type": "object",
                          "properties": { "ocs": { "type": "object", "properties": {
                            "data": { "type": "object", "additionalProperties": { "type": "object" } }
                          } } }
                        } } }
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "Account": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "integer" },
                      "email": { "type": "string" }
                    }
                  },
                  "Mailbox": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "integer" },
                      "name": { "type": "string" }
                    }
                  },
                  "Message": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "integer" },
                      "subject": { "type": "string" }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
