package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** String fields of an OpenAPI document are read only from JSON strings, never coerced from other primitives. */
class DynamicCompilerValueTypeTest {
    @Test
    fun `an openapi version that is not a string is unsupported`() {
        for (version in listOf("3.0", "3", "true")) {
            assertFailsWith<IllegalArgumentException>(version) {
                compile(contract(openapi = version))
            }
        }
        assertTrue(compile(contract(openapi = "\"3.0.3\"")).actions.isNotEmpty())
    }

    @Test
    fun `a non-string operation id never names an operation`() {
        for (operationId in listOf("7", "true", "1.5")) {
            val descriptor = compile(contract(getOperationId = operationId, postOperationId = operationId))
            // A write without a readable operation ID is ignored; a read gets a derived ID instead.
            assertFalse(descriptor.actions.any { it.binding.method == HttpMethod.POST }, operationId)
            assertTrue(descriptor.warnings.any { it.code == "ignored-unnamed-write" }, operationId)
            val read = descriptor.actions.single { it.binding.method == HttpMethod.GET }
            assertFalse(read.id == operationId.trim('"'), operationId)
        }
        val named = compile(contract())
        assertTrue(named.actions.any { it.id == "items-create" })
    }

    @Test
    fun `parameters with a non-string name or location are not declared`() {
        val parameters = """
            {"name":"id","in":"path","required":true,"schema":{"type":"integer"}},
            {"name":7,"in":"path","required":true,"schema":{"type":"integer"}},
            {"name":"filter","in":1,"schema":{"type":"string"}},
            {"name":"search","in":"query","schema":{"type":"string"}}
        """
        val descriptor = compile(contract(path = "/items/{id}/notes", parameters = parameters))
        val read = descriptor.actions.single { it.binding.method == HttpMethod.GET }
        assertEquals(listOf("id"), read.binding.pathParameters.map { it.name })
        assertEquals(listOf("search"), read.binding.queryParameters.map { it.name })
    }

    private fun compile(document: String): DynamicAppDescriptor = DynamicAppDescriptorCompiler().compile(
        DynamicDiscoveryInput(
            app = AppIdentity("items", "Items", "1.0.0"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/items/api")),
            advertisedOpenApi = AdvertisedOpenApi(
                "/apps/items/openapi.json",
                Json.parseToJsonElement(document),
                OpenApiTrust.sameOriginAdvertisement,
            ),
        ),
    )

    private fun contract(
        openapi: String = "\"3.0.3\"",
        getOperationId: String = "\"items-list\"",
        postOperationId: String = "\"items-create\"",
        path: String = "/items",
        parameters: String = "",
    ): String = """
        {
          "openapi": $openapi,
          "info": {"title": "Items", "version": "1.0.0"},
          "paths": {
            "/apps/items/api$path": {
              "parameters": [$parameters],
              "get": {
                "operationId": $getOperationId,
                "responses": {"200": {"description": "OK", "content": {"application/json": {"schema": {
                  "type": "array",
                  "items": {"type": "object", "properties": {"id": {"type": "integer"}, "name": {"type": "string"}}}
                }}}}}
              },
              "post": {
                "operationId": $postOperationId,
                "requestBody": {"content": {"application/json": {"schema": {
                  "type": "object", "properties": {"name": {"type": "string"}}
                }}}},
                "responses": {"200": {"description": "OK"}}
              }
            }
          }
        }
    """.trimIndent()
}
