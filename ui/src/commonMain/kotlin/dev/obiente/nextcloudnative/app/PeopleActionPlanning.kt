package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

@Serializable
enum class PeopleAction {
    RenamePerson,
    MergePerson,
    SetCover,
    RemoveFace,
    DeletePerson,
}

enum class PeopleActionRisk {
    MetadataWrite,
    DestructiveMetadata,
    MultiStepDestructiveMetadata,
}

enum class PeopleActionAuthRequirement {
    AuthenticatedNextcloudSession,
    ShortLivedRecognizeApiKey,
}

enum class PeopleActionPartialFailure {
    SingleRequestOutcomeRequiresRefresh,
    PerFaceMovesCanPartiallyApply,
}

enum class PeopleActionRetryPolicy {
    NeverAutomaticRefreshBeforeRetry,
    ReconcileEachFaceBeforeResume,
}

enum class PeopleMutationSurface { MemoriesApi, RecognizeDav }

enum class PeopleMutationMethod { POST, MOVE, DELETE }

data class PeopleActionConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

/**
 * A non-executable endpoint description. Path values stay separate so a later transport must encode
 * each value as a path segment instead of interpolating untrusted names into a URL.
 */
data class PeopleMutationRequest(
    val surface: PeopleMutationSurface,
    val method: PeopleMutationMethod,
    val pathTemplate: String,
    val pathValues: Map<String, String> = emptyMap(),
    val destinationPathTemplate: String? = null,
    val destinationPathValues: Map<String, String> = emptyMap(),
    val overwrite: Boolean? = null,
    val bodyFields: Map<String, String> = emptyMap(),
) {
    init {
        require(pathTemplate.startsWith('/'))
        require((destinationPathTemplate == null) == destinationPathValues.isEmpty())
        require(destinationPathTemplate == null || destinationPathTemplate.startsWith('/'))
        require(method == PeopleMutationMethod.MOVE || destinationPathTemplate == null)
        require(method == PeopleMutationMethod.MOVE || overwrite == null)
    }
}

sealed interface PeopleActionBinding {
    data class Single(val request: PeopleMutationRequest) : PeopleActionBinding

    data class PerFaceMoveWorkflow(
        val sourcePathTemplate: String,
        val destinationPathTemplate: String,
        val overwrite: Boolean,
    ) : PeopleActionBinding
}

data class PeopleActionPlan(
    val action: PeopleAction,
    val label: String,
    val enabled: Boolean,
    val disabledReason: String?,
    val risk: PeopleActionRisk,
    val confirmation: PeopleActionConfirmation,
    val authRequirements: Set<PeopleActionAuthRequirement>,
    val partialFailure: PeopleActionPartialFailure,
    val retryPolicy: PeopleActionRetryPolicy,
    val binding: PeopleActionBinding,
) {
    init {
        require(enabled == (disabledReason == null))
    }
}

data class PeopleActionSupport(
    val currentUserId: String,
    val memoriesPeopleApiAvailable: Boolean,
    val recognizeDavAvailable: Boolean,
    val recognizeApiKeyRequired: Boolean = true,
    val recognizeApiKeyAvailable: Boolean = false,
) {
    init {
        require(currentUserId.isNotBlank())
    }
}

data class RecognizeFaceReference(
    val person: PersonMediaReference,
    val detectionId: Long,
    val sourceFileName: String,
) {
    init {
        require(person.backend == NextcloudPeopleBackend.Recognize)
        require(detectionId > 0L) { "The face detection ID is invalid." }
        require(sourceFileName.isNotBlank() && '/' !in sourceFileName) { "The source filename is invalid." }
    }

    val davNodeName: String get() = "$detectionId-$sourceFileName"
}

fun planRenamePerson(
    person: PersonMediaReference,
    currentDisplayName: String,
    requestedName: String,
    support: PeopleActionSupport,
): PeopleActionPlan {
    val newName = requestedName.trim()
    require(newName.isNotEmpty() && '/' !in newName) { "The new person name is invalid." }
    require(newName.toLongOrNull() == null) { "Recognize does not allow numeric-only person names." }
    require(newName != person.lookupName) { "The new person name must be different." }
    return recognizePlan(
        action = PeopleAction.RenamePerson,
        label = "Rename person",
        person = person,
        support = support,
        risk = PeopleActionRisk.MetadataWrite,
        confirmation = PeopleActionConfirmation(
            title = "Rename person?",
            message = "Rename “$currentDisplayName” to “$newName”? This changes the person label in Recognize.",
            confirmLabel = "Rename",
        ),
        partialFailure = PeopleActionPartialFailure.SingleRequestOutcomeRequiresRefresh,
        retryPolicy = PeopleActionRetryPolicy.NeverAutomaticRefreshBeforeRetry,
        binding = PeopleActionBinding.Single(
            PeopleMutationRequest(
                surface = PeopleMutationSurface.RecognizeDav,
                method = PeopleMutationMethod.MOVE,
                pathTemplate = RECOGNIZE_PERSON_PATH,
                pathValues = person.pathValues(),
                destinationPathTemplate = RECOGNIZE_PERSON_PATH,
                destinationPathValues = person.pathValues(personName = newName),
                overwrite = false,
            ),
        ),
    )
}

fun planMergePeople(
    source: PersonMediaReference,
    sourceDisplayName: String,
    target: PersonMediaReference,
    targetDisplayName: String,
    support: PeopleActionSupport,
): PeopleActionPlan {
    require(source.clusterId != target.clusterId) { "A person cannot be merged into itself." }
    require(source.ownerUserId == target.ownerUserId) { "Recognize cannot merge people across owners." }
    return recognizePlan(
        action = PeopleAction.MergePerson,
        label = "Merge with another person",
        person = source,
        support = support,
        additionalDisabledReason = recognizePersonDisabledReason(target, support),
        risk = PeopleActionRisk.MultiStepDestructiveMetadata,
        confirmation = PeopleActionConfirmation(
            title = "Merge people?",
            message = "Merge “$sourceDisplayName” into “$targetDisplayName”? Faces move one at a time, so an interruption can leave both people partially changed.",
            confirmLabel = "Merge",
        ),
        partialFailure = PeopleActionPartialFailure.PerFaceMovesCanPartiallyApply,
        retryPolicy = PeopleActionRetryPolicy.ReconcileEachFaceBeforeResume,
        binding = PeopleActionBinding.PerFaceMoveWorkflow(
            sourcePathTemplate = RECOGNIZE_FACE_PATH,
            destinationPathTemplate = RECOGNIZE_FACE_PATH,
            overwrite = false,
        ),
    )
}

fun planSetPersonCover(
    person: PersonMediaReference,
    personDisplayName: String,
    sourceFile: NextcloudFile,
    support: PeopleActionSupport,
): PeopleActionPlan {
    val disabledReason = when {
        !support.memoriesPeopleApiAvailable -> "Memories person covers are unavailable on this server."
        person.ownerUserId != support.currentUserId -> "Only the owner can change this person’s cover."
        sourceFile.fileId == null -> "Refresh this photo before using it as a cover."
        sourceFile.isDirectory -> "A folder cannot be used as a person cover."
        else -> null
    }
    return PeopleActionPlan(
        action = PeopleAction.SetCover,
        label = "Set as person cover",
        enabled = disabledReason == null,
        disabledReason = disabledReason,
        risk = PeopleActionRisk.MetadataWrite,
        confirmation = PeopleActionConfirmation(
            title = "Change person cover?",
            message = "Use “${sourceFile.name}” as the cover for “$personDisplayName”? This changes only the Memories person cover.",
            confirmLabel = "Set cover",
        ),
        authRequirements = setOf(PeopleActionAuthRequirement.AuthenticatedNextcloudSession),
        partialFailure = PeopleActionPartialFailure.SingleRequestOutcomeRequiresRefresh,
        retryPolicy = PeopleActionRetryPolicy.NeverAutomaticRefreshBeforeRetry,
        binding = PeopleActionBinding.Single(
            PeopleMutationRequest(
                surface = PeopleMutationSurface.MemoriesApi,
                method = PeopleMutationMethod.POST,
                pathTemplate = "/index.php/apps/memories/api/clusters/{backend}/set-cover",
                pathValues = mapOf("backend" to person.backend.apiValue),
                bodyFields = buildMap {
                    put(
                        "name",
                        if (person.backend == NextcloudPeopleBackend.Recognize) {
                            "${person.ownerUserId}/${person.lookupName}"
                        } else {
                            person.lookupName
                        },
                    )
                    sourceFile.fileId?.let { put("fileid", it.toString()) }
                },
            ),
        ),
    )
}

fun planRemoveFace(
    face: RecognizeFaceReference,
    personDisplayName: String,
    support: PeopleActionSupport,
): PeopleActionPlan = recognizePlan(
    action = PeopleAction.RemoveFace,
    label = "Remove face from person",
    person = face.person,
    support = support,
    risk = PeopleActionRisk.DestructiveMetadata,
    confirmation = PeopleActionConfirmation(
        title = "Remove face from person?",
        message = "Remove “${face.sourceFileName}” from “$personDisplayName”? The photo stays in Files, but Recognize may regroup this face later.",
        confirmLabel = "Remove face",
    ),
    partialFailure = PeopleActionPartialFailure.SingleRequestOutcomeRequiresRefresh,
    retryPolicy = PeopleActionRetryPolicy.NeverAutomaticRefreshBeforeRetry,
    binding = PeopleActionBinding.Single(
        PeopleMutationRequest(
            surface = PeopleMutationSurface.RecognizeDav,
            method = PeopleMutationMethod.DELETE,
            pathTemplate = RECOGNIZE_FACE_PATH,
            pathValues = face.person.pathValues(face.davNodeName),
        ),
    ),
)

fun planDeletePerson(
    person: PersonMediaReference,
    personDisplayName: String,
    support: PeopleActionSupport,
): PeopleActionPlan = recognizePlan(
    action = PeopleAction.DeletePerson,
    label = "Remove person",
    person = person,
    support = support,
    risk = PeopleActionRisk.DestructiveMetadata,
    confirmation = PeopleActionConfirmation(
        title = "Remove person?",
        message = "Remove “$personDisplayName”? Photos stay in Files, but every face in this group moves to Recognize’s rejected pool.",
        confirmLabel = "Remove person",
    ),
    partialFailure = PeopleActionPartialFailure.SingleRequestOutcomeRequiresRefresh,
    retryPolicy = PeopleActionRetryPolicy.NeverAutomaticRefreshBeforeRetry,
    binding = PeopleActionBinding.Single(
        PeopleMutationRequest(
            surface = PeopleMutationSurface.RecognizeDav,
            method = PeopleMutationMethod.DELETE,
            pathTemplate = RECOGNIZE_PERSON_PATH,
            pathValues = person.pathValues(),
        ),
    ),
)

private fun recognizePlan(
    action: PeopleAction,
    label: String,
    person: PersonMediaReference,
    support: PeopleActionSupport,
    risk: PeopleActionRisk,
    confirmation: PeopleActionConfirmation,
    partialFailure: PeopleActionPartialFailure,
    retryPolicy: PeopleActionRetryPolicy,
    binding: PeopleActionBinding,
    additionalDisabledReason: String? = null,
): PeopleActionPlan {
    val disabledReason = recognizePersonDisabledReason(person, support) ?: additionalDisabledReason
    return PeopleActionPlan(
        action = action,
        label = label,
        enabled = disabledReason == null,
        disabledReason = disabledReason,
        risk = risk,
        confirmation = confirmation,
        authRequirements = buildSet {
            add(PeopleActionAuthRequirement.AuthenticatedNextcloudSession)
            if (support.recognizeApiKeyRequired) add(PeopleActionAuthRequirement.ShortLivedRecognizeApiKey)
        },
        partialFailure = partialFailure,
        retryPolicy = retryPolicy,
        binding = binding,
    )
}

private fun recognizePersonDisabledReason(
    person: PersonMediaReference,
    support: PeopleActionSupport,
): String? = when {
    person.backend != NextcloudPeopleBackend.Recognize ->
        "This verified action is only available for the Recognize people backend."
    person.ownerUserId != support.currentUserId -> "Only the owner can change this person."
    !support.recognizeDavAvailable -> "The Recognize DAV people API is unavailable on this server."
    support.recognizeApiKeyRequired && !support.recognizeApiKeyAvailable ->
        "Recognize changes require a short-lived X-Recognize-Api-Key, which is unavailable to this client."
    else -> null
}

private fun PersonMediaReference.pathValues(
    faceNodeName: String? = null,
    personName: String = lookupName,
): Map<String, String> = buildMap {
    put("uid", ownerUserId)
    put("person", personName)
    faceNodeName?.let { put("faceNode", it) }
}

private const val RECOGNIZE_PERSON_PATH = "/remote.php/dav/recognize/{uid}/faces/{person}"
private const val RECOGNIZE_FACE_PATH = "/remote.php/dav/recognize/{uid}/faces/{person}/{faceNode}"
