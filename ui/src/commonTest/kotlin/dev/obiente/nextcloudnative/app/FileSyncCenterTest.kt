package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSyncCenterTest {
    @Test
    fun `media suggestion exposes its detected root directly`() {
        val suggestion = MediaSyncFolderSuggestion(
            localRootHint = "media-store://primary/DCIM/Camera",
            displayName = "Camera",
            relativePath = "DCIM/Camera",
            kind = MediaSyncFolderKind.Camera,
            imageCount = 4,
            videoCount = 1,
            suggestedRemoteRootPath = "Photos/Camera",
        )

        assertEquals(
            FileSyncLocalRoot("media-store://primary/DCIM/Camera", "Camera"),
            suggestion.localRoot,
        )
    }

    @Test
    fun previewModelBoundsThumbnailPayloadsAndItemCount() {
        assertFailsWith<IllegalArgumentException> {
            MediaSyncFolderPreviewItem(
                stableId = "media:1",
                displayName = "Photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 10L,
                modifiedAtEpochMillis = 1L,
                thumbnailBytes = ByteArray(MAX_MEDIA_PREVIEW_THUMBNAIL_BYTES + 1),
            )
        }
        val item = MediaSyncFolderPreviewItem(
            stableId = "media:1",
            displayName = "Photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 10L,
            modifiedAtEpochMillis = 1L,
            thumbnailBytes = null,
        )
        assertFailsWith<IllegalArgumentException> {
            MediaSyncFolderPreview(
                localRootHint = "media-store://primary/DCIM/Camera",
                state = MediaSyncFolderPreviewState.Available,
                access = MediaSyncFolderAccess.FullLibrary,
                totalItems = MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS + 1,
                totalBytes = 100L,
                items = List(MAX_MEDIA_SYNC_FOLDER_PREVIEW_ITEMS + 1) { index ->
                    item.copy(stableId = "media:$index")
                },
            )
        }
    }

    @Test
    fun `summary exposes actionable work counts without leaking the root grant`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "content://opaque-grant",
            remoteRootPath = "Notes",
            configuration = FileSyncConfiguration(deviceLabel = "phone"),
            workItems = listOf(
                FileSyncWorkItem(
                    id = 1,
                    relativePath = "note.md",
                    observedLocal = LocalSyncEntry("note.md", SyncEntryKind.File, "local"),
                    observedRemote = RemoteSyncEntry("note.md", SyncEntryKind.File, "remote"),
                    observedBaseline = null,
                    operation = FileSyncOperation.NeedsDecision(
                        "note.md",
                        FileSyncDecisionReason.FirstSyncCollision,
                    ),
                    state = FileSyncExecutionState.AwaitingDecision,
                    decision = FileSyncDecision(
                        FileSyncDecisionReason.FirstSyncCollision,
                        setOf(
                            FileSyncDecisionChoice.UseLocal,
                            FileSyncDecisionChoice.UseRemote,
                            FileSyncDecisionChoice.KeepBoth,
                            FileSyncDecisionChoice.Skip,
                        ),
                    ),
                ),
            ),
            nextWorkId = 2,
        )

        val summary = pair.toCenterSummary("Vault")

        assertEquals("Vault", summary.localDisplayName)
        assertEquals(1, summary.conflicts.size)
        assertEquals(0, summary.failedCount)
    }
}
