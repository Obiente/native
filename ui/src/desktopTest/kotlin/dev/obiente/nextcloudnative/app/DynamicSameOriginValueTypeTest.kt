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
    fun `a viewer catalog element that may have been the app makes the server inconclusive`() = runBlocking {
        val other = """{"id":"other","specs":["other"]}"""
        for (unidentified in listOf("\"junk\"", "7", """{"id":42,"specs":["42"]}""", """{"specs":["example"]}""")) {
            val discovery = viewer("[$other,$unidentified]")
            assertEquals(DynamicContractFallbackReason.ServerProbeInconclusive, discovery.fallbackReason, unidentified)
            assertTrue(discovery.diagnostics.any { "without a readable app ID" in it }, unidentified)
        }
    }

    @Test
    fun `a viewer catalog made only of unidentified elements is unreadable`() = runBlocking {
        val discovery = viewer("""["junk",{"id":42},null]""")
        assertEquals(DynamicContractFallbackReason.ServerProbeInconclusive, discovery.fallbackReason)
        assertTrue("OCS API Viewer returned an unreadable app catalog (stage: catalog, no readable app ID)." in discovery.diagnostics)
    }

    @Test
    fun `unidentified viewer elements beside the app entry are ignored`() = runBlocking {
        val discovery = viewer("""["junk",{"id":42},{"id":"example","specs":["example"]}]""", spec("\"3.0.3\""))
        assertEquals(DynamicDescriptorAcquisition.OcsApiViewer, discovery.acquisition)
    }

    @Test
    fun `a well-formed viewer catalog without the app is absence`() = runBlocking {
        val discovery = viewer("""[{"id":"other","specs":["other"]}]""")
        assertEquals(null, discovery.fallbackReason)
        assertTrue("OCS API Viewer does not advertise an OpenAPI specification for example." in discovery.diagnostics)
    }

    private suspend fun viewer(catalog: String, spec: String? = null) =
        discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            when (request.relativePath) {
                CATALOG -> response(200, catalog)
                "$CATALOG/example" -> spec?.let { response(200, it) } ?: response(404)
                else -> response(404)
            }
        })

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
