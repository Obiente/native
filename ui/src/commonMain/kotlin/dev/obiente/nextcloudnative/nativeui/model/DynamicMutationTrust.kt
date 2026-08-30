package dev.obiente.nextcloudnative.nativeui.model

internal fun DynamicAction.hasTrustedRootMutationEvidence(): Boolean =
    confidence in setOf(Confidence.high, Confidence.verified) &&
        provenance.any { evidence -> evidence.kind in TRUSTED_ROOT_MUTATION_PROVENANCE }

internal fun DynamicForm.hasTrustedRootMutationEvidence(): Boolean =
    confidence in setOf(Confidence.high, Confidence.verified) &&
        provenance.any { evidence -> evidence.kind in TRUSTED_ROOT_MUTATION_PROVENANCE }

private val TRUSTED_ROOT_MUTATION_PROVENANCE = setOf(
    ProvenanceKind.advertisedOpenApi,
    ProvenanceKind.verifiedAdapter,
    ProvenanceKind.verifiedAppPackage,
    ProvenanceKind.appStoreLinkedSourceTag,
)
