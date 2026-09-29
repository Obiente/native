package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.nativeTableRecordScope
import kotlinx.coroutines.CancellationException

internal data class DynamicTableColumnReadPlan(
    val resourceId: String,
    val actionId: String,
    val values: Map<String, String>,
    val parentFieldId: String,
    val parentValue: String,
)

internal fun dynamicTableColumnReadPlan(
    descriptor: DynamicAppDescriptor,
    schema: NativeAppSchema,
    view: ViewSpec,
    record: NativeRecord,
): DynamicTableColumnReadPlan? {
    if (view.component != NativeComponent.detail || !record.actionBindingProvenanceValid) return null
    val scope = nativeTableRecordScope(schema, view.resourceId, record) ?: return null
    val composite = scope.composite
    val rowRelation = scope.rowRelationship
    val rowParentField = requireNotNull(rowRelation.childFieldId)
    val columnParentField = requireNotNull(scope.columnRelationship.childFieldId)
    val parent = scope.parentIdentity
    val action = descriptor.actions.singleOrNull { it.id == composite.columnSourceActionId } ?: return null
    if (action.resourceId != composite.columnResourceId || action.binding.method != HttpMethod.GET ||
        action.intent !in setOf(ActionIntent.list, ActionIntent.read) || action.risk != ActionRisk.readOnly ||
        action.confidence !in TABLE_DETAIL_CONFIDENCE || !action.binding.path.isApproved(descriptor.endpointPolicy) ||
        action.provenance.none { it.kind in TABLE_DETAIL_PROVENANCE }) return null
    // A verified parent link can bind its identity field, even when the route calls that field id.
    val linkedParent = descriptor.links.any { link ->
        link.resourceId == composite.parentResourceId && link.sourceFieldId == rowRelation.parentFieldId &&
            (link.target as? DynamicLinkTarget.Action)?.actionId == action.id &&
            link.confidence in TABLE_DETAIL_CONFIDENCE && link.provenance.any { it.kind in TABLE_DETAIL_PROVENANCE }
    }
    val parameters = action.binding.pathParameters + action.binding.queryParameters.filter { it.required }
    if (parameters.isEmpty() || parameters.any { parameter ->
            parameter.name !in setOf(rowParentField, columnParentField) &&
                !(linkedParent && parameter.name == rowRelation.parentFieldId)
        }) return null
    val values = parameters.associate { it.name to parent }
    return DynamicTableColumnReadPlan(composite.columnResourceId, action.id, values, columnParentField, parent)
}

internal data class DynamicTableDetailLoadOutcome(
    val records: List<NativeRecord>,
    val partialFailureMessage: String?,
    val relatedRecords: Map<String, List<NativeRecord>> = emptyMap(),
    val relatedFailureMessage: String? = null,
)

internal suspend fun loadDynamicTableDetailWithOutcome(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    view: ViewSpec,
    schema: NativeAppSchema,
    values: Map<String, String>,
    runtimeContext: Map<String, String>,
    cachePolicy: NextcloudApiCachePolicy,
): DynamicTableDetailLoadOutcome {
    val primary = loadDynamicRecordsWithOutcome(services, session, descriptor, view.sourceActionId,
        values, runtimeContext, cachePolicy)
    val plan = primary.records.singleOrNull()?.let { dynamicTableColumnReadPlan(descriptor, schema, view, it) }
    return loadDynamicTableDetailColumns(primary, plan) { request ->
        loadDynamicRecordsWithOutcome(services, session, descriptor, request.actionId,
            request.values, request.values, cachePolicy)
    }
}

internal suspend fun loadDynamicTableDetailColumns(
    primary: DynamicRecordLoadOutcome,
    plan: DynamicTableColumnReadPlan?,
    load: suspend (DynamicTableColumnReadPlan) -> DynamicRecordLoadOutcome,
): DynamicTableDetailLoadOutcome {
    if (plan == null || primary.partialFailureMessage != null) {
        return DynamicTableDetailLoadOutcome(primary.records, primary.partialFailureMessage)
    }
    return try {
        val columns = load(plan)
        if (columns.partialFailureMessage != null || columns.records.any {
                it.values[plan.parentFieldId] != plan.parentValue || !it.actionBindingProvenanceValid
            }) {
            DynamicTableDetailLoadOutcome(primary.records, null, mapOf(plan.resourceId to emptyList()), "Column details could not be verified.")
        } else DynamicTableDetailLoadOutcome(primary.records, null, mapOf(plan.resourceId to columns.records))
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        DynamicTableDetailLoadOutcome(primary.records, null, mapOf(plan.resourceId to emptyList()), "Column details could not be loaded.")
    }
}

private val TABLE_DETAIL_CONFIDENCE = setOf(Confidence.high, Confidence.verified)
private val TABLE_DETAIL_PROVENANCE = setOf(ProvenanceKind.advertisedOpenApi,
    ProvenanceKind.verifiedAppPackage, ProvenanceKind.verifiedAdapter)
