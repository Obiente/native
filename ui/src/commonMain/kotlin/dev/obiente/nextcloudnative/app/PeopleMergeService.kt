package dev.obiente.nextcloudnative.app

sealed interface PeopleMergeRunResult {
    data class Completed(val workflow: PersonMergeWorkflow) : PeopleMergeRunResult

    data class Paused(
        val workflow: PersonMergeWorkflow,
        val outcomeUnknown: Boolean,
    ) : PeopleMergeRunResult

    data class Unavailable(val message: String) : PeopleMergeRunResult
}

/**
 * End-to-end coordinator for Recognize's non-atomic person merge.
 *
 * A confirmed run advances after successful moves, but stops on the first rejected or uncertain
 * outcome. Resuming requires a caller-triggered fresh inventory of both people, which prevents a
 * hidden retry from duplicating a move whose result was unknown.
 */
class PeopleMergeService(
    private val faceReader: RecognizedFaceReadService,
    private val mutationService: PeopleMutationService,
) {
    suspend fun prepare(
        session: NextcloudSession,
        source: PersonMediaReference,
        target: PersonMediaReference,
    ): PersonMergeWorkflow {
        val faces = faceReader.loadCompleteFacesForMerge(session, source)
        require(faces.isNotEmpty()) { "This person has no face assignments to merge." }
        return PersonMergeWorkflow.create(
            source = source,
            target = target,
            faces = faces.map { face -> face.toFaceReference(source) },
        )
    }

    suspend fun runConfirmed(
        session: NextcloudSession,
        bridgeDiscovery: RecognizeBridgeDiscovery,
        plan: PeopleActionPlan,
        initialWorkflow: PersonMergeWorkflow,
        initialReconciliation: PeopleMergeReconciliation? = null,
        onProgress: (PersonMergeWorkflow) -> Unit = {},
    ): PeopleMergeRunResult {
        require(plan.action == PeopleAction.MergePerson)
        var workflow = initialWorkflow
        var reconciliation = initialReconciliation
        repeat(workflow.items.size + 1) {
            when (
                val result = mutationService.execute(
                    session = session,
                    bridgeDiscovery = bridgeDiscovery,
                    plan = plan,
                    confirmed = true,
                    mergeWorkflow = workflow,
                    mergeReconciliation = reconciliation,
                )
            ) {
                is PeopleMutationServiceResult.Outcome -> when (val outcome = result.outcome) {
                    is PeopleMutationExecutionOutcome.MergeAdvanced -> {
                        workflow = outcome.workflow
                        reconciliation = null
                        onProgress(workflow)
                    }

                    is PeopleMutationExecutionOutcome.MergeCompleted -> {
                        onProgress(outcome.workflow)
                        return PeopleMergeRunResult.Completed(outcome.workflow)
                    }

                    is PeopleMutationExecutionOutcome.MergePaused -> {
                        onProgress(outcome.workflow)
                        return PeopleMergeRunResult.Paused(outcome.workflow, outcome.outcomeUnknown)
                    }

                    else -> return PeopleMergeRunResult.Unavailable(
                        "The server returned an unexpected merge result.",
                    )
                }

                is PeopleMutationServiceResult.TokenUnavailable ->
                    return PeopleMergeRunResult.Unavailable(result.message)

                is PeopleMutationServiceResult.Planning -> when (val planning = result.result) {
                    is PeopleExecutionPlanningResult.Completed ->
                        return PeopleMergeRunResult.Completed(planning.workflow)

                    else -> return PeopleMergeRunResult.Unavailable(planning.mergePlanningMessage())
                }
            }
        }
        return PeopleMergeRunResult.Unavailable("The merge stopped before all faces were processed.")
    }

    suspend fun reconcileAfterRefresh(
        session: NextcloudSession,
        workflow: PersonMergeWorkflow,
    ): PeopleMergeReconciliation {
        val sourceFaces = faceReader.loadCompleteFacesForMerge(session, workflow.source)
        val targetFaces = faceReader.loadCompleteFacesForMerge(session, workflow.target)
        return reconcilePeopleMergeAfterRefresh(
            workflow = workflow,
            sourceDetectionIds = sourceFaces.mapTo(linkedSetOf(), RecognizedFaceMedia::detectionId),
            targetDetectionIds = targetFaces.mapTo(linkedSetOf(), RecognizedFaceMedia::detectionId),
        )
    }
}

private fun PeopleExecutionPlanningResult.mergePlanningMessage(): String = when (this) {
    is PeopleExecutionPlanningResult.Disabled -> reason
    is PeopleExecutionPlanningResult.ConfirmationRequired -> "Confirm the merge before continuing."
    PeopleExecutionPlanningResult.FaceInventoryRequired -> "A complete face inventory is required."
    is PeopleExecutionPlanningResult.BridgeTokenRequired -> "A fresh Recognize key is required."
    is PeopleExecutionPlanningResult.ReconciliationRequired -> reason
    is PeopleExecutionPlanningResult.Ready -> "The merge request is ready but was not executed."
    is PeopleExecutionPlanningResult.Completed -> "The merge is complete."
    is PeopleExecutionPlanningResult.Invalid -> reason
}
