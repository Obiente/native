package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.template.scanBracedTemplate

/** A selected resource record and any exact parameter bindings already known by the host. */
data class DynamicResourceRecordContext(
    val resourceId: String,
    val recordId: String,
    val fieldValues: Map<String, String?> = emptyMap(),
    val parameterValues: Map<String, String> = emptyMap(),
    val currentLayoutId: String? = null,
    val visitedStates: Set<DynamicNavigationState> = emptySet(),
    val actionSafeIdentity: Boolean = true,
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
 * surface. This is semantic rather than app-specific: account-like containers prefer their single
 * mailbox collection, and mailbox-like containers prefer their single message collection.
 *
 * Every candidate has already passed the planner's read-only, complete-binding, and cycle checks.
 * Ambiguous equal-scoring children remain explicit tabs instead of being guessed.
 */
fun DynamicAppDescriptor.preferredSemanticContextualChild(
    context: DynamicResourceRecordContext,
): DynamicNavigationDestination? {
    val collectionLayoutIds = layouts
        .filter(DynamicLayout::isCollectionNavigationLayout)
        .mapTo(hashSetOf(), DynamicLayout::id)
    val scored = planDynamicNavigation(context).contextualChildDestinations.mapNotNull { destination ->
        if (destination.layoutId !in collectionLayoutIds) return@mapNotNull null
        val score = preferredSemanticChildScore(context.resourceId, destination)
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
    if (TECHNICAL_CHILD_CONCEPTS.any(child::hasNavigationConcept)) return true
    val parentIsMessage = parent.hasNavigationConcept("message") || parent.hasNavigationConcept("email")
    return parentIsMessage && MESSAGE_HELPER_CHILD_CONCEPTS.any(child::hasNavigationConcept)
}

private fun preferredSemanticChildScore(
    parentResourceId: String,
    destination: DynamicNavigationDestination,
): Int {
    val parent = parentResourceId.navigationSemanticIdentity()
    val parentIsAccount = parent.hasNavigationConcept("account") || parent.hasNavigationConcept("container")
    val parentIsMailbox = parent.hasNavigationConcept("mailbox") || parent.hasNavigationConcept("folder")
    val parentIsTaxonomy = listOf("category", "tag", "keyword", "label").any(parent::hasNavigationConcept)
    val mailboxEvidence = destination.semanticConceptEvidence("mailbox")
    val folderEvidence = destination.semanticConceptEvidence("folder")
    val messageEvidence = maxOf(
        destination.semanticConceptEvidence("message"),
        destination.semanticConceptEvidence("email"),
    )
    return when {
        parentIsAccount && mailboxEvidence > 0 -> 400 + mailboxEvidence
        parentIsAccount && folderEvidence > 0 -> 300 + folderEvidence
        parentIsMailbox && messageEvidence > 0 -> 400 + messageEvidence
        parentIsTaxonomy -> 350
        else -> 0
    }
}

private fun DynamicNavigationDestination.semanticConceptEvidence(concept: String): Int = when {
    resourceId.navigationSemanticIdentity().hasNavigationConcept(concept) -> 3
    label.navigationSemanticIdentity().hasNavigationConcept(concept) -> 2
    actionId.navigationSemanticIdentity().hasNavigationConcept(concept) -> 1
    else -> 0
}

private fun String.navigationSemanticIdentity(): String =
    lowercase().filter(Char::isLetterOrDigit)

private fun String.hasNavigationConcept(singular: String): Boolean =
    contains(singular) || contains("${singular}s")

private val TECHNICAL_CHILD_CONCEPTS = setOf(
    "debug",
    "diagnostic",
    "internal",
    "metadata",
    "protocol",
    "schema",
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
        .filter(DynamicLayout::isRootNavigationLayout)
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
        if (rootResourceIds.none { root -> root.sameResourceAs(form.resourceId) }) return@mapNotNull null
        DynamicNavigationFormAction(
            formId = form.id,
            label = form.title,
            resourceId = form.resourceId,
            actionId = action.id,
        )
    }.distinctBy { action -> action.label.normalizedActionLabel() }
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

    val contextualForms = forms.mapNotNull { form ->
        val action = actionsById[form.actionId] ?: return@mapNotNull null
        if (action.binding.method == HttpMethod.GET || action.binding.pathParameters.isEmpty()) return@mapNotNull null
            val resolution = action.resolveNavigationParameters(selectedRecord, allowEphemeralIdentity = false)
        if (!resolution.complete || !resolution.usedContext) return@mapNotNull null
        DynamicNavigationFormAction(
            formId = form.id,
            label = form.title,
            resourceId = form.resourceId,
            actionId = action.id,
            pathParameterValues = resolution.values,
        )
    }.sortedWith(compareBy(DynamicNavigationFormAction::label, DynamicNavigationFormAction::formId))

    return DynamicNavigationPlan(
        rootDestinations = rootDestinations,
        rootFormActions = rootForms,
        contextualChildDestinations = contextualChildren,
        contextualFormActions = contextualForms,
    )
}

private fun String.normalizedActionLabel(): String = lowercase()
    .replace(Regex("^\\[api\\s+v?[0-9.]+]\\s*"), "")
    .replace(" a ", " ")
    .replace(" an ", " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun DynamicLayout.isCollectionNavigationLayout(): Boolean = kind == LayoutKind.list || kind == LayoutKind.grid

private fun DynamicLayout.isContextualNavigationLayout(): Boolean =
    isCollectionNavigationLayout() || kind == LayoutKind.detail

private fun DynamicLayout.isRootNavigationLayout(): Boolean =
    isCollectionNavigationLayout() || kind == LayoutKind.detail && resourceId.rootSingletonIdentity() in ROOT_SINGLETON_IDENTITIES

private fun String.rootSingletonIdentity(): String = lowercase().filter(Char::isLetterOrDigit)

private val ROOT_SINGLETON_IDENTITIES = setOf(
    "capabilities",
    "config",
    "configuration",
    "household",
    "preferences",
    "profile",
    "settings",
    "status",
    "team",
)

private fun DynamicAction.isCollectionReadAction(): Boolean =
    binding.method == HttpMethod.GET && intent == ActionIntent.list && risk == ActionRisk.readOnly

private fun DynamicAction.isContextualReadAction(): Boolean =
    binding.method == HttpMethod.GET && intent in setOf(ActionIntent.list, ActionIntent.read) && risk == ActionRisk.readOnly

private fun DynamicAction.isRootReadAction(): Boolean =
    binding.method == HttpMethod.GET && intent in setOf(ActionIntent.list, ActionIntent.read) &&
        risk == ActionRisk.readOnly && !binding.hasUnboundRequiredBodyFields()

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
    navigationParameters.forEach { parameter ->
        val value = context?.exactValue(parameter.name)
            ?: context?.recordId.takeIf {
                context != null && allowEphemeralIdentity && parameter.name.isContextFilterFor(context.resourceId)
            }
            ?: context?.recordIdFor(
                parameterName = parameter.name,
                actionResourceId = resourceId,
                actionPath = binding.path,
                link = link,
                allowEphemeralIdentity = allowEphemeralIdentity,
            )
        if (!value.isNullOrBlank()) {
            resolved[parameter.name] = value
            usedContext = true
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
        missingRequiredParameterNames = missingRequiredNames,
    )
}

/**
 * A collection query such as `tracks?album=…` is a safe read-only child facet for an `albums`
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
        tail.endsWith("ses") || tail.endsWith("xes") || tail.endsWith("zes") -> tail.dropLast(2)
        tail.endsWith("s") && tail.length > 1 -> tail.dropLast(1)
        else -> tail
    }
}
