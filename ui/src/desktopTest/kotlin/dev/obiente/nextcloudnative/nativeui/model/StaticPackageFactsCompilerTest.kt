package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Package facts emitted by static contract extraction flow into descriptor actions as data. The
 * document mirrors the extractor's output shape for a synthetic package; it changes no admission.
 */
class StaticPackageFactsCompilerTest {
    @Test
    fun packageFactsTypedFieldsAndMultipartBodiesFlowIntoTheDescriptor() {
        val descriptor = compile(OpenApiTrust.nextcloudSignedAppPackage, "https://apps.nextcloud.com/packages/surveys_x#lib/Controller")

        val list = descriptor.actions.single { it.binding.path == "/ocs/v2.php/apps/surveys_x/api/v3/forms" }
        val facts = requireNotNull(list.contractFacts)
        assertEquals(DynamicRouteSource.attribute, facts.routeSource)
        assertEquals(DynamicAccessLevel.authenticatedUser, facts.access)
        assertEquals(true, facts.csrfRequired)
        assertEquals(DynamicRateLimit(5, 100), facts.userRateLimit)
        assertEquals(DynamicPasswordConfirmation(strict = true), facts.passwordConfirmation)
        assertEquals(
            listOf(DynamicOpaqueAttribute("OCA\\SurveysX\\Attribute\\Level", mapOf("minimumLevel" to "OCA\\SurveysX\\App::LEVEL_EDITOR"))),
            facts.appAttributes,
        )
        val formFields = descriptor.resources.single { it.id == list.resourceId }.fields.associateBy(DynamicField::id)
        assertEquals(listOf("0", "1", "2"), formFields.getValue("access").enumValues)
        assertTrue(formFields.getValue("title").required)

        val update = descriptor.actions.single { it.binding.method == HttpMethod.PUT && it.binding.path.endsWith("/notes/{id}") }
        assertTrue(requireNotNull(update.contractFacts).mergeHint)
        val noteFields = descriptor.resources.single { it.id == update.resourceId }.fields.associateBy(DynamicField::id)
        assertTrue(noteFields.getValue("content").nullable)

        val assign = descriptor.actions.single { it.binding.path.endsWith("/assignLabel") }
        assertEquals(listOf("cardId"), requireNotNull(assign.contractFacts).requestPathParameters)

        val upload = descriptor.actions.single { it.binding.path.endsWith("/attachments") }
        assertEquals("multipart/form-data", upload.binding.body?.contentType)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun aSameOriginAdvertisementCannotAssertPackageFacts() {
        val descriptor = compile(OpenApiTrust.sameOriginAdvertisement, "https://cloud.example.test/openapi.json")
        assertTrue(descriptor.actions.isNotEmpty())
        descriptor.actions.forEach { action -> assertNull(action.contractFacts, action.id) }
    }

    private fun compile(trust: OpenApiTrust, documentUrl: String): DynamicAppDescriptor =
        DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("surveys_x", "Surveys", "5.4.1"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/surveys_x", "/ocs/v2.php/apps/surveys_x"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = documentUrl,
                    document = Json.parseToJsonElement(DOCUMENT),
                    trust = trust,
                ),
            ),
        )

    private companion object {
        const val API_HEADER = """{"name":"OCS-APIRequest","in":"header","required":true,"schema":{"type":"boolean","default":true}}"""

        val DOCUMENT = """
            {
              "openapi": "3.0.3",
              "info": {"title": "surveys_x verified read-only routes", "version": "5.4.1"},
              "x-nextcloud-native-contract-kind": "verified-read-only-routes",
              "paths": {
                "/ocs/v2.php/apps/surveys_x/api/v3/forms": {
                  "get": {
                    "operationId": "ocs.api.getforms",
                    "parameters": [$API_HEADER],
                    "x-nextcloud-native-route-source": "attribute",
                    "x-nextcloud-native-policy": {
                      "access": "user", "csrfRequired": true, "cors": false,
                      "passwordConfirmation": {"strict": true},
                      "userRateLimit": {"limit": 5, "period": 100},
                      "appAttributes": [{"name": "OCA\\SurveysX\\Attribute\\Level", "arguments": {"minimumLevel": "OCA\\SurveysX\\App::LEVEL_EDITOR"}}]
                    },
                    "responses": {"200": {"description": "Declared 200 response", "content": {"application/json": {"schema": {
                      "type": "array",
                      "items": {"type": "object", "required": ["id", "title"], "properties": {
                        "id": {"type": "integer", "format": "int64"},
                        "title": {"type": "string"},
                        "access": {"type": "integer", "format": "int64", "enum": [0, 1, 2]}
                      }}
                    }}}}}
                  }
                },
                "/apps/surveys_x/notes/{id}": {
                  "put": {
                    "operationId": "route.notes.update",
                    "parameters": [{"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}}, $API_HEADER],
                    "x-nextcloud-native-verified-crud": true,
                    "x-nextcloud-native-route-source": "routes-file",
                    "x-nextcloud-native-merge-hint": "nullable-null-default-parameters",
                    "x-nextcloud-native-policy": {"access": "user", "csrfRequired": true, "cors": false},
                    "requestBody": {"required": false, "content": {"application/json": {"schema": {
                      "type": "object", "additionalProperties": false,
                      "properties": {"content": {"type": "string", "nullable": true}, "favorite": {"type": "boolean", "nullable": true}}
                    }}}},
                    "responses": {"200": {"description": "ok"}}
                  }
                },
                "/apps/surveys_x/cards/{cardId}/assignLabel": {
                  "put": {
                    "operationId": "route.cardapi.assignlabel",
                    "parameters": [
                      {"name": "cardId", "in": "path", "required": true, "schema": {"type": "string"}, "x-nextcloud-native-request-input": "request-parameter"},
                      $API_HEADER
                    ],
                    "x-nextcloud-native-verified-crud": true,
                    "x-nextcloud-native-resource-id": "cards",
                    "requestBody": {"required": true, "content": {"application/json": {"schema": {
                      "type": "object", "additionalProperties": false, "required": ["labelId"],
                      "properties": {"labelId": {"type": "integer"}}
                    }}}},
                    "responses": {"200": {"description": "ok"}}
                  }
                },
                "/apps/surveys_x/cards/{cardId}/attachments": {
                  "post": {
                    "operationId": "route.attachment.create",
                    "parameters": [{"name": "cardId", "in": "path", "required": true, "schema": {"type": "integer"}}, $API_HEADER],
                    "x-nextcloud-native-verified-crud": true,
                    "requestBody": {"required": true, "content": {"multipart/form-data": {"schema": {
                      "type": "object", "required": ["type", "file"],
                      "properties": {
                        "type": {"type": "string"},
                        "file": {"type": "string", "format": "binary", "x-nextcloud-native-request-input": "uploaded-file"}
                      }
                    }}}},
                    "responses": {"200": {"description": "ok"}}
                  }
                }
              }
            }
        """.trimIndent()
    }
}
