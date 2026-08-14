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
    fun automaticMediaSyncRequiresCompleteLibraryVisibilityAndAUsablePreview() {
        val suggestion = MediaSyncFolderSuggestion(
            localRootHint = "media-store://primary/DCIM/Camera",
            displayName = "Camera",
            relativePath = "DCIM/Camera",
            kind = MediaSyncFolderKind.Camera,
            imageCount = 1,
            videoCount = 1,
            suggestedRemoteRootPath = "Photos/Camera",
        )
        fun preview(access: MediaSyncFolderAccess, state: MediaSyncFolderPreviewState) =
            MediaSyncFolderPreview(
                localRootHint = suggestion.localRootHint,
                state = state,
                access = access,
                totalItems = if (state == MediaSyncFolderPreviewState.Empty) 0 else 2,
                totalBytes = 20L,
                items = emptyList(),
                message = null,
            )

        assertEquals(
            false,
            isMediaFolderPreviewReady(
                suggestion,
                preview(MediaSyncFolderAccess.LimitedSelection, MediaSyncFolderPreviewState.Available),
            ),
        )
        assertEquals(
            false,
            isMediaFolderPreviewReady(
                suggestion,
                preview(MediaSyncFolderAccess.FullLibrary, MediaSyncFolderPreviewState.Empty),
            ),
        )
        assertEquals(
            true,
            isMediaFolderPreviewReady(
                suggestion,
                preview(MediaSyncFolderAccess.FullLibrary, MediaSyncFolderPreviewState.Changed),
            ),
        )
        assertEquals(true, isMediaFolderPreviewReady(null, null))
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
                    observedLocal = LocalSyncEntry(
                        "note.md",
                        SyncEntryKind.File,
                        "local",
                        size = 120L,
                        modifiedEpochMillis = 1_000L,
                    ),
                    observedRemote = RemoteSyncEntry(
                        "note.md",
                        SyncEntryKind.File,
                        "remote",
                        size = 140L,
                        modifiedEpochMillis = 2_000L,
                    ),
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

        val summary = pair.toCenterSummary(
            localDisplayName = "Vault",
            runState = FileSyncPairRunState.Active,
            networkState = FileSyncNetworkState.Available,
        )

        assertEquals("Vault", summary.localDisplayName)
        assertEquals(1, summary.conflicts.size)
        assertEquals(
            FileSyncConflictSideSummary(SyncEntryKind.File, 120L, 1_000L),
            summary.conflicts.single().local,
        )
        assertEquals(
            FileSyncConflictSideSummary(SyncEntryKind.File, 140L, 2_000L),
            summary.conflicts.single().remote,
        )
        assertEquals(0, summary.failedCount)
    }

    @Test
    fun `summary counts verified sync baselines as completed paths`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "content://opaque-grant",
            remoteRootPath = "Photos",
            configuration = FileSyncConfiguration(deviceLabel = "phone"),
            baselines = listOf(
                FileSyncBaseline(
                    relativePath = "photo.jpg",
                    kind = SyncEntryKind.File,
                    localRevision = "local-photo",
                    remoteEtag = "remote-photo",
                ),
                FileSyncBaseline(
                    relativePath = "clip.mov",
                    kind = SyncEntryKind.File,
                    localRevision = "local-clip",
                    remoteEtag = "remote-clip",
                ),
            ),
        )

        assertEquals(
            2,
            pair.toCenterSummary(
                localDisplayName = "Camera",
                runState = FileSyncPairRunState.Active,
                networkState = FileSyncNetworkState.Available,
            ).completedCount,
        )
    }

    @Test
    fun `summary exposes why skipped work is paused`() {
        val reason = "Directory deletion is paused because selective or ignored items may exist below it."
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "content://opaque-grant",
            remoteRootPath = "Projects",
            configuration = FileSyncConfiguration(
                deviceLabel = "phone",
                deletionPolicy = FileSyncDeletionPolicy.Propagate,
                ignoredPatterns = listOf("**/.cache/**"),
            ),
            workItems = listOf(
                FileSyncWorkItem(
                    id = 1,
                    relativePath = "Archive",
                    observedLocal = null,
                    observedRemote = RemoteSyncEntry("Archive", SyncEntryKind.Directory, "remote"),
                    observedBaseline = FileSyncBaseline(
                        "Archive",
                        SyncEntryKind.Directory,
                        "local",
                        "remote",
                    ),
                    operation = FileSyncOperation.Skipped("Archive", reason),
                    state = FileSyncExecutionState.Skipped,
                ),
            ),
            nextWorkId = 2,
        )

        val summary = pair.toCenterSummary(
            localDisplayName = "Projects",
            runState = FileSyncPairRunState.Paused,
            networkState = FileSyncNetworkState.WaitingForNetwork,
        )

        assertEquals(1, summary.skippedCount)
        assertEquals(listOf(reason), summary.skippedReasons)
        assertEquals(FileSyncPairRunState.Paused, summary.runState)
        assertEquals(FileSyncNetworkState.WaitingForNetwork, summary.networkState)
    }

    @Test
    fun `live network state distinguishes offline metered and unknown connections`() {
        assertEquals(
            FileSyncNetworkState.WaitingForNetwork,
            liveFileSyncNetworkState(false, null, FileSyncNetworkPolicy.AnyConnection),
        )
        assertEquals(
            FileSyncNetworkState.WaitingForNetwork,
            liveFileSyncNetworkState(true, false, FileSyncNetworkPolicy.Unmetered),
        )
        assertEquals(
            FileSyncNetworkState.Available,
            liveFileSyncNetworkState(true, true, FileSyncNetworkPolicy.Unmetered),
        )
        assertEquals(
            FileSyncNetworkState.Unknown,
            liveFileSyncNetworkState(null, null, FileSyncNetworkPolicy.AnyConnection),
        )
    }
}
