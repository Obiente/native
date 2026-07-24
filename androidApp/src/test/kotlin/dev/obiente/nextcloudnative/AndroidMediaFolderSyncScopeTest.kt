package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncDirection
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidMediaFolderSyncScopeTest {
    @Test
    fun previewAndUploaderScopeContainsOnlyDirectVisibleMediaFiles() {
        val root = Files.createTempDirectory("media-scope-").toFile()
        try {
            root.resolve("photo.jpg").writeBytes(byteArrayOf(1))
            root.resolve("negative.raf").writeBytes(byteArrayOf(2))
            root.resolve("clip.mp4").writeBytes(byteArrayOf(3))
            root.resolve("notes.txt").writeText("not media")
            root.resolve(".hidden.jpg").writeBytes(byteArrayOf(4))
            root.resolve("folder.jpg").mkdir()
            root.resolve("nested").also { nested ->
                nested.mkdir()
                nested.resolve("inside.jpg").writeBytes(byteArrayOf(5))
            }

            val uploaderFiles = mediaFolderSyncFiles(root).map { it.name }
            val previewFiles = inspectMediaFolderSyncScope(root, maximumPreviewItems = 12)

            assertEquals(listOf("clip.mp4", "negative.raf", "photo.jpg"), uploaderFiles)
            assertEquals(uploaderFiles.toSet(), previewFiles.previewFiles.map { it.name }.toSet())
            assertEquals(uploaderFiles.size, previewFiles.totalItems)
            assertTrue(isMediaFolderSyncVideo(root.resolve("clip.mp4")))
            assertFalse(isMediaFolderSyncVideo(root.resolve("photo.jpg")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyPreviewLookupUsesExactPathEvenWithLikeMetacharacters() {
        val root = Files.createTempDirectory("media_%_scope-").toFile()
        try {
            val file = root.resolve("100%_actual.jpg").also { it.writeBytes(byteArrayOf(1)) }
            val lookup = mediaStorePreviewSelection(
                modernStorage = false,
                relativePath = "DCIM/Camera%_",
                file = file,
            )

            assertEquals("${android.provider.MediaStore.Files.FileColumns.DATA} = ?", lookup.selection)
            assertEquals(listOf(file.absolutePath), lookup.arguments)
            assertFalse("LIKE" in lookup.selection)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun inspectionRetainsOnlyBoundedRecentFilesWhileCountingTheWholeScope() {
        val root = Files.createTempDirectory("media-bounded-").toFile()
        try {
            repeat(20) { index ->
                root.resolve("photo-$index.jpg").also { file ->
                    file.writeBytes(ByteArray(index + 1))
                    file.setLastModified(1_000L + index)
                }
            }

            val inspection = inspectMediaFolderSyncScope(root, maximumPreviewItems = 3)

            assertEquals(20, inspection.totalItems)
            assertEquals(210L, inspection.totalBytes)
            assertEquals(
                listOf("photo-19.jpg", "photo-18.jpg", "photo-17.jpg"),
                inspection.previewFiles.map { it.name },
            )
            assertFalse(inspection.exceedsSyncLimit)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stablePreviewIdentityChangesWithFileRevision() {
        val initial = stableMediaFileId("photo.jpg", size = 10L, modifiedAt = 100L)

        assertEquals(initial, stableMediaFileId("photo.jpg", size = 10L, modifiedAt = 100L))
        assertNotEquals(initial, stableMediaFileId("photo.jpg", size = 11L, modifiedAt = 100L))
        assertNotEquals(initial, stableMediaFileId("photo.jpg", size = 10L, modifiedAt = 101L))
    }

    @Test
    fun detectedMediaRootsAcceptUploadOnlyDirection() {
        val root = "media-store://primary/DCIM/Camera"

        assertTrue(supportsAndroidFileSyncDirection(root, FileSyncDirection.UploadOnly))
        assertFalse(supportsAndroidFileSyncDirection(root, FileSyncDirection.DownloadOnly))
        assertFalse(supportsAndroidFileSyncDirection(root, FileSyncDirection.Bidirectional))
        assertTrue(
            supportsAndroidFileSyncDirection(
                "content://documents/tree/primary%3ANotes",
                FileSyncDirection.Bidirectional,
            ),
        )
    }
}
