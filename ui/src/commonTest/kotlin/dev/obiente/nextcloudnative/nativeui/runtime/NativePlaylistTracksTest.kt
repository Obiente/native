package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.*

class NativePlaylistTracksTest {
    private val resource = ResourceSpec("tracks", "Tracks", Confidence.high, emptyList())
    private val view = ViewSpec("playlist.detail", "Playlist", "playlists", NativeComponent.detail, "playlist.read", Confidence.high)
    private val action = ActionSpec("playlist.read", "Playlist", "playlists",
        ApiBinding(HttpMethod.GET, "/apps/music/api/playlists/{id}", "playlist.read", queryParameterNames = listOf("fulltree")),
        ActionIntent.read, ActionRisk.readOnly, false, Confidence.high,
        evidence = listOf(Evidence(EvidenceSource.verifiedAppPackage, "synthetic")))
    private val schema = NativeAppSchema("0.1", AppIdentity("music", "Music", "3.2.1"), Confidence.high,
        resources = listOf(resource), views = listOf(view), actions = listOf(action))
    private fun scalar(value: String) = NativeStructuredValue.Scalar(value, NativeStructuredScalarKind.string)
    private fun track(id: String, index: Int) = NativeStructuredValue.ObjectValue(listOf(
        NativeStructuredEntry("id", "ID", scalar(id)), NativeStructuredEntry("index", "Index", scalar(index.toString())),
        NativeStructuredEntry("title", "Title", scalar("Track $id")),
        NativeStructuredEntry("files", "Files", NativeStructuredValue.ObjectValue(listOf(
            NativeStructuredEntry("audio/mpeg", "Audio", scalar("17")))))))
    private fun playlist(vararg tracks: NativeStructuredValue) = NativeRecord("4", emptyMap(),
        displayValues = mapOf("name" to "Synthetic playlist"), structuredValues = mapOf("tracks" to NativeStructuredValue.ListValue(tracks.toList())))

    @Test
    fun completeTracksKeepPlaylistOrderAndRepeatedTrackOccurrencesRemainDistinctReadOnlyRecords() {
        val result = assertNotNull(nativePlaylistTracks(schema, view, listOf(playlist(track("9", 1), track("9", 0)))))
        assertEquals(listOf("4:0:9", "4:1:9"), result.records.map { it.id })
        assertEquals(listOf("9", "9"), result.records.map { it.displayValues["id"] })
        assertEquals(listOf("0", "1"), result.records.map { it.displayValues["index"] })
        assertEquals(listOf("1", "2"), result.records.map { nativeMediaPresentation(resource, it).trackNumber })
        assertTrue(result.records.all { !it.actionSafeIdentity && it.values.keys == setOf(NATIVE_SYNTHETIC_RESOURCE_FIELD) })
        assertTrue(result.records.all { nativeAudioTrack(resource, it) != null })
        assertEquals("Synthetic playlist", result.title)
        assertEquals(emptyList(), assertNotNull(nativePlaylistTracks(schema, view, listOf(playlist()))).records)
    }

    @Test
    fun playlistPositionsDoNotUseUnknownOrDifferentAlbumTrackNumbers() {
        val tracks = listOf("0", "17").mapIndexed { index, albumNumber ->
            val item = track("9", index)
            item.copy(entries = item.entries + NativeStructuredEntry("trackNumber", "Track number", scalar(albumNumber)))
        }
        val result = assertNotNull(nativePlaylistTracks(schema, view, listOf(playlist(*tracks.toTypedArray()))))
        assertEquals(listOf("1", "2"), result.records.map { nativeMediaPresentation(resource, it).trackNumber })
        assertEquals(listOf("4:0:9", "4:1:9"), result.records.map { it.id })
        assertEquals(listOf("0", "17"), result.records.map { it.displayValues["trackNumber"] })
    }

    @Test
    fun malformedTruncatedOrUnverifiedPlaylistCannotBecomeAPlayableCollection() {
        val valid = playlist(track("9", 0))
        val stub = NativeStructuredValue.ObjectValue(listOf(NativeStructuredEntry("id", "ID", scalar("9")),
            NativeStructuredEntry("index", "Index", scalar("0")), NativeStructuredEntry("uri", "URI", scalar("/apps/music/api/tracks/9"))))
        listOf(playlist(stub), playlist(track("9", 1)), playlist(track("9", 0), track("8", 0)),
            playlist(track("../9", 0)), playlist(track("9", 0).copy(omittedEntries = 1)),
            valid.copy(structuredValues = mapOf("tracks" to NativeStructuredValue.ListValue(listOf(track("9", 0)), 1))),
            valid.copy(actionBindingProvenanceValid = false)).forEach {
            assertNull(nativePlaylistTracks(schema, view, listOf(it)))
        }
        assertNull(nativePlaylistTracks(schema.copy(actions = listOf(action.copy(evidence = emptyList()))), view, listOf(valid)))
        assertNull(nativePlaylistTracks(schema.copy(app = schema.app.copy(version = "3.2.2")), view, listOf(valid)))
    }
}
