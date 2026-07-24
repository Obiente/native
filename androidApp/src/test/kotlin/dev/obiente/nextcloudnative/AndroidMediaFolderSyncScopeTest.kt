package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncDecisionChoice
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.claimNextFileSyncOperation
import dev.obiente.nextcloudnative.app.resolveFileSyncDecision
import dev.obiente.nextcloudnative.app.scanFileSyncPair
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    @Test
    fun useRemoteTypeConflictCannotDeleteDetectedLocalMedia() {
        val root = Files.createTempDirectory("media-conflict-").toFile()
        try {
            val localFile = root.resolve("photo.jpg").also { it.writeBytes(byteArrayOf(1)) }
            val pair = FileSyncPair(
                id = "pair",
                accountId = "account",
                localRootId = "media-store://primary/DCIM/Camera",
                remoteRootPath = "Photos/Camera",
                configuration = FileSyncConfiguration(
                    direction = FileSyncDirection.UploadOnly,
                    deviceLabel = "device",
                ),
            )
            var state = FileSyncCoordinatorState(listOf(pair))
            state = scanFileSyncPair(
                state = state,
                pairId = pair.id,
                localEntries = listOf(
                    LocalSyncEntry("photo.jpg", SyncEntryKind.File, "local-revision", size = 1L),
                ),
                remoteEntries = listOf(
                    RemoteSyncEntry("photo.jpg", SyncEntryKind.Directory, "remote-etag"),
                ),
                nowEpochMillis = 1L,
            )
            val work = state.pairs.single().workItems.single()
            state = resolveFileSyncDecision(
                state,
                pair.id,
                work.id,
                FileSyncDecisionChoice.UseRemote,
            )
            val command = assertNotNull(
                claimNextFileSyncOperation(state, pair.id, nowEpochMillis = 2L).command,
            )

            assertIs<FileSyncOperation.Download>(command.operation)
            assertFalse(isAndroidFileSyncExecutionAllowed(pair.localRootId, command.operation))
            val tree = AndroidMediaStoreSyncLocalTree(root)
            assertFailsWith<UnsupportedOperationException> {
                tree.delete("photo.jpg", "local-revision")
            }
            assertTrue(localFile.exists())
            assertEquals(byteArrayOf(1).toList(), localFile.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun uploaderEnumerationFailsAtOneEntryBeyondItsBound() {
        val root = Files.createTempDirectory("media-over-limit-").toFile()
        try {
            repeat(4) { index ->
                root.resolve("photo-$index.jpg").writeBytes(byteArrayOf(index.toByte()))
            }

            val failure = assertFailsWith<IllegalArgumentException> {
                mediaFolderSyncFiles(root, maximumEntries = 3)
            }

            assertTrue("too many uploadable files" in failure.message.orEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
