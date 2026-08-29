package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.template.scanBracedTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A selected resource record and any exact parameter bindings already known by the host. */
data class DynamicResourceRecordContext(
    val resourceId: String,
    val recordId: String,
    val fieldValues: Map<String, String?> = emptyMap(),
    val parameterValues: Map<String, String> = emptyMap(),
    val currentLayoutId: String? = null,
    val visitedStates: Set<DynamicNavigationState> = emptySet(),
    val actionSafeIdentity: Boolean = true,
    /**
     * Whether the selected record's request, parent, and response identities agree.
     *
     * Read-only navigation may remain useful when display data is retained after a conflict, but
     * the record must not authorize a mutation until its provenance is unambiguous again.
     */
    val actionBindingProvenanceValid: Boolean = true,
)

/** Resource/view/context identity used to prevent a dynamic navigation graph from revisiting itself. */
data class DynamicNavigationState(
    val resourceIdentity: String,
    val layoutId: String,
    val parameterValues: Map<String, String>,
)

internal fun dynamicNavigationState(
    resourceId: String,
    layoutId: String,
    parameterValues: Map<String, String> = emptyMap(),
): DynamicNavigationState = DynamicNavigationState(
    resourceIdentity = resourceId.resourceIdentity(),
    layoutId = layoutId,
    parameterValues = parameterValues.toSortedMap(),
)

data class DynamicNavigationDestination(
    val layoutId: String,
    val label: String,
    val resourceId: String,
    val actionId: String,
    val pathParameterValues: Map<String, String> = emptyMap(),
) {
    fun navigationState(): DynamicNavigationState = dynamicNavigationState(
        resourceId = resourceId,
        layoutId = layoutId,
        parameterValues = pathParameterValues,
    )
}

data class DynamicNavigationFormAction(
    val formId: String,
    val label: String,
    val resourceId: String,
    val actionId: String,
    val pathParameterValues: Map<String, String> = emptyMap(),
)

data class DynamicNavigationPlan(
    val rootDestinations: List<DynamicNavigationDestination>,
    val rootFormActions: List<DynamicNavigationFormAction> = emptyList(),
    val contextualChildDestinations: List<DynamicNavigationDestination> = emptyList(),
    val contextualFormActions: List<DynamicNavigationFormAction> = emptyList(),
)

/**
 * Picks the only safe child collection when the selected record has no dedicated surface.
 * The plan already enforces read-only intent, complete bindings and cycle suppression.
 */
fun DynamicAppDescriptor.singleSafeContextualChild(
    context: DynamicResourceRecordContext,
    hasDedicatedSurface: Boolean,
): DynamicNavigationDestination? {
    if (hasDedicatedSurface) return null
    return planDynamicNavigation(context).contextualChildDestinations.singleOrNull()
}

/**
 * Chooses a useful collection child for container records that otherwise have a technical detail
 * surface. This is semantic rather than app-specific: list, checklist, project, and container
 * records prefer their single item, task, or entry collection; account-like containers prefer
 * their single mailbox collection; and mailbox-like containers prefer their single message
 * collection.
 *
 * Automatic entry requires an accepted declared relationship, a read-only list action, a
 * collection layout, and complete record-context binding. Ambiguous equal-scoring children remain
 * explicit tabs instead of being guessed.
 */
fun DynamicAppDescriptor.preferredSemanticContextualChild(
    context: DynamicResourceRecordContext,
): DynamicNavigationDestination? {
    val actionsById = actions.associateBy(DynamicAction::id)
    val collectionLayoutsById = layouts
        .filter(DynamicLayout::isCollectionNavigationLayout)
        .associateBy(DynamicLayout::id)
    val declaredChildLinksByAction = acyclicNavigationLinks(actionsById)
        .filter { edge -> edge.link.resourceId.sameResourceAs(context.resourceId) }
        .associateBy { edge -> edge.action.id }
    val plannedChildren = planDynamicNavigation(context).contextualChildDestinations
    val hasDeclaredContentCollection = plannedChildren.any { destination ->
        semanticConceptsForDestination(destination).any(SEMANTIC_CONTENT_CONTAINER_CONCEPTS::contains)
    }
    val scored = plannedChildren.mapNotNull { destination ->
        val layout = collectionLayoutsById[destination.layoutId] ?: return@mapNotNull null
        val action = actionsById[destination.actionId]
            ?.takeIf(DynamicAction::isCollectionReadAction)
            ?: return@mapNotNull null
        val edge = declaredChildLinksByAction[action.id] ?: return@mapNotNull null
        if (!layout.resourceId.sameResourceAs(action.resourceId)) return@mapNotNull null
        val resolution = action.resolveNavigationParameters(context, edge.link)
        if (!resolution.complete || !resolution.usedContext) return@mapNotNull null
        if (isSecondaryTechnicalDestination(context, destination)) return@mapNotNull null
        val score = preferredSemanticChildScore(
            context = context,
            destination = destination,
            hasDeclaredContentCollection = hasDeclaredContentCollection,
        )
        destination.takeIf { score > 0 }?.let { it to score }
    }
    val bestScore = scored.maxOfOrNull { (_, score) -> score } ?: return null
    return scored.filter { (_, score) -> score == bestScore }
        .singleOrNull()
        ?.first
}

/**
 * Keeps protocol and response-helper resources out of normal content tabs while retaining them as
 * explicitly selectable advanced views. The rule uses relationship shape and semantic names, never
 * the app id.
 */
fun DynamicAppDescriptor.isSecondaryTechnicalDestination(
    context: DynamicResourceRecordContext,
    destination: DynamicNavigationDestination,
): Boolean {
    val action = actions.firstOrNull { action -> action.id == destination.actionId }
    if (action != null && action.risk != ActionRisk.readOnly) return true
    val parent = context.resourceId.navigationSemanticIdentity()
    val child = (
        destination.resourceId + " " + destination.label + " " + destination.actionId
    ).navigationSemanticIdentity()
    if (SECONDARY_CHILD_CONCEPTS.any(child::hasNavigationConcept)) return true
    val parentIsMessage = parent.hasNavigationConcept("message") || parent.hasNavigationConcept("email")
    return parentIsMessage && MESSAGE_HELPER_CHILD_CONCEPTS.any(child::hasNavigationConcept)
}

private fun DynamicAppDescriptor.preferredSemanticChildScore(
    context: DynamicResourceRecordContext,
    destination: DynamicNavigationDestination,
    hasDeclaredContentCollection: Boolean,
): Int {
    val parentConcepts = semanticConceptsForResource(context.resourceId)
    val childConcepts = semanticConceptsForDestination(destination)
    val parentIsContentContainer = parentConcepts.any(SEMANTIC_CONTENT_CONTAINER_CONCEPTS::contains)
    val parentIsAccount = parentConcepts.any(SEMANTIC_ACCOUNT_CONTAINER_CONCEPTS::contains)
    val parentIsMailbox = parentConcepts.any(SEMANTIC_MAILBOX_CONTAINER_CONCEPTS::contains)
    val parentIsTaxonomy = parentConcepts.any(SEMANTIC_TAXONOMY_CONCEPTS::contains)
    val contentEvidence = destination.semanticConceptEvidence(
        childConcepts,
        SEMANTIC_CONTENT_CHILD_CONCEPTS,
    )
    val contentCollectionEvidence = destination.semanticConceptEvidence(
        childConcepts,
        SEMANTIC_CONTENT_CONTAINER_CONCEPTS,
    )
    val mailboxEvidence = destination.semanticConceptEvidence(childConcepts, setOf("mailbox"))
    val folderEvidence = destination.semanticConceptEvidence(childConcepts, setOf("folder"))
    val messageEvidence = destination.semanticConceptEvidence(childConcepts, setOf("message", "email"))
    val taxonomyContentEvidence = destination.semanticConceptEvidence(
        childConcepts,
        PRIMARY_CONTENT_ROOT_CONCEPTS,
    )
    return when {
        parentIsContentContainer && contentEvidence > 0 -> 500 + contentEvidence
        hasDeclaredContentCollection && contentCollectionEvidence > 0 -> 450 + contentCollectionEvidence
        parentIsAccount && mailboxEvidence > 0 -> 400 + mailboxEvidence
        parentIsAccount && folderEvidence > 0 -> 300 + folderEvidence
        parentIsMailbox && messageEvidence > 0 -> 400 + messageEvidence
        parentIsTaxonomy && taxonomyContentEvidence > 0 -> 350 + taxonomyContentEvidence
        else -> 0
    }
}

private fun DynamicAppDescriptor.semanticConceptsForResource(resourceId: String): Set<String> = buildSet {
    addAll(resourceId.semanticConceptTokens())
    resources
        .asSequence()
        .filter { resource -> resource.id.sameResourceAs(resourceId) }
        .flatMap { resource -> sequenceOf(resource.id, resource.label) }
        .flatMap { value -> value.semanticConceptTokens().asSequence() }
        .forEach(::add)
    layouts
        .asSequence()
        .filter { layout -> layout.resourceId.sameResourceAs(resourceId) }
        .flatMap { layout -> sequenceOf(layout.resourceId, layout.title) }
        .flatMap { value -> value.semanticConceptTokens().asSequence() }
        .forEach(::add)
}

private fun DynamicAppDescriptor.semanticConceptsForDestination(
    destination: DynamicNavigationDestination,
): Set<String> = buildSet {
    addAll(destination.resourceId.semanticConceptTokens())
    addAll(destination.label.semanticConceptTokens())
    addAll(destination.actionId.semanticConceptTokens())
    actions.firstOrNull { action -> action.id == destination.actionId }?.let { action ->
        addAll(action.label.semanticConceptTokens())
        addAll(action.resourceId.semanticConceptTokens())
    }
}

private fun DynamicNavigationDestination.semanticConceptEvidence(
    concepts: Set<String>,
    expectedConcepts: Set<String>,
): Int = when {
    resourceId.semanticConceptTokens().any(expectedConcepts::contains) -> 3
    label.semanticConceptTokens().any(expectedConcepts::contains) -> 2
    actionId.semanticConceptTokens().any(expectedConcepts::contains) -> 1
    concepts.any(expectedConcepts::contains) -> 1
    else -> 0
}

private fun String.navigationSemanticIdentity(): String =
    lowercase().filter(Char::isLetterOrDigit)

private fun String.hasNavigationConcept(singular: String): Boolean =
    contains(singular) || contains("${singular}s")

private val SECONDARY_CHILD_CONCEPTS = setOf(
    "archive",
    "deleted",
    "debug",
    "diagnostic",
    "history",
    "internal",
    "metadata",
    "protocol",
    "recycle",
    "schema",
    "trash",
)

private val MESSAGE_HELPER_CHILD_CONCEPTS = setOf(
    "dkim",
    "dmarc",
    "itinerary",
    "needstranslation",
    "raw",
    "smartreply",
    "source",
    "thread",
)

private val SEMANTIC_CONTENT_CONTAINER_CONCEPTS = setOf(
    "checklist",
    "container",
    "list",
    "project",
)

private val SEMANTIC_CONTENT_CHILD_CONCEPTS = setOf(
    "entry",
    "item",
    "task",
)

private val SEMANTIC_ACCOUNT_CONTAINER_CONCEPTS = setOf(
    "account",
    "container",
)

private val SEMANTIC_MAILBOX_CONTAINER_CONCEPTS = setOf(
    "folder",
    "mailbox",
)

/**
 * Resolves a declared read action for a selected record, including a response-observed identity.
 * Observed identities are deliberately allowed only here and in other read-navigation paths; form
 * planning continues to require contract-declared action-safe identities.
 */
fun DynamicAppDescriptor.resolveDynamicRecordReadParameters(
    actionId: String,
    context: DynamicResourceRecordContext,
): Map<String, String>? {
    val action = actions.firstOrNull { it.id == actionId } ?: return null
    if (action.binding.method != HttpMethod.GET ||
        action.intent !in setOf(ActionIntent.list, ActionIntent.read) ||
        action.risk != ActionRisk.readOnly
    ) return null
    val resolution = action.resolveNavigationParameters(context, allowEphemeralIdentity = true)
    return resolution.values.takeIf { resolution.complete }
}

enum class DynamicChildCandidateStatus {
    included,
    selfEdge,
    cycle,
    missingContext,
    ancestorOnlyContext,
    noLayout,
    noLink,
}

data class DynamicChildNavigationDiagnostic(
    val actionId: String,
    val resourceId: String,
    val layoutId: String?,
    val status: DynamicChildCandidateStatus,
    val missingContextParameters: List<String> = emptyList(),
)

/** Pure, response-free explanation of how declared collection actions relate to [context]. */
fun DynamicAppDescriptor.explainDynamicChildNavigation(
    context: DynamicResourceRecordContext,
): List<DynamicChildNavigationDiagnostic> {
    val actionsById = actions.associateBy(DynamicAction::id)
    val acceptedLinks = acyclicNavigationLinks(actionsById)
    val includedActionIds = planDynamicNavigation(context).contextualChildDestinations
        .mapTo(mutableSetOf(), DynamicNavigationDestination::actionId)
    return actions.asSequence()
        .filter(DynamicAction::isCollectionReadAction)
        .map { action ->
            val layout = layouts.firstOrNull { candidate ->
                candidate.sourceActionId == action.id && candidate.isCollectionNavigationLayout()
            }
            val rawContextLinks = links.filter { link ->
                link.resourceId.sameResourceAs(context.resourceId) &&
                    (link.target as? DynamicLinkTarget.Action)?.actionId == action.id
            }
            val accepted = acceptedLinks.any { edge ->
                edge.action.id == action.id && edge.link.resourceId.sameResourceAs(context.resourceId)
            }
            val resolution = action.resolveNavigationParameters(context, rawContextLinks.firstOrNull())
            val status = when {
                action.id in includedActionIds -> DynamicChildCandidateStatus.included
                layout == null -> DynamicChildCandidateStatus.noLayout
                action.resourceId.sameResourceAs(context.resourceId) -> DynamicChildCandidateStatus.selfEdge
                rawContextLinks.isNotEmpty() && !accepted -> DynamicChildCandidateStatus.cycle
                !resolution.complete -> DynamicChildCandidateStatus.missingContext
                rawContextLinks.isEmpty() -> DynamicChildCandidateStatus.noLink
                !resolution.usedSelectedRecord -> DynamicChildCandidateStatus.ancestorOnlyContext
                else -> DynamicChildCandidateStatus.noLink
            }
            DynamicChildNavigationDiagnostic(
                actionId = action.id,
                resourceId = action.resourceId,
                layoutId = layout?.id,
                status = status,
                missingContextParameters = resolution.missingRequiredParameterNames,
            )
        }
        .sortedWith(compareBy(DynamicChildNavigationDiagnostic::resourceId, DynamicChildNavigationDiagnostic::actionId))
        .toList()
}

/**
 * Derives navigation without app-specific IDs or network access.
 *
 * Exact parameter values and same-named record fields take precedence. A record ID fallback is
 * deliberately narrow: it can bind the selected resource's qualified `*Id`, the same resource's
 * bare `id`, or the identity field of an emitted parent-child action link.
 */
fun DynamicAppDescriptor.planDynamicNavigation(
    selectedRecord: DynamicResourceRecordContext? = null,
): DynamicNavigationPlan {
    val actionsById = actions.associateBy(DynamicAction::id)
    val navigationLinks = acyclicNavigationLinks(actionsById)
    val rootDestinations = layouts
        .asSequence()
        .mapNotNull { layout ->
            val action = layout.sourceActionId?.let(actionsById::get) ?: return@mapNotNull null
            action.takeIf(DynamicAction::isRootReadAction)
                ?.resolveNavigationParameters(null)
                ?.takeIf(PathParameterResolution::complete)
                ?.let { resolution -> layout.toDestination(action, resolution.values) }
        }
        .sortedWith(
            compareByDescending<DynamicNavigationDestination> { destination ->
                destination.primaryRootScore(this)
            }.thenBy(DynamicNavigationDestination::label)
                .thenBy(DynamicNavigationDestination::layoutId),
        )
        .toList()

    val rootResourceIds = rootDestinations.mapTo(linkedSetOf()) { it.resourceId }
    val rootForms = forms.mapNotNull { form ->
        val action = actionsById[form.actionId] ?: return@mapNotNull null
        if (action.binding.method == HttpMethod.GET || action.binding.pathParameters.isNotEmpty()) return@mapNotNull null
        val rootResponseFieldIds = rootDestinations
            .asSequence()
            .filter { destination -> destination.resourceId.sameResourceAs(form.resourceId) }
            .mapNotNull { destination -> actionsById[destination.actionId] }
            .flatMap { readAction -> readAction.responseFieldIds.asSequence() }
            .toSet()
        if (action.canBindExecuteBodyFromSelectedRecord(form, rootResponseFieldIds)) {
            return@mapNotNull null
        }
        if (
            action.effect == ActionEffect.upload &&
            !action.isVerifiedCompiledUploadForm(form)
        ) {
            return@mapNotNull null
        }
        val belongsToVisibleRoot = rootResourceIds.any { root -> root.sameResourceAs(form.resourceId) }
        if (!belongsToVisibleRoot && !action.isTrustedSelfContainedRootForm(form)) {
            return@mapNotNull null
        }
        DynamicNavigationFormAction(
            formId = form.id,
            label = form.title,
            resourceId = form.resourceId,
            actionId = action.id,
        )
    }.distinctBy { action ->
        action.resourceId.resourceIdentity() to action.label.normalizedActionLabel()
    }
        .sortedWith(compareBy(DynamicNavigationFormAction::label, DynamicNavigationFormAction::formId))

    if (selectedRecord == null) return DynamicNavigationPlan(rootDestinations, rootForms)

    val actionLinks = navigationLinks.mapNotNull { edge ->
        edge.takeIf { it.link.resourceId.sameResourceAs(selectedRecord.resourceId) }
    }
    val candidateLayouts = layouts.filter { layout ->
        layout.isContextualNavigationLayout() &&
            !layout.resourceId.sameResourceAs(selectedRecord.resourceId)
    }
    val linksByAction = actionLinks.associate { edge -> edge.action.id to edge.link }
    val contextualChildren = candidateLayouts
        .asSequence()
        .filter(DynamicLayout::isContextualNavigationLayout)
        .mapNotNull { layout ->
            val action = layout.sourceActionId?.let(actionsById::get) ?: return@mapNotNull null
            if (!action.isContextualReadAction()) return@mapNotNull null
            val link = linksByAction[action.id]
            if (
                layout.kind == LayoutKind.detail && link == null &&
                !action.binding.path.nestsAnyParameterUnderResource(
                    action.binding.pathParameters.map(HttpParameter::name),
                    selectedRecord.resourceId,
                )
            ) return@mapNotNull null
            val resolution = action.resolveNavigationParameters(selectedRecord, link)
            if (!resolution.complete || (!resolution.usedContext && link == null)) return@mapNotNull null
            if (!resolution.usedSelectedRecord) return@mapNotNull null
            if (!layout.advancesFrom(selectedRecord, action, resolution, link)) return@mapNotNull null
            layout.toDestination(
                action = action,
                pathParameterValues = selectedRecord.parameterValues + resolution.values,
                linkedLabel = link?.label,
            )
        }
        .distinctBy(DynamicNavigationDestination::actionId)
        .sortedWith(compareBy(DynamicNavigationDestination::label, DynamicNavigationDestination::layoutId))
        .toList()

    val contextualForms = forms
        .takeIf { selectedRecord.actionBindingProvenanceValid }
        .orEmpty()
        .mapNotNull { form ->
            val action = actionsById[form.actionId] ?: return@mapNotNull null
            if (action.binding.method == HttpMethod.GET) return@mapNotNull null
            if (
                action.effect == ActionEffect.upload &&
                !action.isVerifiedCompiledUploadForm(form)
            ) {
                return@mapNotNull null
            }
            if (
                action.intent == ActionIntent.update &&
                !action.resourceId.sameResourceAs(selectedRecord.resourceId) &&
                !hasUnambiguousContextBoundSingletonUpdate(
                    action = action,
                    form = form,
                    context = selectedRecord,
                )
            ) {
                return@mapNotNull null
            }
            val selectedRecordResponseFieldIds = layouts
                .singleOrNull { layout -> layout.id == selectedRecord.currentLayoutId }
                ?.sourceActionId
                ?.let(actionsById::get)
                ?.takeIf { readAction ->
                    readAction.resourceId.sameResourceAs(selectedRecord.resourceId)
                }
                ?.responseFieldIds
                ?.toSet()
                .orEmpty()
            val values = action.resolveContextualFormValues(
                app = app,
                form = form,
                context = selectedRecord,
                parentLinks = actionLinks,
                selectedRecordResponseFieldIds = selectedRecordResponseFieldIds,
            ) ?: return@mapNotNull null
            if (!selectedRecord.permitsContextualForm(action, resources)) return@mapNotNull null
            DynamicNavigationFormAction(
                formId = form.id,
                label = form.title,
                resourceId = form.resourceId,
                actionId = action.id,
                pathParameterValues = values,
            )
        }.sortedWith(compareBy(DynamicNavigationFormAction::label, DynamicNavigationFormAction::formId))

    return DynamicNavigationPlan(
        rootDestinations = rootDestinations,
        rootFormActions = rootForms,
        contextualChildDestinations = contextualChildren,
        contextualFormActions = contextualForms,
    )
}

private fun DynamicAction.isVerifiedCompiledUploadForm(form: DynamicForm): Boolean {
    val body = binding.body ?: return false
    val properties = (body.schema as? JsonObject)
        ?.get("properties") as? JsonObject
        ?: return false
    val fileField = form.fields.singleOrNull { field -> field.kind == FieldKind.file }
        ?: return false
    val fileSchema = properties[fileField.fieldId] as? JsonObject ?: return false
    return intent == ActionIntent.execute &&
        effect == ActionEffect.upload &&
        risk == ActionRisk.mutating &&
        hasTrustedRootMutationEvidence() &&
        form.hasTrustedRootMutationEvidence() &&
        form.resourceId.sameResourceAs(resourceId) &&
        fileSchema["type"] == JsonPrimitive("string") &&
        fileSchema["format"] == JsonPrimitive("binary") &&
        body.contentType.substringBefore(';').trim().lowercase().startsWith("multipart/")
}

/**
 * A scoped singleton write may target the active child surface while its trusted context record is
 * the selected parent. The exact active detail layout must prove one read surface for the action's
 * resource; this is deliberately separate from ordinary same-record update authorization.
 */
private fun DynamicAppDescriptor.hasUnambiguousContextBoundSingletonUpdate(
    action: DynamicAction,
    form: DynamicForm,
    context: DynamicResourceRecordContext,
): Boolean {
    if (
        action.risk != ActionRisk.mutating ||
        !action.hasVerifiedDynamicContractEvidence() ||
        !form.hasVerifiedDynamicContractEvidence() ||
        !form.resourceId.sameResourceAs(action.resourceId)
    ) {
        return false
    }
    val currentLayoutId = context.currentLayoutId ?: return false
    val activeLayout = layouts.singleOrNull { layout ->
        layout.id == currentLayoutId &&
            layout.kind == LayoutKind.detail &&
            layout.resourceId.sameResourceAs(action.resourceId) &&
            layout.hasVerifiedDynamicContractEvidence()
    } ?: return false
    val readActionId = activeLayout.sourceActionId ?: return false
    val activeRead = actions.singleOrNull { candidate ->
        candidate.id == readActionId &&
            candidate.resourceId.sameResourceAs(action.resourceId) &&
            candidate.binding.method == HttpMethod.GET &&
            candidate.intent in setOf(ActionIntent.read, ActionIntent.list) &&
            candidate.risk == ActionRisk.readOnly &&
            candidate.hasVerifiedDynamicContractEvidence()
    } ?: return false
    if (activeRead.id == action.id) return false
    if (!activeRead.binding.isExactContextBoundSingletonRoute(action.binding)) return false
    return resources.count { resource ->
        resource.id.sameResourceAs(action.resourceId)
    } == 1
}

private fun DynamicHttpBinding.isExactContextBoundSingletonRoute(
    writeBinding: DynamicHttpBinding,
): Boolean =
    path == writeBinding.path &&
        pathParameters.map(HttpParameter::name).toSet() ==
        writeBinding.pathParameters.map(HttpParameter::name).toSet() &&
        queryParameters.filter(HttpParameter::required).map(HttpParameter::name).toSet() ==
        writeBinding.queryParameters.filter(HttpParameter::required).map(HttpParameter::name).toSet()

private fun DynamicAction.hasVerifiedDynamicContractEvidence(): Boolean =
    confidence == Confidence.verified ||
        (
            confidence == Confidence.high &&
                provenance.any { evidence ->
                    evidence.kind == ProvenanceKind.verifiedAppPackage
                }
            )

private fun DynamicForm.hasVerifiedDynamicContractEvidence(): Boolean =
    confidence == Confidence.verified ||
        (
            confidence == Confidence.high &&
                provenance.any { evidence ->
                    evidence.kind == ProvenanceKind.verifiedAppPackage
                }
            )

private fun DynamicLayout.hasVerifiedDynamicContractEvidence(): Boolean =
    confidence == Confidence.verified ||
        (
            confidence == Confidence.high &&
                provenance.any { evidence ->
                    evidence.kind == ProvenanceKind.verifiedAppPackage
                }
            )

/**
 * Applies exact record-level capability fields before exposing a mutation form.
 *
 * A relationship-proven child create is governed by the child action contract, not by whether the
 * selected parent itself is editable. For same-record writes, however, a declared capability whose
 * value is absent or malformed is unknown and therefore cannot authorize the form. Once any
 * edit/delete capability is declared, each mutation category needs its own affirmative evidence.
 */
private fun DynamicResourceRecordContext.permitsContextualForm(
    action: DynamicAction,
    resources: List<DynamicResource>,
): Boolean {
    if (!action.resourceId.sameResourceAs(resourceId)) return true
    val resource = resources.firstOrNull { candidate ->
        candidate.id.sameResourceAs(action.resourceId)
    } ?: return true
    val capabilityFields = resource.fields.mapNotNull { field ->
        val semanticId = field.id.lowercase().filter(Char::isLetterOrDigit)
        semanticId.takeIf(RECORD_MUTATION_CAPABILITY_IDS::contains)?.let { it to field.id }
    }.toMap()
    if (capabilityFields.isEmpty()) return true

    fun declaredCapability(id: String): Boolean? {
        val fieldId = capabilityFields[id] ?: return null
        return fieldValues[fieldId]?.dynamicCapabilityBooleanOrNull()
    }

    if ("readonly" in capabilityFields && declaredCapability("readonly") != false) return false
    if ("writable" in capabilityFields && declaredCapability("writable") != true) return false
    if ("canwrite" in capabilityFields && declaredCapability("canwrite") != true) return false

    val deletion = when (action.effect) {
        ActionEffect.delete,
        ActionEffect.permanentDelete,
        -> true
        ActionEffect.clear,
        ActionEffect.leave,
        -> false
        else -> action.intent == ActionIntent.delete
    }
    val scopedCapabilities = setOf("canedit", "canupdate", "candelete")
        .filter(capabilityFields::containsKey)
    if (scopedCapabilities.isEmpty()) return true
    return if (deletion) {
        "candelete" in scopedCapabilities && declaredCapability("candelete") == true
    } else {
        val editCapabilities = setOf("canedit", "canupdate").filter(scopedCapabilities::contains)
        action.intent !in setOf(ActionIntent.update, ActionIntent.execute) ||
            (
                editCapabilities.isNotEmpty() &&
                    editCapabilities.all { id -> declaredCapability(id) == true }
                )
    }
}

private fun String.dynamicCapabilityBooleanOrNull(): Boolean? = when (trim().lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}

/**
 * Resolves a contextual mutation from either route parameters or one exact required parent field.
 *
 * Some verified contracts scope child creation in the request body instead of the URL. Such an
 * action is contextual only when an accepted parent-child link proves the resource relationship,
 * the action is a create mutation, and one required form field names the selected parent exactly.
 * This keeps the rule reusable while withholding unrelated or ambiguous writes.
 */
private fun DynamicAction.resolveContextualFormValues(
    app: AppIdentity,
    form: DynamicForm,
    context: DynamicResourceRecordContext,
    parentLinks: List<NavigationLinkEdge>,
    selectedRecordResponseFieldIds: Set<String>,
): Map<String, String>? {
    val routeResolution = resolveNavigationParameters(context, allowEphemeralIdentity = false)
    // A same-named field is contextual data, not proof that this mutation targets the selected
    // record. Route writes require the resolver to identify the selected record itself.
    if (routeResolution.complete && routeResolution.usedSelectedRecord) {
        return routeResolution.values
    }
    if (!routeResolution.complete || routeResolution.usedContext) return null
    val requiredBodyFieldIds = requiredBodyFieldIds()
    if (
        canBindExecuteBodyFromSelectedRecord(form, selectedRecordResponseFieldIds) &&
        hasTypedExecuteRecordRelationship(app, context, parentLinks)
    ) {
        val recordBodyValues = requiredBodyFieldIds.associateWith { fieldId ->
            context.exactValue(fieldId) ?: return null
        }
        return routeResolution.values + recordBodyValues
    }
    if (intent != ActionIntent.create || risk != ActionRisk.mutating) return null
    if (!context.actionSafeIdentity || !context.actionBindingProvenanceValid) return null

    val parentLink = parentLinks.singleOrNull { edge ->
        edge.action.resourceId.sameResourceAs(resourceId)
    } ?: return null

    if (requiredBodyFieldIds.isEmpty()) return null
    val bodyFieldNames = form.fields
        .asSequence()
        .filter(FormField::required)
        .map(FormField::fieldId)
        .filter(requiredBodyFieldIds::contains)
        .filter { fieldId -> fieldId.isContextFilterFor(context.resourceId) }
        .distinct()
        .toList()
    val parentFieldId = bodyFieldNames.singleOrNull() ?: return null
    val parentValue = context.exactValue(parentFieldId)
        ?: context.recordIdFor(
            parameterName = parentFieldId,
            actionResourceId = resourceId,
            actionPath = binding.path,
            link = parentLink.link,
            allowEphemeralIdentity = false,
        )
        ?: return null
    return routeResolution.values + (parentFieldId to parentValue)
}

private fun DynamicAction.hasTypedExecuteRecordRelationship(
    app: AppIdentity,
    context: DynamicResourceRecordContext,
    parentLinks: List<NavigationLinkEdge>,
): Boolean =
    parentLinks.singleOrNull { edge ->
        edge.action.id == id && edge.link.resourceId.sameResourceAs(context.resourceId)
    } != null || isPinnedChoresInvitationAccept(app, context)

/**
 * Chores 0.1.0 accepts an invitation through a body-scoped command whose controller and payload
 * are imported only from the exact signed package. Its read route compiles as `invites`, while the
 * command compiles as `invitations`, and upstream exposes no OpenAPI link between them. This
 * version-pinned adapter is the typed relationship between an invitation record and Accept.
 */
private fun DynamicAction.isPinnedChoresInvitationAccept(
    app: AppIdentity,
    context: DynamicResourceRecordContext,
): Boolean =
    app.id == "chores" &&
        app.version == "0.1.0" &&
        context.resourceId.sameResourceAs("invites") &&
        resourceId.sameResourceAs("invitations") &&
        binding.method == HttpMethod.POST &&
        binding.path == "/apps/chores/api/v1.0/account/invites/accept" &&
        requiredBodyFieldIds() == setOf("teamId") &&
        provenance.any { evidence -> evidence.kind == ProvenanceKind.verifiedAppPackage }

/**
 * A verified execute action may be selected from a record when every required body field is
 * declared by that record's active read contract. The values remain hidden form bindings and are
 * never inferred from display-only or response-observed data.
 */
private fun DynamicAction.canBindExecuteBodyFromSelectedRecord(
    form: DynamicForm,
    responseFieldIds: Set<String>,
): Boolean {
    val requiredBodyFieldIds = requiredBodyFieldIds()
    if (requiredBodyFieldIds.isEmpty()) return false
    val requiredFormFieldIds = form.fields
        .filter(FormField::required)
        .mapTo(linkedSetOf(), FormField::fieldId)
    return intent == ActionIntent.execute &&
        risk == ActionRisk.mutating &&
        binding.pathParameters.isEmpty() &&
        binding.queryParameters.none(HttpParameter::required) &&
        resourceId.sameResourceAs(form.resourceId) &&
        hasVerifiedDynamicContractEvidence() &&
        form.hasVerifiedDynamicContractEvidence() &&
        requiredBodyFieldIds.all(responseFieldIds::contains) &&
        requiredBodyFieldIds.all(requiredFormFieldIds::contains)
}

private fun DynamicAction.requiredBodyFieldIds(): Set<String> =
    ((binding.body?.schema as? JsonObject)?.get("required") as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        .orEmpty()

private fun String.normalizedActionLabel(): String = lowercase()
    .replace(Regex("^\\[api\\s+v?[0-9.]+]\\s*"), "")
    .replace(" a ", " ")
    .replace(" an ", " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun DynamicLayout.isCollectionNavigationLayout(): Boolean = kind == LayoutKind.list || kind == LayoutKind.grid

private fun DynamicLayout.isContextualNavigationLayout(): Boolean =
    isCollectionNavigationLayout() || kind == LayoutKind.detail

private fun DynamicAction.isCollectionReadAction(): Boolean =
    binding.method == HttpMethod.GET && intent == ActionIntent.list && risk == ActionRisk.readOnly

private fun DynamicAction.isContextualReadAction(): Boolean =
    binding.method == HttpMethod.GET && intent in setOf(ActionIntent.list, ActionIntent.read) && risk == ActionRisk.readOnly

private fun DynamicAction.isRootReadAction(): Boolean =
    binding.method == HttpMethod.GET && intent in setOf(ActionIntent.list, ActionIntent.read) &&
        risk == ActionRisk.readOnly &&
        !binding.hasUnboundRequiredBodyFields() &&
        !isInteractiveLookupHelper() &&
        hasPositiveRootReadEvidence()

/**
 * Search-as-you-type endpoints are data sources for relation pickers, not standalone app roots.
 * Require both explicit helper semantics and a declared query term so ordinary filterable
 * collections and search-centric apps remain navigable.
 */
private fun DynamicAction.isInteractiveLookupHelper(): Boolean {
    val concepts = (
        id + " " + label + " " + resourceId + " " + binding.path
    ).semanticConceptTokens()
    if (concepts.none(INTERACTIVE_LOOKUP_CONCEPTS::contains)) return false
    return binding.queryParameters.any { parameter ->
        parameter.name.semanticConceptTokens().any(INTERACTIVE_LOOKUP_QUERY_CONCEPTS::contains)
    }
}

/**
 * A response contract may expose helper GET routes whose input is modeled as a JSON body, such as
 * an avatar/photo proxy requiring a `key`. They are valid contextual actions but cannot be opened
 * as a parameterless app root.
 */
private fun DynamicHttpBinding.hasUnboundRequiredBodyFields(): Boolean {
    val objectSchema = body?.schema as? kotlinx.serialization.json.JsonObject ?: return false
    return (objectSchema["required"] as? kotlinx.serialization.json.JsonArray)
        ?.isNotEmpty() == true
}

private val INTERACTIVE_LOOKUP_CONCEPTS = setOf(
    "autocomplete",
    "autocompletion",
    "lookup",
    "suggest",
    "suggestion",
    "typeahead",
)

private val INTERACTIVE_LOOKUP_QUERY_CONCEPTS = setOf(
    "prefix",
    "query",
    "search",
    "term",
    "text",
)

private fun DynamicLayout.toDestination(
    action: DynamicAction,
    pathParameterValues: Map<String, String>,
    linkedLabel: String? = null,
): DynamicNavigationDestination = DynamicNavigationDestination(
    layoutId = id,
    label = linkedLabel?.takeIf(String::isNotBlank) ?: title,
    resourceId = resourceId,
    actionId = action.id,
    pathParameterValues = pathParameterValues,
)

private data class PathParameterResolution(
    val values: Map<String, String>,
    val complete: Boolean,
    val usedContext: Boolean,
    val usedSelectedRecord: Boolean = false,
    val missingRequiredParameterNames: List<String> = emptyList(),
)

private fun DynamicAction.resolveNavigationParameters(
    context: DynamicResourceRecordContext?,
    link: DynamicLink? = null,
    allowEphemeralIdentity: Boolean = true,
): PathParameterResolution {
    val pathTemplate = binding.path.scanBracedTemplate()
    if (pathTemplate.malformed) {
        return PathParameterResolution(emptyMap(), complete = false, usedContext = false)
    }
    val navigationParameters = (
        binding.pathParameters + binding.queryParameters.filter(HttpParameter::required)
    ).distinctBy(HttpParameter::name).toMutableList()
    context?.let { record ->
        binding.queryParameters
            .asSequence()
            .filterNot(HttpParameter::required)
            .filter { parameter -> parameter.name.isContextFilterFor(record.resourceId) }
            .sortedBy { parameter -> parameter.name.length }
            .firstOrNull()
            ?.takeIf { inferred -> navigationParameters.none { it.name == inferred.name } }
            ?.let(navigationParameters::add)
    }
    if (navigationParameters.isEmpty()) {
        return PathParameterResolution(emptyMap(), complete = true, usedContext = false)
    }
    val resolved = linkedMapOf<String, String>()
    var usedContext = false
    var usedSelectedRecord = false
    navigationParameters.forEach { parameter ->
        val exactParameterValue = context?.parameterValues
            ?.get(parameter.name)
            ?.takeIf(String::isNotBlank)
        val exactFieldValue = context?.fieldValues
            ?.get(parameter.name)
            ?.takeIf { !it.isNullOrBlank() }
        val ephemeralIdentityValue = context?.recordId.takeIf {
                context != null && allowEphemeralIdentity && parameter.name.isContextFilterFor(context.resourceId)
            }
        val declaredIdentityValue = context?.recordIdFor(
                parameterName = parameter.name,
                actionResourceId = resourceId,
                actionPath = binding.path,
                link = link,
                allowEphemeralIdentity = allowEphemeralIdentity,
            )
        val value = exactParameterValue
            ?: exactFieldValue
            ?: ephemeralIdentityValue
            ?: declaredIdentityValue
        if (!value.isNullOrBlank()) {
            resolved[parameter.name] = value
            usedContext = true
            val exactParameterIsSelectedIdentity =
                exactParameterValue != null &&
                    (
                        parameter.name.isContextFilterFor(context.resourceId) ||
                            (
                                parameter.name.isIdentityField() &&
                                    binding.path.nestsParameterUnderResource(
                                        parameter.name,
                                        context.resourceId,
                                    )
                            )
                    )
            val exactFieldIsSelectedIdentity =
                exactParameterValue == null &&
                exactFieldValue != null &&
                    (
                        parameter.name.isContextFilterFor(context.resourceId) ||
                            (
                                parameter.name.isIdentityField() &&
                                    (
                                        link?.resourceId?.sameResourceAs(context.resourceId) == true ||
                                            binding.path.nestsParameterUnderResource(
                                                parameter.name,
                                                context.resourceId,
                                            )
                                    )
                            )
                    )
            val ephemeralIdentityWasSelected =
                exactParameterValue == null &&
                    exactFieldValue == null &&
                    ephemeralIdentityValue != null
            val declaredIdentityWasSelected =
                exactParameterValue == null &&
                    exactFieldValue == null &&
                    ephemeralIdentityValue == null &&
                    declaredIdentityValue != null
            if (
                exactParameterIsSelectedIdentity ||
                exactFieldIsSelectedIdentity ||
                ephemeralIdentityWasSelected ||
                declaredIdentityWasSelected
            ) {
                usedSelectedRecord = true
            }
        }
    }
    val unresolvedPlaceholders = pathTemplate.tokens.any { it.name !in resolved }
    val missingRequiredNames = navigationParameters
        .filter { it.required && it.name !in resolved }
        .map(HttpParameter::name)
    return PathParameterResolution(
        values = resolved,
        complete = !unresolvedPlaceholders && missingRequiredNames.isEmpty(),
        usedContext = usedContext,
        usedSelectedRecord = usedSelectedRecord,
        missingRequiredParameterNames = missingRequiredNames,
    )
}

/**
 * A collection query such as `tracks?album=...` is a safe read-only child facet for an `albums`
 * record. Exact semantic equality keeps unrelated options such as page, limit, and sort inert.
 */
private fun String.isContextFilterFor(contextResourceId: String): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    val stem = normalized.removeSuffix("id")
    return stem.isNotBlank() && stem.resourceIdentity() == contextResourceId.resourceIdentity()
}

private fun DynamicLayout.advancesFrom(
    context: DynamicResourceRecordContext,
    action: DynamicAction,
    resolution: PathParameterResolution,
    link: DynamicLink?,
): Boolean {
    // A declared edge back onto the same semantic resource is not a child relationship. This is
    // the source of Account -> Account and Project -> Project loops in generated contracts.
    if (link != null && resourceId.sameResourceAs(context.resourceId)) return false

    val effectiveParameters = (context.parameterValues + resolution.values).toSortedMap()
    val destination = dynamicNavigationState(resourceId, id, effectiveParameters)
    if (destination in context.visitedStates) return false

    val advancesResource = !resourceId.sameResourceAs(context.resourceId)
    val advancesView = context.currentLayoutId?.let { it != id } == true
    val addsContext = resolution.values.any { (name, value) -> context.parameterValues[name] != value }
    if (!advancesResource && !advancesView && !addsContext) return false

    val currentLayout = context.currentLayoutId
    if (
        currentLayout != null && currentLayout == id &&
        resourceId.sameResourceAs(context.resourceId) &&
        effectiveParameters == context.parameterValues.toSortedMap()
    ) {
        return false
    }
    return action.resourceId.sameResourceAs(resourceId)
}

private data class NavigationLinkEdge(
    val link: DynamicLink,
    val action: DynamicAction,
)

/**
 * Converts advertised action links into a directed acyclic resource graph. Self edges are always
 * invalid. When a contract advertises a longer cycle, deterministic link ordering retains the
 * first proven direction and drops the edge that would close the cycle.
 */
private fun DynamicAppDescriptor.acyclicNavigationLinks(
    actionsById: Map<String, DynamicAction>,
): List<NavigationLinkEdge> {
    val candidates = links.mapNotNull { link ->
        val actionId = (link.target as? DynamicLinkTarget.Action)?.actionId ?: return@mapNotNull null
        val action = actionsById[actionId] ?: return@mapNotNull null
        if (link.resourceId.sameResourceAs(action.resourceId)) return@mapNotNull null
        NavigationLinkEdge(link, action)
    }.sortedWith(compareBy({ it.link.id }, { it.action.id }))

    val accepted = mutableListOf<NavigationLinkEdge>()
    candidates.forEach { candidate ->
        val source = candidate.link.resourceId.resourceIdentity()
        val target = candidate.action.resourceId.resourceIdentity()
        if (!accepted.hasResourcePath(target, source)) accepted += candidate
    }
    return accepted
}

private fun List<NavigationLinkEdge>.hasResourcePath(start: String, destination: String): Boolean {
    val pending = ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    pending.add(start)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current == destination) return true
        asSequence()
            .filter { it.link.resourceId.resourceIdentity() == current }
            .map { it.action.resourceId.resourceIdentity() }
            .forEach(pending::add)
    }
    return false
}

private fun DynamicResourceRecordContext.exactValue(parameterName: String): String? =
    parameterValues[parameterName]?.takeIf(String::isNotBlank)
        ?: fieldValues[parameterName]?.takeIf { !it.isNullOrBlank() }

private fun DynamicResourceRecordContext.recordIdFor(
    parameterName: String,
    actionResourceId: String,
    actionPath: String,
    link: DynamicLink?,
    allowEphemeralIdentity: Boolean,
): String? {
    if (recordId.isBlank()) return null
    if (!actionSafeIdentity && !allowEphemeralIdentity) return null
    if (parameterName.equals("id", ignoreCase = true) && actionResourceId.sameResourceAs(resourceId)) {
        return recordId
    }
    if (parameterName.resourceIdStem()?.sameResourceAs(resourceId) == true) return recordId
    if (parameterName.equals("id", ignoreCase = true) && actionPath.nestsParameterUnderResource(parameterName, resourceId)) {
        return recordId
    }
    if (link?.resourceId?.sameResourceAs(resourceId) == true && parameterName.equals("id", ignoreCase = true)) {
        val sourceField = link.sourceFieldId
        if (sourceField.isIdentityField()) {
            return fieldValues[sourceField]?.takeIf { !it.isNullOrBlank() } ?: recordId
        }
    }
    return null
}

/** `/messages/{id}/body` proves that the selected message identity scopes the body read. */
private fun String.nestsParameterUnderResource(parameterName: String, contextResourceId: String): Boolean {
    val segments = substringBefore('?').split('/').filter(String::isNotBlank)
    val parameterSegment = "{$parameterName}"
    return segments.indices.any { index ->
        segments[index] == parameterSegment && index > 0 &&
            segments[index - 1].sameResourceAs(contextResourceId)
    }
}

private fun String.nestsAnyParameterUnderResource(
    parameterNames: List<String>,
    contextResourceId: String,
): Boolean = parameterNames.any { parameterName ->
    nestsParameterUnderResource(parameterName, contextResourceId)
}

private fun String.resourceIdStem(): String? = takeIf {
    length > 2 && endsWith("Id", ignoreCase = true)
}?.dropLast(2)

private fun String.isIdentityField(): Boolean = lowercase() in setOf("databaseid", "id", "uuid", "token")

internal fun String.sameDynamicResourceAs(other: String): Boolean = resourceIdentity() == other.resourceIdentity()

private fun String.sameResourceAs(other: String): Boolean = sameDynamicResourceAs(other)

/**
 * Ranks the primary user workspace ahead of API taxonomies and configuration endpoints.
 *
 * This intentionally uses declared interaction shape rather than an app-id allow-list. A root
 * collection with safe create/update/delete actions is normally the object users came to manage;
 * categories, tags and other indexes remain reachable as tabs but do not win merely because their
 * label sorts first. An exact app/resource noun match still has the strongest evidence, allowing a
 * dedicated taxonomy app to open its own taxonomy.
 */
private fun DynamicNavigationDestination.primaryRootScore(descriptor: DynamicAppDescriptor): Int {
    val app = descriptor.app
    val appIdentities = listOf(app.id, app.name).map(String::resourceIdentity).filter(String::isNotBlank).toSet()
    val destinationIdentities = listOf(resourceId, label)
        .map(String::resourceIdentity)
        .filter(String::isNotBlank)
        .toSet()
    var score = if (appIdentities.any(destinationIdentities::contains)) 1_000 else 0

    val appWords = listOf(app.id, app.name).flatMap(String::semanticWords).toSet()
    val destinationWords = listOf(resourceId, label).flatMap(String::semanticWords).toSet()
    score += appWords.intersect(destinationWords).size * 100

    val resourceActions = descriptor.actions.filter { action ->
        action.resourceId.sameResourceAs(resourceId)
    }
    if (resourceActions.any { action -> action.intent == ActionIntent.create && action.risk == ActionRisk.mutating }) {
        score += 500
    }
    if (resourceActions.any { action -> action.intent == ActionIntent.update && action.risk == ActionRisk.mutating }) {
        score += 240
    }
    if (resourceActions.any { action -> action.intent == ActionIntent.delete && action.risk != ActionRisk.readOnly }) {
        score += 160
    }
    if (resourceActions.any { action ->
            action.intent == ActionIntent.read &&
                action.risk == ActionRisk.readOnly &&
                action.binding.pathParameters.isNotEmpty()
        }
    ) {
        score += 100
    }
    val collectionActionIds = descriptor.layouts
        .filter(DynamicLayout::isCollectionNavigationLayout)
        .mapNotNullTo(hashSetOf(), DynamicLayout::sourceActionId)
    val leadsToChildCollection = descriptor.links.any { link ->
        if (!link.resourceId.sameResourceAs(resourceId)) return@any false
        val actionId = (link.target as? DynamicLinkTarget.Action)?.actionId ?: return@any false
        val childAction = descriptor.actions.firstOrNull { action -> action.id == actionId } ?: return@any false
        actionId in collectionActionIds &&
            childAction.resourceId.let { childResource -> !childResource.sameResourceAs(resourceId) } &&
            childAction.intent in setOf(ActionIntent.list, ActionIntent.read) &&
            childAction.risk == ActionRisk.readOnly
    }
    if (leadsToChildCollection) score += 600

    val destinationConcepts = listOf(resourceId, label)
        .flatMap(String::semanticWords)
        .mapTo(hashSetOf(), String::resourceIdentity)
    if (destinationConcepts.any(PRIMARY_CONTENT_ROOT_CONCEPTS::contains)) score += 1_000
    if (destinationConcepts.any(SECONDARY_ROOT_CONCEPTS::contains)) score -= 450
    if (destinationConcepts.any(TECHNICAL_ROOT_CONCEPTS::contains)) score -= 900
    return score
}

private val RECORD_MUTATION_CAPABILITY_IDS = setOf(
    "readonly",
    "writable",
    "canwrite",
    "canedit",
    "canupdate",
    "candelete",
)

private val PRIMARY_CONTENT_ROOT_CONCEPTS = setOf(
    "board",
    "card",
    "contact",
    "conversation",
    "document",
    "event",
    "file",
    "message",
    "note",
    "photo",
    "recipe",
    "record",
    "table",
    "task",
    "track",
)

private val SECONDARY_ROOT_CONCEPTS = setOf(
    "category",
    "certificate",
    "facet",
    "filter",
    "internaladdress",
    "label",
    "outbox",
    "quickaction",
    "tag",
    "taxonomy",
    "template",
    "textblock",
    "type",
)

private val TECHNICAL_ROOT_CONCEPTS = setOf(
    "capability",
    "config",
    "configuration",
    "diagnostic",
    "metadata",
    "preference",
    "provisioning",
    "setting",
)

private fun String.semanticWords(): List<String> = lowercase()
    .split(Regex("[^a-z0-9]+"))
    .filter(String::isNotBlank)

private fun String.resourceIdentity(): String {
    val tail = lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
        .lastOrNull()
        .orEmpty()
    return when {
        tail.endsWith("ies") && tail.length > 3 -> tail.dropLast(3) + "y"
        tail.endsWith("ches") || tail.endsWith("shes") -> tail.dropLast(2)
        tail.endsWith("sses") || tail.endsWith("xes") || tail.endsWith("zes") -> tail.dropLast(2)
        tail.endsWith("s") && tail.length > 1 -> tail.dropLast(1)
        else -> tail
    }
}
