package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptorCompiler
import dev.obiente.nextcloudnative.nativeui.model.DynamicDiscoveryInput
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.OpenApiTrust
import dev.obiente.nextcloudnative.nativeui.model.validationErrors
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicMultipartContractTest {
    @Test
    fun `signed description converts declared scalar fields into a bounded multipart body`() {
        val descriptor = compile(SIGNED_DESCRIPTION_CONTRACT, OpenApiTrust.nextcloudSignedAppPackage)
        val action = descriptor.actions.single { it.id == "upload-photo" }
        val body = assertNotNull(action.binding.body)
        val schema = body.schema as JsonObject
        val properties = schema["properties"] as JsonObject
        val image = properties["image"] as JsonObject

        assertEquals("multipart/form-data", body.contentType)
        assertEquals("binary", (image["format"] as JsonPrimitive).contentOrNull)
        assertEquals("image/*", (image["contentMediaType"] as JsonPrimitive).contentOrNull)
        assertEquals(
            FieldKind.file,
            descriptor.forms.single { it.actionId == action.id }.fields.single { it.fieldId == "image" }.kind,
        )
        assertEquals(setOf("folderId", "caption", "image"), properties.keys)

        val itemImage = assertNotNull(
            descriptor.actions.single { it.id == "upload-item-image" }.binding.body,
        )
        val itemProperties = (itemImage.schema as JsonObject)["properties"] as JsonObject
        assertEquals("multipart/form-data", itemImage.contentType)
        assertEquals(setOf("image"), itemProperties.keys)
    }

    @Test
    fun `unsigned descriptions cannot change JSON transport semantics`() {
        val descriptor = compile(SIGNED_DESCRIPTION_CONTRACT, OpenApiTrust.sameOriginAdvertisement)
        val body = descriptor.actions.single { it.id == "upload-photo" }.binding.body
        val properties = (body?.schema as? JsonObject)?.get("properties") as? JsonObject
        val explicit = descriptor.actions.single { it.id == "upload-document" }

        assertEquals("application/json", body?.contentType)
        assertTrue(properties?.containsKey("image") == false)
        assertEquals("multipart/form-data", explicit.binding.body?.contentType)
        assertEquals(
            FieldKind.file,
            descriptor.forms.single { it.actionId == explicit.id }.fields.single { it.fieldId == "document" }.kind,
        )
    }

    @Test
    fun `multipart request retains picker capability and scalar parts without raw local data`() {
        val descriptor = compile(SIGNED_DESCRIPTION_CONTRACT, OpenApiTrust.nextcloudSignedAppPackage)
        val action = descriptor.actions.single { it.id == "upload-photo" }
        val selected = localUploadFile(
            selectionId = "0123456789abcdef",
            displayName = "pantry test.png",
            mimeType = "image/png",
            sizeBytes = 1234,
        )

        val request = buildDynamicApiRequest(
            descriptor = descriptor,
            action = action,
            values = mapOf(
                "image" to encodeDynamicLocalUploadSelection(selected),
                "folderId" to "7",
                "caption" to "A safe caption",
            ),
        )
        val multipart = assertNotNull(request.multipartBody)

        assertNull(request.body)
        assertNull(request.contentType)
        assertEquals("image", multipart.fileFieldName)
        assertEquals(selected, multipart.file)
        assertEquals(
            listOf(MultipartTextField("caption", "A safe caption"), MultipartTextField("folderId", "7")),
            multipart.textFields.sortedBy(MultipartTextField::name),
        )
    }

    @Test
    fun `multipart picker capability is retained for rejection retry and released after success`() {
        val selected = localUploadFile(
            selectionId = "0123456789abcdef",
            displayName = "pantry test.png",
            mimeType = "image/png",
            sizeBytes = 1234,
        )
        val released = mutableListOf<LocalUploadFile>()
        val rejected = NativeActionExecutionResult.Failure(
            message = "The server rejected the upload.",
            outcome = NativeActionFailureOutcome.Rejected,
        )

        repeat(2) {
            releaseMultipartUploadAfterSuccess(rejected, selected, released::add)
        }

        assertTrue(released.isEmpty())

        releaseMultipartUploadAfterSuccess(
            NativeActionExecutionResult.Success("Uploaded."),
            selected,
            released::add,
        )

        assertEquals(listOf(selected), released)
    }

    @Test
    fun `multipart request rejects typed paths and contracts with multiple binary fields`() {
        val descriptor = compile(SIGNED_DESCRIPTION_CONTRACT, OpenApiTrust.nextcloudSignedAppPackage)
        val action = descriptor.actions.single { it.id == "upload-photo" }

        assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor = descriptor,
                action = action,
                values = mapOf("image" to "/tmp/pantry.png"),
            )
        }

        val ambiguous = compile(AMBIGUOUS_MULTIPART_CONTRACT, OpenApiTrust.sameOriginAdvertisement)
        val ambiguousAction = ambiguous.actions.single { it.id == "upload-two-files" }
        assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor = ambiguous,
                action = ambiguousAction,
                values = emptyMap(),
            )
        }
    }

    @Test
    fun `optional multipart file operations are withheld without fileless transport support`() {
        val descriptor = compile(OPTIONAL_MULTIPART_CONTRACT, OpenApiTrust.sameOriginAdvertisement)

        assertTrue(descriptor.actions.none { it.id == "update-attachment" })
        assertTrue(descriptor.forms.none { it.actionId == "update-attachment" })
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `runtime rejects hand built optional multipart file contracts before decoding a selection`() {
        val descriptor = compile(SIGNED_DESCRIPTION_CONTRACT, OpenApiTrust.sameOriginAdvertisement)
        val requiredAction = descriptor.actions.single { it.id == "upload-document" }
        val requiredBody = assertNotNull(requiredAction.binding.body)
        val schema = requiredBody.schema as JsonObject
        val optionalAction = requiredAction.copy(
            binding = requiredAction.binding.copy(
                body = requiredBody.copy(schema = JsonObject(schema - "required")),
            ),
        )
        val optionalDescriptor = descriptor.copy(
            actions = descriptor.actions.map { action ->
                if (action.id == optionalAction.id) optionalAction else action
            },
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            buildDynamicApiRequest(
                descriptor = optionalDescriptor,
                action = optionalAction,
                values = emptyMap(),
            )
        }

        assertEquals("Optional multipart file fields are not supported.", failure.message)
    }

    @Test
    fun `wildcard response with exact binary schema is not compiled as a JSON layout`() {
        val descriptor = compile(BINARY_PREVIEW_CONTRACT, OpenApiTrust.sameOriginAdvertisement)
        val preview = descriptor.actions.single { it.id == "get-preview" }

        assertTrue(descriptor.layouts.none { it.sourceActionId == preview.id })
    }

    private fun compile(contract: String, trust: OpenApiTrust) =
        DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("example", "Example", "1.0.0"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/example/api"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "/apps/example/openapi.json",
                    document = Json.parseToJsonElement(contract),
                    trust = trust,
                ),
            ),
        )

    private companion object {
        val SIGNED_DESCRIPTION_CONTRACT = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/apps/example/api/items/{id}/image": {
                  "post": {
                    "operationId": "upload-item-image",
                    "summary": "Upload item image",
                    "description": "Expects a multipart/form-data request with the image file in a field named **image**.",
                    "parameters": [
                      {
                        "name": "id",
                        "in": "path",
                        "required": true,
                        "schema": { "type": "integer" }
                      }
                    ],
                    "responses": { "204": { "description": "Uploaded" } }
                  }
                },
                "/apps/example/api/documents": {
                  "post": {
                    "operationId": "upload-document",
                    "summary": "Upload document",
                    "requestBody": {
                      "required": true,
                      "content": {
                        "multipart/form-data": {
                          "schema": {
                            "type": "object",
                            "required": ["document"],
                            "properties": {
                              "document": {
                                "type": "string",
                                "format": "binary",
                                "contentMediaType": "application/pdf"
                              }
                            }
                          }
                        }
                      }
                    },
                    "responses": { "204": { "description": "Uploaded" } }
                  }
                },
                "/apps/example/api/photos": {
                  "post": {
                    "operationId": "upload-photo",
                    "summary": "Upload photo",
                    "description": "Expects a multipart/form-data request with the image file in a field named **image**. The optional folderId and caption may be sent as additional form fields.",
                    "requestBody": {
                      "required": true,
                      "content": {
                        "application/json": {
                          "schema": {
                            "type": "object",
                            "properties": {
                              "folderId": { "type": "integer" },
                              "caption": { "type": "string" }
                            }
                          }
                        }
                      }
                    },
                    "responses": {
                      "200": {
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": { "id": { "type": "integer" } }
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

        val AMBIGUOUS_MULTIPART_CONTRACT = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/apps/example/api/files": {
                  "post": {
                    "operationId": "upload-two-files",
                    "requestBody": {
                      "required": true,
                      "content": {
                        "multipart/form-data": {
                          "schema": {
                            "type": "object",
                            "properties": {
                              "first": { "type": "string", "format": "binary" },
                              "second": { "type": "string", "format": "binary" }
                            }
                          }
                        }
                      }
                    },
                    "responses": { "204": { "description": "Uploaded" } }
                  }
                }
              }
            }
        """.trimIndent()

        val OPTIONAL_MULTIPART_CONTRACT = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/apps/example/api/attachments/{id}": {
                  "post": {
                    "operationId": "update-attachment",
                    "parameters": [
                      {
                        "name": "id",
                        "in": "path",
                        "required": true,
                        "schema": { "type": "integer" }
                      }
                    ],
                    "requestBody": {
                      "required": true,
                      "content": {
                        "multipart/form-data": {
                          "schema": {
                            "type": "object",
                            "required": ["caption"],
                            "properties": {
                              "attachment": { "type": "string", "format": "binary" },
                              "caption": { "type": "string" }
                            }
                          }
                        }
                      }
                    },
                    "responses": { "204": { "description": "Updated" } }
                  }
                }
              }
            }
        """.trimIndent()

        val BINARY_PREVIEW_CONTRACT = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/apps/example/api/previews/{id}": {
                  "get": {
                    "operationId": "get-preview",
                    "parameters": [
                      {
                        "name": "id",
                        "in": "path",
                        "required": true,
                        "schema": { "type": "integer" }
                      }
                    ],
                    "responses": {
                      "200": {
                        "content": {
                          "*/*": {
                            "schema": { "type": "string", "format": "binary" }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
