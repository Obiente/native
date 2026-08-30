package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue

class DynamicObjectCompositionSafetyTest {
    @Test
    fun `conditional and unknown member constraints never become unconstrained write forms`() {
        val constraints = listOf(
            """"if":{"required":["title"]},"then":{"required":["enabled"]},"else":{"not":{}}""",
            """"then":{"required":["enabled"]}""",
            """"else":{"required":["enabled"]}""",
            """"const":{"title":"fixed"}""",
            """"enum":[{"title":"fixed"}]""",
            """"dependentRequired":{"title":["enabled"]}""",
            """"dependentSchemas":{"title":{"required":["enabled"]}}""",
            """"unevaluatedProperties":false""",
            """"readOnly":true""",
            """"${'$'}dynamicRef":"#shape"""",
            """"futureConstraint":{"required":["enabled"]}""",
        )
        constraints.forEach { constraint ->
            val member = """{"type":"object","additionalProperties":false,"properties":{"title":{"type":"string"}},$constraint}"""
            listOf(member, """{"allOf":[$member]}""").forEach { composition ->
                val descriptor = DynamicAppDescriptorCompiler().compile(input(composition, "3.1.1"))
                assertTrue(descriptor.actions.none { it.id == "replace-item" }, constraint)
                assertTrue(descriptor.forms.none { it.actionId == "replace-item" }, constraint)
            }
        }
    }

    @Test
    fun `malformed shape keywords are not erased from composed write contracts`() {
        listOf(""""required":"title"""", """"required":[null]""", """"properties":[]""", """"allOf":{}""")
            .forEach { constraint ->
                val composition = """{"type":"object","additionalProperties":false,$constraint},
                    {"type":"object","additionalProperties":false,"properties":{"title":{"type":"string"}}}"""
                val descriptor = DynamicAppDescriptorCompiler().compile(input(composition, "3.1.1"))
                assertTrue(descriptor.actions.none { it.id == "replace-item" }, constraint)
            }
    }

    @Test
    fun `closed shapes with descriptive annotations retain supported field constraints`() {
        val field = """{"type":"string","minLength":3}"""
        val composition = """{"type":"object","additionalProperties":false,"properties":{"title":$field},
            "required":["title"],"description":"A title","example":{"title":"Sample"},"${'$'}comment":"Documentation"}"""
        val descriptor = DynamicAppDescriptorCompiler().compile(input(composition, "3.1.1"))
        val action = descriptor.actions.single { it.id == "replace-item" }
        val schema = action.binding.body!!.schema.jsonObject
        assertEquals(Json.parseToJsonElement(field), schema["properties"]!!.jsonObject["title"])
        assertEquals(Json.parseToJsonElement("""["title"]"""), schema["required"])
        assertTrue(descriptor.forms.any { it.actionId == action.id })
    }

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

    private fun input(composition: String, version: String = "3.0.3") = DynamicDiscoveryInput(
        app = AppIdentity("example", "Example", "1"),
        endpointPolicy = EndpointPolicy(
            serverOrigin = "https://cloud.example.test",
            approvedApiPrefixes = listOf("/apps/example/api"),
        ),
        advertisedOpenApi = AdvertisedOpenApi(
            documentUrl = "/apps/example/openapi.json",
            document = Json.parseToJsonElement(document(composition).replace("3.0.3", version)),
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
