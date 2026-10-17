package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicSameOriginValueTypeTest {
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    @Test
    fun `a server contract whose openapi version is not a string is not imported`() = runBlocking {
        for (version in listOf("3.0", "3", "true")) {
            val discovery = discover(spec(version))
            assertEquals(DynamicDescriptorAcquisition.MetadataFallback, discovery.acquisition, version)
            assertEquals(DynamicContractFallbackReason.UnsupportedContract, discovery.fallbackReason, version)
            assertTrue(discovery.diagnostics.any { "invalid OpenAPI JSON body for example" in it }, version)
        }
        assertTrue(discover(spec("\"3.0.3\"")).acquisition != DynamicDescriptorAcquisition.MetadataFallback)
    }

    @Test
    fun `a viewer catalog entry whose id is not a string does not name the app`() = runBlocking {
        val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app.copy(id = "42"), execute = { request ->
            when (request.relativePath) {
                CATALOG -> response(200, """[{"id":42,"specs":["42"]}]""")
                else -> response(404)
            }
        })
        assertEquals(DynamicDescriptorAcquisition.MetadataFallback, discovery.acquisition)
        assertTrue(discovery.diagnostics.none { "could not load" in it || "invalid OpenAPI" in it })
    }

    private suspend fun discover(spec: String) = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
        when (request.relativePath) {
            CATALOG -> response(200, """[{"id":"example","specs":["example"]}]""")
            "$CATALOG/example" -> response(200, spec)
            else -> response(404)
        }
    })

    private fun spec(version: String) = """{"openapi":$version,"info":{"title":"Example","version":"1"},""" +
        """"paths":{"/index.php/apps/example/api/items":{"get":{"operationId":"items-list","responses":{"200":""" +
        """{"description":"OK","content":{"application/json":{"schema":{"type":"array","items":""" +
        """{"type":"object","properties":{"id":{"type":"integer"}}}}}}}}}}}}"""

    private companion object {
        const val CATALOG = "/index.php/apps/ocs_api_viewer/apps"

        fun response(status: Int, body: String = ""): NextcloudApiResponse =
            NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", etag = null)
    }
}
