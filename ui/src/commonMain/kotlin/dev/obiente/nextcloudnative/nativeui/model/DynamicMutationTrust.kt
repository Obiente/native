package dev.obiente.nextcloudnative.nativeui.model

/**
 * Signed contracts may expose app-level commands whose resource has no corresponding read route.
 * They still belong in the native workspace when every request value comes from the form itself;
 * otherwise valid operations such as imports, scans, and provisioning remain impossible to reach.
 * Route-bound mutations continue to require a selected record and are never promoted to the root.
 */
internal fun DynamicAction.isTrustedSelfContainedRootForm(form: DynamicForm): Boolean =
    binding.method != HttpMethod.GET &&
        binding.pathParameters.isEmpty() &&
        risk == ActionRisk.mutating &&
        resourceId.sameDynamicResourceAs(form.resourceId) &&
        hasTrustedRootMutationEvidence() &&
        form.hasTrustedRootMutationEvidence()

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
