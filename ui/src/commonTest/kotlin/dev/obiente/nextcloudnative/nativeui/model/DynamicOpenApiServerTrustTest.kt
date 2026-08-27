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
