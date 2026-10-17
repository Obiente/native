package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MediaVideoOriginalTest {
    private val galleryVideo = NextcloudFile(
        path = "memories/collections/timeline/20000/41",
        name = "clip.mp4",
        isDirectory = false,
        mimeType = "video/mp4",
        size = null,
        lastModified = null,
        fileId = 41L,
        hasPreview = true,
        etag = "gallery-etag",
        originalAccessAllowed = false,
        davPathAuthoritative = false,
        memoriesRenderAllowed = true,
        mediaWidth = 1_920,
        mediaHeight = 1_080,
        mediaDurationSeconds = 75,
    )
    private val filesVideo = NextcloudFile(
        path = "Photos/2026/clip.mp4",
        name = "clip.mp4",
        isDirectory = false,
        mimeType = "video/mp4",
        size = 4_096L,
        lastModified = null,
        fileId = 41L,
        hasPreview = true,
        etag = "current-etag",
    )

    @Test
    fun onlyPreviewOnlyVideosWithAStableIdNeedAFilesLookup() {
        assertTrue(galleryVideo.needsVideoOriginalResolution("alice"))
        assertFalse(galleryVideo.needsVideoOriginalResolution(""))
        assertFalse(galleryVideo.copy(fileId = null).needsVideoOriginalResolution("alice"))
        assertFalse(galleryVideo.copy(fileId = 0L).needsVideoOriginalResolution("alice"))
        assertFalse(filesVideo.needsVideoOriginalResolution("alice"))
        assertFalse(
            galleryVideo.copy(name = "photo.jpg", mimeType = "image/jpeg")
                .needsVideoOriginalResolution("alice"),
        )
    }

    @Test
    fun acceptsOnlyTheSameReadableVideoAndKeepsGalleryMetadataItLacks() {
        val accepted = authoritativeVideoOriginalOrNull(galleryVideo, filesVideo, "alice")

        assertEquals("Photos/2026/clip.mp4", accepted?.path)
        assertEquals("current-etag", accepted?.etag, "The DAV record is the current generation.")
        assertEquals(4_096L, accepted?.size)
        assertEquals(75, accepted?.mediaDurationSeconds)
        assertEquals(1_920, accepted?.mediaWidth)
        assertTrue(accepted?.hasAuthoritativeMediaDavAccess("alice") == true)

        assertNull(authoritativeVideoOriginalOrNull(galleryVideo, null, "alice"))
        assertNull(authoritativeVideoOriginalOrNull(galleryVideo, filesVideo.copy(fileId = 42L), "alice"))
        assertNull(authoritativeVideoOriginalOrNull(galleryVideo, filesVideo.copy(isDirectory = true), "alice"))
        assertNull(
            authoritativeVideoOriginalOrNull(galleryVideo, filesVideo.copy(originalAccessAllowed = false), "alice"),
        )
        assertNull(
            authoritativeVideoOriginalOrNull(galleryVideo, filesVideo.copy(davPathAuthoritative = false), "alice"),
        )
        assertNull(
            authoritativeVideoOriginalOrNull(
                galleryVideo,
                filesVideo.copy(path = "Photos/clip.jpg", name = "clip.jpg", mimeType = "image/jpeg"),
                "alice",
            ),
        )
        for (unsafe in listOf("/Photos/clip.mp4", "Photos/../clip.mp4", "Photos//clip.mp4", "")) {
            assertNull(
                authoritativeVideoOriginalOrNull(galleryVideo, filesVideo.copy(path = unsafe), "alice"),
                "Path '$unsafe' must not become a DAV read target.",
            )
        }
        assertNull(authoritativeVideoOriginalOrNull(galleryVideo, filesVideo, ""))
    }

    @Test
    fun resolutionReportsResolvedPreviewOnlyAndFailedLookups() = runBlocking<Unit> {
        val lookups = mutableListOf<Long>()
        val resolved = resolveMediaVideoOriginal(galleryVideo, "alice") { id -> lookups += id; filesVideo }
        assertEquals(MediaVideoOriginalState.Resolved(authoritativeVideoOriginalOrNull(galleryVideo, filesVideo, "alice")!!), resolved)
        assertEquals(listOf(41L), lookups)

        assertEquals(
            MediaVideoOriginalState.PreviewOnly,
            resolveMediaVideoOriginal(galleryVideo, "alice") { null },
        )
        assertEquals(
            MediaVideoOriginalState.PreviewOnly,
            resolveMediaVideoOriginal(galleryVideo, "alice") { filesVideo.copy(fileId = 7L) },
        )
        assertEquals(
            MediaVideoOriginalState.LookupFailed,
            resolveMediaVideoOriginal(galleryVideo, "alice") { error("synthetic transport failure") },
        )
        assertEquals(
            MediaVideoOriginalState.Direct,
            resolveMediaVideoOriginal(filesVideo, "alice") { error("An authoritative record must not be looked up.") },
        )
        for (unresolvable in listOf(galleryVideo.copy(fileId = null), galleryVideo.copy(fileId = 0L))) {
            assertEquals(
                MediaVideoOriginalState.PreviewOnly,
                resolveMediaVideoOriginal(unresolvable, "alice") { error("Nothing to look up.") },
                "A gallery video without a usable file ID explains that only a preview exists.",
            )
        }
    }

    @Test
    fun aDownloadRestrictedNonGalleryVideoIsNeverResolvedToItsOriginal() = runBlocking<Unit> {
        // Like a Talk attachment shared with downloads hidden: a real Files path, access denied.
        val restricted = filesVideo.copy(originalAccessAllowed = false)

        assertFalse(restricted.needsVideoOriginalResolution("alice"))
        assertEquals(MediaVideoOriginalState.Direct, restricted.initialVideoOriginalState("alice"))
        assertEquals(
            MediaVideoOriginalState.Direct,
            resolveMediaVideoOriginal(restricted, "alice") { error("A policy restriction must not be looked up.") },
        )
        assertSame(restricted, MediaVideoOriginalState.Direct.playbackFileFor(restricted))
        assertNull(
            restricted.nativeVideoPlaybackSource("alice", nativePlaybackAvailable = true, livePhotoSource = null, motionPlaying = false),
            "The original stays unavailable for playback.",
        )
    }

    @Test
    fun theViewerStartsFromAnHonestState() {
        assertEquals(MediaVideoOriginalState.Resolving, galleryVideo.initialVideoOriginalState("alice"))
        assertEquals(MediaVideoOriginalState.PreviewOnly, galleryVideo.copy(fileId = null).initialVideoOriginalState("alice"))
        assertEquals(MediaVideoOriginalState.Direct, filesVideo.initialVideoOriginalState("alice"))
        assertEquals(
            MediaVideoOriginalState.Direct,
            galleryVideo.copy(name = "photo.jpg", mimeType = "image/jpeg").initialVideoOriginalState("alice"),
        )
    }

    @Test
    fun cancellationIsNotReportedAsAFailedLookup() {
        assertFailsWith<CancellationException> {
            runBlocking {
                resolveMediaVideoOriginal(galleryVideo, "alice") { throw CancellationException("closed") }
            }
        }
    }

    @Test
    fun aResolvedGalleryVideoStreamsFromItsFilesPath() {
        val unresolved = MediaVideoOriginalState.PreviewOnly.playbackFileFor(galleryVideo)
        assertSame(galleryVideo, unresolved)
        assertNull(
            unresolved.nativeVideoPlaybackSource(
                userId = "alice",
                nativePlaybackAvailable = true,
                livePhotoSource = null,
                motionPlaying = false,
            ),
            "A preview-only gallery record has no DAV path to stream.",
        )

        val playable = resolveBlocking().playbackFileFor(galleryVideo)
        val source = playable.nativeVideoPlaybackSource(
            userId = "alice",
            nativePlaybackAvailable = true,
            livePhotoSource = null,
            motionPlaying = false,
        )
        assertEquals("Photos/2026/clip.mp4", assertIs<NativeVideoPlaybackSource.DavFile>(source).file.path)
        assertEquals(
            "Photos/2026/clip.mp4",
            playable.wholeFileVideoCompatibilityPlanOrNull("alice")?.file?.path,
            "The compatibility player can seek the resolved original by size and ETag.",
        )
    }

    @Test
    fun tileBadgeMarksVideosWithTheirKnownDuration() {
        assertEquals("1:15", mediaTileVideoBadgeLabel(galleryVideo))
        assertEquals("", mediaTileVideoBadgeLabel(filesVideo), "Unknown duration still shows the video cue.")
        assertEquals("1:01:01", mediaTileVideoBadgeLabel(galleryVideo.copy(mediaDurationSeconds = 3_661)))
        assertNull(mediaTileVideoBadgeLabel(galleryVideo.copy(name = "photo.jpg", mimeType = "image/jpeg")))
        assertNull(mediaTileVideoBadgeLabel(filesVideo.copy(isDirectory = true)))
    }

    private fun resolveBlocking(): MediaVideoOriginalState = runBlocking {
        resolveMediaVideoOriginal(galleryVideo, "alice") { filesVideo }
    }
}
