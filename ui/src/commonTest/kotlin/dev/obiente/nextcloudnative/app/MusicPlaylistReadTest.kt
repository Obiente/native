package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MusicPlaylistReadTest {
    private val union = Json.parseToJsonElement("""{"anyOf":[{"type":"string"},{"type":"integer"},{"type":"boolean"},{"type":"null"}]}""")
    private val action = DynamicAction("playlist.read", "Playlist", "playlists", ActionIntent.read,
        ActionRisk.readOnly, false, DynamicHttpBinding(HttpMethod.GET, "/apps/music/api/playlists/{id}",
            pathParameters = listOf(HttpParameter("id", true, JsonPrimitive("integer"), ParameterSource.resourceField)),
            queryParameters = listOf(HttpParameter("fulltree", false, union, ParameterSource.userInput))),
        confidence = Confidence.high, provenance = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "synthetic", "contract")))
    private val descriptor = DynamicAppDescriptor("1.0", AppIdentity("music", "Music", "3.2.1"),
        EndpointPolicy("https://fixture.invalid", listOf("/apps/music/api")), actions = listOf(action))

    @Test
    fun verifiedDeclaredReadRequestsFullPlaylistWithoutChangingIdentityOrExplicitChoice() {
        assertEquals(mapOf("id" to "4", "fulltree" to "true"), reviewedPlaylistReadValues(descriptor, action, mapOf("id" to "4")))
        val explicit = mapOf("id" to "4", "fulltree" to "false")
        assertEquals(explicit, reviewedPlaylistReadValues(descriptor, action, explicit))
    }

    @Test
    fun missingUnsupportedOrUntrustedContractNeverInventsQuery() {
        val values = mapOf("id" to "4")
        listOf(action.copy(provenance = emptyList()), action.copy(confidence = Confidence.low),
            action.copy(binding = action.binding.copy(method = HttpMethod.POST)),
            action.copy(binding = action.binding.copy(path = "/outside/playlists/{id}")),
            action.copy(binding = action.binding.copy(queryParameters = emptyList())),
            action.copy(binding = action.binding.copy(queryParameters = listOf(HttpParameter("fulltree", false,
                Json.parseToJsonElement("""{"anyOf":[{"type":{}},{"type":"boolean"}]}"""), ParameterSource.userInput))))).forEach {
            assertEquals(values, reviewedPlaylistReadValues(descriptor, it, values))
        }
        assertEquals(values, reviewedPlaylistReadValues(descriptor.copy(app = descriptor.app.copy(version = "3.2.2")), action, values))
    }
}
