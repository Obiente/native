package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class DynamicOpenApiServerTrustTest {
    @Test
    fun `trusted single cross origin server rebases to its declared path`() {
        assertEquals(
            "/ocs/v2.php/apps/example/api",
            openApiServerBase(
                document = document("https://{nextcloudHost}/ocs/v2.php/apps/example/api"),
                origin = "https://cloud.example.test",
                allowTrustedRebase = true,
            ),
        )
    }

    @Test
    fun `untrusted single cross origin server remains fail closed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            openApiServerBase(
                document = document("https://{nextcloudHost}/ocs/v2.php/apps/example/api"),
                origin = "https://cloud.example.test",
                allowTrustedRebase = false,
            )
        }

        assertTrue(failure.message.orEmpty().contains("Cross-origin OpenAPI server"))
    }

    @Test
    fun `trusted provenance cannot rebase a concrete foreign origin`() {
        listOf(
            "https://api.vendor.test/apps/example", "https://{tenant}.vendor.test/apps/example",
            "https://vendor.test:{port}/apps/example", "ftp://{host}/apps/example",
            "https://user@{host}/apps/example", "//api.vendor.test/apps/example",
        ).forEach { server ->
            assertFailsWith<IllegalArgumentException>(server) {
                openApiServerBase(document(server), "https://cloud.example.test", allowTrustedRebase = true)
            }
        }
    }

    @Test
    fun `signed and linked contracts cannot turn foreign writes into Nextcloud actions`() {
        val json = Json.parseToJsonElement("""{
            "openapi":"3.0.3", "servers":[{"url":"https://api.vendor.test/apps/example"}],
            "paths":{"/items":{"post":{"operationId":"createItem","responses":{"200":{"description":"OK"}}}}}
        }""")
        OpenApiTrust.entries.filter { it != OpenApiTrust.sameOriginAdvertisement }.forEach { trust ->
            val failure = assertFailsWith<IllegalArgumentException>(trust.name) {
                DynamicAppDescriptorCompiler().compile(DynamicDiscoveryInput(
                    app = AppIdentity("example", "Example", "1"), endpointPolicy = policy("example"),
                    advertisedOpenApi = AdvertisedOpenApi("https://source.example.test/openapi.json", json, trust),
                ))
            }
            assertTrue(failure.message.orEmpty().contains("Concrete cross-origin OpenAPI server rebasing"))
        }
    }

    @Test
    fun `same origin and host templated declarations retain their path bases`() {
        listOf("https://cloud.example.test", "https://cloud.example.test:443", "https://{host}", "https://{host}:{port}").forEach { origin ->
            assertEquals("/apps/example", openApiServerBase(document("$origin/apps/example"),
                "https://cloud.example.test", allowTrustedRebase = true))
        }
        assertEquals("/apps/example", openApiServerBase(document("https://cloud.example.test/apps/example"),
            "https://cloud.example.test", allowTrustedRebase = false))
    }

    @Test
    fun `foreign server alternatives and per operation overrides cannot bypass origin checks`() {
        val foreign = """[{"url":"https://api.vendor.test/apps/example"}]"""
        listOf(
            """{"servers":[{"url":"/apps/example"},{"url":"https://api.vendor.test/apps/example"}]}""",
            """{"servers":[{"url":"/apps/example"}],"paths":{"/items":{"servers":$foreign}}}""",
            """{"servers":[{"url":"/apps/example"}],"paths":{"/items":{"post":{"servers":$foreign}}}}""",
        ).forEach { json ->
            assertFailsWith<IllegalArgumentException> {
                openApiServerBase(Json.parseToJsonElement(json).jsonObject,
                    "https://cloud.example.test", allowTrustedRebase = true)
            }
        }
    }

    @Test
    fun `malformed server declarations cannot fall back to implicit local endpoints`() {
        listOf("null", "{}", "[{}]", "[null]", "[{\"url\":1}]").forEach { servers ->
            assertFailsWith<IllegalArgumentException> {
                openApiServerBase(Json.parseToJsonElement("""{"servers":$servers}""").jsonObject,
                    "https://cloud.example.test", allowTrustedRebase = true)
            }
        }
    }

    @Test
    fun `approved absolute app path is not duplicated by a server path base`() {
        assertEquals(
            "/apps/maps/api/1.0/devices",
            resolveOpenApiPath(
                base = "/index.php/apps/maps/api/1.0",
                path = "/apps/maps/api/{apiversion}/devices",
                policy = policy("maps"),
            ),
        )
    }

    @Test
    fun `server path base still applies to a relative contract path`() {
        assertEquals(
            "/index.php/apps/maps/api/1.0/favorites",
            resolveOpenApiPath(
                base = "/index.php/apps/maps/api/1.0",
                path = "/favorites",
                policy = policy("maps"),
            ),
        )
    }

    private fun document(server: String) = Json.parseToJsonElement(
        """{"servers":[{"url":"$server"}]}""",
    ).jsonObject

    private fun policy(appId: String) = EndpointPolicy(
        serverOrigin = "https://cloud.example.test",
        approvedApiPrefixes = listOf(
            "/apps/$appId",
            "/index.php/apps/$appId",
            "/ocs/v1.php/apps/$appId",
            "/ocs/v2.php/apps/$appId",
        ),
    )
}
