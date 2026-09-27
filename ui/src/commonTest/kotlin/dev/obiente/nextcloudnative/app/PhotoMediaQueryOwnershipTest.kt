package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PhotoMediaQueryOwnershipTest {
    @Test
    fun timelineAndFolderDestinationsPlanOnlyTheirOwnQuery() {
        assertEquals(
            setOf(PhotoMediaQueryOwner.Timeline),
            photoMediaQueryOwners(PhotoDestination.Timeline),
        )
        assertEquals(
            setOf(PhotoMediaQueryOwner.FolderInventory),
            photoMediaQueryOwners(PhotoDestination.Folders),
        )
    }

    @Test
    fun nonTimelineDestinationsDoNotPlanTimelineOrFolderInventoryQueries() {
        listOf(
            PhotoDestination.Albums,
            PhotoDestination.People,
            PhotoDestination.Favorites,
        ).forEach { destination ->
            assertEquals(emptySet(), photoMediaQueryOwners(destination))
        }
    }

    @Test
    fun carryoverScopesAreStableAndSeparatedByQueryOwner() {
        val timelineScope = photoMediaCarryoverScope(
            accountScope = "account-a",
            owner = PhotoMediaQueryOwner.Timeline,
        )
        val folderScope = photoMediaCarryoverScope(
            accountScope = "account-a",
            owner = PhotoMediaQueryOwner.FolderInventory,
        )

        assertEquals("account-a|photos:timeline", timelineScope)
        assertEquals("account-a|photos:folder-inventory", folderScope)
        assertNotEquals(timelineScope, folderScope)
    }

    @Test
    fun queryOwnersKeepIndependentCarryoverGenerations() = runBlocking {
        val store = MediaTimelineDavCarryoverStore()
        val timelineScope = photoMediaCarryoverScope(
            accountScope = "account-a",
            owner = PhotoMediaQueryOwner.Timeline,
        )
        val folderScope = photoMediaCarryoverScope(
            accountScope = "account-a",
            owner = PhotoMediaQueryOwner.FolderInventory,
        )
        val account = NextcloudAccountId("a".repeat(64))
        val producer = requireNotNull(store.producer(account))
        val timelineGeneration = requireNotNull(store.beginAccountGeneration(account, timelineScope, producer))
        val folderGeneration = requireNotNull(store.beginAccountGeneration(account, folderScope, producer))
        val timelineCursor = PhotoTimelineCursor("timeline-cursor")
        val folderCursor = PhotoTimelineCursor("folder-cursor")
        val timelineCarryover = carryover("Photos/timeline.jpg", 1L)
        val folderCarryover = carryover("Photos/Trips/folder.jpg", 2L)

        store.put(account, timelineScope, timelineGeneration, timelineCursor, timelineCarryover, producer)
        store.put(account, folderScope, folderGeneration, folderCursor, folderCarryover, producer)

        assertEquals(
            timelineCarryover,
            store.take(account, timelineScope, timelineGeneration, timelineCursor, producer),
        )
        assertEquals(
            folderCarryover,
            store.take(account, folderScope, folderGeneration, folderCursor, producer),
        )
    }

    private fun carryover(
        path: String,
        fileId: Long,
    ): MediaTimelineDavCarryover =
        MediaTimelineDavCarryover(
            partitions = mapOf(
                MediaTimelinePartitionKey.Mime(MediaSearchDavPartition.ImageMime) to
                    MediaTimelinePartitionCarryover(
                        files = listOf(
                            NextcloudFile(
                                path = path,
                                name = path.substringAfterLast('/'),
                                isDirectory = false,
                                mimeType = "image/jpeg",
                                size = 1L,
                                lastModified = formatDavMediaSearchTimestamp(100L + fileId),
                                fileId = fileId,
                                hasPreview = true,
                            ),
                        ),
                        remoteCursorAfterFetched = null,
                    ),
            ),
        )
}
