package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DynamicOpenApiTemplateTransportTest {
    @Test
    fun onlyTemplatedOriginComponentsMayBeRebased() {
        val origins = listOf("https://cloud.example.test", "https://cloud.example.test:443",
            "https://cloud.example.test:8443", "http://cloud.example.test", "http://cloud.example.test:8080",
            "https://[2001:db8::1]", "https://[2001:db8::1]:8443")
        origins.forEach { origin ->
            val accountScheme = origin.substringBefore("://")
            val accountPort = if (origin.endsWith(":8443")) 8443 else if (origin.endsWith(":8080")) 8080
                else if (accountScheme == "https") 443 else 80
            listOf("http", "https").forEach { scheme ->
                listOf("", ":80", ":443", ":8080", ":8443", ":{port}", ":0", ":65536").forEach { port ->
                    val server = "$scheme://{host}$port/apps/example"
                    val declaredPort = if (port.isEmpty()) (if (scheme == "https") 443 else 80) else port.drop(1).toIntOrNull()
                    val accepted = scheme == accountScheme && (port == ":{port}" || declaredPort == accountPort)
                    val document = Json.parseToJsonElement("""{"servers":[{"url":"$server"}]}""").jsonObject
                    if (accepted) assertEquals("/apps/example", openApiServerBase(document, origin, true), "$origin / $server")
                    else assertFailsWith<IllegalArgumentException>("$origin / $server") { openApiServerBase(document, origin, true) }
                    assertFailsWith<IllegalArgumentException> { openApiServerBase(document, origin, false) }
                }
            }
        }
    }

    @Test
    fun trustedAlternativesAndOverridesCannotChangeConcreteTransport() {
        listOf("http://{host}", "https://{host}:8443").forEach { server ->
            val bad = """[{"url":"$server/apps/example"}]"""
            listOf(
                """{"servers":[{"url":"/apps/example"},{"url":"$server/apps/example"}]}""",
                """{"servers":[{"url":"/apps/example"}],"paths":{"/items":{"servers":$bad}}}""",
                """{"servers":[{"url":"/apps/example"}],"paths":{"/items":{"post":{"servers":$bad}}}}""",
            ).forEach { json ->
                assertFailsWith<IllegalArgumentException> {
                    openApiServerBase(Json.parseToJsonElement(json).jsonObject, "https://cloud.example.test", true)
                }
            }
            OpenApiTrust.entries.forEach { trust ->
                val json = Json.parseToJsonElement("""{"openapi":"3.0.3","servers":$bad,
                    "paths":{"/items":{"post":{"operationId":"createItem","responses":{"200":{"description":"OK"}}}}}}""")
                assertFailsWith<IllegalArgumentException> {
                    DynamicAppDescriptorCompiler().compile(DynamicDiscoveryInput(
                        app = AppIdentity("example", "Example", "1"),
                        endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/example")),
                        advertisedOpenApi = AdvertisedOpenApi("/apps/example/openapi.json", json, trust),
                    ))
                }
            }
        }
    }
}
