package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoTimelineRemoteNavigationTest {
    @Test
    fun matchingMemoriesGenerationAtomicallyReplacesTheRetainedWindow() {
        val pending = PhotoTimelineState(
            entries = listOf(entry(1L, 900L)),
            nextCursor = PhotoTimelineCursor("older"),
            generation = 4L,
            rawStackFileIdsByEntryIdentity = mapOf("file:1" to listOf(101L)),
        ).beginNextPage().state
        val loadedEntries = listOf(
            entry(20L, 200L),
            entry(19L, 190L),
        )

        val applied = assertIs<PhotoTimelineNavigationApplyResult.Applied>(
            applyMemoriesTimelineNavigationResult(
                state = pending,
                snapshot = snapshot(sourceGeneration = 7L),
                result = MemoriesTimelineNavigationLoadResult.Loaded(
                    sourceGeneration = 7L,
                    targetDayId = 31L,
                    page = PhotoTimelinePage(
                        entries = loadedEntries,
                        nextCursor = null,
                        rawObserved = true,
                        rawStackFileIdsByEntryIdentity =
                            mapOf("file:20" to listOf(120L)),
                    ),
                    advertisedNewerItemCount = 42,
                ),
            ),
        )

        assertEquals(31L, applied.targetDayId)
        assertEquals(listOf(20L, 19L), applied.state.entries.map { it.file.fileId })
        assertNull(applied.state.loading)
        assertNull(applied.state.nextCursor)
        assertNull(applied.state.error)
        assertNull(applied.state.failedLoadKind)
        assertEquals(5L, applied.state.generation)
        assertEquals(42, applied.state.discardedNewerEntries)
        assertTrue(applied.state.loadedOlderPages)
        assertTrue(applied.state.rawEverObserved)
        assertEquals(
            mapOf("file:20" to listOf(120L)),
            applied.state.rawStackFileIdsByEntryIdentity,
        )
    }

    @Test
    fun mismatchedOrStaleGenerationNeverReplacesVisibleMedia() {
        val state = PhotoTimelineState(entries = listOf(entry(1L, 900L)))
        val snapshot = snapshot(sourceGeneration = 8L)
        val mismatched = applyMemoriesTimelineNavigationResult(
            state = state,
            snapshot = snapshot,
            result = MemoriesTimelineNavigationLoadResult.Loaded(
                sourceGeneration = 7L,
                targetDayId = 31L,
                page = PhotoTimelinePage(listOf(entry(20L, 200L)), null),
                advertisedNewerItemCount = 1,
            ),
        )
        val stale = applyMemoriesTimelineNavigationResult(
            state = state,
            snapshot = snapshot,
            result = MemoriesTimelineNavigationLoadResult.Stale,
        )

        assertTrue(
            assertIs<PhotoTimelineNavigationApplyResult.Retained>(mismatched).snapshotStale,
        )
        assertTrue(
            assertIs<PhotoTimelineNavigationApplyResult.Retained>(stale).snapshotStale,
        )
        assertEquals(listOf(1L), state.entries.map { it.file.fileId })
    }

    @Test
    fun unavailableTargetKeepsTheCurrentWindowAndSurfacesARecoverableMessage() {
        val retained = assertIs<PhotoTimelineNavigationApplyResult.Retained>(
            applyMemoriesTimelineNavigationResult(
                state = PhotoTimelineState(entries = listOf(entry(1L, 900L))),
                snapshot = snapshot(sourceGeneration = 2L),
                result = MemoriesTimelineNavigationLoadResult.Unavailable(
                    "That day is temporarily unavailable.",
                ),
            ),
        )

        assertFalse(retained.snapshotStale)
        assertEquals("That day is temporarily unavailable.", retained.message)
    }

    private fun snapshot(sourceGeneration: Long): MemoriesTimelineNavigationSnapshot =
        MemoriesTimelineNavigationSnapshot(
            sourceGeneration = sourceGeneration,
            geometry = requireNotNull(
                buildMemoriesTimelinePlaceholderGeometry(
                    MemoriesMainTimelineDayIndex(
                        listOf(
                            NativeMediaDay(60L, 2),
                            NativeMediaDay(31L, 1),
                        ),
                    ),
                ),
            ),
        )

    private fun entry(
        id: Long,
        capturedAt: Long,
    ): PhotoTimelineEntry = PhotoTimelineEntry(
        file = NextcloudFile(
            path = "Photos/$id.jpg",
            name = "$id.jpg",
            isDirectory = false,
            mimeType = "image/jpeg",
            size = 1L,
            lastModified = capturedAt.toString(),
            fileId = id,
            hasPreview = true,
        ),
        capturedAtEpochSeconds = capturedAt,
    )
}
