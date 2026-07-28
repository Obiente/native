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

class MemoriesFolderBrowsingTest {
    @Test
    fun folderPathNormalizesSeparatorsAndRejectsTraversal() {
        assertEquals("/Photos/Trips", MemoriesFolderPath.of("Photos//Trips/").value)
        assertEquals("/", MemoriesFolderPath.of("///").value)

        assertFailsWith<IllegalArgumentException> {
            MemoriesFolderPath.of("/Photos/../Private")
        }
        assertFailsWith<IllegalArgumentException> {
            MemoriesFolderPath.of("/Photos/Bad\u0000Name")
        }
    }

    @Test
    fun directChildrenRequestKeepsPathInEncodedQueryParameter() {
        val request = memoriesDirectChildFoldersRequest(
            MemoriesFolderPath.of("/Photos/Family Trips"),
        )

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/index.php/apps/memories/api/folders/sub", request.relativePath)
        assertEquals(mapOf("folder" to "/Photos/Family Trips"), request.queryParameters)
        assertEquals(512L * 1024L, request.maximumResponseBytes)
        assertTrue(request.ocsApiRequest)
        assertNull(request.body)
        assertEquals(
            "https://cloud.example.test/index.php/apps/memories/api/folders/sub" +
                "?folder=%2FPhotos%2FFamily%20Trips",
            buildNextcloudApiUrl("https://cloud.example.test", request),
        )
    }

    @Test
    fun folderDayRequestUsesExplicitRecursionAndDisablesPreloads() {
        val folder = MemoriesFolderPath.of("/Photos")
        val direct = memoriesFolderDayIndexRequest(folder, recursive = false)
        val recursive = memoriesFolderDayIndexRequest(folder, recursive = true)

        assertEquals(
            mapOf("folder" to "/Photos", "recursive" to "0", "nopreload" to "1"),
            direct.queryParameters,
        )
        assertEquals("1", recursive.queryParameters["recursive"])
        assertEquals("/index.php/apps/memories/api/days", direct.relativePath)
        assertEquals(2L * 1024L * 1024L, direct.maximumResponseBytes)
        assertTrue(direct.ocsApiRequest)
        assertNull(direct.body)
    }

    @Test
    fun folderDayContentsRequestRetainsTheFolderFilter() {
        val request = memoriesFolderDayContentsRequest(
            folder = MemoriesFolderPath.of("/Photos/Trips"),
            recursive = true,
            dayIds = listOf(30L, 29L),
        )

        assertEquals(
            "/index.php/apps/memories/api/days/30,29",
            request.relativePath,
        )
        assertEquals(
            mapOf("folder" to "/Photos/Trips", "recursive" to "1"),
            request.queryParameters,
        )
        assertEquals(8L * 1024L * 1024L, request.maximumResponseBytes)
        assertNull(request.body)
    }

    @Test
    fun directChildrenAreTypedBoundedAndDeterministicallySorted() {
        val parent = MemoriesFolderPath.of("/Photos")
        val result = parseMemoriesDirectChildFoldersResponse(
            response(
                200,
                """
                    [
                      {
                        "fileid": 20,
                        "name": "Trips 10",
                        "previews": [{"fileid": 201}, {"fileid": 202}]
                      },
                      {
                        "fileid": 10,
                        "name": "Family",
                        "previews": [{"fileid": 101}]
                      },
                      {
                        "fileid": 15,
                        "name": "Trips 2",
                        "previews": []
                      }
                    ]
                """,
            ),
            parent,
        )

        val folders = assertIs<MemoriesFolderBrowseLoadResult.Loaded<MemoriesDirectChildFolders>>(
            result,
        ).value
        assertEquals(
            listOf("Family", "Trips 2", "Trips 10"),
            folders.folders.map(MemoriesDirectChildFolder::name),
        )
        assertEquals(
            listOf("/Photos/Family", "/Photos/Trips 2", "/Photos/Trips 10"),
            folders.folders.map { it.path.value },
        )
        assertEquals(listOf(101L), folders.folders[0].previewFileIds)
        assertEquals(emptyList(), folders.folders[1].previewFileIds)
        assertEquals(listOf(201L, 202L), folders.folders[2].previewFileIds)
    }

    @Test
    fun directChildrenRejectUnsafeNamesAndExcessPreviewsAsIncompatible() {
        val unsafe = parseMemoriesDirectChildFoldersResponse(
            response(200, """[{"fileid":10,"name":"../escape","previews":[]}]"""),
            MemoriesFolderPath.of("/Photos"),
        )
        val excess = parseMemoriesDirectChildFoldersResponse(
            response(
                200,
                """
                    [{
                      "fileid": 10,
                      "name": "Family",
                      "previews": [
                        {"fileid": 1},
                        {"fileid": 2},
                        {"fileid": 3},
                        {"fileid": 4},
                        {"fileid": 5}
                      ]
                    }]
                """,
            ),
            MemoriesFolderPath.of("/Photos"),
        )

        assertInvalidResponse(unsafe)
        assertInvalidResponse(excess)
    }

    @Test
    fun folderDayIndexIsTypedSortedAndKeepsBrowseScope() {
        val folder = MemoriesFolderPath.of("/Photos/Trips")
        val result = parseMemoriesFolderDayIndexResponse(
            response(
                200,
                """
                    [
                      {"dayid": 20, "count": 2},
                      {"dayid": 22, "count": 4},
                      {"dayid": 21, "count": 3}
                    ]
                """,
            ),
            folder,
            recursive = true,
        )

        val index = assertIs<MemoriesFolderBrowseLoadResult.Loaded<MemoriesFolderDayIndex>>(
            result,
        ).value
        assertEquals(folder, index.folder)
        assertTrue(index.recursive)
        assertEquals(listOf(22L, 21L, 20L), index.days.map(NativeMediaDay::id))
        assertEquals(9L, index.totalItemCount)
    }

    @Test
    fun duplicateFolderAndDayIdentitiesAreRejectedAsIncompatible() {
        val duplicateFolders = parseMemoriesDirectChildFoldersResponse(
            response(
                200,
                """
                    [
                      {"fileid": 10, "name": "Family", "previews":[]},
                      {"fileid": 11, "name": "Family", "previews":[]}
                    ]
                """,
            ),
            MemoriesFolderPath.of("/Photos"),
        )
        val duplicateDays = parseMemoriesFolderDayIndexResponse(
            response(
                200,
                """[{"dayid":20,"count":2},{"dayid":20,"count":3}]""",
            ),
            MemoriesFolderPath.of("/Photos"),
            recursive = false,
        )

        assertInvalidResponse(duplicateFolders)
        assertInvalidResponse(duplicateDays)
    }

    @Test
    fun absentAndRejectedEndpointsHaveExplicitFallbackClassifications() {
        val parent = MemoriesFolderPath.of("/Photos")
        val absent = assertIs<MemoriesFolderBrowseLoadResult.UseFallback>(
            parseMemoriesDirectChildFoldersResponse(response(404, ""), parent),
        )
        val incompatible = assertIs<MemoriesFolderBrowseLoadResult.UseFallback>(
            parseMemoriesFolderDayIndexResponse(response(422, ""), parent, recursive = false),
        )

        assertEquals(MemoriesFolderBrowseAvailability.Absent, absent.availability)
        assertEquals(MemoriesFolderBrowseFallbackReason.EndpointAbsent, absent.reason)
        assertEquals(404, absent.httpStatus)
        assertEquals(MemoriesFolderBrowseAvailability.Incompatible, incompatible.availability)
        assertEquals(MemoriesFolderBrowseFallbackReason.EndpointRejected, incompatible.reason)
        assertEquals(422, incompatible.httpStatus)
    }

    @Test
    fun authenticationAndTransientFailuresAreNotFallbacks() {
        val folder = MemoriesFolderPath.of("/Photos")

        assertEquals(
            401,
            assertFailsWith<MemoriesFolderBrowseHttpException> {
                parseMemoriesDirectChildFoldersResponse(response(401, ""), folder)
            }.status,
        )
        assertEquals(
            503,
            assertFailsWith<MemoriesFolderBrowseHttpException> {
                parseMemoriesFolderDayIndexResponse(response(503, ""), folder, recursive = true)
            }.status,
        )
    }

    @Test
    fun successfulBodiesAreCheckedAgainstTransportBoundsAgain() {
        val folders = parseMemoriesDirectChildFoldersResponse(
            NextcloudApiResponse(
                status = 200,
                body = ByteArray((512 * 1024) + 1),
                contentType = "application/json",
                etag = null,
            ),
            MemoriesFolderPath.of("/Photos"),
        )
        val days = parseMemoriesFolderDayIndexResponse(
            NextcloudApiResponse(
                status = 200,
                body = ByteArray((2 * 1024 * 1024) + 1),
                contentType = "application/json",
                etag = null,
            ),
            MemoriesFolderPath.of("/Photos"),
            recursive = false,
        )

        assertInvalidResponse(folders)
        assertInvalidResponse(days)
    }

    @Test
    fun cancellationFromEitherReadRemainsCancellation() {
        val service = MemoriesFolderBrowseReadService { _, _ ->
            throw CancellationException("Synthetic cancellation")
        }
        val session = session()
        val folder = MemoriesFolderPath.of("/Photos")

        assertFailsWith<CancellationException> {
            runBlocking { service.loadDirectChildren(session, folder) }
        }
        assertFailsWith<CancellationException> {
            runBlocking { service.loadDayIndex(session, folder, recursive = true) }
        }
    }

    @Test
    fun readServiceUsesTheBoundedOfficialRequests() {
        val requests = mutableListOf<NextcloudApiRequest>()
        val service = MemoriesFolderBrowseReadService { _, request ->
            requests += request
            response(200, "[]")
        }
        val folder = MemoriesFolderPath.of("/Photos/Trips")

        runBlocking {
            assertIs<MemoriesFolderBrowseLoadResult.Loaded<MemoriesDirectChildFolders>>(
                service.loadDirectChildren(session(), folder),
            )
            assertIs<MemoriesFolderBrowseLoadResult.Loaded<MemoriesFolderDayIndex>>(
                service.loadDayIndex(session(), folder, recursive = false),
            )
        }

        assertEquals(
            listOf(
                "/index.php/apps/memories/api/folders/sub",
                "/index.php/apps/memories/api/days",
            ),
            requests.map(NextcloudApiRequest::relativePath),
        )
        assertEquals(listOf(512L * 1024L, 2L * 1024L * 1024L), requests.map { it.maximumResponseBytes })
    }

    @Test
    fun preferredInventoryUsesChildrenAndCachedFilteredDayPages() {
        val requests = mutableListOf<NextcloudApiRequest>()
        val service = MemoriesPreferredFolderInventoryReadService { _, request ->
            requests += request
            when (request.relativePath) {
                "/index.php/apps/memories/api/folders/sub" -> response(
                    200,
                    """[{"fileid":50,"name":"Trips","previews":[{"fileid":51}]}]""",
                )
                "/index.php/apps/memories/api/days" -> response(
                    200,
                    (9 downTo 1).joinToString(prefix = "[", postfix = "]") { day ->
                        """{"dayid":$day,"count":1}"""
                    },
                )
                else -> {
                    val dayIds = request.relativePath.substringAfterLast('/')
                        .split(',')
                        .map(String::toLong)
                    response(
                        200,
                        dayIds.joinToString(prefix = "[", postfix = "]") { day ->
                            """{"fileid":${day + 100},"dayid":$day,"basename":"day-$day.jpg","mimetype":"image/jpeg"}"""
                        },
                    )
                }
            }
        }
        var fallbackUsed = false

        val first = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "fixture-account",
                selectedFolderPath = "Photos",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                cursor = null,
                fallback = {
                    fallbackUsed = true
                    PhotoFolderInventoryPage(emptyList(), null)
                },
            )
        }
        val second = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "fixture-account",
                selectedFolderPath = "Photos",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                cursor = first.nextCursor,
                fallback = {
                    fallbackUsed = true
                    PhotoFolderInventoryPage(emptyList(), null)
                },
            )
        }

        assertFalse(fallbackUsed)
        assertEquals(9, first.records.size)
        assertTrue(first.records.first().isDirectory)
        assertEquals("Photos/Trips", first.records.first().path)
        assertEquals(listOf(51L), first.records.first().directoryPreviewFileIds)
        assertEquals(8, first.records.count { !it.isDirectory })
        assertEquals(1, second.records.size)
        assertNull(second.nextCursor)
        assertEquals(
            listOf(
                "/index.php/apps/memories/api/folders/sub",
                "/index.php/apps/memories/api/days",
                "/index.php/apps/memories/api/days/9,8,7,6,5,4,3,2",
                "/index.php/apps/memories/api/days/1",
            ),
            requests.map(NextcloudApiRequest::relativePath),
        )
        assertTrue(
            requests.drop(2).all { request ->
                request.queryParameters ==
                    mapOf("folder" to "/Photos", "recursive" to "0")
            },
        )
    }

    @Test
    fun preferredRootInventoryPublishesEveryDirectRootFolder() {
        val requests = mutableListOf<NextcloudApiRequest>()
        val service = MemoriesPreferredFolderInventoryReadService { _, request ->
            requests += request
            when (request.relativePath) {
                "/index.php/apps/memories/api/folders/sub" -> response(
                    200,
                    """
                        [
                          {"fileid":50,"name":"Camera","previews":[{"fileid":51}]},
                          {"fileid":60,"name":"Pictures","previews":[]}
                        ]
                    """,
                )
                "/index.php/apps/memories/api/days" -> response(200, "[]")
                else -> error("Unexpected synthetic request: ${request.relativePath}")
            }
        }

        val page = runBlocking {
            service.loadPage(
                session = session(),
                accountScope = "fixture-account",
                selectedFolderPath = "",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                cursor = null,
                fallback = { error("The supported root folder route must not use DAV fallback.") },
            )
        }
        val repository = PhotoFolderInventoryRepository()
        repository.tryAddPage(page.records)
        val root = repository.browse(
            PhotoFolderBrowseState(
                selectedFolderPath = "",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
            ),
        )

        assertEquals(listOf("Camera", "Pictures"), root.folders.map(PhotoFolderSummary::path))
        assertEquals(listOf(51L), root.folders.first().previewFileIds)
        assertEquals(emptyList(), root.folders.last().previewFileIds)
        assertEquals(
            listOf("/", "/"),
            requests.map { request -> request.queryParameters.getValue("folder") },
        )
    }

    @Test
    fun preferredInventoryFallsBackOnlyForCapabilityFailure() {
        var fallbackCalls = 0
        val absent = MemoriesPreferredFolderInventoryReadService { _, _ ->
            response(404, "")
        }
        val page = runBlocking {
            absent.loadPage(
                session = session(),
                accountScope = "fixture-account",
                selectedFolderPath = "",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                cursor = null,
                fallback = {
                    fallbackCalls += 1
                    PhotoFolderInventoryPage(emptyList(), null)
                },
            )
        }
        assertEquals(1, fallbackCalls)
        assertTrue(page.records.isEmpty())

        val unauthorized = MemoriesPreferredFolderInventoryReadService { _, _ ->
            response(401, "")
        }
        assertFailsWith<MemoriesFolderBrowseHttpException> {
            runBlocking {
                unauthorized.loadPage(
                    session = session(),
                    accountScope = "fixture-account",
                    selectedFolderPath = "",
                    scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
                    cursor = null,
                    fallback = {
                        fallbackCalls += 1
                        PhotoFolderInventoryPage(emptyList(), null)
                    },
                )
            }
        }
        assertEquals(1, fallbackCalls)
    }

    private fun assertInvalidResponse(result: MemoriesFolderBrowseLoadResult<*>) {
        val fallback = assertIs<MemoriesFolderBrowseLoadResult.UseFallback>(result)
        assertEquals(MemoriesFolderBrowseAvailability.Incompatible, fallback.availability)
        assertEquals(MemoriesFolderBrowseFallbackReason.InvalidResponse, fallback.reason)
        assertNull(fallback.httpStatus)
    }

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
