package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PeoplePostMutationReconciliationTest {
    @Test
    fun renameCoverAndFaceRemovalUseFreshServerPerson() {
        val previous = person(name = "Unnamed person", queryName = "42", count = 4, cover = 11)
        val renamed = previous.copy(name = "Example Person", queryName = "Example Person")
        val newCover = previous.copy(coverFileId = 91, coverEtag = "cover-v2")
        val oneFaceRemoved = previous.copy(count = 3)

        assertEquals(
            renamed,
            assertIs<PeoplePostMutationReconciliation.CurrentPerson>(
                reconcilePersonAfterMutation(PeopleAction.RenamePerson, previous, listOf(renamed)),
            ).person,
        )
        assertEquals(
            newCover,
            assertIs<PeoplePostMutationReconciliation.CurrentPerson>(
                reconcilePersonAfterMutation(PeopleAction.SetCover, previous, listOf(newCover)),
            ).person,
        )
        assertEquals(
            oneFaceRemoved,
            assertIs<PeoplePostMutationReconciliation.CurrentPerson>(
                reconcilePersonAfterMutation(PeopleAction.RemoveFace, previous, listOf(oneFaceRemoved)),
            ).person,
        )
    }

    @Test
    fun disappearedOrTerminalPersonReturnsToGallery() {
        val previous = person()

        assertIs<PeoplePostMutationReconciliation.Gallery>(
            reconcilePersonAfterMutation(PeopleAction.RemoveFace, previous, emptyList()),
        )
        assertIs<PeoplePostMutationReconciliation.Gallery>(
            reconcilePersonAfterMutation(PeopleAction.MergePerson, previous, listOf(previous)),
        )
        assertIs<PeoplePostMutationReconciliation.Gallery>(
            reconcilePersonAfterMutation(PeopleAction.DeletePerson, previous, listOf(previous)),
        )
    }

    @Test
    fun identityMatchingDoesNotCrossOwnerBackendOrDuplicateCluster() {
        val previous = person()
        val otherOwner = previous.copy(userId = "other-user")
        val otherBackend = previous.copy(backend = NextcloudPeopleBackend.FaceRecognition.apiValue)

        assertIs<PeoplePostMutationReconciliation.Gallery>(
            reconcilePersonAfterMutation(
                PeopleAction.SetCover,
                previous,
                listOf(otherOwner, otherBackend),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            reconcilePersonAfterMutation(
                PeopleAction.RenamePerson,
                previous,
                listOf(previous, previous.copy(name = "Duplicate")),
            )
        }
    }

    private fun person(
        name: String = "Example Person",
        queryName: String = name,
        count: Int = 4,
        cover: Long? = 11,
    ) = NextcloudPerson(
        id = 42,
        name = name,
        userId = "example-user",
        queryName = queryName,
        count = count,
        coverFileId = cover,
        coverEtag = "cover-v1",
        backend = NextcloudPeopleBackend.Recognize.apiValue,
    )
}
