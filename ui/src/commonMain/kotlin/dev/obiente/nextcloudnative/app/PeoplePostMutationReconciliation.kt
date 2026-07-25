package dev.obiente.nextcloudnative.app

sealed interface PeoplePostMutationReconciliation {
    data class CurrentPerson(val person: NextcloudPerson) : PeoplePostMutationReconciliation
    data object Gallery : PeoplePostMutationReconciliation
}

/**
 * Reconciles a successful mutation with a fresh, read-only Memories people response.
 *
 * Recognize keeps the numeric cluster ID stable across a rename and cover change. A removed final
 * face can make the cluster disappear, while merge and delete intentionally return to the gallery.
 */
fun reconcilePersonAfterMutation(
    action: PeopleAction,
    previous: NextcloudPerson,
    refreshedPeople: List<NextcloudPerson>,
): PeoplePostMutationReconciliation {
    if (action == PeopleAction.MergePerson || action == PeopleAction.DeletePerson) {
        return PeoplePostMutationReconciliation.Gallery
    }
    val matches = refreshedPeople.filter { candidate ->
        candidate.id == previous.id &&
            candidate.userId == previous.userId &&
            candidate.backend == previous.backend
    }
    require(matches.size <= 1) { "The refreshed people response contains a duplicate cluster identity." }
    return matches.singleOrNull()
        ?.let(PeoplePostMutationReconciliation::CurrentPerson)
        ?: PeoplePostMutationReconciliation.Gallery
}
