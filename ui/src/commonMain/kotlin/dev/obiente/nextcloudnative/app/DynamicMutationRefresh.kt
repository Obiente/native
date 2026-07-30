package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord

/**
 * How the host should treat its selected record after an authoritative mutation succeeds.
 *
 * A related parent must remain available while a child route reloads because its verified identity
 * is still needed to bind that read. The parent is nevertheless stale and must be replaced by an
 * authoritative read when its own surface becomes visible again.
 */
internal enum class DynamicSelectedRecordReconciliation {
    Keep,
    KeepRouteAndReloadWhenVisible,
    ClearDeletedSelection,
}

/**
 * Contract-derived cache and collection reconciliation required after one mutation.
 *
 * Screen snapshots embed the host's accumulated related-resource map. Invalidating only views that
 * directly render [affectedResourceIds] is therefore insufficient: an otherwise unrelated screen
 * can retain stale parent or child records in its embedded snapshot. The host must invalidate every
 * cached screen for the active account and app, discard the affected in-memory related records, and
 * reload the visible view.
 */
internal data class DynamicMutationRefreshPlan(
    val actionId: String,
    val actionResourceId: String,
    val affectedResourceIds: Set<String>,
    val affectedViewIds: Set<String>,
    val selectedRecordReconciliation: DynamicSelectedRecordReconciliation,
    val invalidateAllAppScreenSnapshots: Boolean = true,
    val reloadVisibleView: Boolean = true,
) {
    fun discardAffectedRelatedRecords(
        recordsByResourceId: Map<String, List<NativeRecord>>,
    ): Map<String, List<NativeRecord>> =
        recordsByResourceId.filterKeys { resourceId -> resourceId !in affectedResourceIds }
}

/**
 * Plans post-mutation refresh from exact schema IDs and declared resource relationships.
 *
 * No semantic-name matching is used. Missing, read-only, or internally inconsistent action
 * contracts do not produce a refresh plan because the host cannot safely attribute the mutation.
 */
internal fun NativeAppSchema.planDynamicMutationRefresh(
    action: ActionSpec,
    selectedRecordResourceId: String?,
): DynamicMutationRefreshPlan? {
    val canonicalAction = actions.singleOrNull { candidate -> candidate.id == action.id }
        ?.takeIf { candidate -> candidate == action }
        ?: return null
    if (
        canonicalAction.binding.method == HttpMethod.GET ||
        canonicalAction.risk == ActionRisk.readOnly ||
        canonicalAction.intent in setOf(ActionIntent.list, ActionIntent.read)
    ) {
        return null
    }

    val knownResourceIds = resources.mapTo(linkedSetOf()) { resource -> resource.id }
    if (canonicalAction.resourceId !in knownResourceIds) return null

    val connectedResourceGroups = buildList {
        relationships.forEach { relationship ->
            val resources = setOf(relationship.parentResourceId, relationship.childResourceId)
            if (resources.all(knownResourceIds::contains)) add(resources)
        }
        views.mapNotNull { view -> view.compositeDataGrid }.forEach { composite ->
            val resources = setOf(
                composite.parentResourceId,
                composite.columnResourceId,
                composite.rowResourceId,
            )
            if (resources.all(knownResourceIds::contains)) add(resources)
        }
    }
    val affectedResourceIds = linkedSetOf(canonicalAction.resourceId)
    var expanded: Boolean
    do {
        expanded = false
        connectedResourceGroups.forEach { group ->
            if (group.any(affectedResourceIds::contains) && affectedResourceIds.addAll(group)) {
                expanded = true
            }
        }
    } while (expanded)

    val affectedViewIds = views.asSequence()
        .filter { view ->
            view.resourceId in affectedResourceIds ||
                view.compositeDataGrid?.let { composite ->
                    sequenceOf(
                        composite.parentResourceId,
                        composite.columnResourceId,
                        composite.rowResourceId,
                    ).any(affectedResourceIds::contains)
                } == true
        }
        .mapTo(linkedSetOf()) { view -> view.id }

    val selectedRecordReconciliation = when {
        selectedRecordResourceId == null ->
            DynamicSelectedRecordReconciliation.Keep
        selectedRecordResourceId == canonicalAction.resourceId &&
            canonicalAction.clearsSelectedRecordAfterSuccess() ->
            DynamicSelectedRecordReconciliation.ClearDeletedSelection
        selectedRecordResourceId in affectedResourceIds ->
            DynamicSelectedRecordReconciliation.KeepRouteAndReloadWhenVisible
        else ->
            DynamicSelectedRecordReconciliation.Keep
    }

    return DynamicMutationRefreshPlan(
        actionId = canonicalAction.id,
        actionResourceId = canonicalAction.resourceId,
        affectedResourceIds = affectedResourceIds,
        affectedViewIds = affectedViewIds,
        selectedRecordReconciliation = selectedRecordReconciliation,
    )
}

/**
 * Selection lifetime follows the concrete operation effect, not its broad UI intent. Clearing
 * subordinate data leaves the selected record available, while leaving a resource removes the
 * caller's access even though its intent is execute. The unspecified fallback preserves schemas
 * that predate ActionEffect and represented record deletion only by intent.
 */
private fun ActionSpec.clearsSelectedRecordAfterSuccess(): Boolean = when (effect) {
    ActionEffect.delete,
    ActionEffect.permanentDelete,
    ActionEffect.leave,
    -> true
    ActionEffect.unspecified -> intent == ActionIntent.delete
    else -> false
}
