package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhotoTimelinePagingTest {
    @Test
    fun refreshAndAppendKeepMoreThanEightyItemsInStableOrder() {
        val firstEntries = (1L..200L).map { id ->
            entry(id, capturedAt = 10_000L - id)
        }
        val refresh = PhotoTimelineState().beginRefresh()
        val refreshToken = requireNotNull(refresh.token)
        var state = refresh.state.accept(
            refreshToken,
            PhotoTimelinePage(firstEntries, PhotoTimelineCursor("page-2")),
        )

        val append = state.beginNextPage()
        val appendToken = requireNotNull(append.token)
        state = append.state.accept(
            appendToken,
            PhotoTimelinePage(
                entries = (190L..350L).map { id -> entry(id, capturedAt = 10_000L - id) },
                nextCursor = null,
            ),
        )

        assertEquals(350, state.entries.size)
        assertEquals((1L..350L).toList(), state.entries.map { it.file.fileId })
        assertFalse(state.canLoadNextPage)
        assertNull(state.nextCursor)
    }

    @Test
    fun newerPageRecordReplacesDuplicateFileIdentityAndPathFallbackDeduplicates() {
        val original = entry(
            id = 42L,
            capturedAt = 2_000L,
            path = "Photos/Old name.jpg",
        )
        val moved = entry(
            id = 42L,
            capturedAt = 3_000L,
            path = "Photos/New name.jpg",
        )
        val pathOnly = entry(
            id = null,
            capturedAt = 1_000L,
            path = "/Photos/Path only.jpg/",
        )
        val pathOnlyUpdated = entry(
            id = null,
            capturedAt = 1_500L,
            path = "Photos/Path only.jpg",
        )

        val merged = mergePhotoTimelineEntries(
            existing = listOf(original, pathOnly),
            incoming = listOf(moved, pathOnlyUpdated),
        )

        assertEquals(listOf(moved, pathOnlyUpdated), merged)
    }

    @Test
    fun retentionLimitStopsFurtherInMemoryPagingWithoutUnboundedGrowth() {
        val refresh = PhotoTimelineState(retentionLimit = 3).beginRefresh()
        val state = refresh.state.accept(
            requireNotNull(refresh.token),
            PhotoTimelinePage(
                entries = listOf(
                    entry(1L, 3L),
                    entry(2L, 2L),
                    entry(3L, 1L),
                ),
                nextCursor = PhotoTimelineCursor("older"),
            ),
        )

        assertEquals(3, state.entries.size)
        assertTrue(state.retentionLimitReached)
        assertFalse(state.canLoadNextPage)
        assertNull(state.beginNextPage().token)
    }

    @Test
    fun pageTokenCarriesAndEnforcesTheBoundedRequestSize() {
        val refresh = PhotoTimelineState(pageSize = 2).beginRefresh()
        val token = requireNotNull(refresh.token)

        assertEquals(2, token.pageSize)
        assertFailsWith<IllegalArgumentException> {
            refresh.state.accept(
                token,
                PhotoTimelinePage(
                    entries = listOf(entry(1L, 3L), entry(2L, 2L), entry(3L, 1L)),
                    nextCursor = null,
                ),
            )
        }
    }

    @Test
    fun cancelledAndSupersededLoadsIgnoreLateResults() {
        val first = PhotoTimelineState().beginRefresh()
        val firstToken = requireNotNull(first.token)
        val cancelled = first.state.cancelPendingLoad()
        val ignoredAfterCancel = cancelled.accept(
            firstToken,
            PhotoTimelinePage(listOf(entry(1L, 1L)), null),
        )

        assertSame(cancelled, ignoredAfterCancel)
        assertTrue(ignoredAfterCancel.entries.isEmpty())

        val second = cancelled.beginRefresh()
        val third = second.state.beginRefresh()
        val secondLate = third.state.accept(
            requireNotNull(second.token),
            PhotoTimelinePage(listOf(entry(2L, 2L)), null),
        )
        val thirdAccepted = secondLate.accept(
            requireNotNull(third.token),
            PhotoTimelinePage(listOf(entry(3L, 3L)), null),
        )

        assertTrue(secondLate.entries.isEmpty())
        assertEquals(listOf(3L), thirdAccepted.entries.map { it.file.fileId })
        assertSame(thirdAccepted, thirdAccepted.cancel(firstToken))
    }

    @Test
    fun failureBelongsOnlyToTheActiveGenerationAndKeepsLoadedItems() {
        val loaded = PhotoTimelineState(
            entries = listOf(entry(1L, 1L)),
            nextCursor = PhotoTimelineCursor("older"),
        )
        val append = loaded.beginNextPage()
        val failed = append.state.fail(requireNotNull(append.token), "  timed out  ")

        assertEquals("timed out", failed.error)
        assertEquals(loaded.entries, failed.entries)
        assertTrue(failed.canLoadNextPage)
    }

    @Test
    fun repeatedServerPageStopsAutomaticPagingWithoutGrowingTheTimeline() {
        val cursor = PhotoTimelineCursor("same")
        val loaded = PhotoTimelineState(
            entries = listOf(entry(1L, 1L)),
            nextCursor = cursor,
        )
        val append = loaded.beginNextPage()
        val stopped = append.state.accept(
            requireNotNull(append.token),
            PhotoTimelinePage(listOf(entry(1L, 1L)), cursor),
        )

        assertEquals(loaded.entries, stopped.entries)
        assertNull(stopped.nextCursor)
        assertFalse(stopped.canLoadNextPage)
        assertEquals("The server repeated the same photo timeline page.", stopped.error)
    }

    @Test
    fun dateIndexBuildsMonthAndYearStopsWithJumpFractions() {
        val entries = listOf(
            entry(1L, timestamp("Fri, 01 Mar 2024 12:00:00 GMT")),
            entry(2L, timestamp("Thu, 29 Feb 2024 12:00:00 GMT")),
            entry(3L, timestamp("Mon, 01 Jan 2024 12:00:00 GMT")),
            entry(4L, timestamp("Sun, 31 Dec 2023 12:00:00 GMT")),
        )

        val index = buildPhotoTimelineDateIndex(entries)

        assertEquals(
            listOf(
                PhotoTimelineMonthSection(PhotoTimelineMonth(2024, 3), 0, 1),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2024, 2), 1, 1),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2024, 1), 2, 1),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2023, 12), 3, 1),
            ),
            index.sections,
        )
        assertEquals("February 2024", index.sectionAtFraction(0.34f)?.month?.label)
        assertEquals(2, index.itemIndexFor(PhotoTimelineMonth(2024, 1)))
        assertEquals(1f, index.fractionFor(PhotoTimelineMonth(2023, 12)))
        assertNull(index.itemIndexFor(PhotoTimelineMonth(2022, 1)))
    }

    @Test
    fun timezoneResolverMovesBoundaryMediaIntoTheCorrectLocalMonth() {
        val boundary = entry(
            id = 1L,
            capturedAt = timestamp("Fri, 01 Mar 2024 00:30:00 GMT"),
        )

        val utc = buildPhotoTimelineDateIndex(listOf(boundary))
        val west = buildPhotoTimelineDateIndex(
            listOf(boundary),
            FixedOffsetPhotoTimelineMonthResolver(offsetMinutes = -60),
        )

        assertEquals(PhotoTimelineMonth(2024, 3), utc.sections.single().month)
        assertEquals(PhotoTimelineMonth(2024, 2), west.sections.single().month)
    }

    @Test
    fun preEpochDatesUseFloorDivisionAcrossTimezoneBoundaries() {
        val boundary = entry(
            id = 1L,
            capturedAt = timestamp("Thu, 01 Jan 1970 00:30:00 GMT"),
        )
        val west = buildPhotoTimelineDateIndex(
            listOf(boundary),
            FixedOffsetPhotoTimelineMonthResolver(offsetMinutes = -60),
        )

        assertEquals(PhotoTimelineMonth(1969, 12), west.sections.single().month)
    }

    @Test
    fun nextcloudFileConversionRejectsDirectoriesAndMissingDates() {
        val valid = file(1L, "Photos/Photo.jpg", "Sun, 27 Jul 2026 09:00:00 GMT")
        val directory = valid.copy(isDirectory = true)
        val missing = valid.copy(lastModified = null)

        assertEquals(timestamp(requireNotNull(valid.lastModified)), valid.toPhotoTimelineEntryOrNull()?.capturedAtEpochSeconds)
        assertNull(directory.toPhotoTimelineEntryOrNull())
        assertNull(missing.toPhotoTimelineEntryOrNull())
    }

    @Test
    fun sectionGridIndicesIncludeEarlierMonthHeaders() {
        val index = buildPhotoTimelineDateIndex(
            listOf(
                entry(1L, timestamp("Fri, 01 Mar 2024 12:00:00 GMT")),
                entry(2L, timestamp("Thu, 29 Feb 2024 12:00:00 GMT")),
                entry(3L, timestamp("Wed, 28 Feb 2024 12:00:00 GMT")),
                entry(4L, timestamp("Sun, 31 Dec 2023 12:00:00 GMT")),
            ),
        )

        assertEquals(0, photoTimelineGridIndex(index.sections[0], 0))
        assertEquals(2, photoTimelineGridIndex(index.sections[1], 1))
        assertEquals(5, photoTimelineGridIndex(index.sections[2], 2))
        assertEquals(0, activePhotoTimelineSectionIndex(index, 1))
        assertEquals(1, activePhotoTimelineSectionIndex(index, 4))
        assertEquals(2, activePhotoTimelineSectionIndex(index, 5))
    }

    @Test
    fun davTimestampFormattingRoundTripsBeforeAndAfterUnixEpoch() {
        listOf(
            "Mon, 01 Jan 0001 00:00:00 GMT",
            "Wed, 31 Dec 1969 23:59:59 GMT",
            "Thu, 01 Jan 1970 00:00:00 GMT",
            "Mon, 27 Jul 2026 09:16:50 GMT",
        ).forEach { value ->
            val epoch = requireNotNull(parseDavMediaSearchTimestamp(value))
            assertEquals(value, formatDavMediaSearchTimestamp(epoch))
        }
    }

    @Test
    fun davTimelineAdvancesOnlyThePartitionWithMoreResults() = kotlinx.coroutines.runBlocking {
        val firstImages = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
            file(
                id = id,
                path = "Photos/image-$id.jpg",
                lastModified = (10_000L - id).toString(),
            )
        }
        val firstVideos = listOf(
            file(10_001L, "Photos/video.mp4", "9_500").copy(mimeType = "video/mp4"),
        )
        val requestBodies = mutableListOf<String>()

        val firstPage = collectMediaTimelineDavPage(
            userId = "account",
            cursor = null,
            execute = { body ->
                requestBodies += body
                MediaSearchDavTransportResponse(
                    status = 207,
                    body = (if ("image/%" in body) "images" else "videos").encodeToByteArray(),
                )
            },
            parse = { body ->
                when (body.decodeToString()) {
                    "images" -> firstImages
                    "videos" -> firstVideos
                    else -> emptyList()
                }
            },
            shouldSearchRaw = { false },
        )

        assertEquals(2, requestBodies.size)
        assertEquals(PHOTO_TIMELINE_PARTITION_PAGE_SIZE + 1, firstPage.files.size)
        val cursor = requireNotNull(firstPage.nextCursor)
        requestBodies.clear()

        val secondPage = collectMediaTimelineDavPage(
            userId = "account",
            cursor = cursor,
            execute = { body ->
                requestBodies += body
                MediaSearchDavTransportResponse(207, "older-image".encodeToByteArray())
            },
            parse = {
                listOf(file(999L, "Photos/older.jpg", "1"))
            },
            shouldSearchRaw = { error("RAW discovery must not repeat on cursor pages.") },
        )

        assertEquals(1, requestBodies.size)
        assertTrue("image/%" in requestBodies.single())
        assertFalse("video/%" in requestBodies.single())
        assertTrue("<d:lte>" in requestBodies.single())
        assertTrue("GMT</d:literal>" in requestBodies.single())
        assertTrue(
            """xmlns:sd="https://github.com/icewind1991/SearchDAV/ns"""" in
                requestBodies.single(),
        )
        assertTrue("<sd:firstresult>1</sd:firstresult>" in requestBodies.single())
        assertNull(secondPage.nextCursor)
    }

    @Test
    fun davTimelineDoesNotSkipMoreThanOnePageWithTheSameTimestamp() =
        kotlinx.coroutines.runBlocking {
            val timestamp = "Mon, 27 Jul 2026 09:16:50 GMT"
            val allImages = (1L..450L).map { id ->
                file(
                    id = id,
                    path = "Photos/image-$id.jpg",
                    lastModified = timestamp,
                )
            }
            val requestedOffsets = mutableListOf<Int>()
            val requestedBodies = mutableListOf<String>()

            suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = cursor,
                    execute = { body ->
                        requestedBodies += body
                        val isImage = "<d:literal>image/%</d:literal>" in body
                        val offset = Regex(
                            """<sd:firstresult>(\d+)</sd:firstresult>""",
                        ).find(body)?.groupValues?.get(1)?.toInt() ?: 0
                        if (isImage) requestedOffsets += offset
                        MediaSearchDavTransportResponse(
                            status = 207,
                            body = if (isImage) {
                                "images:$offset".encodeToByteArray()
                            } else {
                                "videos".encodeToByteArray()
                            },
                        )
                    },
                    parse = { body ->
                        val marker = body.decodeToString()
                        if (marker.startsWith("images:")) {
                            val offset = marker.substringAfter(':').toInt()
                            allImages.drop(offset).take(PHOTO_TIMELINE_PARTITION_PAGE_SIZE)
                        } else {
                            emptyList()
                        }
                    },
                    shouldSearchRaw = { false },
                )

            val pages = buildList {
                var cursor: PhotoTimelineCursor? = null
                do {
                    val page = load(cursor)
                    add(page)
                    cursor = page.nextCursor
                } while (cursor != null)
            }
            val loaded = pages.flatMap(MediaTimelineDavPage::files)

            assertEquals(listOf(0, 100, 200, 300, 400), requestedOffsets)
            assertEquals(450, loaded.size)
            assertEquals(450, loaded.map(NextcloudFile::path).distinct().size)
            assertTrue("<d:lte>" in requestedBodies[2])
            assertTrue("<sd:firstresult>100</sd:firstresult>" in requestedBodies[2])
            assertTrue("<sd:firstresult>400</sd:firstresult>" in requestedBodies.last())
            assertNull(pages.last().nextCursor)
        }

    private fun entry(
        id: Long?,
        capturedAt: Long,
        path: String = "Photos/$id.jpg",
    ) = PhotoTimelineEntry(
        file = file(id, path, capturedAt.toString()),
        capturedAtEpochSeconds = capturedAt,
    )

    private fun file(
        id: Long?,
        path: String,
        lastModified: String,
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1L,
        lastModified = lastModified,
        fileId = id,
        hasPreview = true,
    )

    private fun timestamp(value: String): Long = requireNotNull(parseDavMediaSearchTimestamp(value))
}
