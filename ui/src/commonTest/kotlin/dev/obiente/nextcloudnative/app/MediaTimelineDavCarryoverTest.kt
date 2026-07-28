package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTimelineDavCarryoverTest {
    @Test
    fun unconsumedPartitionPageIsReusedBeforeAnotherSearchDavRequest() = runBlocking {
        val fixture = TimelineFixture()
        val store = MediaTimelineDavCarryoverStore()

        val first = fixture.load(
            cursor = null,
            carryoverStore = store,
            accountScope = "account-a",
        )
        assertTrue(requireNotNull(first.nextCursor).value.startsWith("v4c|"))
        fixture.requestBodies.clear()
        val second = fixture.load(
            cursor = requireNotNull(first.nextCursor),
            carryoverStore = store,
            accountScope = "account-a",
        )

        assertEquals(1, fixture.requestBodies.size)
        assertTrue("image/%" in fixture.requestBodies.single())
        assertEquals(
            listOf("Photos/video.mp4", "Photos/older.jpg"),
            second.files.map(NextcloudFile::path),
        )
        assertNull(second.nextCursor)
    }

    @Test
    fun compactCursorFallsBackToStatelessPartitionQueries() = runBlocking {
        val fixture = TimelineFixture()
        val first = fixture.load(
            cursor = null,
            carryoverStore = MediaTimelineDavCarryoverStore(),
            accountScope = "account-a",
        )
        fixture.requestBodies.clear()

        val second = fixture.load(cursor = requireNotNull(first.nextCursor))

        assertEquals(2, fixture.requestBodies.size)
        assertTrue(fixture.requestBodies.any { body -> "image/%" in body })
        assertTrue(fixture.requestBodies.any { body -> "video/%" in body })
        assertEquals(
            listOf("Photos/video.mp4", "Photos/older.jpg"),
            second.files.map(NextcloudFile::path),
        )
        assertNull(second.nextCursor)
    }

    @Test
    fun fullCarryoverPagesAdvancePastEveryFetchedFileBeforeRefetching() = runBlocking {
        val images = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
            file(id, "Photos/image-$id.jpg", 20_000L - id * 2L)
        }
        val videos = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
            file(
                id = 10_000L + id,
                path = "Photos/video-$id.mp4",
                epochSeconds = 19_999L - id * 2L,
                mimeType = "video/mp4",
            )
        }
        val requests = mutableListOf<String>()
        val store = MediaTimelineDavCarryoverStore()

        suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
            collectMediaTimelineDavPage(
                userId = "account",
                cursor = cursor,
                execute = { body ->
                    requests += body
                    val marker = when {
                        "<d:lt><d:prop><d:getlastmodified/></d:prop>" in body -> "empty"
                        "image/%" in body -> "images"
                        "video/%" in body -> "videos"
                        else -> error("Unexpected RAW SearchDAV request.")
                    }
                    MediaSearchDavTransportResponse(207, marker.encodeToByteArray())
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "images" -> images
                        "videos" -> videos
                        "empty" -> emptyList()
                        else -> error("Unexpected SearchDAV response.")
                    }
                },
                shouldSearchRaw = { false },
                carryoverStore = store,
                carryoverAccountScope = "account-a",
            )

        val first = load(null)
        assertEquals(2, requests.size)

        requests.clear()
        val second = load(requireNotNull(first.nextCursor))
        assertTrue(requests.isEmpty())
        assertEquals(DEFAULT_PHOTO_TIMELINE_PAGE_SIZE, second.files.size)

        requests.clear()
        val third = load(requireNotNull(second.nextCursor))
        assertEquals(2, requests.size)
        assertTrue(
            requests.any { body ->
                formatDavMediaSearchTimestamp(19_600L) in body &&
                    "<d:literal>200</d:literal></d:lt>" in body
            },
        )
        assertTrue(
            requests.any { body ->
                formatDavMediaSearchTimestamp(19_599L) in body &&
                    "<d:literal>10200</d:literal></d:lt>" in body
            },
        )
        assertTrue(requests.all { body -> "<sd:firstresult>" !in body })
        assertTrue(third.files.isEmpty())
        assertNull(third.nextCursor)
    }

    @Test
    fun refreshGenerationDiscardsStalePayloadWithoutConsumingFreshPayload() = runBlocking {
        val fixture = TimelineFixture()
        val store = MediaTimelineDavCarryoverStore()
        val stale = fixture.load(
            cursor = null,
            carryoverStore = store,
            accountScope = "account-a",
        )
        val fresh = fixture.load(
            cursor = null,
            carryoverStore = store,
            accountScope = "account-a",
        )
        val staleCursor = requireNotNull(stale.nextCursor)
        val freshCursor = requireNotNull(fresh.nextCursor)
        assertNotEquals(staleCursor, freshCursor)

        fixture.requestBodies.clear()
        fixture.load(
            cursor = staleCursor,
            carryoverStore = store,
            accountScope = "account-a",
        )
        assertEquals(2, fixture.requestBodies.size)

        fixture.requestBodies.clear()
        fixture.load(
            cursor = freshCursor,
            carryoverStore = store,
            accountScope = "account-a",
        )
        assertEquals(1, fixture.requestBodies.size)
        assertTrue("image/%" in fixture.requestBodies.single())
    }

    @Test
    fun runtimeCarryoverIsBoundedByPageCursorAndAccountLimits() = runBlocking {
        val store = MediaTimelineDavCarryoverStore(
            maximumAccountScopes = 1,
            maximumCursorsPerAccount = 1,
        )
        val firstCursor = PhotoTimelineCursor("first")
        val secondCursor = PhotoTimelineCursor("second")
        val firstGeneration = store.beginAccountGeneration("account-a")
        val carryover = MediaTimelineDavCarryover(
            mapOf(
                MediaTimelinePartitionKey.Mime(MediaSearchDavPartition.VideoMime) to
                    MediaTimelinePartitionCarryover(
                        files = listOf(file(1L, "Photos/video.mp4", 100L, "video/mp4")),
                        remoteCursorAfterFetched = null,
                    ),
            ),
        )

        store.put("account-a", firstGeneration, firstCursor, carryover)
        store.put("account-a", firstGeneration, secondCursor, carryover)

        assertNull(store.take("account-a", firstGeneration, firstCursor))
        assertEquals(carryover, store.take("account-a", firstGeneration, secondCursor))

        val secondGeneration = store.beginAccountGeneration("account-b")
        assertTrue(secondGeneration > firstGeneration)
        assertNull(store.take("account-a", firstGeneration, secondCursor))
        assertFailsWith<IllegalArgumentException> {
            MediaTimelinePartitionCarryover(
                files = List(PHOTO_TIMELINE_PARTITION_PAGE_SIZE + 1) { index ->
                    file(index.toLong(), "Photos/$index.jpg", index.toLong())
                },
                remoteCursorAfterFetched = null,
            )
        }
        Unit
    }

    private class TimelineFixture {
        val requestBodies = mutableListOf<String>()
        private val images = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
            file(
                id = id,
                path = "Photos/image-$id.jpg",
                epochSeconds = 10_000L - id,
            )
        }
        private val video = file(
            id = 10_001L,
            path = "Photos/video.mp4",
            epochSeconds = 9_500L,
            mimeType = "video/mp4",
        )
        private val olderImage = file(
            id = 10_002L,
            path = "Photos/older.jpg",
            epochSeconds = 1L,
        )

        suspend fun load(
            cursor: PhotoTimelineCursor?,
            carryoverStore: MediaTimelineDavCarryoverStore? = null,
            accountScope: String? = null,
        ): MediaTimelineDavPage = collectMediaTimelineDavPage(
            userId = "account",
            cursor = cursor,
            execute = { body ->
                requestBodies += body
                val marker = when {
                    "image/%" in body &&
                        "<d:lt><d:prop><d:getlastmodified/></d:prop>" in body -> "older-image"
                    "image/%" in body -> "images"
                    "video/%" in body -> "video"
                    else -> error("Unexpected RAW SearchDAV request.")
                }
                MediaSearchDavTransportResponse(207, marker.encodeToByteArray())
            },
            parse = { body ->
                when (body.decodeToString()) {
                    "images" -> images
                    "older-image" -> listOf(olderImage)
                    "video" -> listOf(video)
                    else -> error("Unexpected SearchDAV response.")
                }
            },
            shouldSearchRaw = { false },
            carryoverStore = carryoverStore,
            carryoverAccountScope = accountScope,
        )
    }

    companion object {
        private fun file(
            id: Long,
            path: String,
            epochSeconds: Long,
            mimeType: String = "image/jpeg",
        ) = NextcloudFile(
            path = path,
            name = path.substringAfterLast('/'),
            isDirectory = false,
            mimeType = mimeType,
            size = 1L,
            lastModified = formatDavMediaSearchTimestamp(epochSeconds),
            fileId = id,
            hasPreview = true,
        )
    }
}
