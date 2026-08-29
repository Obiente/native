package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class DynamicObjectCompositionSafetyTest {
    @Test
    fun `object allOf mutations require every contributing fragment to prove a closed shape`() {
        val unsafeCompositions = listOf(
            """
                {"type":"object","properties":{"title":{"type":"string"}}},
                {"type":"object","additionalProperties":false,"properties":{"enabled":{"type":"boolean"}}}
            """.trimIndent(),
            """
                {"type":"object","additionalProperties":false,"properties":{"title":{"type":"string"}}},
                {"type":"object","additionalProperties":{"type":"string"}}
            """.trimIndent(),
            """
                {"type":"object","additionalProperties":false,"properties":{"title":{"type":"string"}}},
                {"type":"object","additionalProperties":false,"minProperties":1}
            """.trimIndent(),
        )

        unsafeCompositions.forEach { composition ->
            val descriptor = DynamicAppDescriptorCompiler().compile(input(composition))

            assertTrue(descriptor.actions.none { action -> action.id == "replace-item" })
            assertTrue(descriptor.forms.none { form -> form.actionId == "replace-item" })
        }
    }

    private fun input(composition: String) = DynamicDiscoveryInput(
        app = AppIdentity("example", "Example", "1"),
        endpointPolicy = EndpointPolicy(
            serverOrigin = "https://cloud.example.test",
            approvedApiPrefixes = listOf("/apps/example/api"),
        ),
        advertisedOpenApi = AdvertisedOpenApi(
            documentUrl = "/apps/example/openapi.json",
            document = Json.parseToJsonElement(document(composition)),
            trust = OpenApiTrust.sameOriginAdvertisement,
        ),
    )

    private fun document(composition: String): String = """
        {
          "openapi":"3.0.3",
          "info":{"title":"Composed mutation","version":"1"},
          "paths":{
            "/apps/example/api/items":{
              "get":{
                "operationId":"list-items",
                "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                  "type":"array",
                  "items":{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"}}}
                }}}}}
              }
            },
            "/apps/example/api/items/{id}":{
              "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
              "put":{
                "operationId":"replace-item",
                "requestBody":{"required":true,"content":{"application/json":{"schema":{
                  "type":"object",
                  "allOf":[$composition]
                }}}},
                "responses":{"200":{"description":"Updated"}}
              }
            }
          }
        }
    """.trimIndent()
}
