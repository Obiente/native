package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMusicWorkspaceTest {
    @Test
    fun `declared library shape enables ordered adaptive navigation without app identity`() {
        val destinations = listOf(
            destination("settings", "Preferences", NativeComponent.detail),
            destination("songs", "All songs"),
            destination("releases", "Releases"),
            destination("composers", "Composers"),
            destination("directories", "Directories"),
            destination("stations", "Internet stations"),
        )

        val intent = assertNotNull(
            planNativeMusicWorkspace(
                destinations = destinations,
                selectedViewId = "songs-view",
                widthClass = NativeMusicWorkspaceWidthClass.Expanded,
            ),
        )

        assertEquals(NativeMusicNavigationPlacement.Sidebar, intent.placement)
        assertEquals("songs-view", intent.activeDestination.viewId)
        assertEquals(
            listOf(
                NativeMusicSectionKind.Albums,
                NativeMusicSectionKind.Artists,
                NativeMusicSectionKind.Tracks,
                NativeMusicSectionKind.Folders,
                NativeMusicSectionKind.Radio,
                NativeMusicSectionKind.Settings,
            ),
            intent.destinations.map(NativeMusicWorkspaceDestination::kind),
        )
    }

    @Test
    fun `track collection with supporting media sections enables a library workspace`() {
        val intent = planNativeMusicWorkspace(
            destinations = listOf(
                destination("tracks", "Tracks"),
                destination("playlists", "Playlists"),
                destination("podcastChannels", "Podcast channels"),
            ),
            selectedViewId = "tracks-view",
            widthClass = NativeMusicWorkspaceWidthClass.Compact,
        )

        assertNotNull(intent)
        assertEquals(NativeMusicNavigationPlacement.TabStrip, intent.placement)
    }

    @Test
    fun `compound album track resource remains a track collection`() {
        val intent = assertNotNull(
            planNativeMusicWorkspace(
                destinations = listOf(
                    destination("albums", "Albums"),
                    destination("artists", "Artists"),
                    destination("albumTracks", "Album tracks"),
                ),
                selectedViewId = "albumTracks-view",
                widthClass = NativeMusicWorkspaceWidthClass.Medium,
            ),
        )

        assertEquals(
            NativeMusicSectionKind.Tracks,
            intent.destinations.single { it.viewId == "albumTracks-view" }.kind,
        )
    }

    @Test
    fun `ambiguous single media collection does not replace generic navigation`() {
        val intent = planNativeMusicWorkspace(
            destinations = listOf(
                destination("albums", "Albums"),
                destination("settings", "Settings", NativeComponent.detail),
                destination("audit", "Audit", NativeComponent.dataTable),
            ),
            selectedViewId = "albums-view",
            widthClass = NativeMusicWorkspaceWidthClass.Medium,
        )

        assertNull(intent)
    }

    @Test
    fun `preferred landing is albums then artists then tracks`() {
        val destinations = listOf(
            destination("tracks", "All tracks"),
            destination("artists", "Artists"),
            destination("albums", "Albums"),
            destination("settings", "Settings", NativeComponent.detail),
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("unrelated-id", "Sound collection", "1"),
            confidence = Confidence.verified,
            views = destinations.map { (_, view) -> view },
        )

        assertEquals(
            "albums-view",
            preferredNativeMusicLandingViewId(
                destinations = destinations.map { (destination, _) -> destination },
                schema = schema,
            ),
        )
    }

    @Test
    fun `preferred landing ignores a non-library view named albums`() {
        val destinations = listOf(
            destination("albumSettings", "Albums", NativeComponent.detail),
            destination("artists", "Artists"),
            destination("tracks", "All tracks"),
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("unrelated-id", "Sound collection", "1"),
            confidence = Confidence.verified,
            views = destinations.map { (_, view) -> view },
        )

        assertEquals(
            "artists-view",
            preferredNativeMusicLandingViewId(
                destinations = destinations.map { (destination, _) -> destination },
                schema = schema,
            ),
        )
    }

    @Test
    fun `short landscape uses tabs while wider workspaces use rails and sidebars`() {
        assertEquals(
            NativeMusicWorkspaceWidthClass.Compact,
            nativeMusicWorkspaceWidthClass(widthDp = 840f, heightDp = 390f),
        )
        assertEquals(
            NativeMusicWorkspaceWidthClass.Medium,
            nativeMusicWorkspaceWidthClass(widthDp = 840f, heightDp = 700f),
        )
        assertEquals(
            NativeMusicWorkspaceWidthClass.Expanded,
            nativeMusicWorkspaceWidthClass(widthDp = 1_200f, heightDp = 800f),
        )
    }

    @Test
    fun `every adaptive width keeps the selected root section and selects its matching placement`() {
        val destinations = listOf(
            destination("albums", "Albums"),
            destination("artists", "Artists"),
            destination("tracks", "Tracks"),
        )

        NativeMusicWorkspaceWidthClass.entries.forEach { widthClass ->
            val intent = assertNotNull(
                planNativeMusicWorkspace(destinations, "artists-view", widthClass),
            )

            assertEquals("artists-view", intent.activeDestination.viewId)
            assertEquals(
                when (widthClass) {
                    NativeMusicWorkspaceWidthClass.Compact -> NativeMusicNavigationPlacement.TabStrip
                    NativeMusicWorkspaceWidthClass.Medium -> NativeMusicNavigationPlacement.Rail
                    NativeMusicWorkspaceWidthClass.Expanded -> NativeMusicNavigationPlacement.Sidebar
                },
                intent.placement,
            )
        }
    }

    @Test
    fun `selected album keeps its root library section active while tracks are open`() {
        val destinations = listOf(
            destination("albums", "Albums"),
            destination("artists", "Artists"),
            destination("tracks", "Tracks"),
        )

        val activeViewId = nativeMusicActiveNavigationViewId(
            destinations = destinations,
            selectedViewId = "albumTracks-view",
            navigationHistoryViewIds = listOf("albums-view", "album-detail-view"),
        )
        val intent = assertNotNull(
            planNativeMusicWorkspace(
                destinations = destinations,
                selectedViewId = activeViewId,
                widthClass = NativeMusicWorkspaceWidthClass.Compact,
            ),
        )

        assertEquals("albums-view", activeViewId)
        assertEquals("albums-view", intent.activeDestination.viewId)
    }

    @Test
    fun `root selection clears child bindings before loading another library section`() {
        val destination = NativeMusicWorkspaceDestination(
            viewId = "artists-view",
            label = "Artists",
            resourceId = "artists",
            pathParameterValues = mapOf("collection" to "artists"),
            kind = NativeMusicSectionKind.Artists,
        )

        val selection = selectNativeMusicRoot(destination)

        assertEquals("artists-view", selection.viewId)
        assertEquals(mapOf("collection" to "artists"), selection.pathParameterValues)
        assertTrue(selection.navigationHistoryViewIds.isEmpty())
        assertNull(selection.selectedRecord)
        assertNull(selection.selectedRecordResourceId)
    }

    @Test
    fun `stable contract identifiers classify localized collection labels`() {
        val intent = assertNotNull(
            planNativeMusicWorkspace(
                destinations = listOf(
                    destination("albumCatalog", "Albumes"),
                    destination("artistDirectory", "Artistas"),
                    destination("trackIndex", "Pistas"),
                ),
                selectedViewId = "trackIndex-view",
                widthClass = NativeMusicWorkspaceWidthClass.Medium,
            ),
        )

        assertEquals(
            listOf(
                NativeMusicSectionKind.Albums,
                NativeMusicSectionKind.Artists,
                NativeMusicSectionKind.Tracks,
            ),
            intent.destinations.map(NativeMusicWorkspaceDestination::kind),
        )
    }

    @Test
    fun `verified semantic evidence classifies opaque localized collection contracts`() {
        val intent = assertNotNull(
            planNativeMusicWorkspace(
                destinations = listOf(
                    destination(
                        resourceId = "catalogA",
                        label = "Coleccion",
                        evidence = listOf(Evidence(EvidenceSource.verifiedAdapter, "semantic: albums")),
                    ),
                    destination("artistDirectory", "Artistas"),
                    destination("trackIndex", "Pistas"),
                ),
                selectedViewId = "catalogA-view",
                widthClass = NativeMusicWorkspaceWidthClass.Expanded,
            ),
        )

        assertEquals(NativeMusicSectionKind.Albums, intent.activeDestination.kind)
    }

    @Test
    fun `descriptive artist label cannot turn a non-library settings view into an artist section`() {
        val intent = assertNotNull(
            planNativeMusicWorkspace(
                destinations = listOf(
                    destination("albums", "Albums"),
                    destination("tracks", "Tracks"),
                    destination("metadata", "Artist settings", NativeComponent.detail),
                ),
                selectedViewId = "albums-view",
                widthClass = NativeMusicWorkspaceWidthClass.Medium,
            ),
        )

        assertEquals(
            NativeMusicSectionKind.Other,
            intent.destinations.single { it.viewId == "metadata-view" }.kind,
        )
    }

    @Test
    fun `collection artwork uses authoritative selected parent before first track fallback`() {
        val albumResource = ResourceSpec("albums", "Albums", Confidence.verified)
        val trackResource = ResourceSpec("tracks", "Tracks", Confidence.verified)
        val parent = NativeRecord(
            id = "album-42",
            values = mapOf(
                "title" to "Selected album",
                "coverUrl" to "/artwork/album-42.jpg",
            ),
        )
        val context = assertNotNull(nativeAudioCollectionContext(albumResource, parent))
        val track = NativeRecord(
            id = "track-7",
            values = mapOf(
                "title" to "First track",
                "coverUrl" to "/artwork/track-7.jpg",
            ),
        )
        val resolverCalls = mutableListOf<String>()
        val resolver = NativeMediaArtworkResolver { resource, record ->
            resolverCalls += "${resource.id}:${record.id}"
            NativeMediaArtworkReference(
                relativePath = "/artwork/${resource.id}-${record.id}.jpg",
                cacheKey = "${resource.id}:${record.id}",
                fallback = NativeMediaArtworkFallback.Album,
            )
        }

        val artwork = nativeAudioCollectionArtworkReference(
            collectionContext = context,
            childResource = trackResource,
            firstPlayableRecord = track,
            resolver = resolver,
        )

        assertEquals("/artwork/albums-album-42.jpg", artwork.relativePath)
        assertEquals(NativeMediaArtworkFallback.Album, artwork.fallback)
        assertEquals(listOf("albums:album-42"), resolverCalls)
    }

    @Test
    fun `renderer dataset context resolves only its declared parent collection`() {
        val albums = ResourceSpec("albums", "Albums", Confidence.verified)
        val tracks = ResourceSpec("tracks", "Tracks", Confidence.verified)
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("generic-library", "Media library", "1"),
            confidence = Confidence.verified,
            resources = listOf(albums, tracks),
        )
        val parent = NativeRecord("album-42", values = mapOf("name" to "Selected album"))

        val context = nativeAudioCollectionContext(
            schema,
            NativeDatasetContext(parentResourceId = "albums", parentRecord = parent),
        )

        assertEquals(NativeAudioCollectionKind.Album, context?.kind)
        assertEquals(albums, context?.parentResource)
        assertEquals(parent, context?.parentRecord)
        assertNull(
            nativeAudioCollectionContext(
                schema,
                NativeDatasetContext(parentResourceId = "missing", parentRecord = parent),
            ),
        )
    }

    @Test
    fun `collection play action queues every record and starts the first playable record`() {
        val resource = ResourceSpec("tracks", "Tracks", Confidence.verified)
        val context = assertNotNull(
            nativeAudioCollectionContext(
                ResourceSpec("albums", "Albums", Confidence.verified),
                NativeRecord("album-42", values = mapOf("name" to "Selected album")),
            ),
        )
        val unavailable = NativeRecord(
            "note-1",
            values = mapOf("title" to "Album notes", "artist" to "Example artist"),
        )
        val playable = NativeRecord(
            "track-7",
            values = mapOf(
                "title" to "First playable track",
                "artist" to "Example artist",
                "fileId" to "7001",
                "mimeType" to "audio/mpeg",
            ),
        )
        val laterPlayable = playable.copy(id = "track-8", values = playable.values + ("title" to "Later track"))
        var received: List<NativeRecord>? = null
        var selected: NativeRecord? = null
        var receivedContext: NativeAudioCollectionContext? = null
        val player = NativeAudioRecordPlayer { _, records, start, parentContext ->
            received = records
            selected = start
            receivedContext = parentContext
        }

        assertTrue(player.playCollectionIfPossible(resource, listOf(unavailable, playable, laterPlayable), context))
        assertEquals(listOf(unavailable, playable, laterPlayable), received)
        assertEquals(playable, selected)
        assertEquals(context, receivedContext)
    }

    @Test
    fun `collection play action is a no op when no audio representation is declared`() {
        val resource = ResourceSpec("tracks", "Tracks", Confidence.verified)
        val context = assertNotNull(
            nativeAudioCollectionContext(
                ResourceSpec("albums", "Albums", Confidence.verified),
                NativeRecord("album-42", values = mapOf("name" to "Selected album")),
            ),
        )
        var called = false
        val player = NativeAudioRecordPlayer { _, _, _, _ -> called = true }

        assertTrue(!player.playCollectionIfPossible(
            resource,
            listOf(NativeRecord("note-1", values = mapOf("title" to "Album notes"))),
            context,
        ))
        assertTrue(!called)
    }

    @Test
    fun `collection artwork falls back to a playable track only when the parent has no image`() {
        val albumResource = ResourceSpec("albums", "Albums", Confidence.verified)
        val trackResource = ResourceSpec("tracks", "Tracks", Confidence.verified)
        val context = assertNotNull(
            nativeAudioCollectionContext(
                albumResource,
                NativeRecord(id = "album-42", values = mapOf("title" to "Selected album")),
            ),
        )
        val track = NativeRecord(
            id = "track-7",
            values = mapOf(
                "title" to "First track",
                "coverUrl" to "/artwork/track-7.jpg",
            ),
        )

        val artwork = nativeAudioCollectionArtworkReference(
            collectionContext = context,
            childResource = trackResource,
            firstPlayableRecord = track,
            resolver = null,
        )

        assertEquals("/artwork/track-7.jpg", artwork.relativePath)
        assertEquals(NativeMediaArtworkFallback.Track, artwork.fallback)
    }

    private fun destination(
        resourceId: String,
        label: String,
        component: NativeComponent = NativeComponent.mediaLibrary,
        evidence: List<Evidence> = emptyList(),
    ): Pair<DynamicNavigationDestination, ViewSpec> {
        val viewId = "$resourceId-view"
        return DynamicNavigationDestination(
            layoutId = viewId,
            label = label,
            resourceId = resourceId,
            actionId = "$resourceId-list",
        ) to ViewSpec(
            id = viewId,
            title = label,
            resourceId = resourceId,
            component = component,
            sourceActionId = "$resourceId-list",
            confidence = Confidence.verified,
            evidence = evidence,
        )
    }
}
