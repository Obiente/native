package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeopleActionPlanningTest {
    @Test
    fun disablesRecognizeChangesWhenShortLivedApiKeyIsUnavailable() {
        val plan = planRenamePerson(source, "Grace", "Grace Hopper", support(apiKey = false))

        assertFalse(plan.enabled)
        assertEquals(
            "Recognize changes require a short-lived X-Recognize-Api-Key, which is unavailable to this client.",
            plan.disabledReason,
        )
        assertEquals(
            setOf(
                PeopleActionAuthRequirement.AuthenticatedNextcloudSession,
                PeopleActionAuthRequirement.ShortLivedRecognizeApiKey,
            ),
            plan.authRequirements,
        )
    }

    @Test
    fun plansRenameAsNonOverwritingMetadataMove() {
        val plan = planRenamePerson(source, "Grace", "Grace Hopper", support())
        val request = (plan.binding as PeopleActionBinding.Single).request

        assertTrue(plan.enabled)
        assertEquals(PeopleActionRisk.MetadataWrite, plan.risk)
        assertEquals(PeopleMutationMethod.MOVE, request.method)
        assertEquals(false, request.overwrite)
        assertEquals("Grace", request.pathValues["person"])
        assertEquals("Grace Hopper", request.destinationPathValues["person"])
        assertEquals(PeopleActionRetryPolicy.NeverAutomaticRefreshBeforeRetry, plan.retryPolicy)
    }

    @Test
    fun plansCoverThroughMemoriesWithoutRecognizeApiKey() {
        val plan = planSetPersonCover(
            source,
            "Grace",
            photo(fileId = 44L),
            support(apiKey = false),
        )
        val request = (plan.binding as PeopleActionBinding.Single).request

        assertTrue(plan.enabled)
        assertEquals(setOf(PeopleActionAuthRequirement.AuthenticatedNextcloudSession), plan.authRequirements)
        assertEquals(PeopleMutationSurface.MemoriesApi, request.surface)
        assertEquals(mapOf("name" to "ada/Grace", "fileid" to "44"), request.bodyFields)
    }

    @Test
    fun givesDestructiveActionsSpecificPhotoPreservationCopy() {
        val remove = planRemoveFace(face(11L), "Grace", support())
        val delete = planDeletePerson(source, "Grace", support())

        assertEquals(PeopleActionRisk.DestructiveMetadata, remove.risk)
        assertTrue("photo stays in Files" in remove.confirmation.message)
        assertEquals(PeopleActionRisk.DestructiveMetadata, delete.risk)
        assertTrue("Photos stay in Files" in delete.confirmation.message)
        assertEquals(PeopleMutationMethod.DELETE, (delete.binding as PeopleActionBinding.Single).request.method)
    }

    @Test
    fun marksMergeAsPartialAndReconciliationOnly() {
        val plan = planMergePeople(source, "Grace", target, "Ada", support())

        assertEquals(PeopleActionRisk.MultiStepDestructiveMetadata, plan.risk)
        assertEquals(PeopleActionPartialFailure.PerFaceMovesCanPartiallyApply, plan.partialFailure)
        assertEquals(PeopleActionRetryPolicy.ReconcileEachFaceBeforeResume, plan.retryPolicy)
        assertTrue(plan.binding is PeopleActionBinding.PerFaceMoveWorkflow)
        assertTrue("interruption" in plan.confirmation.message)
    }

    @Test
    fun mergeWorkflowMovesOnlyOneFaceAtATimeAndCompletes() {
        var workflow = PersonMergeWorkflow.create(source, target, listOf(face(11L), face(12L))).start()

        workflow = workflow.beginNext()
        assertEquals(1, workflow.progress.inFlight)
        assertEquals("11-one.jpg", workflow.activeMove?.pathValues?.get("faceNode"))
        assertEquals("Ada", workflow.activeMove?.destinationPathValues?.get("person"))
        workflow = workflow.markActiveSucceeded()
        assertEquals(PersonMergePhase.Running, workflow.phase)

        workflow = workflow.beginNext().markActiveSucceeded()
        assertEquals(PersonMergePhase.Completed, workflow.phase)
        assertEquals(2, workflow.progress.succeeded)
        assertNull(workflow.activeMove)
    }

    @Test
    fun ambiguousMergeFailureBlocksUntilRefreshReconcilesIt() {
        var workflow = PersonMergeWorkflow.create(source, target, listOf(face(11L), face(12L)))
            .start()
            .beginNext()
            .markActiveFailed("Connection closed after upload", outcomeUnknown = true)

        assertEquals(PersonMergePhase.NeedsManualReconciliation, workflow.phase)
        assertEquals(PersonMergeItemState.OutcomeUnknown, workflow.items.first().state)

        workflow = workflow.reconcileAfterRefresh(
            sourceDetectionIds = setOf(12L),
            targetDetectionIds = setOf(11L),
        )
        assertEquals(PersonMergePhase.PausedForRefresh, workflow.phase)
        assertEquals(PersonMergeItemState.Succeeded, workflow.items.first().state)
        assertEquals(PersonMergeItemState.Pending, workflow.items.last().state)

        workflow = workflow.start().beginNext()
        assertEquals(12L, workflow.items.single { it.state == PersonMergeItemState.InFlight }.face.detectionId)
    }

    @Test
    fun missingFaceAfterRefreshRemainsManualInsteadOfBeingGuessedSuccessful() {
        val workflow = PersonMergeWorkflow.create(source, target, listOf(face(11L)))
            .start()
            .beginNext()
            .markActiveFailed("Server error", outcomeUnknown = false)
            .reconcileAfterRefresh(emptySet(), emptySet())

        assertEquals(PersonMergePhase.NeedsManualReconciliation, workflow.phase)
        assertEquals(PersonMergeItemState.MissingAfterRefresh, workflow.items.single().state)
    }

    private fun support(apiKey: Boolean = true) = PeopleActionSupport(
        currentUserId = "ada",
        memoriesPeopleApiAvailable = true,
        recognizeDavAvailable = true,
        recognizeApiKeyAvailable = apiKey,
    )

    private fun face(id: Long) = RecognizeFaceReference(
        person = source,
        detectionId = id,
        sourceFileName = if (id == 11L) "one.jpg" else "two.jpg",
    )

    private fun photo(fileId: Long?) = NextcloudFile(
        path = "Photos/portrait.jpg",
        name = "portrait.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 123L,
        lastModified = null,
        fileId = fileId,
        hasPreview = true,
    )

    private companion object {
        val source = PersonMediaReference(NextcloudPeopleBackend.Recognize, 1L, "ada", "Grace")
        val target = PersonMediaReference(NextcloudPeopleBackend.Recognize, 2L, "ada", "Ada")
    }
}
