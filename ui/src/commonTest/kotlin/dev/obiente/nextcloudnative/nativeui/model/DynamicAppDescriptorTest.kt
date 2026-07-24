package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DynamicAppDescriptorTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun decodesAndValidatesRustDynamicDescriptorWireContract() {
        val descriptor = json.decodeFromString<DynamicAppDescriptor>(DESCRIPTOR_JSON)

        assertEquals("1.0", descriptor.descriptorVersion)
        assertEquals(HttpMethod.GET, descriptor.actions.single().binding.method)
        assertIs<DynamicLinkTarget.FieldUrl>(descriptor.links.single().target)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun rejectsUnapprovedEndpointAfterDeserialization() {
        val descriptor = json.decodeFromString<DynamicAppDescriptor>(DESCRIPTOR_JSON)
        val escaped = descriptor.copy(
            actions = descriptor.actions.map { action ->
                action.copy(binding = action.binding.copy(path = "/remote.php/dav/files/alice"))
            },
        )

        assertTrue(escaped.validationErrors().any { it.startsWith("Unapproved action endpoint") })
        assertFailsWith<IllegalArgumentException> { escaped.requireValid() }
    }

    @Test
    fun rejectsObservedActionTamperedIntoWrite() {
        val descriptor = json.decodeFromString<DynamicAppDescriptor>(DESCRIPTOR_JSON)
        val tampered = descriptor.copy(
            actions = descriptor.actions.map { action ->
                action.copy(binding = action.binding.copy(method = HttpMethod.POST))
            },
        )

        assertTrue(tampered.validationErrors().any { it.contains("lacks trusted provenance") })
    }

    @Test
    fun mapsValidatedDescriptorIntoCurrentPresentationSchema() {
        val descriptor = json.decodeFromString<DynamicAppDescriptor>(DESCRIPTOR_JSON)

        val schema = descriptor.toNativeAppSchema()

        assertEquals("0.1", schema.schemaVersion)
        assertEquals(NativeComponent.mediaGrid, schema.views.single().component)
        assertEquals("chores-list-observed", schema.actions.single().binding.operationId)
        assertTrue(schema.warnings.any { it.code == "dynamic-executor-required" })
    }

    private companion object {
        val DESCRIPTOR_JSON = """
            {
              "descriptorVersion": "1.0",
              "app": { "id": "chores", "name": "Chores", "version": "0.9.0" },
              "endpointPolicy": {
                "serverOrigin": "https://cloud.example.test",
                "approvedApiPrefixes": ["/index.php/apps/chores/api"]
              },
              "capabilities": [],
              "permissions": [
                {
                  "id": "auth.nextcloud-session",
                  "label": "Nextcloud session authentication",
                  "kind": "authenticatedSession",
                  "state": "required",
                  "confidence": "medium",
                  "provenance": {
                    "kind": "successfulReadObservation",
                    "source": "/index.php/apps/chores/api/v1/chores",
                    "detail": "Successful JSON read"
                  }
                }
              ],
              "resources": [
                {
                  "id": "chores",
                  "label": "Chores",
                  "collection": true,
                  "fields": [
                    {
                      "id": "title",
                      "label": "Title",
                      "kind": "string",
                      "required": true,
                      "readOnly": true,
                      "nullable": false,
                      "multiple": false,
                      "format": null,
                      "enumValues": null,
                      "confidence": "medium",
                      "provenance": []
                    },
                    {
                      "id": "iconUrl",
                      "label": "Icon URL",
                      "kind": "image",
                      "required": false,
                      "readOnly": true,
                      "nullable": true,
                      "multiple": false,
                      "format": "uri",
                      "enumValues": null,
                      "confidence": "medium",
                      "provenance": []
                    }
                  ],
                  "capabilityIds": [],
                  "permissionIds": [],
                  "confidence": "medium",
                  "provenance": []
                }
              ],
              "layouts": [
                {
                  "id": "chores.list",
                  "title": "Chores",
                  "resourceId": "chores",
                  "kind": "list",
                  "fields": [
                    { "fieldId": "title", "role": "title", "visible": true }
                  ],
                  "sourceActionId": "chores-list-observed",
                  "confidence": "medium",
                  "provenance": []
                }
              ],
              "links": [
                {
                  "id": "chores.icon.link",
                  "label": "Icon",
                  "resourceId": "chores",
                  "sourceFieldId": "iconUrl",
                  "target": { "kind": "fieldUrl", "allowExternal": false },
                  "confidence": "medium",
                  "provenance": []
                }
              ],
              "forms": [],
              "actions": [
                {
                  "id": "chores-list-observed",
                  "label": "List chores",
                  "resourceId": "chores",
                  "intent": "list",
                  "risk": "readOnly",
                  "requiresConfirmation": false,
                  "binding": {
                    "method": "GET",
                    "path": "/index.php/apps/chores/api/v1/chores",
                    "pathParameters": [],
                    "queryParameters": [],
                    "body": null,
                    "auth": [
                      { "scheme": "nextcloud-session", "kind": "nextcloudSession", "scopes": [] }
                    ],
                    "ocs": null
                  },
                  "capabilityIds": [],
                  "permissionIds": ["auth.nextcloud-session"],
                  "confidence": "medium",
                  "provenance": [
                    {
                      "kind": "successfulReadObservation",
                      "source": "/index.php/apps/chores/api/v1/chores",
                      "detail": "Successful JSON read"
                    }
                  ]
                }
              ],
              "warnings": []
            }
        """.trimIndent()
    }
}
