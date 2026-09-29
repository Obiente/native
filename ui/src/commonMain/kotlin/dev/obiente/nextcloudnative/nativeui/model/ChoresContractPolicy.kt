package dev.obiente.nextcloudnative.nativeui.model

/** Version eligibility only; callers still require verified package evidence and exact action bindings. */
internal fun AppIdentity.isSupportedChoresVersion(): Boolean =
    id == "chores" && version in setOf("0.1.0", "0.2.0")

internal fun DynamicAppDescriptor.verifiedRecordIdentityFieldId(action: DynamicAction): String? {
    if (
        !app.isSupportedChoresVersion() ||
        action.binding.method != HttpMethod.GET ||
        action.binding.path != "/apps/chores/api/v1.0/account/invites" ||
        action.confidence != Confidence.verified ||
        action.provenance.none { provenance -> provenance.kind == ProvenanceKind.verifiedAppPackage } ||
        action.responseFieldIds.count { fieldId -> fieldId == "inviteId" } != 1 ||
        actions.count { candidate -> candidate.id == action.id } != 1
    ) {
        return null
    }
    return "inviteId"
}

internal fun ActionSpec.hasVerifiedChoresCompletionContract(): Boolean = !(
        binding.method != HttpMethod.POST ||
        binding.path.substringBefore('?').trimEnd('/') != "/apps/chores/api/v1.0/team/{teamId}/work" ||
        binding.pathParameterNames != listOf("teamId") ||
        binding.requiredPathParameterNames != listOf("teamId") ||
        binding.queryParameterNames.isNotEmpty() ||
        binding.requiredQueryParameterNames.isNotEmpty() ||
        binding.bodyFieldNames != listOf("work") ||
        binding.requiredBodyFieldNames != listOf("work") ||
        binding.bodyContentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" ||
        binding.allowsObservedBodyFields ||
        intent != ActionIntent.execute || risk != ActionRisk.mutating ||
        evidence.none { entry -> entry.source == EvidenceSource.verifiedAppPackage }
    )
