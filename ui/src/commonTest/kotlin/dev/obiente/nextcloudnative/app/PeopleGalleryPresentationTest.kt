package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleGalleryPresentationTest {
    @Test
    fun `named people lead and filters retain backend boundaries`() {
        val people = listOf(
            person(1, "", 90),
            person(2, "Ada", 5),
            person(3, "Grace", 8),
            person(4, "Other backend", 100, backend = NextcloudPeopleBackend.FaceRecognition),
        )

        val all = buildPeopleGalleryPresentation(
            people,
            NextcloudPeopleBackend.Recognize,
            query = "",
            nameFilter = PeopleNameFilter.All,
        )
        assertEquals(listOf("Ada", "Grace", "Unnamed person"), all.people.map(NextcloudPerson::name))
        assertEquals(2, all.namedCount)
        assertEquals(1, all.unnamedCount)

        val unnamed = buildPeopleGalleryPresentation(
            people,
            NextcloudPeopleBackend.Recognize,
            query = "",
            nameFilter = PeopleNameFilter.Unnamed,
        )
        assertEquals(listOf(1L), unnamed.people.map(NextcloudPerson::id))
    }

    @Test
    fun `search uses display names and never cluster ids`() {
        val person = person(424242, "Ada Lovelace", 4)
        assertEquals(
            listOf(person),
            buildPeopleGalleryPresentation(
                listOf(person),
                NextcloudPeopleBackend.Recognize,
                query = "lovelace",
                nameFilter = PeopleNameFilter.All,
            ).people,
        )
        assertTrue(
            buildPeopleGalleryPresentation(
                listOf(person),
                NextcloudPeopleBackend.Recognize,
                query = "424242",
                nameFilter = PeopleNameFilter.All,
            ).people.isEmpty(),
        )
    }

    private fun person(
        id: Long,
        assignedName: String,
        count: Int,
        backend: NextcloudPeopleBackend = NextcloudPeopleBackend.Recognize,
    ) = NextcloudPerson(
        id = id,
        name = assignedName.ifBlank { "Unnamed person" },
        userId = "user",
        queryName = assignedName.ifBlank { id.toString() },
        count = count,
        coverFileId = null,
        coverEtag = null,
        backend = backend.apiValue,
    )
}
