package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicViewerSpecListTest {
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    @Test
    fun anUnreadableAdvertisedSpecListIsInconclusive() = runBlocking {
        for (specs in listOf(null, "\"example\"", "{}", """["../escape", 5, null, "bad id!"]""", """["other", 7]""")) {
            val (discovery, requested) = discover(specs)
            assertEquals(DynamicContractFallbackReason.ServerProbeInconclusive, discovery.fallbackReason, specs)
            assertEquals(
                listOf("The server API probe was inconclusive: UnreadableResponse."),
                discovery.diagnostics.filter { "server API probe" in it },
                specs,
            )
            assertEquals(emptyList(), requested.filter { it.startsWith("$CATALOG/") }, specs)
        }
    }

    @Test
    fun wellFormedIdentifiersAreStillUsedBesideMalformedOnes() = runBlocking {
        val (discovery, requested) = discover("""["bad id!", "example", 42, "example-public"]""")
        assertEquals(listOf("$CATALOG/example", "$CATALOG/example-public"), requested.filter { it.startsWith("$CATALOG/") })
        // Both advertised specs answered 404, which is definitive, so nothing inconclusive was recorded.
        assertEquals(null, discovery.fallbackReason)
    }

    @Test
    fun aGenuinelyEmptyOrForeignSpecListIsAbsence() = runBlocking {
        for (specs in listOf("[]", """["other", "other-public"]""")) {
            val (discovery, requested) = discover(specs)
            assertEquals(null, discovery.fallbackReason, specs)
            assertEquals(emptyList(), discovery.diagnostics.filter { "server API probe" in it }, specs)
            assertEquals(emptyList(), requested.filter { it.startsWith("$CATALOG/") }, specs)
        }
    }

    private suspend fun discover(specs: String?): Pair<DynamicDescriptorDiscovery, List<String>> {
        val requested = mutableListOf<String>()
        val entry = if (specs == null) """{"id":"example"}""" else """{"id":"example","specs":$specs}"""
        val discovery = discoverDynamicAppDescriptor("https://fixture.invalid", app, execute = { request ->
            requested += request.relativePath
            if (request.relativePath == CATALOG) {
                NextcloudApiResponse(200, "[$entry]".encodeToByteArray(), "application/json", etag = null)
            } else {
                NextcloudApiResponse(404, ByteArray(0), "application/json", etag = null)
            }
        })
        return discovery to requested
    }

    private companion object {
        const val CATALOG = "/index.php/apps/ocs_api_viewer/apps"
    }
}
