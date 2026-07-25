package dev.obiente.nextcloudnative.app

/**
 * Presentation state for the person action menu.
 *
 * This deliberately describes whether an action can be prepared in the native UI. It does not
 * execute a mutation and it does not relax any of the requirements in [PeopleActionPlan].
 */
data class PeopleActionMenuItem(
    val action: PeopleAction,
    val label: String,
    val enabled: Boolean,
    val disabledReason: String? = null,
) {
    init {
        require(enabled == (disabledReason == null))
    }
}

fun personActionMenuItems(
    person: PersonMediaReference,
    support: PeopleActionSupport,
    hasSelectablePhoto: Boolean,
    hasDirectFaceReferences: Boolean,
): List<PeopleActionMenuItem> {
    val recognizeReason = recognizeActionDisabledSummary(person, support)
    val coverReason = when {
        !support.memoriesPeopleApiAvailable -> "Memories cover editing is unavailable."
        person.ownerUserId != support.currentUserId -> "Only the owner can change this cover."
        !hasSelectablePhoto -> "No loaded photo is available."
        else -> null
    }
    val removeFaceReason = recognizeReason ?: if (!hasDirectFaceReferences) {
        "Face assignment details are not available yet."
    } else {
        null
    }

    return listOf(
        PeopleActionMenuItem(
            action = PeopleAction.RenamePerson,
            label = "Rename person",
            enabled = recognizeReason == null,
            disabledReason = recognizeReason,
        ),
        PeopleActionMenuItem(
            action = PeopleAction.MergePerson,
            label = "Merge with another person",
            enabled = recognizeReason == null,
            disabledReason = recognizeReason,
        ),
        PeopleActionMenuItem(
            action = PeopleAction.SetCover,
            label = "Choose cover photo",
            enabled = coverReason == null,
            disabledReason = coverReason,
        ),
        PeopleActionMenuItem(
            action = PeopleAction.RemoveFace,
            label = "Remove face from person",
            enabled = removeFaceReason == null,
            disabledReason = removeFaceReason,
        ),
        PeopleActionMenuItem(
            action = PeopleAction.DeletePerson,
            label = "Remove person",
            enabled = recognizeReason == null,
            disabledReason = recognizeReason,
        ),
    )
}

private fun recognizeActionDisabledSummary(
    person: PersonMediaReference,
    support: PeopleActionSupport,
): String? = when {
    person.backend != NextcloudPeopleBackend.Recognize -> "This action requires the Recognize backend."
    person.ownerUserId != support.currentUserId -> "Only the owner can change this person."
    !support.recognizeDavAvailable -> "Recognize person editing is unavailable."
    support.recognizeApiKeyRequired && !support.recognizeApiKeyAvailable ->
        "Recognize's short-lived API key is unavailable."
    else -> null
}
