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
                reconcilePersonAfterMutation(
                    PeoplePostMutationExpectation.Rename("Example Person"),
                    previous,
                    listOf(renamed),
                ),
            ).person,
        )
        assertEquals(
            newCover,
            assertIs<PeoplePostMutationReconciliation.CurrentPerson>(
                reconcilePersonAfterMutation(
                    PeoplePostMutationExpectation.SetCover(91),
                    previous,
                    listOf(newCover),
                ),
            ).person,
        )
        assertEquals(
            oneFaceRemoved,
            assertIs<PeoplePostMutationReconciliation.CurrentPerson>(
                reconcilePersonAfterMutation(
                    PeoplePostMutationExpectation.RemoveFace(41),
                    previous,
                    listOf(oneFaceRemoved),
                    refreshedFaceDetectionIds = setOf(40, 42, 43),
                ),
            ).person,
        )
    }

    @Test
    fun unchangedImmediateReadKeepsReconciliationPending() {
        val previous = person(name = "Old name", queryName = "42", count = 4, cover = 11)

        assertIs<PeoplePostMutationReconciliation.Pending>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.Rename("New name"),
                previous,
                listOf(previous),
            ),
        )
        assertIs<PeoplePostMutationReconciliation.Pending>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.SetCover(91),
                previous,
                listOf(previous),
            ),
        )
        assertIs<PeoplePostMutationReconciliation.Pending>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.RemoveFace(41),
                previous,
                listOf(previous.copy(count = previous.count - 1)),
                refreshedFaceDetectionIds = setOf(41, 42, 43),
            ),
        )
    }

    @Test
    fun exactFaceAbsenceWinsOverConcurrentAggregateCountChanges() {
        val previous = person(count = 4)

        assertIs<PeoplePostMutationReconciliation.CurrentPerson>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.RemoveFace(41),
                previous,
                listOf(previous),
                refreshedFaceDetectionIds = setOf(40, 42, 43, 44),
            ),
        )
        assertIs<PeoplePostMutationReconciliation.Pending>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.RemoveFace(41),
                previous,
                listOf(previous.copy(count = 3)),
                refreshedFaceDetectionIds = setOf(40, 41, 42),
            ),
        )
    }

    @Test
    fun renameRequiresAuthoritativeQueryNameInsteadOfDisplayFallback() {
        val previous = person(name = "Unnamed person", queryName = "42")
        val staleDisplay = previous.copy(name = "Unnamed person", queryName = "42")

        assertIs<PeoplePostMutationReconciliation.Pending>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.Rename("Unnamed person"),
                previous,
                listOf(staleDisplay),
            ),
        )
    }

    @Test
    fun disappearedOrTerminalPersonReturnsToGallery() {
        val previous = person()

        assertIs<PeoplePostMutationReconciliation.Gallery>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.RemoveFace(41),
                previous,
                emptyList(),
            ),
        )
        assertIs<PeoplePostMutationReconciliation.Gallery>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.RemovePerson(PeopleAction.MergePerson),
                previous,
                listOf(previous),
            ),
        )
        assertIs<PeoplePostMutationReconciliation.Gallery>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.RemovePerson(PeopleAction.DeletePerson),
                previous,
                listOf(previous),
            ),
        )
    }

    @Test
    fun pendingExpectationsRoundTripThroughSaveableState() {
        val expectations = listOf(
            PeoplePostMutationExpectation.Rename("Example Person"),
            PeoplePostMutationExpectation.SetCover(91),
            PeoplePostMutationExpectation.RemoveFace(41),
            PeoplePostMutationExpectation.RemovePerson(PeopleAction.MergePerson),
            PeoplePostMutationExpectation.RemovePerson(PeopleAction.DeletePerson),
        )

        expectations.forEach { expectation ->
            assertEquals(
                expectation,
                decodePeoplePostMutationExpectation(expectation.encodeForSavedState()),
            )
        }
    }

    @Test
    fun identityMatchingDoesNotCrossOwnerBackendOrDuplicateCluster() {
        val previous = person()
        val otherOwner = previous.copy(userId = "other-user")
        val otherBackend = previous.copy(backend = NextcloudPeopleBackend.FaceRecognition.apiValue)

        assertIs<PeoplePostMutationReconciliation.Pending>(
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.SetCover(91),
                previous,
                listOf(otherOwner, otherBackend),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            reconcilePersonAfterMutation(
                PeoplePostMutationExpectation.Rename("Renamed"),
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
