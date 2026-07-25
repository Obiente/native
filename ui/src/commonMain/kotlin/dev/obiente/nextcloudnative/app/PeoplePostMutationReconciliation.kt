package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface PeoplePostMutationReconciliation {
    data class CurrentPerson(val person: NextcloudPerson) : PeoplePostMutationReconciliation
    data object Gallery : PeoplePostMutationReconciliation
    data object Pending : PeoplePostMutationReconciliation
}

@Serializable
sealed interface PeoplePostMutationExpectation {
    val action: PeopleAction

    @Serializable
    data class Rename(val expectedName: String) : PeoplePostMutationExpectation {
        override val action: PeopleAction = PeopleAction.RenamePerson

        init {
            require(expectedName.isNotBlank())
        }
    }

    @Serializable
    data class SetCover(val expectedFileId: Long) : PeoplePostMutationExpectation {
        override val action: PeopleAction = PeopleAction.SetCover

        init {
            require(expectedFileId > 0L)
        }
    }

    @Serializable
    data class RemoveFace(val detectionId: Long) : PeoplePostMutationExpectation {
        override val action: PeopleAction = PeopleAction.RemoveFace

        init {
            require(detectionId > 0L)
        }
    }

    @Serializable
    data class RemovePerson(
        override val action: PeopleAction,
    ) : PeoplePostMutationExpectation {
        init {
            require(action == PeopleAction.MergePerson || action == PeopleAction.DeletePerson)
        }
    }
}

private val peoplePostMutationStateJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun PeoplePostMutationExpectation.encodeForSavedState(): String =
    peoplePostMutationStateJson.encodeToString(this)

internal fun decodePeoplePostMutationExpectation(encoded: String): PeoplePostMutationExpectation =
    peoplePostMutationStateJson.decodeFromString(encoded)

/**
 * Reconciles a successful mutation with a fresh, read-only Memories people response.
 *
 * Identity alone is not enough: an immediate server read can still contain the pre-mutation
 * representation. Reconciliation remains pending until the expected name, cover, exact face
 * absence, or cluster disappearance is visible.
 */
fun reconcilePersonAfterMutation(
    expectation: PeoplePostMutationExpectation,
    previous: NextcloudPerson,
    refreshedPeople: List<NextcloudPerson>,
    refreshedFaceDetectionIds: Set<Long>? = null,
): PeoplePostMutationReconciliation {
    if (expectation is PeoplePostMutationExpectation.RemovePerson) {
        return PeoplePostMutationReconciliation.Gallery
    }
    val matches = refreshedPeople.filter { candidate ->
        candidate.id == previous.id &&
            candidate.userId == previous.userId &&
            candidate.backend == previous.backend
    }
    require(matches.size <= 1) { "The refreshed people response contains a duplicate cluster identity." }
    val current = matches.singleOrNull()
    if (current == null) {
        return if (expectation is PeoplePostMutationExpectation.RemoveFace) {
            PeoplePostMutationReconciliation.Gallery
        } else {
            PeoplePostMutationReconciliation.Pending
        }
    }
    val postconditionVisible = when (expectation) {
        is PeoplePostMutationExpectation.Rename ->
            current.queryName == expectation.expectedName
        is PeoplePostMutationExpectation.SetCover ->
            current.coverFileId == expectation.expectedFileId
        is PeoplePostMutationExpectation.RemoveFace ->
            refreshedFaceDetectionIds?.let { expectation.detectionId !in it } == true
        is PeoplePostMutationExpectation.RemovePerson -> true
    }
    return if (postconditionVisible) {
        PeoplePostMutationReconciliation.CurrentPerson(current)
    } else {
        PeoplePostMutationReconciliation.Pending
    }
}

fun PeopleActionPlan.postMutationExpectation(): PeoplePostMutationExpectation =
    when (action) {
        PeopleAction.RenamePerson -> {
            val request = (binding as? PeopleActionBinding.Single)?.request
            val expectedName = requireNotNull(request?.destinationPathValues?.get("person")) {
                "The rename plan does not contain its reviewed destination person name."
            }
            PeoplePostMutationExpectation.Rename(expectedName)
        }
        PeopleAction.SetCover -> {
            val request = (binding as? PeopleActionBinding.Single)?.request
            val expectedFileId = requireNotNull(request?.bodyFields?.get("fileid")?.toLongOrNull()) {
                "The cover plan does not contain its reviewed source file ID."
            }
            PeoplePostMutationExpectation.SetCover(expectedFileId)
        }
        PeopleAction.RemoveFace -> {
            val request = (binding as? PeopleActionBinding.Single)?.request
            val faceNode = requireNotNull(request?.pathValues?.get("faceNode")) {
                "The face-removal plan does not contain its reviewed face node."
            }
            val detectionId = requireNotNull(faceNode.substringBefore('-').toLongOrNull()) {
                "The face-removal plan does not contain a valid reviewed detection ID."
            }
            PeoplePostMutationExpectation.RemoveFace(detectionId)
        }
        PeopleAction.MergePerson,
        PeopleAction.DeletePerson,
        -> PeoplePostMutationExpectation.RemovePerson(action)
    }
