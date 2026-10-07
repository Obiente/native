package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class DynamicMediaNavigationTest {
    private val proof = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "synthetic", "contract"))
    private val album = DynamicResource("albums", "Albums", true, confidence = Confidence.high)
    private val tracks = DynamicResource("tracks", "Tracks", true, confidence = Confidence.high)
    private val read = DynamicAction("tracks.list", "Tracks", "tracks", ActionIntent.list,
        ActionRisk.readOnly, false, DynamicHttpBinding(HttpMethod.GET, "/apps/example/api/tracks",
            queryParameters = listOf(HttpParameter("album", false, JsonPrimitive("integer"), ParameterSource.resourceField))),
        confidence = Confidence.high, provenance = proof)
    private val layout = DynamicLayout("tracks.list", "Tracks", "tracks", LayoutKind.list,
        sourceActionId = read.id, confidence = Confidence.high)
    private val descriptor = DynamicAppDescriptor("1.0", AppIdentity("example", "Example", "1"),
        EndpointPolicy("https://fixture.invalid", listOf("/apps/example/api")),
        resources = listOf(album, tracks), actions = listOf(read), layouts = listOf(layout))
    private val context = DynamicResourceRecordContext("albums", "23", actionSafeIdentity = false)

    @Test
    fun albumOpensExistingFilteredTrackCollectionWithoutDependingOnAppIdentity() {
        val target = assertNotNull(descriptor.preferredSemanticContextualChild(context))
        assertEquals(read.id, target.actionId)
        assertEquals(mapOf("album" to "23"), target.pathParameterValues)
        assertEquals("tracks", target.resourceId)
    }

    @Test
    fun playlistSupportsDeclaredNestedTrackPathAndPreservesAccountContext() {
        val nested = read.copy(binding = DynamicHttpBinding(HttpMethod.GET,
            "/apps/example/api/playlists/{playlistId}/tracks", pathParameters = listOf(
                HttpParameter("playlistId", true, JsonPrimitive("integer"), ParameterSource.resourceField))))
        val fixture = descriptor.copy(resources = listOf(album.copy(id = "playlists", label = "Playlists"), tracks),
            actions = listOf(nested))
        val target = assertNotNull(fixture.preferredSemanticContextualChild(
            context.copy(resourceId = "playlists", parameterValues = mapOf("accountId" to "4"))))
        assertEquals("23", target.pathParameterValues["playlistId"])
        assertEquals("4", target.pathParameterValues["accountId"])
    }

    @Test
    fun ambiguousUnverifiedUnboundOrForeignReadsDoNotBecomeAutomaticEntryPoints() {
        listOf(read.copy(provenance = emptyList()), read.copy(confidence = Confidence.low),
            read.copy(binding = read.binding.copy(method = HttpMethod.POST)),
            read.copy(binding = read.binding.copy(path = "/unapproved/tracks")),
            read.copy(binding = read.binding.copy(queryParameters = emptyList())),
            read.copy(binding = read.binding.copy(queryParameters = listOf(
                HttpParameter("artist", false, JsonPrimitive("integer"), ParameterSource.resourceField))))).forEach {
            assertNull(descriptor.copy(actions = listOf(it)).preferredSemanticContextualChild(context))
        }
        assertNull(descriptor.preferredSemanticContextualChild(context.copy(actionBindingProvenanceValid = false)))
        assertNull(descriptor.preferredSemanticContextualChild(context.copy(parameterValues = mapOf("album" to "99"))))
        val duplicate = read.copy(id = "tracks.alternative", binding = read.binding.copy(path = "/apps/example/api/other-tracks"))
        assertNull(descriptor.copy(actions = listOf(read, duplicate), layouts = listOf(layout,
            layout.copy(id = "alternative", sourceActionId = duplicate.id))).preferredSemanticContextualChild(context))
    }
}
