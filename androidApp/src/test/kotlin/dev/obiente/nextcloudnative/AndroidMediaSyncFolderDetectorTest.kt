package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.MediaSyncFolderKind
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(suggestions[0].localRootHint.endsWith("primary%3ADCIM%2FCamera"))
    }

    @Test
    fun videoOnlyFoldersReceiveAVideoDestination() {
        val suggestion = buildMediaSyncFolderSuggestions(
            listOf(DetectedMediaFolderItem("Movies/Clips", isImage = false)),
        ).single()

        assertEquals(MediaSyncFolderKind.Videos, suggestion.kind)
        assertEquals("Videos/Clips", suggestion.suggestedRemoteRootPath)
    }
}
