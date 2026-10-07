package dev.obiente.nextcloudnative.nativeui.model

/** Prefer playable contents over metadata when an existing verified read binds this container. */
internal fun DynamicAppDescriptor.preferredMediaCollectionChild(
    context: DynamicResourceRecordContext,
): DynamicNavigationDestination? {
    if (!context.actionBindingProvenanceValid || context.recordId.isBlank()) return null
    val parent = resources.singleOrNull { it.id == context.resourceId } ?: return null
    val parentConcepts = (parent.id + " " + parent.label).semanticConceptTokens()
    if (parentConcepts.none { it == "album" || it == "playlist" }) return null
    return planDynamicNavigation(context).contextualChildDestinations.filter { destination ->
        val layout = layouts.singleOrNull { it.id == destination.layoutId } ?: return@filter false
        if (layout.kind !in setOf(LayoutKind.list, LayoutKind.grid)) return@filter false
        val action = actions.singleOrNull { it.id == destination.actionId } ?: return@filter false
        val child = resources.singleOrNull { it.id == action.resourceId } ?: return@filter false
        if (!(child.id + " " + child.label).hasAnySemanticConcept(setOf("track", "song"))) return@filter false
        if (action.binding.method != HttpMethod.GET || action.intent != ActionIntent.list ||
            action.risk != ActionRisk.readOnly || action.confidence !in setOf(Confidence.high, Confidence.verified) ||
            !action.binding.path.isApproved(endpointPolicy) ||
            action.provenance.none { it.kind in setOf(ProvenanceKind.advertisedOpenApi,
                ProvenanceKind.verifiedAppPackage, ProvenanceKind.verifiedAdapter) } ||
            isSecondaryTechnicalDestination(context, destination)) return@filter false
        // A label never creates a relationship: the planned read must bind a declared parent filter.
        (action.binding.pathParameters + action.binding.queryParameters).any { parameter ->
            parameter.name.removeSuffix("Id").removeSuffix("ID").sameDynamicResourceAs(parent.id) &&
                destination.pathParameterValues[parameter.name] == context.recordId
        }
    }.singleOrNull()
}
