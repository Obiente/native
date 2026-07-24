package dev.obiente.nextcloudnative.app

enum class PersonMergePhase {
    Ready,
    Running,
    PausedForRefresh,
    NeedsManualReconciliation,
    Completed,
}

enum class PersonMergeItemState {
    Pending,
    InFlight,
    Succeeded,
    Failed,
    OutcomeUnknown,
    MissingAfterRefresh,
}

data class PersonMergeItem(
    val face: RecognizeFaceReference,
    val state: PersonMergeItemState = PersonMergeItemState.Pending,
    val attempts: Int = 0,
    val error: String? = null,
)

data class PersonMergeProgress(
    val total: Int,
    val succeeded: Int,
    val pending: Int,
    val inFlight: Int,
    val failed: Int,
) {
    val completed: Int get() = succeeded + failed
}

/**
 * Pure, sequential state machine for Recognize's non-atomic merge workflow.
 *
 * It never executes DAV and never retries automatically. Any failure pauses the workflow until the
 * caller refreshes both people and reconciles each uncertain detection.
 */
@ConsistentCopyVisibility
data class PersonMergeWorkflow private constructor(
    val source: PersonMediaReference,
    val target: PersonMediaReference,
    val items: List<PersonMergeItem>,
    val phase: PersonMergePhase,
) {
    init {
        require(items.isNotEmpty())
        require(items.map { it.face.detectionId }.distinct().size == items.size)
        require(items.all { it.face.person == source })
        require(items.count { it.state == PersonMergeItemState.InFlight } <= 1)
    }

    val progress: PersonMergeProgress
        get() = PersonMergeProgress(
            total = items.size,
            succeeded = items.count { it.state == PersonMergeItemState.Succeeded },
            pending = items.count { it.state == PersonMergeItemState.Pending },
            inFlight = items.count { it.state == PersonMergeItemState.InFlight },
            failed = items.count {
                it.state in setOf(
                    PersonMergeItemState.Failed,
                    PersonMergeItemState.OutcomeUnknown,
                    PersonMergeItemState.MissingAfterRefresh,
                )
            },
        )

    val activeMove: PeopleMutationRequest?
        get() = items.singleOrNull { it.state == PersonMergeItemState.InFlight }?.face?.moveRequest(target)

    fun start(): PersonMergeWorkflow {
        require(phase == PersonMergePhase.Ready || phase == PersonMergePhase.PausedForRefresh)
        require(items.none { it.state == PersonMergeItemState.InFlight })
        require(items.any { it.state == PersonMergeItemState.Pending })
        return copy(phase = PersonMergePhase.Running)
    }

    fun beginNext(): PersonMergeWorkflow {
        require(phase == PersonMergePhase.Running)
        require(items.none { it.state == PersonMergeItemState.InFlight })
        val index = items.indexOfFirst { it.state == PersonMergeItemState.Pending }
        require(index >= 0) { "No pending face remains." }
        return copy(
            items = items.replaceAt(index) { it.copy(state = PersonMergeItemState.InFlight, attempts = it.attempts + 1, error = null) },
        )
    }

    fun markActiveSucceeded(): PersonMergeWorkflow {
        val index = activeIndex()
        val updated = items.replaceAt(index) { it.copy(state = PersonMergeItemState.Succeeded, error = null) }
        return copy(
            items = updated,
            phase = if (updated.all { it.state == PersonMergeItemState.Succeeded }) {
                PersonMergePhase.Completed
            } else {
                PersonMergePhase.Running
            },
        )
    }

    fun markActiveFailed(message: String, outcomeUnknown: Boolean): PersonMergeWorkflow {
        require(message.isNotBlank())
        val index = activeIndex()
        return copy(
            items = items.replaceAt(index) {
                it.copy(
                    state = if (outcomeUnknown) PersonMergeItemState.OutcomeUnknown else PersonMergeItemState.Failed,
                    error = message,
                )
            },
            phase = if (outcomeUnknown) {
                PersonMergePhase.NeedsManualReconciliation
            } else {
                PersonMergePhase.PausedForRefresh
            },
        )
    }

    /**
     * Reconcile failed or ambiguous moves from freshly fetched source and target person contents.
     * A detection in neither collection is never guessed: it remains blocked for manual recovery.
     */
    fun reconcileAfterRefresh(
        sourceDetectionIds: Set<Long>,
        targetDetectionIds: Set<Long>,
    ): PersonMergeWorkflow {
        require(phase in setOf(PersonMergePhase.PausedForRefresh, PersonMergePhase.NeedsManualReconciliation))
        require(sourceDetectionIds.intersect(targetDetectionIds).isEmpty()) {
            "A detection cannot be present in both source and target refresh results."
        }
        val updated = items.map { item ->
            if (item.state !in setOf(PersonMergeItemState.Failed, PersonMergeItemState.OutcomeUnknown)) return@map item
            when (item.face.detectionId) {
                in targetDetectionIds -> item.copy(state = PersonMergeItemState.Succeeded, error = null)
                in sourceDetectionIds -> item.copy(state = PersonMergeItemState.Pending, error = null)
                else -> item.copy(
                    state = PersonMergeItemState.MissingAfterRefresh,
                    error = "Face is in neither the source nor target person after refresh.",
                )
            }
        }
        val nextPhase = when {
            updated.all { it.state == PersonMergeItemState.Succeeded } -> PersonMergePhase.Completed
            updated.any { it.state == PersonMergeItemState.MissingAfterRefresh } ->
                PersonMergePhase.NeedsManualReconciliation
            else -> PersonMergePhase.PausedForRefresh
        }
        return copy(items = updated, phase = nextPhase)
    }

    private fun activeIndex(): Int {
        require(phase == PersonMergePhase.Running)
        val active = items.withIndex().filter { it.value.state == PersonMergeItemState.InFlight }
        require(active.size == 1) { "Exactly one face must be in flight." }
        return active.single().index
    }

    companion object {
        fun create(
            source: PersonMediaReference,
            target: PersonMediaReference,
            faces: List<RecognizeFaceReference>,
        ): PersonMergeWorkflow {
            require(source.backend == NextcloudPeopleBackend.Recognize)
            require(target.backend == NextcloudPeopleBackend.Recognize)
            require(source.ownerUserId == target.ownerUserId)
            require(source.clusterId != target.clusterId)
            return PersonMergeWorkflow(source, target, faces.map(::PersonMergeItem), PersonMergePhase.Ready)
        }
    }
}

private fun RecognizeFaceReference.moveRequest(target: PersonMediaReference): PeopleMutationRequest =
    PeopleMutationRequest(
        surface = PeopleMutationSurface.RecognizeDav,
        method = PeopleMutationMethod.MOVE,
        pathTemplate = "/remote.php/dav/recognize/{uid}/faces/{person}/{faceNode}",
        pathValues = mapOf("uid" to person.ownerUserId, "person" to person.lookupName, "faceNode" to davNodeName),
        destinationPathTemplate = "/remote.php/dav/recognize/{uid}/faces/{person}/{faceNode}",
        destinationPathValues = mapOf("uid" to target.ownerUserId, "person" to target.lookupName, "faceNode" to davNodeName),
        overwrite = false,
    )

private inline fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { itemIndex, item -> if (itemIndex == index) transform(item) else item }
