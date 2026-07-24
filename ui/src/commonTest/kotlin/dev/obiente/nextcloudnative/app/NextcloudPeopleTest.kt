package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextcloudPeopleTest {
    @Test
    fun plansTypedSameOriginMemoriesPeopleReads() {
        assertEquals(
            NextcloudApiRequest(
                method = NextcloudApiMethod.GET,
                relativePath = "/index.php/apps/memories/api/clusters/recognize",
                queryParameters = mapOf("fileid" to "42"),
                ocsApiRequest = true,
            ),
            memoriesPeopleListRequest(NextcloudPeopleBackend.Recognize, containingFileId = 42L),
        )

        val person = reference()
        assertEquals(
            mapOf("recognize" to "ada/Grace Hopper", "nopreload" to "1", "facerect" to "1"),
            memoriesPersonDayIndexRequest(person).queryParameters,
        )
        assertEquals(
            "/index.php/apps/memories/api/days/20260721,20260720",
            memoriesPersonDaysRequest(person, listOf(20260721L, 20260720L)).relativePath,
        )
    }

    @Test
    fun modelsRecognizeCoverAsBackendObjectRatherThanSourceFile() {
        val person = person(id = 7L, name = "Ada Lovelace", count = 12).copy(
            coverFileId = 314L,
            coverEtag = "etag-photo-source",
        )

        assertEquals(PersonCoverReference(314L, "etag-photo-source"), person.coverReferenceOrNull())
    }

    @Test
    fun keepsPersonIdentityOutOfRequestPaths() {
        val request = memoriesPersonDayIndexRequest(reference())

        assertEquals(false, "ada" in request.relativePath)
        assertEquals(false, "Grace Hopper" in request.relativePath)
        assertEquals("ada/Grace Hopper", request.queryParameters["recognize"])
    }

    @Test
    fun rejectsAmbiguousPeopleAndDayIdentifiers() {
        assertFailsWith<IllegalArgumentException> {
            reference().copy(lookupName = "one/person")
        }
        assertFailsWith<IllegalArgumentException> {
            memoriesPersonDaysRequest(reference(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            memoriesPersonDaysRequest(reference(), listOf(20260721L, 20260721L))
        }
    }

    @Test
    fun searchesLocallyAndExposesOnlyReadOperations() = runBlocking {
        var requestedBackend: NextcloudPeopleBackend? = null
        var requestedPerson: NextcloudPerson? = null
        val service = NextcloudPeopleReadService(
            loadPeople = { _, backend ->
                requestedBackend = backend
                listOf(
                    person(id = 1L, name = "Grace Hopper", count = 4),
                    person(id = 2L, name = "Ada Lovelace", count = 12),
                    person(id = 3L, name = "Other backend", count = 99, backend = "facerecognition"),
                )
            },
            loadMedia = { _, selected ->
                requestedPerson = selected
                listOf(file(99L))
            },
        )

        val matches = service.searchPeople(
            session,
            PeopleSearchQuery(NextcloudPeopleBackend.Recognize, "  ada  "),
        )
        val media = service.listPersonMedia(session, matches.single())

        assertEquals(NextcloudPeopleBackend.Recognize, requestedBackend)
        assertEquals(listOf("Ada Lovelace"), matches.map(NextcloudPerson::name))
        assertEquals(matches.single(), requestedPerson)
        assertEquals(listOf(99L), media.map(NextcloudFile::fileId))
    }

    @Test
    fun namedPeopleLeadUnnamedClustersRegardlessOfClusterSize() {
        val unnamedLarge = person(id = 9L, name = "Unnamed person", count = 900).copy(queryName = "9")
        val namedSmall = person(id = 2L, name = "Ada Lovelace", count = 3)
        val unnamedSmall = person(id = 8L, name = "Unnamed person", count = 2).copy(queryName = "8")

        assertEquals(
            listOf(2L, 9L, 8L),
            sortNextcloudPeopleForDisplay(listOf(unnamedSmall, unnamedLarge, namedSmall)).map(NextcloudPerson::id),
        )
    }

    private fun reference() = PersonMediaReference(
        backend = NextcloudPeopleBackend.Recognize,
        clusterId = 7L,
        ownerUserId = "ada",
        lookupName = "Grace Hopper",
    )

    private fun person(
        id: Long,
        name: String,
        count: Int,
        backend: String = "recognize",
    ) = NextcloudPerson(
        id = id,
        name = name,
        userId = "ada",
        queryName = name,
        count = count,
        coverFileId = null,
        coverEtag = null,
        backend = backend,
    )

    private fun file(id: Long) = NextcloudFile(
        path = "memories/people/2/$id",
        name = "$id.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = null,
        lastModified = null,
        fileId = id,
        hasPreview = true,
    )

    private companion object {
        val session = NextcloudSession("https://cloud.example.test", "ada", "app-password")
    }
}
