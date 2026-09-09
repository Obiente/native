package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicOpenApiVersionBindingTest {
    @Test
    fun bindsServerVersionOnlyWhenTheEffectiveSchemaPermitsIt() {
        assertPath("""{"type":"string"}""", "/apps/example/api/v1/items")
        assertPath("""{"type":"string","enum":["v1","v2"]}""", "/apps/example/api/v1/items")
        assertPath("""{"type":"string","enum":["v2"]}""", "/apps/example/api/v2/items")
        assertPath("""{"type":"string","default":"v2"}""", "/apps/example/api/v2/items")
        assertPath("""{"type":"string","enum":["v2","v3"]}""", "/apps/example/api/{apiVersion}/items")
        assertPath("""{"type":"string","enum":["v2"],"default":"v1"}""", "/apps/example/api/{apiVersion}/items")
        assertPath("""{"type":"string","pattern":"^v2$"}""", "/apps/example/api/{apiVersion}/items")
        assertPath("""{"type":"integer"}""", "/apps/example/api/{apiVersion}/items")
    }

    @Test
    fun operationOverridesAndLocalReferencesAreResolvedBeforeBinding() {
        val descriptor = compile("""{"type":"string","enum":["v1"]}""", """
            "parameters":[{"name":"apiVersion","in":"path","required":true,
                "schema":{"${'$'}ref":"#/components/schemas/Version"}}],
        """.trimIndent())
        assertEquals("/apps/example/api/v2/items", descriptor.actions.single().binding.path)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    private fun assertPath(schema: String, expected: String) {
        val descriptor = compile(schema)
        val action = descriptor.actions.single()
        assertEquals(expected, action.binding.path)
        assertEquals(if ("{" in expected) listOf("apiVersion") else emptyList(), action.binding.pathParameters.map { it.name })
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    private fun compile(schema: String, operationParameters: String = "") = DynamicAppDescriptorCompiler().compile(
        DynamicDiscoveryInput(
            app = AppIdentity("example", "Example", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/example")),
            advertisedOpenApi = AdvertisedOpenApi("/apps/example/openapi.json", Json.parseToJsonElement("""
                {"openapi":"3.0.3","info":{"title":"Example","version":"1"},
                "servers":[{"url":"/apps/example/api/v1"}],
                "components":{"schemas":{"Version":{"type":"string","enum":["v2"]}}},
                "paths":{"/apps/example/api/{apiVersion}/items":{
                    "parameters":[{"name":"apiVersion","in":"path","required":true,"schema":$schema}],
                    "get":{$operationParameters "operationId":"listItems","responses":{"200":{"description":"OK",
                        "content":{"application/json":{"schema":{"type":"array","items":{"type":"object"}}}}}}}
                }}}
            """.trimIndent()), OpenApiTrust.sameOriginAdvertisement),
        ),
    )
}
