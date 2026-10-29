package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicDiscoveryDiagnosticPrivacyTest {
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    @Test
    fun aFailedCatalogRequestKeepsNoExceptionText() = runBlocking {
        val discovery = discover { request ->
            if (request.relativePath == CATALOG) throw IllegalStateException(SECRET_MESSAGE)
            response(404)
        }
        assertPrivate(discovery)
        assertTrue("OCS API Viewer is unavailable (stage: catalog, NoResponse)." in discovery.diagnostics)
    }

    @Test
    fun aFailedSpecRequestKeepsNoExceptionText() = runBlocking {
        val discovery = discover { request ->
            when (request.relativePath) {
                CATALOG -> response(200, """[{"id":"example","specs":["example"]}]""")
                "$CATALOG/example" -> throw IllegalStateException(SECRET_MESSAGE)
                else -> response(404)
            }
        }
        assertPrivate(discovery)
        assertTrue("OCS API Viewer could not load example (stage: spec, NoResponse)." in discovery.diagnostics)
    }

    @Test
    fun aSpecStatusIsReducedToItsClass() = runBlocking {
        val discovery = discover { request ->
            when (request.relativePath) {
                CATALOG -> response(200, """[{"id":"example","specs":["example"]}]""")
                "$CATALOG/example" -> response(503)
                else -> response(404)
            }
        }
        assertTrue("OCS API Viewer could not load example (stage: spec, HTTP 5xx)." in discovery.diagnostics)
    }

    @Test
    fun anUncompilableSpecKeepsNoCompilerMessage() = runBlocking {
        val foreignSpec = """{"openapi":"3.0.3","info":{"title":"x","version":"1"},""" +
            """"servers":[{"url":"https://evil.invalid/apps/example?token=abc123"}],""" +
            """"paths":{"/items":{"get":{"operationId":"list","responses":{"200":{"description":"ok"}}}}}}"""
        val discovery = discover { request ->
            when (request.relativePath) {
                CATALOG -> response(200, """[{"id":"example","specs":["example"]}]""")
                "$CATALOG/example" -> response(200, foreignSpec)
                else -> response(404)
            }
        }
        assertEquals(DynamicContractFallbackReason.UnsupportedContract, discovery.fallbackReason)
        assertTrue("OCS API Viewer returned an unusable specification for example (stage: compilation)." in discovery.diagnostics)
        assertPrivate(discovery)
    }

    @Test
    fun aServerContentTypeIsReducedToABoundedMediaType() = runBlocking {
        for ((contentType, expected) in listOf(
            "application/json; token=abc123" to "media type application/json",
            "text/html<script>https://evil.invalid/</script>" to "media type unavailable",
        )) {
            val discovery = discover { request ->
                when (request.relativePath) {
                    CATALOG -> response(200, """[{"id":"example","specs":["example"]}]""")
                    "$CATALOG/example" -> NextcloudApiResponse(200, "{".encodeToByteArray(), contentType, etag = null)
                    else -> response(404)
                }
            }
            assertTrue(discovery.diagnostics.any { expected in it }, contentType)
            assertPrivate(discovery)
        }
    }

    private fun assertPrivate(discovery: DynamicDescriptorDiscovery) {
        val text = discovery.diagnostics.joinToString("\n")
        for (secret in listOf("evil.invalid", "secret.invalid", "abc123", "token", "IllegalState", "/private/path", "https://")) {
            assertTrue(secret !in text, "Diagnostics exposed $secret: $text")
        }
    }

    private suspend fun discover(execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse) =
        discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = execute)

    private companion object {
        const val CATALOG = "/index.php/apps/ocs_api_viewer/apps"
        const val SECRET_MESSAGE = "Failed to connect to https://secret.invalid/private/path?token=abc123"

        fun response(status: Int, body: String = ""): NextcloudApiResponse =
            NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", etag = null)
    }
}
