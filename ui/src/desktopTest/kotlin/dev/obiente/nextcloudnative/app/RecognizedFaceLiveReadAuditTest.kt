package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecognizedFaceLiveReadAuditTest {
    @Test
    fun `live exact face picker pages and plans chosen detection without mutation`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FACE_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val selectedPerson = services.listPeople(session, NextcloudPeopleBackend.Recognize.apiValue)
            .maxByOrNull(NextcloudPerson::count)
            ?: error("The live server returned no Recognize people.")
        val observed = mutableListOf<NextcloudApiRequest>()
        val reader = RecognizedFaceReadService { activeSession, request ->
            assertTrue(request.method == NextcloudApiMethod.GET)
            assertTrue(request.body == null)
            observed += request
            services.executeNextcloudApi(activeSession, request)
        }

        val person = selectedPerson.toMediaReference()
        val index = reader.loadDayIndex(session, person)
        val first = reader.loadPage(session, person, index, dayPageSize = 2)
        val second = first.nextCursor?.let { reader.loadPage(session, person, index, it, dayPageSize = 2) }
        val faces = (first.faces + second?.faces.orEmpty()).distinctBy(RecognizedFaceMedia::detectionId)
        val selectedFace = recognizedFaceByDetectionId(faces, faces.last().detectionId)
        val plan = planRemoveRecognizedFace(
            media = selectedFace,
            person = person,
            personDisplayName = selectedPerson.name,
            support = PeopleActionSupport(
                currentUserId = person.ownerUserId,
                memoriesPeopleApiAvailable = true,
                recognizeDavAvailable = true,
                recognizeApiKeyRequired = true,
                recognizeApiKeyAvailable = true,
            ),
        )
        val plannedRequest = (plan.binding as PeopleActionBinding.Single).request

        assertTrue(observed.size in 2..3)
        assertTrue(faces.isNotEmpty())
        assertTrue(plannedRequest.pathValues["faceNode"]?.startsWith("${selectedFace.detectionId}-") == true)
        assertTrue(observed.all { it.method == NextcloudApiMethod.GET && it.body == null })
        println(
            "face-picker-page-audit outcome=success requests=${observed.size} methods=get-only " +
                "days=${index.days.size} faces=${faces.size} planned=not-executed content=redacted",
        )
    }

    @Test
    fun `live person gallery audit pages beyond initial window with GET only`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FACE_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val selected = services.listPeople(session, NextcloudPeopleBackend.Recognize.apiValue)
            .maxByOrNull(NextcloudPerson::count)
            ?: error("The live server returned no Recognize people.")
        val observed = mutableListOf<NextcloudApiRequest>()
        val reader = NextcloudPersonMediaReadService { activeSession, request ->
            assertTrue(request.method == NextcloudApiMethod.GET)
            assertTrue(request.body == null)
            assertTrue(request.relativePath.startsWith("/index.php/apps/memories/api/days"))
            assertTrue(request.maximumResponseBytes <= MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES)
            observed += request
            services.executeNextcloudApi(activeSession, request)
        }

        val person = selected.toMediaReference()
        val index = reader.loadDayIndex(session, person)
        val first = reader.loadPage(session, person, index)
        val second = first.nextCursor?.let { cursor ->
            reader.loadPage(session, person, index, cursor)
        }
        val files = (first.items + second?.items.orEmpty()).map { it.toPersonMediaFile(person) }

        assertTrue(index.days.isNotEmpty())
        assertTrue(observed.size in 2..3)
        assertTrue(files.mapNotNull(NextcloudFile::fileId).distinct().size == files.size)
        assertTrue(files.all { file -> !file.isDirectory && requireNotNull(file.fileId) > 0L })
        println(
            "person-gallery-audit outcome=success requests=${observed.size} methods=get-only " +
                "days=${index.days.size} first=${first.items.size} second=${second?.items?.size ?: 0} " +
                "has-more=${second?.nextCursor != null} content=redacted",
        )
    }

    @Test
    fun `live face selection audit is bounded GET only and sanitized`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FACE_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val people = sortNextcloudPeopleForDisplay(
            services.listPeople(session, NextcloudPeopleBackend.Recognize.apiValue),
        )
        assertTrue(people.isNotEmpty(), "The live server returned no Recognize people.")
        val selected = people.first()
        val observed = mutableListOf<NextcloudApiRequest>()
        val reader = RecognizedFaceReadService { activeSession, request ->
            assertTrue(request.method == NextcloudApiMethod.GET)
            assertTrue(request.body == null)
            assertTrue(request.relativePath.startsWith("/index.php/apps/memories/api/days"))
            assertTrue(request.maximumResponseBytes <= 4L * 1024L * 1024L)
            observed += request
            services.executeNextcloudApi(activeSession, request)
        }

        val faces = reader.loadInitialFaces(
            session = session,
            person = selected.toMediaReference(),
            maximumDays = 1,
        )

        assertTrue(observed.size in 1..2)
        assertTrue(faces.all { face ->
            face.detectionId > 0L &&
                requireNotNull(face.file.fileId) > 0L &&
                !face.file.isDirectory
        })
        val rectangleCount = faces.count { it.rectangle != null }
        println(
            "face-audit outcome=success requests=${observed.size} methods=get-only " +
                "people=${people.size} faces=${faces.size} rectangles=$rectangleCount content=redacted",
        )
    }
}
