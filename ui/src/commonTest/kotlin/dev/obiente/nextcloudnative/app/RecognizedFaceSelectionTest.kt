package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecognizedFaceSelectionTest {
    @Test
    fun `face outline requires both normalized rectangle and complete source dimensions`() {
        val rectangle = NativeFaceRectangle(x = 0.2f, y = 0.1f, width = 0.3f, height = 0.4f)

        assertEquals(
            NativeFaceOutlineGeometry(rectangle, sourceWidth = 6240, sourceHeight = 4160),
            nativeFaceOutlineGeometryOrNull(rectangle, sourceWidth = 6240, sourceHeight = 4160),
        )
        assertNull(nativeFaceOutlineGeometryOrNull(rectangle, sourceWidth = null, sourceHeight = 4160))
        assertNull(nativeFaceOutlineGeometryOrNull(rectangle, sourceWidth = 6240, sourceHeight = null))
        assertNull(nativeFaceOutlineGeometryOrNull(null, sourceWidth = 6240, sourceHeight = 4160))
    }

    private val person = PersonMediaReference(
        backend = NextcloudPeopleBackend.Recognize,
        clusterId = 42,
        ownerUserId = "person",
        lookupName = "Named Person",
    )

    @Test
    fun parsesBoundedFaceIdentitySeparatelyFromSourceFile() {
        val face = parseMemoriesRecognizedFaces(
            response(
                """
                [{
                  "fileid":900,"faceid":77,"basename":"photo.jpg","mimetype":"image/jpeg",
                  "etag":"source-etag","epoch":1784800000,"w":4000,"h":3000,
                  "facerect":{"x":0.2,"y":0.1,"w":0.3,"h":0.4}
                }]
                """.trimIndent(),
            ),
            person,
        ).single()

        assertEquals(77, face.detectionId)
        assertEquals(900, face.file.fileId)
        assertEquals("photo.jpg", face.file.name)
        assertEquals(4000, face.sourceWidth)
        assertEquals(3000, face.sourceHeight)
        assertEquals(NativeFaceRectangle(0.2f, 0.1f, 0.3f, 0.4f), face.rectangle)
    }

    @Test
    fun clipsSlightDetectorOverflowForSafeOverlay() {
        val face = parseMemoriesRecognizedFaces(
            response(
                """
                [{
                  "fileid":1,"faceid":2,"basename":"edge.jpg","w":100,"h":100,
                  "facerect":{"x":-0.1,"y":0.8,"w":0.4,"h":0.5}
                }]
                """.trimIndent(),
            ),
            person,
        ).single()

        assertEquals(NativeFaceRectangle(0f, 0.8f, 0.3f, 0.2f), face.rectangle)
    }

    @Test
    fun malformedOptionalRectangleDoesNotHideActionableFace() {
        val face = parseMemoriesRecognizedFaces(
            response(
                """
                [{
                  "fileid":1,"faceid":2,"basename":"photo.jpg",
                  "facerect":{"x":0.1,"y":0.1,"w":0,"h":0.2}
                }]
                """.trimIndent(),
            ),
            person,
        ).single()

        assertNull(face.rectangle)
    }

    @Test
    fun skipsItemsThatCannotIdentifyExactDetectionOrSafeFilename() {
        val faces = parseMemoriesRecognizedFaces(
            response(
                """
                [
                  {"fileid":1,"basename":"missing-face.jpg"},
                  {"fileid":2,"faceid":2,"basename":"../unsafe.jpg"},
                  {"fileid":3,"faceid":3,"basename":"safe.jpg"}
                ]
                """.trimIndent(),
            ),
            person,
        )

        assertEquals(listOf(3L), faces.map(RecognizedFaceMedia::detectionId))
    }

    @Test
    fun rejectsDuplicateDetectionIds() {
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesRecognizedFaces(
                response(
                    """
                    [
                      {"fileid":1,"faceid":8,"basename":"one.jpg"},
                      {"fileid":2,"faceid":8,"basename":"two.jpg"}
                    ]
                    """.trimIndent(),
                ),
                person,
            )
        }
    }

    @Test
    fun parsesAndDeduplicatesPersonDayWindow() {
        val days = parseMemoriesPersonDayIndex(
            response(
                """
                [
                  {"dayid":20260723,"count":4},
                  {"dayid":20260723,"count":4},
                  {"dayid":20260722,"photos":2}
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(20260723L, 20260722L), days.map(MemoriesPersonDay::dayId))
        assertEquals(listOf(4, 2), days.map(MemoriesPersonDay::itemCount))
    }

    @Test
    fun readServiceUsesOnlyTwoBoundedGetRequests() = runBlocking {
        val observed = mutableListOf<NextcloudApiRequest>()
        val service = RecognizedFaceReadService { _, request ->
            observed += request
            if (request.relativePath.endsWith("/api/days")) {
                response("""[{"dayid":20260723},{"dayid":20260722}]""")
            } else {
                response("""[{"fileid":10,"faceid":11,"basename":"photo.jpg"}]""")
            }
        }

        val faces = service.loadInitialFaces(session, person, maximumDays = 1)

        assertEquals(1, faces.size)
        assertEquals(2, observed.size)
        assertTrue(observed.all { it.method == NextcloudApiMethod.GET && it.body == null })
        assertEquals("/index.php/apps/memories/api/days/20260723", observed.last().relativePath)
        assertEquals("1", observed.first().queryParameters["facerect"])
        assertEquals("1", observed.last().queryParameters["facerect"])
    }

    @Test
    fun pagedPickerSelectionRemainsBoundToExactDetectionAfterAppendingFaces() = runBlocking {
        val observed = mutableListOf<NextcloudApiRequest>()
        val service = RecognizedFaceReadService { _, request ->
            observed += request
            when {
                request.relativePath.endsWith("/api/days") -> response(
                    """
                    [
                      {"dayid":20260723,"count":1},
                      {"dayid":20260722,"count":1},
                      {"dayid":20260721,"count":1}
                    ]
                    """.trimIndent(),
                )
                request.relativePath.endsWith("/20260723,20260722") -> response(
                    """
                    [
                      {"fileid":900,"faceid":101,"basename":"shared-photo.jpg"},
                      {"fileid":901,"faceid":102,"basename":"second.jpg"}
                    ]
                    """.trimIndent(),
                )
                else -> response(
                    """[{"fileid":900,"faceid":202,"basename":"shared-photo.jpg"}]""",
                )
            }
        }

        val index = service.loadDayIndex(session, person)
        val first = service.loadPage(session, person, index, dayPageSize = 2)
        val second = service.loadPage(session, person, index, first.nextCursor, dayPageSize = 2)
        val appended = first.faces + second.faces
        val selected = recognizedFaceByDetectionId(appended, detectionId = 101L)
        val plan = planRemoveRecognizedFace(
            media = selected,
            person = person,
            personDisplayName = "Named Person",
            support = editableSupport(),
        )
        val request = (plan.binding as PeopleActionBinding.Single).request

        assertEquals(listOf(101L, 102L, 202L), appended.map(RecognizedFaceMedia::detectionId))
        assertEquals(900L, selected.file.fileId)
        assertEquals("101-shared-photo.jpg", request.pathValues["faceNode"])
        assertTrue(request.pathValues.values.none { it.startsWith("202-") })
        assertNull(second.nextCursor)
        assertEquals(3, observed.size)
        assertTrue(observed.all { it.method == NextcloudApiMethod.GET && it.body == null })
    }

    @Test
    fun exactDetectionSelectionRejectsMissingOrDuplicateIdentity() {
        val first = parseMemoriesRecognizedFaces(
            response("""[{"fileid":900,"faceid":101,"basename":"photo.jpg"}]"""),
            person,
        ).single()

        assertFailsWith<IllegalStateException> {
            recognizedFaceByDetectionId(listOf(first), detectionId = 202L)
        }
        assertFailsWith<IllegalArgumentException> {
            recognizedFaceByDetectionId(listOf(first, first), detectionId = 101L)
        }
    }

    @Test
    fun completeMergeInventoryReadsEveryDayInBoundedChunks() = runBlocking {
        val observed = mutableListOf<NextcloudApiRequest>()
        val daysJson = (1L..33L).joinToString(prefix = "[", postfix = "]") { day ->
            """{"dayid":$day,"count":1}"""
        }
        val service = RecognizedFaceReadService { _, request ->
            observed += request
            when {
                request.relativePath.endsWith("/api/days") -> response(daysJson)
                request.relativePath.endsWith("/33") ->
                    response("""[{"fileid":33,"faceid":330,"basename":"last.jpg"}]""")
                else -> response("""[{"fileid":1,"faceid":10,"basename":"first.jpg"}]""")
            }
        }

        val faces = service.loadCompleteFacesForMerge(session, person)

        assertEquals(listOf(10L, 330L), faces.map(RecognizedFaceMedia::detectionId))
        assertEquals(3, observed.size)
        assertTrue(observed.all { it.method == NextcloudApiMethod.GET && it.body == null })
        assertEquals(32, observed[1].relativePath.substringAfterLast('/').split(',').size)
        assertEquals("/index.php/apps/memories/api/days/33", observed[2].relativePath)
    }

    @Test
    fun completeMergeInventoryStopsBeforeChildReadsWhenAdvertisedCountExceedsCeiling() = runBlocking {
        val observed = mutableListOf<NextcloudApiRequest>()
        val service = RecognizedFaceReadService { _, request ->
            observed += request
            response("""[{"dayid":20260723,"count":3}]""")
        }

        assertFailsWith<IllegalArgumentException> {
            service.loadCompleteFacesForMerge(session, person, maximumFaces = 2)
        }
        assertEquals(1, observed.size)
    }

    @Test
    fun removePlanUsesDetectionNodeAndNeverSourceFileId() {
        val face = parseMemoriesRecognizedFaces(
            response("""[{"fileid":900,"faceid":77,"basename":"photo.jpg"}]"""),
            person,
        ).single()
        val plan = planRemoveRecognizedFace(
            media = face,
            person = person,
            personDisplayName = "Named Person",
            support = editableSupport(),
        )
        val request = (plan.binding as PeopleActionBinding.Single).request

        assertTrue(plan.enabled)
        assertEquals(PeopleMutationMethod.DELETE, request.method)
        assertEquals("77-photo.jpg", request.pathValues["faceNode"])
        assertTrue(request.pathValues.values.none { it == "900" })
        assertEquals(PeopleActionRisk.DestructiveMetadata, plan.risk)
    }

    @Test
    fun missingShortLivedRecognizeKeyKeepsPlanDisabled() {
        val face = parseMemoriesRecognizedFaces(
            response("""[{"fileid":900,"faceid":77,"basename":"photo.jpg"}]"""),
            person,
        ).single()
        val plan = planRemoveRecognizedFace(
            media = face,
            person = person,
            personDisplayName = "Named Person",
            support = editableSupport().copy(recognizeApiKeyAvailable = false),
        )

        assertEquals(false, plan.enabled)
        assertNotNull(plan.disabledReason)
    }

    private fun editableSupport() = PeopleActionSupport(
        currentUserId = "person",
        memoriesPeopleApiAvailable = true,
        recognizeDavAvailable = true,
        recognizeApiKeyRequired = true,
        recognizeApiKeyAvailable = true,
    )

    private fun response(json: String) = NextcloudApiResponse(
        status = 200,
        body = json.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )

    private val session = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "person",
        appPassword = "secret",
    )
}
