package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioFileReference
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioCollectionKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMediaArtworkFallback
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredEntry
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredScalarKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioCollectionContext
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAudioPlaybackTest {
    @Test
    fun `audio track uses mime to file id map rather than database track id`() {
        val track = nativeAudioTrack(trackResource, trackRecord())

        assertEquals("Database track 42", track?.title)
        assertEquals(
            listOf(
                NativeAudioFileReference(9001, "audio/mpeg"),
                NativeAudioFileReference(9002, "audio/flac"),
            ),
            track?.files,
        )
        assertEquals(185_500L, track?.durationMillis)
    }

    @Test
    fun `compact track inherits album label and identifier from selected collection`() {
        val album = NativeRecord(
            id = "784",
            values = mapOf("name" to "Selected album"),
        )
        val context = nativeAudioCollectionContext(
            ResourceSpec("albums", "Albums", Confidence.verified),
            album,
        )
        val track = nativeAudioTrack(
            trackResource,
            trackRecord().copy(
                structuredValues = trackRecord().structuredValues + (
                    "album" to NativeStructuredValue.ObjectValue(
                        entries = listOf(
                            NativeStructuredEntry(
                                key = "id",
                                label = "Id",
                                value = NativeStructuredValue.Scalar(
                                    "784",
                                    NativeStructuredScalarKind.number,
                                ),
                            ),
                        ),
                    )
                    ),
            ),
            context,
        )

        assertEquals(NativeAudioCollectionKind.Album, context?.kind)
        assertEquals("Selected album", track?.album)
        assertEquals(784L, track?.albumId)
    }

    @Test
    fun `paired file id and audio mime form a reusable playable source`() {
        val record = NativeRecord(
            id = "track-7",
            values = mapOf(
                "title" to "Portable shape",
                "artistName" to "Example artist",
                "fileId" to "7001",
                "mimeType" to "audio/ogg",
                "durationMs" to "90123",
            ),
        )

        val track = assertNotNull(nativeAudioTrack(trackResource, record))

        assertEquals(
            listOf(NativeAudioFileReference(7001, "audio/ogg")),
            track.files,
        )
        assertEquals(90_123L, track.durationMillis)
    }

    @Test
    fun `artwork cache keys normalize front controller and query order`() {
        assertEquals(
            "/apps/audio_library/api/albums/7/cover?a=1&b=2",
            stableDynamicAssetCacheKey(
                "/index.php/apps/audio_library/api/albums/7/cover?b=2&a=1",
            ),
        )
        assertTrue("image/jpeg; charset=utf-8".isSupportedDynamicArtworkContentType())
        assertTrue("application/octet-stream".isSupportedDynamicArtworkContentType())
        assertFalse("application/json".isSupportedDynamicArtworkContentType())
        assertFalse(null.isSupportedDynamicArtworkContentType())
    }

    @Test
    fun `signed collection route yields exact same-origin file source`() {
        val capability = nativeAudioSourceCapability(
            discovery = discovery(
                DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes,
                actions = listOf(
                    trackAction("/index.php/apps/audio_library/api/tracks"),
                    albumCoverAction("/index.php/apps/audio_library/api/albums/{albumId}/cover"),
                ),
            ),
            action = trackAction("/index.php/apps/audio_library/api/tracks"),
        )
        val source = capability?.source(nativeTrack().copy(albumId = 784))

        assertEquals("/index.php/apps/audio_library/api/files/9001/download", source?.relativePath)
        assertEquals("audio/mpeg", source?.mimeType)
        assertEquals(
            "/index.php/apps/audio_library/api/albums/784/cover",
            source?.artworkRelativePath,
        )
    }

    @Test
    fun `verified artwork routes resolve albums and track album fallback without probing artists`() {
        val resolver = assertNotNull(
            nativeMediaArtworkResolver(
                discovery(
                    DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes,
                    actions = listOf(
                        trackAction("/apps/audio_library/api/tracks"),
                        coverAction(
                            "album-cover",
                            "albums",
                            "/apps/audio_library/api/albums/{albumId}/cover",
                            "albumId",
                        ),
                        coverAction(
                            "artist-cover",
                            "artists",
                            "/apps/audio_library/api/artists/{artistId}/cover",
                            "artistId",
                        ),
                    ),
                ),
            ),
        )
        val album = resolver.resolve(
            ResourceSpec("albums", "Albums", Confidence.verified),
            NativeRecord(
                id = "784",
                values = mapOf(
                    "name" to "Album",
                    "cover" to "/apps/audio_library/api/albums/784/cover",
                ),
            ),
        )
        val artistWithoutImage = resolver.resolve(
            ResourceSpec("artists", "Artists", Confidence.verified),
            NativeRecord(id = "659", values = mapOf("name" to "Artist", "image" to null)),
        )
        val track = resolver.resolve(
            trackResource,
            trackRecord().copy(
                structuredValues = trackRecord().structuredValues + (
                    "album" to structuredId("784")
                    ),
            ),
        )
        val untrustedCrossAppCover = resolver.resolve(
            ResourceSpec("albums", "Albums", Confidence.verified),
            NativeRecord(
                id = "784",
                values = mapOf("name" to "Album", "cover" to "/apps/other/api/albums/784/cover"),
            ),
        )

        assertEquals("/apps/audio_library/api/albums/784/cover", album.relativePath)
        assertEquals(NativeMediaArtworkFallback.Artist, artistWithoutImage.fallback)
        assertNull(artistWithoutImage.relativePath)
        assertEquals("/apps/audio_library/api/albums/784/cover", track.relativePath)
        assertEquals("track:42:/apps/audio_library/api/albums/784/cover", track.cacheKey)
        assertNull(untrustedCrossAppCover.relativePath)
    }

    @Test
    fun `artwork inference rejects unsigned and cross app routes`() {
        val unsigned = discovery(
            DynamicDescriptorAcquisition.StaticAppAsset,
            actions = listOf(
                albumCoverAction("/apps/audio_library/api/albums/{albumId}/cover"),
            ),
        )
        val crossApp = discovery(
            DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes,
            actions = listOf(
                albumCoverAction("/apps/other/api/albums/{albumId}/cover"),
            ),
        )

        assertNull(nativeMediaArtworkResolver(unsigned))
        assertNull(nativeMediaArtworkResolver(crossApp))
    }

    @Test
    fun `untrusted track metadata is normalized before playback`() {
        val capability = assertNotNull(
            nativeAudioSourceCapability(
                discovery = discovery(DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes),
                action = trackAction("/apps/audio_library/api/tracks"),
            ),
        )
        val source = assertNotNull(
            capability.source(
                nativeTrack().copy(
                    title = "  Track\u0000\nname  ",
                    artist = "Artist\tname",
                    album = "a".repeat(600),
                ),
            ),
        )

        assertEquals("Track name", source.title)
        assertEquals("Artist name", source.artist)
        assertEquals(512, source.album?.length)
    }

    @Test
    fun `advertised download path must exactly match the derived app api route`() {
        val capability = assertNotNull(
            nativeAudioSourceCapability(
                discovery(DynamicDescriptorAcquisition.StaticAppAsset),
                trackAction("/apps/audio_library/api/tracks"),
            ),
        )
        val valid = nativeTrack(
            files = listOf(
                NativeAudioFileReference(
                    fileId = null,
                    mimeType = "audio/mpeg",
                    advertisedRelativePath = "/index.php/apps/audio_library/api/files/9001/download",
                ),
            ),
        )
        val crossApp = nativeTrack(
            files = listOf(
                NativeAudioFileReference(
                    fileId = null,
                    mimeType = "audio/mpeg",
                    advertisedRelativePath = "/apps/other/api/files/9001/download",
                ),
            ),
        )

        assertEquals(
            "/index.php/apps/audio_library/api/files/9001/download",
            capability.source(valid)?.relativePath,
        )
        assertNull(capability.source(crossApp))
    }

    @Test
    fun `metadata-only and unrelated collection routes cannot authorize playback`() {
        assertNull(
            nativeAudioSourceCapability(
                discovery(DynamicDescriptorAcquisition.MetadataFallback),
                trackAction("/apps/audio_library/api/tracks"),
            ),
        )
        assertNull(
            nativeAudioSourceCapability(
                discovery(DynamicDescriptorAcquisition.StaticAppAsset),
                trackAction("/apps/audio_library/api/albums"),
            ),
        )
        assertNull(
            nativeAudioSourceCapability(
                discovery(DynamicDescriptorAcquisition.StaticAppAsset),
                trackAction("/remote.php/dav/files/user/tracks"),
            ),
        )
    }

    @Test
    fun `queue preserves visible order and advances without wrapping`() {
        val first = nativeTrack("one", 1)
        val second = nativeTrack("two", 2)
        val queue = startNativeAudioQueue(listOf(first, second, first), "one")

        assertEquals(listOf("one", "two"), queue.tracks.map(NativeAudioTrack::recordId))
        assertEquals("two", queue.next().currentTrack?.recordId)
        assertNull(queue.next().next().currentTrack)
        assertEquals("one", queue.next().previous().currentTrack?.recordId)
    }

    @Test
    fun `playback error retains the selected queue item for recovery and inspection`() {
        val queue = startNativeAudioQueue(
            tracks = listOf(nativeTrack("one", 1), nativeTrack("two", 2)),
            selectedRecordId = "one",
        )
        val error = NativeAudioEngineState(
            sourceId = "one:9001:audio/mpeg",
            status = NativeAudioEngineStatus.Error,
            error = "The stream ended before playback started.",
        )

        assertEquals("one", queue.currentTrack?.recordId)
        assertEquals(NativeAudioEngineStatus.Error, error.status)
        assertEquals("The stream ended before playback started.", error.error)
        assertEquals(2, queue.tracks.size)
    }

    @Test
    fun `playback url never accepts credentials in server url`() {
        val source = NativeAudioPlaybackSource(
            id = "track",
            relativePath = "/apps/audio_library/api/files/9001/download",
            mimeType = "audio/mpeg",
        )

        assertFailsWith<IllegalArgumentException> {
            nativeAudioPlaybackUrl(
                NextcloudSession("https://user:secret@cloud.example", "user", "secret"),
                source,
            )
        }
        assertEquals(
            "https://cloud.example/apps/audio_library/api/files/9001/download",
            nativeAudioPlaybackUrl(
                NextcloudSession("https://cloud.example/", "user", "secret"),
                source,
            ),
        )
    }

    private fun discovery(
        acquisition: DynamicDescriptorAcquisition,
        actions: List<DynamicAction> = emptyList(),
    ) = DynamicDescriptorDiscovery(
        descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("audio_library", "Audio library", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example"),
            actions = actions,
        ),
        sourcePath = "/apps/audio_library/openapi.json",
        acquisition = acquisition,
    )

    private fun trackAction(path: String) = DynamicAction(
        id = "list-tracks",
        label = "Tracks",
        resourceId = "tracks",
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(method = HttpMethod.GET, path = path),
        confidence = Confidence.verified,
    )

    private fun albumCoverAction(path: String) = DynamicAction(
        id = "album-cover",
        label = "Album cover",
        resourceId = "albums",
        intent = ActionIntent.read,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.GET,
            path = path,
            pathParameters = listOf(
                dev.obiente.nextcloudnative.nativeui.model.HttpParameter(
                    name = "albumId",
                    required = true,
                    schema = buildJsonObject {},
                    source = ParameterSource.resourceField,
                ),
            ),
        ),
        confidence = Confidence.verified,
    )

    private fun coverAction(
        id: String,
        resourceId: String,
        path: String,
        parameter: String,
    ) = DynamicAction(
        id = id,
        label = "Cover",
        resourceId = resourceId,
        intent = ActionIntent.read,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.GET,
            path = path,
            pathParameters = listOf(
                dev.obiente.nextcloudnative.nativeui.model.HttpParameter(
                    name = parameter,
                    required = true,
                    schema = buildJsonObject {},
                    source = ParameterSource.resourceField,
                ),
            ),
        ),
        confidence = Confidence.verified,
    )

    private fun structuredId(id: String) = NativeStructuredValue.ObjectValue(
        entries = listOf(
            NativeStructuredEntry(
                key = "id",
                label = "Id",
                value = NativeStructuredValue.Scalar(id, NativeStructuredScalarKind.number),
            ),
        ),
    )

    private fun trackRecord() = NativeRecord(
        id = "42",
        values = mapOf(
            "title" to "Database track 42",
            "trackNumber" to "3",
            "duration" to "185.5",
        ),
        structuredValues = mapOf(
            "files" to NativeStructuredValue.ObjectValue(
                entries = listOf(
                    NativeStructuredEntry(
                        key = "audio/flac",
                        label = "FLAC",
                        value = NativeStructuredValue.Scalar("9002", NativeStructuredScalarKind.number),
                    ),
                    NativeStructuredEntry(
                        key = "audio/mpeg",
                        label = "MP3",
                        value = NativeStructuredValue.Scalar("9001", NativeStructuredScalarKind.number),
                    ),
                    NativeStructuredEntry(
                        key = "image/jpeg",
                        label = "Not audio",
                        value = NativeStructuredValue.Scalar("99", NativeStructuredScalarKind.number),
                    ),
                ),
            ),
        ),
    )

    private fun nativeTrack(
        id: String = "42",
        fileId: Long = 9001,
        files: List<NativeAudioFileReference> = listOf(NativeAudioFileReference(fileId, "audio/mpeg")),
    ) = NativeAudioTrack(
        recordId = id,
        title = id,
        artist = null,
        album = null,
        albumId = null,
        durationMillis = null,
        files = files,
    )

    private companion object {
        val trackResource = ResourceSpec(
            id = "tracks",
            name = "Tracks",
            confidence = Confidence.verified,
        )
    }
}
