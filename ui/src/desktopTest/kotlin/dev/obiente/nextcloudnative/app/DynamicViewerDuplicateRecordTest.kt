package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** API Viewer catalogs follow the App Store rule for duplicate records of the requested app. */
class DynamicViewerDuplicateRecordTest {
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    @Test
    fun `differing records for the app are inconclusive and import nothing`() = runBlocking {
        val requested = mutableListOf<String>()
        val discovery = discover("""[{"id":"example","specs":[]},{"id":"example","specs":["example"]}]""", requested)
        assertEquals(DynamicDescriptorAcquisition.MetadataFallback, discovery.acquisition)
        assertEquals(DynamicContractFallbackReason.ServerProbeInconclusive, discovery.fallbackReason)
        assertTrue(
            "OCS API Viewer listed differing records for example (stage: catalog, duplicate app records)." in discovery.diagnostics,
        )
        assertFalse("$CATALOG/example" in requested)
    }

    @Test
    fun `identical records for the app are read once`() = runBlocking {
        // JSON equality, so the key order of the copy does not matter.
        val discovery = discover("""[{"id":"example","specs":["example"]},{"specs":["example"],"id":"example"}]""")
        assertEquals(DynamicDescriptorAcquisition.OcsApiViewer, discovery.acquisition)
    }

    @Test
    fun `differing duplicates of another app do not matter`() = runBlocking {
        val discovery = discover(
            """[{"id":"other","specs":[]},{"id":"other","specs":["other"]},{"id":"example","specs":["example"]}]""",
        )
        assertEquals(DynamicDescriptorAcquisition.OcsApiViewer, discovery.acquisition)
    }

    private suspend fun discover(catalog: String, requested: MutableList<String> = mutableListOf()) =
        discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            requested += request.relativePath
            when (request.relativePath) {
                CATALOG -> response(200, catalog)
                "$CATALOG/example" -> response(200, SPEC)
                else -> response(404)
            }
        })

    private companion object {
        const val CATALOG = "/index.php/apps/ocs_api_viewer/apps"
        const val SPEC = """{"openapi":"3.0.3","info":{"title":"Example","version":"1"},""" +
            """"paths":{"/index.php/apps/example/api/items":{"get":{"operationId":"items-list","responses":{"200":""" +
            """{"description":"OK","content":{"application/json":{"schema":{"type":"array","items":""" +
            """{"type":"object","properties":{"id":{"type":"integer"}}}}}}}}}}}}"""

        fun response(status: Int, body: String = ""): NextcloudApiResponse =
            NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", etag = null)
    }
}
