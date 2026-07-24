package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.MediaSyncFolderKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMediaSyncFolderDetectorTest {
    @Test
    fun cameraAndScreenshotFoldersArePrioritizedAndCounted() {
        val suggestions = buildMediaSyncFolderSuggestions(
            listOf(
                DetectedMediaFolderItem("Pictures/Artwork", isImage = true),
                DetectedMediaFolderItem("Pictures/Screenshots", isImage = true),
                DetectedMediaFolderItem("Pictures/Screenshots", isImage = true),
                DetectedMediaFolderItem("DCIM/Camera", isImage = true),
                DetectedMediaFolderItem("DCIM/Camera", isImage = false),
            ),
        )

        assertEquals(listOf("Camera", "Screenshots", "Artwork"), suggestions.map { it.displayName })
        assertEquals(MediaSyncFolderKind.Camera, suggestions[0].kind)
        assertEquals(1, suggestions[0].imageCount)
        assertEquals(1, suggestions[0].videoCount)
        assertEquals("Photos/Camera", suggestions[0].suggestedRemoteRootPath)
        assertEquals("media-store://primary/DCIM/Camera", suggestions[0].localRootHint)
        assertEquals("Camera", suggestions[0].localRoot.displayName)
    }

    @Test
    fun videoOnlyFoldersReceiveAVideoDestination() {
        val suggestion = buildMediaSyncFolderSuggestions(
            listOf(DetectedMediaFolderItem("Movies/Clips", isImage = false)),
        ).single()

        assertEquals(MediaSyncFolderKind.Videos, suggestion.kind)
        assertEquals("Videos/Clips", suggestion.suggestedRemoteRootPath)
    }

    @Test
    fun suggestionsAggregateBytesWithoutKeepingPerItemState() {
        val suggestions = buildMediaSyncFolderSuggestions(
            listOf(
                DetectedMediaFolderItem("DCIM/Camera", isImage = true, sizeBytes = 4_000L),
                DetectedMediaFolderItem("DCIM/Camera", isImage = false, sizeBytes = 12_000L),
            ),
        )

        assertEquals(16_000L, suggestions.single().totalBytes)
    }

    @Test
    fun thumbnailCacheEvictsLeastRecentlyUsedEntry() {
        val cache = MediaFolderThumbnailCache(maximumEntries = 2)
        cache.put("old", byteArrayOf(1))
        cache.put("kept", byteArrayOf(2))
        cache.get("old")
        cache.put("new", byteArrayOf(3))

        assertEquals(setOf("old", "new"), cache.keys())
    }

    @Test
    fun fullLibraryAccessRequiresBothMediaTypesOnModernAndroid() {
        assertFalse(
            hasFullMediaLibraryAccess(36) { permission ->
                permission == android.Manifest.permission.READ_MEDIA_IMAGES
            },
        )
        assertTrue(
            hasFullMediaLibraryAccess(36) { permission ->
                permission in setOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                )
            },
        )
        assertTrue(
            hasFullMediaFolderAccess(
                sdk = 36,
                includesImages = true,
                includesVideos = false,
            ) { permission ->
                permission == android.Manifest.permission.READ_MEDIA_IMAGES
            },
        )
        assertFalse(
            hasFullMediaFolderAccess(
                sdk = 36,
                includesImages = true,
                includesVideos = true,
            ) { permission ->
                permission == android.Manifest.permission.READ_MEDIA_IMAGES
            },
        )
    }

    @Test
    fun mediaStoreRootResolutionStaysInsideSharedStorage() {
        val storage = Files.createTempDirectory("media-root-").toFile()
        val camera = storage.resolve("DCIM/Camera")
        camera.mkdirs()

        assertEquals(
            camera.canonicalFile,
            resolveMediaStoreSyncRoot("media-store://primary/DCIM/Camera", storage),
        )
        assertFailsWith<IllegalArgumentException> {
            resolveMediaStoreSyncRoot("media-store://primary/DCIM/../Secrets", storage)
        }
    }
}
