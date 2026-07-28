package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoFolderInventoryPagingTest {
    @Test
    fun `first page is published before the following page is requested`() = runBlocking {
        val pager = pager()
        val publications = mutableListOf<PhotoFolderInventoryPagingState>()
        var requestCount = 0

        val result = pager.load(
            loadPage = { cursor, _ ->
                requestCount += 1
                when (requestCount) {
                    1 -> {
                        assertNull(cursor)
                        page("Photos/current.jpg", nextCursor = "older")
                    }
                    2 -> {
                        assertEquals(1, publications.size)
                        assertEquals(
                            1,
                            publications.single().publication!!.summary.indexedMediaRecordCount,
                        )
                        assertEquals("older", cursor?.value)
                        page("Photos/older.jpg")
                    }
                    else -> error("Unexpected inventory request.")
                }
            },
            onPublish = publications::add,
        )

        assertTrue(result.complete)
        assertEquals(2, publications.size)
        assertEquals(2, result.publication!!.summary.indexedMediaRecordCount)
    }

    @Test
    fun `later failure retains published data and retry resumes the exact failed cursor`() =
        runBlocking {
            val pager = pager()
            val failedCursor = PhotoFolderInventoryCursor("page-two")
            var requestCount = 0

            val failed = pager.load { cursor, _ ->
                requestCount += 1
                when (requestCount) {
                    1 -> PhotoFolderInventoryPage(
                        records = listOf(file("Photos/first.jpg")),
                        nextCursor = failedCursor,
                    )
                    2 -> {
                        assertEquals(failedCursor, cursor)
                        error("Network unavailable")
                    }
                    else -> error("Unexpected inventory request.")
                }
            }

            assertFalse(failed.complete)
            assertEquals("Network unavailable", failed.error)
            assertEquals(failedCursor, failed.resumeCursor)
            assertEquals(1, failed.publication!!.summary.indexedMediaRecordCount)

            val requestedByRetry = mutableListOf<PhotoFolderInventoryCursor?>()
            val recovered = pager.load { cursor, _ ->
                requestedByRetry += cursor
                PhotoFolderInventoryPage(
                    records = listOf(file("Photos/second.jpg")),
                    nextCursor = null,
                )
            }

            assertEquals(1, requestedByRetry.size)
            assertEquals(failedCursor, requestedByRetry.single())
            assertTrue(recovered.complete)
            assertNull(recovered.error)
            assertEquals(2, recovered.publication!!.summary.indexedMediaRecordCount)
        }

    @Test
    fun `cancellation retains the in-flight cursor and does not become a load error`() =
        runBlocking {
            val pager = pager()
            val interruptedCursor = PhotoFolderInventoryCursor("interrupted")
            var requestCount = 0

            assertFailsWith<CancellationException> {
                pager.load { cursor, _ ->
                    requestCount += 1
                    when (requestCount) {
                        1 -> PhotoFolderInventoryPage(
                            records = listOf(file("Photos/first.jpg")),
                            nextCursor = interruptedCursor,
                        )
                        2 -> {
                            assertEquals(interruptedCursor, cursor)
                            throw CancellationException("Owner left composition.")
                        }
                        else -> error("Unexpected inventory request.")
                    }
                }
            }

            assertFalse(pager.state.loading)
            assertNull(pager.state.error)
            assertEquals(interruptedCursor, pager.state.resumeCursor)
            assertEquals(1, pager.state.publication!!.summary.indexedMediaRecordCount)
        }

    @Test
    fun `repeated cursor stops without issuing another request`() = runBlocking {
        val pager = pager()
        val repeatedCursor = PhotoFolderInventoryCursor("repeat")
        var requestCount = 0

        val result = pager.load { cursor, _ ->
            requestCount += 1
            when (requestCount) {
                1 -> PhotoFolderInventoryPage(
                    records = listOf(file("Photos/first.jpg")),
                    nextCursor = repeatedCursor,
                )
                2 -> {
                    assertEquals(repeatedCursor, cursor)
                    PhotoFolderInventoryPage(
                        records = listOf(file("Photos/second.jpg")),
                        nextCursor = repeatedCursor,
                    )
                }
                else -> error("The repeated cursor was followed.")
            }
        }

        assertEquals(2, requestCount)
        assertEquals(
            PhotoFolderInventorySafetyStopReason.RepeatedCursor,
            result.safetyStopReason,
        )
        assertEquals(2, result.publication!!.summary.indexedMediaRecordCount)
        assertTrue(result.incompleteInventoryMessage()?.contains("repeated a photo page") == true)
    }

    @Test
    fun `page with no novel records stops before following its cursor`() = runBlocking {
        val pager = pager()
        var requestCount = 0

        val result = pager.load { _, _ ->
            requestCount += 1
            when (requestCount) {
                1 -> page("Photos/first.jpg", nextCursor = "second")
                2 -> page("Photos/first.jpg", nextCursor = "third")
                else -> error("The empty-progress cursor was followed.")
            }
        }

        assertEquals(2, requestCount)
        assertEquals(
            PhotoFolderInventorySafetyStopReason.NoNovelRecords,
            result.safetyStopReason,
        )
        assertEquals("third", result.resumeCursor?.value)
        assertEquals(1, result.publication!!.summary.indexedMediaRecordCount)
        assertTrue(result.incompleteInventoryMessage()?.contains("stopped returning new photos") == true)
    }

    @Test
    fun `raw observation is propagated to every following request`() = runBlocking {
        val pager = pager()
        val rawFlags = mutableListOf<Boolean>()
        var requestCount = 0

        val result = pager.load { _, rawPreviouslyObserved ->
            rawFlags += rawPreviouslyObserved
            requestCount += 1
            when (requestCount) {
                1 -> PhotoFolderInventoryPage(
                    records = listOf(file("Photos/shot.RAF", "image/x-fuji-raf")),
                    nextCursor = PhotoFolderInventoryCursor("second"),
                    rawObserved = true,
                )
                2 -> page("Photos/older.jpg")
                else -> error("Unexpected inventory request.")
            }
        }

        assertEquals(listOf(false, true), rawFlags)
        assertTrue(result.rawPreviouslyObserved)
    }

    @Test
    fun `account and refresh generation pagers cannot share accumulated state`() = runBlocking {
        val first = pager(accountKey = "cloud-a:user", generation = 1)
        val refreshed = pager(accountKey = "cloud-a:user", generation = 2)
        val otherAccount = pager(accountKey = "cloud-b:user", generation = 1)

        first.load { _, _ -> page("Photos/first-account.jpg") }
        refreshed.load { _, _ -> page("Photos/refreshed.jpg") }
        otherAccount.load { _, _ -> page("Photos/other-account.jpg") }

        assertEquals(1, first.state.owner.generation)
        assertEquals(2, refreshed.state.owner.generation)
        assertEquals(
            listOf("Photos/first-account.jpg"),
            first.selectionSnapshot(
                PhotoFolderBrowseState(scope = PhotoFolderBrowseScope.RecursiveMedia),
            ).selectedMediaFiles.map { it.path },
        )
        assertEquals(
            listOf("Photos/refreshed.jpg"),
            refreshed.selectionSnapshot(
                PhotoFolderBrowseState(scope = PhotoFolderBrowseScope.RecursiveMedia),
            ).selectedMediaFiles.map { it.path },
        )
        assertEquals(
            listOf("Photos/other-account.jpg"),
            otherAccount.selectionSnapshot(
                PhotoFolderBrowseState(scope = PhotoFolderBrowseScope.RecursiveMedia),
            ).selectedMediaFiles.map { it.path },
        )
    }

    @Test
    fun `fifty thousand record ceiling rejects a crossing page atomically`() =
        runBlocking {
            val pager = pager(
                maximumSelectedMediaRecords = MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS,
            )
            val acceptedPage = List(MAX_PHOTO_FOLDER_INVENTORY_PAGING_RECORDS - 1) { index ->
                file("Photos/Archive/photo-$index.jpg")
            }
            var requestCount = 0

            val result = pager.load { _, _ ->
                requestCount += 1
                when (requestCount) {
                    1 -> PhotoFolderInventoryPage(
                        records = acceptedPage,
                        nextCursor = PhotoFolderInventoryCursor("overflow"),
                    )
                    2 -> PhotoFolderInventoryPage(
                        records = listOf(
                            file("Photos/Archive/photo-49999.jpg"),
                            file("Photos/Archive/photo-50000.jpg"),
                        ),
                        nextCursor = PhotoFolderInventoryCursor("beyond-limit"),
                    )
                    else -> error("The pager followed a cursor beyond its explicit ceiling.")
                }
            }

            assertEquals(2, requestCount)
            assertEquals(
                PhotoFolderInventoryTruncationReason.MediaRecordLimit,
                result.truncationReason,
            )
            assertEquals(
                MAX_PHOTO_FOLDER_INVENTORY_PAGING_RECORDS - 1,
                result.publication!!.summary.indexedMediaRecordCount,
            )
            assertEquals("overflow", result.resumeCursor?.value)
            assertFalse(result.complete)
            assertTrue(result.incompleteInventoryMessage()?.contains("media safety limit") == true)
        }

    @Test
    fun `page crossing the media ceiling is rejected atomically`() = runBlocking {
        val pager = pager(maximumMediaRecords = 2, maximumSelectedMediaRecords = 2)
        var requestCount = 0

        val result = pager.load { _, _ ->
            requestCount += 1
            when (requestCount) {
                1 -> PhotoFolderInventoryPage(
                    records = listOf(file("Photos/one.jpg"), file("Photos/two.jpg")),
                    nextCursor = PhotoFolderInventoryCursor("overflow"),
                )
                else -> error("The exact configured ceiling should stop before another request.")
            }
        }

        assertEquals(1, requestCount)
        assertEquals(
            PhotoFolderInventoryTruncationReason.MediaRecordLimit,
            result.truncationReason,
        )
        assertEquals(2, result.publication!!.summary.indexedMediaRecordCount)
    }

    @Test
    fun `one acquired inventory retargets folders scopes query and view without loading`() =
        runBlocking {
            val pager = pager(maximumSelectedMediaRecords = 8)
            val rootPage = List(10_001) { index ->
                file("Photos/Camera/root-$index.jpg")
            }
            var loaderCalls = 0

            pager.load { _, _ ->
                loaderCalls += 1
                when (loaderCalls) {
                    1 -> PhotoFolderInventoryPage(
                        records = rootPage,
                        nextCursor = PhotoFolderInventoryCursor("nested"),
                    )
                    2 -> PhotoFolderInventoryPage(
                        records = listOf(
                            file("Photos/Trips/direct-one.jpg"),
                            file("Photos/Trips/direct-two.jpg"),
                            file("Photos/Trips/Archive/recursive.jpg"),
                        ),
                        nextCursor = null,
                    )
                    else -> error("Browsing must not restart inventory acquisition.")
                }
            }

            val root = pager.browse(
                PhotoFolderBrowseState(
                    selectedFolderPath = "Photos",
                    scope = PhotoFolderBrowseScope.RecursiveMedia,
                ),
            )
            val nestedDirect = pager.browse(
                PhotoFolderBrowseState(
                    selectedFolderPath = "Photos/Trips",
                    scope = PhotoFolderBrowseScope.DirectMediaOnly,
                ),
            )
            val nestedRecursive = pager.browse(
                PhotoFolderBrowseState(
                    selectedFolderPath = "Photos/Trips",
                    scope = PhotoFolderBrowseScope.RecursiveMedia,
                ),
            )
            val queriedList = pager.browse(
                PhotoFolderBrowseState(
                    selectedFolderPath = "Photos",
                    query = "Trips",
                    scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                    preference = PhotoFolderBrowsePreference(PhotoFolderViewMode.List),
                ),
            )
            val queriedGrid = pager.browse(
                queriedList.state.copy(
                    preference = PhotoFolderBrowsePreference(PhotoFolderViewMode.Grid),
                ),
            )

            assertEquals(2, loaderCalls)
            assertEquals(10_004, root.recursiveMediaCount)
            assertEquals(8, root.media.size)
            assertEquals(
                listOf("direct-one.jpg", "direct-two.jpg"),
                nestedDirect.media.map { it.cover.name }.sorted(),
            )
            assertEquals(
                listOf("direct-one.jpg", "direct-two.jpg", "recursive.jpg"),
                nestedRecursive.media.map { it.cover.name }.sorted(),
            )
            assertEquals(
                listOf("Photos/Trips/Archive", "Photos/Trips"),
                queriedList.folders.map { it.path },
            )
            assertEquals(queriedList.folders, queriedGrid.folders)
            assertEquals(2, loaderCalls)
        }

    private fun pager(
        accountKey: String = "cloud:user",
        generation: Long = 1,
        maximumMediaRecords: Int = MAX_PHOTO_FOLDER_INVENTORY_PAGING_RECORDS,
        maximumSelectedMediaRecords: Int = 8,
    ) = PhotoFolderInventoryPager(
        owner = PhotoFolderInventoryPagingOwner(accountKey, generation),
        maximumMediaRecords = maximumMediaRecords,
        maximumSelectedMediaRecords = maximumSelectedMediaRecords,
    )

    private fun page(
        path: String,
        nextCursor: String? = null,
    ) = PhotoFolderInventoryPage(
        records = listOf(file(path)),
        nextCursor = nextCursor?.let(::PhotoFolderInventoryCursor),
    )

    private fun file(
        path: String,
        mimeType: String = "image/jpeg",
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = mimeType,
        size = 1,
        lastModified = "2026-07-28T10:00:00Z",
        fileId = path.hashCode().toLong().let { if (it == 0L) 1L else it },
        hasPreview = true,
        etag = "etag-${path.length}",
    )
}
