package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoriesMainTimelineTest {
    @Test
    fun indexRequestIsUnfilteredAndDisablesPreloads() {
        val request = memoriesMainTimelineDayIndexRequest()

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/index.php/apps/memories/api/days", request.relativePath)
        assertEquals(mapOf("nopreload" to "1"), request.queryParameters)
        assertNull(request.body)
        assertTrue(request.ocsApiRequest)
        assertEquals(2L * 1024L * 1024L, request.maximumResponseBytes)
    }

    @Test
    fun dayRequestBatchesOnlyValidatedDayIds() {
        val request = memoriesMainTimelineDaysRequest(listOf(30L, 29L, 28L))

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/index.php/apps/memories/api/days/30,29,28", request.relativePath)
        assertTrue(request.queryParameters.isEmpty())
        assertNull(request.body)
        assertTrue(request.ocsApiRequest)
        assertEquals(8L * 1024L * 1024L, request.maximumResponseBytes)
    }

    @Test
    fun dayIndexIsCompleteTypedAndDeterministicallyOrdered() {
        val result = parseMemoriesMainTimelineDayIndex(
            response(
                200,
                """
                    [
                      {"dayid": 28, "count": 1},
                      {"dayid": 30, "count": 3},
                      {"dayid": 29, "count": 2}
                    ]
                """,
            ),
        )

        val index = assertIs<MemoriesMainTimelineLoadResult.Loaded<MemoriesMainTimelineDayIndex>>(result).value
        assertEquals(listOf(30L, 29L, 28L), index.days.map(NativeMediaDay::id))
        assertEquals(listOf(3, 2, 1), index.days.map(NativeMediaDay::itemCount))
        assertEquals(6L, index.totalItemCount)
    }

    @Test
    fun dayCursorAdvancesByTheLastFetchedDayId() {
        val index = MemoriesMainTimelineDayIndex(
            listOf(
                NativeMediaDay(30L, 2),
                NativeMediaDay(29L, 2),
                NativeMediaDay(28L, 2),
            ),
        )
        val requests = mutableListOf<NextcloudApiRequest>()
        val service = MemoriesMainTimelineReadService { _, request ->
            requests += request
            val dayIds = request.relativePath.substringAfterLast('/')
                .split(',')
                .map(String::toLong)
            response(
                200,
                dayIds.joinToString(prefix = "[", postfix = "]") { dayId ->
                    """{"fileid":${dayId * 10},"dayid":$dayId,"basename":"day-$dayId.jpg","epoch":${dayId * 100}}"""
                },
            )
        }

        val first = runBlocking {
            service.loadPage(
                session = session(),
                index = index,
                maximumItems = 4,
                maximumDays = 2,
            )
        }.loadedPage()
        val second = runBlocking {
            service.loadPage(
                session = session(),
                index = index,
                cursor = first.nextCursor,
                maximumItems = 4,
                maximumDays = 2,
            )
        }.loadedPage()

        assertEquals("/index.php/apps/memories/api/days/30,29", requests[0].relativePath)
        assertEquals("/index.php/apps/memories/api/days/28", requests[1].relativePath)
        assertEquals(PhotoTimelineCursor("memories-days-v1:29"), first.nextCursor)
        assertNull(second.nextCursor)
        assertEquals(listOf(3000L, 2900L), first.entries.map(PhotoTimelineEntry::capturedAtEpochSeconds))
        assertEquals(listOf(2800L), second.entries.map(PhotoTimelineEntry::capturedAtEpochSeconds))
    }

    @Test
    fun conversionPreservesLivePhotoAndRawStackRelationships() {
        val result = parseMemoriesMainTimelineDayContents(
            response(
                200,
                """
                    [
                      {
                        "fileid": 41,
                        "dayid": 30,
                        "basename": "frame.heic",
                        "mimetype": "image/heic",
                        "etag": "etag-41",
                        "epoch": 3000,
                        "liveid": "motion-token"
                      },
                      {
                        "fileid": 42,
                        "dayid": 30,
                        "basename": "frame.dng",
                        "mimetype": "image/x-dcraw",
                        "epoch": 3000
                      },
                      {
                        "fileid": 43,
                        "dayid": 30,
                        "basename": "frame.ORIGINAL.dng",
                        "mimetype": "image/x-dcraw",
                        "epoch": 3000
                      }
                    ]
                """,
            ),
            expectedDays = listOf(NativeMediaDay(30L, 3)),
            maximumItems = 10,
        )

        val media = assertIs<MemoriesMainTimelineLoadResult.Loaded<List<MemoriesMainTimelineEntry>>>(
            result,
        ).value.single()
        assertEquals(41L, media.timelineEntry.file.fileId)
        assertEquals("motion-token", media.timelineEntry.file.livePhoto?.serverToken)
        assertEquals(listOf(42L, 43L), media.rawStackFileIds)
        assertFalse(media.timelineEntry.file.originalAccessAllowed)
        assertFalse(media.timelineEntry.file.davPathAuthoritative)
        assertEquals("memories/collections/timeline/30/41", media.timelineEntry.file.path)

        val page = MemoriesMainTimelinePage(listOf(media), null)
        assertEquals(
            mapOf(media.timelineEntry.identity to listOf(42L, 43L)),
            page.rawStackFileIdsByEntryIdentity,
        )
        val timelinePage = page.asPhotoTimelinePage()
        assertTrue(timelinePage.rawObserved)
        assertTrue(timelinePage.rawStackRelationshipsAuthoritative)
        assertEquals(page.rawStackFileIdsByEntryIdentity, timelinePage.rawStackFileIdsByEntryIdentity)
    }

    @Test
    fun dottedRenderedNameUsesOfficialFirstDotRawCompatibility() {
        val result = parseMemoriesMainTimelineDayContents(
            response(
                200,
                """
                    [
                      {
                        "fileid": 51,
                        "dayid": 30,
                        "basename": "frame.edit.jpg",
                        "mimetype": "image/jpeg"
                      },
                      {
                        "fileid": 52,
                        "dayid": 30,
                        "basename": "frame.ORIGINAL.dng",
                        "mimetype": "image/x-dcraw"
                      },
                      {
                        "fileid": 53,
                        "dayid": 30,
                        "basename": "unmatched.dng",
                        "mimetype": "image/x-dcraw"
                      }
                    ]
                """,
            ),
            expectedDays = listOf(NativeMediaDay(30L, 3)),
            maximumItems = 10,
        )

        val media = assertIs<MemoriesMainTimelineLoadResult.Loaded<List<MemoriesMainTimelineEntry>>>(
            result,
        ).value
        assertEquals(listOf(51L, 53L), media.map { item -> item.timelineEntry.file.fileId })
        assertEquals(listOf(52L), media.first().rawStackFileIds)
        assertTrue(media.last().timelineEntry.file.isRawPhoto())
    }

    @Test
    fun inferredRawStackCannotExceedTheSharedStackBound() {
        val raws = (2L..34L).joinToString(separator = ",") { fileId ->
            """
                {
                  "fileid": $fileId,
                  "dayid": 30,
                  "basename": "frame.dng",
                  "mimetype": "image/x-dcraw"
                }
            """.trimIndent()
        }
        val payload =
            """
                [
                  {
                    "fileid": 1,
                    "dayid": 30,
                    "basename": "frame.jpg",
                    "mimetype": "image/jpeg"
                  },
                  $raws
                ]
            """
        val fallback = parseMemoriesMainTimelineDayContents(
            response(200, payload),
            expectedDays = listOf(NativeMediaDay(30L, 34)),
            maximumItems = 100,
        )

        val classified = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(fallback)
        assertEquals(MemoriesMainTimelineAvailability.Incompatible, classified.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.InvalidResponse, classified.reason)
    }

    @Test
    fun missingEpochFallsBackToSafeDayStart() {
        val result = parseMemoriesMainTimelineDayContents(
            response(
                200,
                """[{"fileid":7,"dayid":30,"basename":"fallback.jpg","mimetype":"image/jpeg"}]""",
            ),
            expectedDays = listOf(NativeMediaDay(30L, 1)),
            maximumItems = 10,
        )

        val entry = assertIs<MemoriesMainTimelineLoadResult.Loaded<List<MemoriesMainTimelineEntry>>>(
            result,
        ).value.single().timelineEntry
        assertEquals(30L * 86_400L, entry.capturedAtEpochSeconds)
        assertEquals((30L * 86_400L).toString(), entry.file.lastModified)
    }

    @Test
    fun absentAndRejectedEndpointsHaveExplicitFallbackClassifications() {
        val absent = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(
            parseMemoriesMainTimelineDayIndex(response(404, "")),
        )
        val incompatible = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(
            parseMemoriesMainTimelineDayIndex(response(422, "")),
        )

        assertEquals(MemoriesMainTimelineAvailability.Absent, absent.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.EndpointAbsent, absent.reason)
        assertEquals(404, absent.httpStatus)
        assertEquals(MemoriesMainTimelineAvailability.Incompatible, incompatible.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.EndpointRejected, incompatible.reason)
        assertEquals(422, incompatible.httpStatus)
    }

    @Test
    fun malformedSuccessfulResponseUsesIncompatibleFallback() {
        val fallback = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(
            parseMemoriesMainTimelineDayIndex(response(200, """{"days":[]}""")),
        )

        assertEquals(MemoriesMainTimelineAvailability.Incompatible, fallback.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.InvalidResponse, fallback.reason)
        assertNull(fallback.httpStatus)
    }

    @Test
    fun successfulResponseCannotExceedItsDeclaredTransportBound() {
        val fallback = parseMemoriesMainTimelineDayIndex(
            NextcloudApiResponse(
                status = 200,
                body = ByteArray((2 * 1024 * 1024) + 1),
                contentType = "application/json",
                etag = null,
            ),
        )

        val classified = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(fallback)
        assertEquals(MemoriesMainTimelineAvailability.Incompatible, classified.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.InvalidResponse, classified.reason)
    }

    @Test
    fun transientAndAuthenticationFailuresRemainErrors() {
        assertEquals(
            401,
            assertFailsWith<MemoriesMainTimelineHttpException> {
                parseMemoriesMainTimelineDayIndex(response(401, ""))
            }.status,
        )
        assertEquals(
            503,
            assertFailsWith<MemoriesMainTimelineHttpException> {
                parseMemoriesMainTimelineDayIndex(response(503, ""))
            }.status,
        )
    }

    @Test
    fun oversizedSingleDayRequestsFallbackWithoutFetchingContents() {
        var requests = 0
        val service = MemoriesMainTimelineReadService { _, _ ->
            requests += 1
            error("An oversized day must not be requested.")
        }
        val index = MemoriesMainTimelineDayIndex(listOf(NativeMediaDay(30L, 201)))

        val fallback = runBlocking {
            service.loadPage(
                session = session(),
                index = index,
                maximumItems = 200,
            )
        }

        val classified = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(fallback)
        assertEquals(MemoriesMainTimelineAvailability.Available, classified.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.SingleDayExceedsPageSize, classified.reason)
        assertEquals(0, requests)
    }

    @Test
    fun historicalOversizedDayRequestsFallbackBeforeEmittingAMemoriesCursor() {
        var requests = 0
        val service = MemoriesMainTimelineReadService { _, _ ->
            requests += 1
            error("No Memories day batch may be requested before a consistent fallback.")
        }
        val index = MemoriesMainTimelineDayIndex(
            listOf(
                NativeMediaDay(30L, 1),
                NativeMediaDay(29L, 201),
                NativeMediaDay(28L, 1),
            ),
        )

        val fallback = runBlocking {
            service.loadPage(
                session = session(),
                index = index,
                maximumItems = 200,
            )
        }

        val classified = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(fallback)
        assertEquals(MemoriesMainTimelineAvailability.Available, classified.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.SingleDayExceedsPageSize, classified.reason)
        assertEquals(0, requests)
    }

    @Test
    fun zeroCountDaysDoNotConsumeBatchesOrHideOlderMedia() {
        val requests = mutableListOf<String>()
        val service = MemoriesMainTimelineReadService { _, request ->
            requests += request.relativePath
            val dayId = request.relativePath.substringAfterLast('/').toLong()
            response(
                200,
                """[{"fileid":$dayId,"dayid":$dayId,"basename":"day-$dayId.jpg","epoch":$dayId}]""",
            )
        }
        val index = MemoriesMainTimelineDayIndex(
            listOf(
                NativeMediaDay(30L, 0),
                NativeMediaDay(29L, 0),
                NativeMediaDay(28L, 1),
                NativeMediaDay(27L, 1),
            ),
        )

        val first = runBlocking {
            service.loadPage(
                session = session(),
                index = index,
                maximumItems = 1,
                maximumDays = 1,
            )
        }.loadedPage()
        val second = runBlocking {
            service.loadPage(
                session = session(),
                index = index,
                cursor = first.nextCursor,
                maximumItems = 1,
                maximumDays = 1,
            )
        }.loadedPage()

        assertEquals(
            listOf(
                "/index.php/apps/memories/api/days/28",
                "/index.php/apps/memories/api/days/27",
            ),
            requests,
        )
        assertEquals(listOf(28L), first.entries.map { entry -> entry.file.fileId })
        assertEquals(PhotoTimelineCursor("memories-days-v1:28"), first.nextCursor)
        assertEquals(listOf(27L), second.entries.map { entry -> entry.file.fileId })
        assertNull(second.nextCursor)
    }

    @Test
    fun responseCannotExceedRequestedTimelinePageSize() {
        val payload = (1L..3L).joinToString(prefix = "[", postfix = "]") { fileId ->
            """{"fileid":$fileId,"dayid":30,"basename":"item-$fileId.jpg","epoch":$fileId}"""
        }
        val fallback = parseMemoriesMainTimelineDayContents(
            response(200, payload),
            expectedDays = listOf(NativeMediaDay(30L, 2)),
            maximumItems = 2,
        )

        val classified = assertIs<MemoriesMainTimelineLoadResult.UseFallback>(fallback)
        assertEquals(MemoriesMainTimelineAvailability.Incompatible, classified.availability)
        assertEquals(MemoriesMainTimelineFallbackReason.InvalidResponse, classified.reason)
    }

    @Test
    fun cancellationFromTransportRemainsCancellation() {
        val service = MemoriesMainTimelineReadService { _, _ ->
            throw CancellationException("Synthetic cancellation")
        }

        assertFailsWith<CancellationException> {
            runBlocking { service.loadDayIndex(session()) }
        }
    }

    @Test
    fun cursorFromAnotherTimelineSourceIsRejected() {
        val index = MemoriesMainTimelineDayIndex(listOf(NativeMediaDay(30L, 1)))
        val service = MemoriesMainTimelineReadService { _, _ -> error("No request expected.") }

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                service.loadPage(
                    session = session(),
                    index = index,
                    cursor = PhotoTimelineCursor("searchdav:v1"),
                )
            }
        }
    }

    @Test
    fun preferredTimelineUsesCachedMemoriesIndexAcrossDayPages() {
        val requests = mutableListOf<String>()
        var fallbackCalls = 0
        val service = MemoriesPreferredTimelineReadService { _, request ->
            requests += request.relativePath
            when (request.relativePath) {
                "/index.php/apps/memories/api/days" -> response(
                    200,
                    """[{"dayid":30,"count":1},{"dayid":29,"count":1}]""",
                )

                "/index.php/apps/memories/api/days/30" -> response(
                    200,
                    """[{"fileid":30,"dayid":30,"basename":"new.jpg","epoch":3000}]""",
                )

                "/index.php/apps/memories/api/days/29" -> response(
                    200,
                    """[{"fileid":29,"dayid":29,"basename":"old.jpg","epoch":2900}]""",
                )

                else -> error("Unexpected request: ${request.relativePath}")
            }
        }

        val first = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "account-a",
                cursor = null,
                maximumItems = 1,
            ) {
                fallbackCalls += 1
                PhotoTimelinePage(emptyList(), null)
            }
        }
        val second = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "account-a",
                cursor = first.nextCursor,
                maximumItems = 1,
            ) {
                fallbackCalls += 1
                PhotoTimelinePage(emptyList(), null)
            }
        }

        assertEquals(
            listOf(
                "/index.php/apps/memories/api/days",
                "/index.php/apps/memories/api/days/30",
                "/index.php/apps/memories/api/days/29",
            ),
            requests,
        )
        assertEquals(listOf(30L), first.entries.map { it.file.fileId })
        assertEquals(listOf(29L), second.entries.map { it.file.fileId })
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun preferredTimelineFallsBackOnlyWhenMemoriesIsUnavailable() {
        var fallbackCursor: PhotoTimelineCursor? = PhotoTimelineCursor("unset")
        val service = MemoriesPreferredTimelineReadService { _, _ -> response(404, "") }

        val page = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "account-a",
                cursor = null,
            ) { cursor ->
                fallbackCursor = cursor
                PhotoTimelinePage(emptyList(), null)
            }
        }

        assertNull(fallbackCursor)
        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun preferredTimelineKeepsDavCursorPagesOnTheFallbackPath() {
        val requests = mutableListOf<String>()
        val fallbackCursors = mutableListOf<PhotoTimelineCursor?>()
        val davCursor = PhotoTimelineCursor("v4|i:o,3000,1|v:end|r:")
        val service = MemoriesPreferredTimelineReadService { _, request ->
            requests += request.relativePath
            when (request.relativePath) {
                "/index.php/apps/memories/api/days" ->
                    response(200, """[{"dayid":30,"count":1}]""")
                "/index.php/apps/memories/api/days/30" ->
                    response(422, "")
                else -> error("Unexpected request: ${request.relativePath}")
            }
        }
        val fallback: suspend (PhotoTimelineCursor?) -> PhotoTimelinePage = { cursor ->
            fallbackCursors += cursor
            PhotoTimelinePage(
                entries = emptyList(),
                nextCursor = if (cursor == null) davCursor else null,
            )
        }

        val first = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "account-a",
                cursor = null,
                fallback = fallback,
            )
        }
        val second = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "account-a",
                cursor = first.nextCursor,
                fallback = fallback,
            )
        }

        assertEquals(
            listOf(
                "/index.php/apps/memories/api/days",
                "/index.php/apps/memories/api/days/30",
            ),
            requests,
        )
        assertEquals(listOf(null, davCursor), fallbackCursors)
        assertNull(second.nextCursor)
    }

    @Test
    fun navigationSnapshotAndTargetReuseTheActiveMemoriesIndex() {
        val requests = mutableListOf<String>()
        val service = MemoriesPreferredTimelineReadService { _, request ->
            requests += request.relativePath
            when (request.relativePath) {
                "/index.php/apps/memories/api/days" -> response(
                    200,
                    """
                        [
                          {"dayid":60,"count":1},
                          {"dayid":59,"count":1},
                          {"dayid":31,"count":1}
                        ]
                    """.trimIndent(),
                )

                "/index.php/apps/memories/api/days/60,59,31" -> response(
                    200,
                    """
                        [
                          {"fileid":60,"dayid":60,"basename":"new.jpg","epoch":6000},
                          {"fileid":59,"dayid":59,"basename":"middle.jpg","epoch":5900},
                          {"fileid":31,"dayid":31,"basename":"old.jpg","epoch":3100}
                        ]
                    """.trimIndent(),
                )

                "/index.php/apps/memories/api/days/31" -> response(
                    200,
                    """[{"fileid":31,"dayid":31,"basename":"old.jpg","epoch":3100}]""",
                )

                else -> error("Unexpected request: ${request.relativePath}")
            }
        }

        runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "account-a",
                cursor = null,
            ) {
                error("Fallback was not expected.")
            }
        }
        val snapshot = requireNotNull(
            runBlocking {
                service.navigationSnapshot("account-a")
            },
        )
        val target = runBlocking {
            service.loadNavigationTarget(
                session = session(),
                accountScope = "account-a",
                sourceGeneration = snapshot.sourceGeneration,
                targetDayId = 31L,
            )
        }

        assertEquals(
            listOf(
                "/index.php/apps/memories/api/days",
                "/index.php/apps/memories/api/days/60,59,31",
                "/index.php/apps/memories/api/days/31",
            ),
            requests,
        )
        assertEquals(listOf(60L, 59L, 31L), snapshot.geometry.days.map { it.dayId })
        val loaded = assertIs<MemoriesTimelineNavigationLoadResult.Loaded>(target)
        assertEquals(2, loaded.advertisedNewerItemCount)
        assertEquals(listOf(31L), loaded.page.entries.map { it.file.fileId })
        assertNull(loaded.page.nextCursor)
    }

    @Test
    fun navigationSnapshotIsNotPublishedForDavFallback() {
        val service = MemoriesPreferredTimelineReadService { _, _ -> response(404, "") }

        runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "account-a",
                cursor = null,
            ) {
                PhotoTimelinePage(emptyList(), PhotoTimelineCursor("dav-page-2"))
            }
        }

        assertNull(
            runBlocking {
                service.navigationSnapshot("account-a")
            },
        )
    }

    @Test
    fun refreshedMemoriesIndexRejectsAnOlderNavigationGeneration() {
        val service = MemoriesPreferredTimelineReadService { _, request ->
            when (request.relativePath) {
                "/index.php/apps/memories/api/days" ->
                    response(200, """[{"dayid":30,"count":1}]""")
                "/index.php/apps/memories/api/days/30" ->
                    response(
                        200,
                        """[{"fileid":30,"dayid":30,"basename":"photo.jpg","epoch":3000}]""",
                    )
                else -> error("Unexpected request: ${request.relativePath}")
            }
        }

        runBlocking {
            service.loadPage(session(), "account-a", cursor = null) {
                error("Fallback was not expected.")
            }
        }
        val firstSnapshot = requireNotNull(
            runBlocking { service.navigationSnapshot("account-a") },
        )
        runBlocking {
            service.loadPage(session(), "account-a", cursor = null) {
                error("Fallback was not expected.")
            }
        }

        assertIs<MemoriesTimelineNavigationLoadResult.Stale>(
            runBlocking {
                service.loadNavigationTarget(
                    session = session(),
                    accountScope = "account-a",
                    sourceGeneration = firstSnapshot.sourceGeneration,
                    targetDayId = 30L,
                )
            },
        )
    }

    @Test
    fun preferredTimelineDoesNotHideAuthenticationFailuresWithFallback() {
        var fallbackCalled = false
        val service = MemoriesPreferredTimelineReadService { _, _ -> response(401, "") }

        assertFailsWith<MemoriesMainTimelineHttpException> {
            runBlocking {
                service.loadPage(
                    session = session(),
                    accountScope = "account-a",
                    cursor = null,
                ) {
                    fallbackCalled = true
                    PhotoTimelinePage(emptyList(), null)
                }
            }
        }
        assertFalse(fallbackCalled)
    }

    private fun MemoriesMainTimelineLoadResult<MemoriesMainTimelinePage>.loadedPage():
        MemoriesMainTimelinePage =
        assertIs<MemoriesMainTimelineLoadResult.Loaded<MemoriesMainTimelinePage>>(this).value

    private fun response(status: Int, body: String): NextcloudApiResponse = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )

    private fun session(): NextcloudSession = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "fixture",
        appPassword = "fixture-secret",
    )
}
