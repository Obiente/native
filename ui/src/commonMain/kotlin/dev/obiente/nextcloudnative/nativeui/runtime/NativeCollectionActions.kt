package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs
import dev.obiente.nextcloudnative.template.scanBracedTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Collection-scoped capabilities for a future generic collection toolbar.
 *
 * Planning is deliberately separate from rendering. The planner accepts only the active verified
 * collection read, its exact resource, the currently loaded records, and the request context that
 * produced that collection. It never uses an app identity or operation name to authorize a write.
 */
internal data class NativeCollectionActionCapabilities(
    val commands: List<NativeCollectionCommandActionPlan>,
    val reorder: NativeCollectionReorderActionPlan?,
    val batches: List<NativeCollectionBatchActionPlan>,
)

internal data class NativeCollectionCommandActionPlan(
    val action: ActionSpec,
    val effect: ActionEffect,
    private val bindingValues: Map<String, String>,
) {
    val requiresConfirmation: Boolean
        get() = action.requiresConfirmation

    fun request(confirmed: Boolean): NativeActionRequest.Submit {
        require(!requiresConfirmation || confirmed) {
            "This collection command requires explicit confirmation."
        }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues,
            confirmed = confirmed,
        )
    }
}

internal data class NativeCollectionOrderedIdentity(
    val recordId: String,
    val order: Long,
)

internal data class NativeCollectionReorderActionPlan(
    val action: ActionSpec,
    val identityFieldId: String,
    val orderFieldId: String,
    val maximumOrderSize: Int,
    private val bodyFieldId: String,
    private val identitySchema: NativeCollectionIdentitySchema,
    private val orderSchema: NativeCollectionIntegerSchema,
    private val derivedOrderValues: List<Long>,
    private val availableRecordIds: Set<String>,
    private val bindingValues: Map<String, String>,
) {
    internal fun pendingMutationScope(resourceId: String): String = buildString {
        append(resourceId.length)
        append(':')
        append(resourceId)
        bindingValues.toSortedMap().forEach { (name, value) ->
            append('|')
            append(name.length)
            append(':')
            append(name)
            append('=')
            append(value.length)
            append(':')
            append(value)
        }
    }

    /**
     * Builds the default request a generic reorder UI should use.
     *
     * Numeric positions are derived by the planner from the verified integer schema. Callers only
     * provide record identity order and never need to invent app-specific gaps or base values.
     */
    fun requestInOrder(recordIdsInOrder: List<String>): NativeActionRequest.Submit {
        require(recordIdsInOrder.size == derivedOrderValues.size) {
            "A reordered collection must contain every planned position."
        }
        return request(
            recordIdsInOrder.mapIndexed { index, recordId ->
                NativeCollectionOrderedIdentity(
                    recordId = recordId,
                    order = derivedOrderValues[index],
                )
            },
        )
    }

    fun request(ordered: List<NativeCollectionOrderedIdentity>): NativeActionRequest.Submit {
        require(ordered.size in 2..maximumOrderSize) {
            "A reordered collection must contain a bounded complete order."
        }
        require(ordered.size == derivedOrderValues.size) {
            "A reordered collection must contain every planned position."
        }
        require(ordered.map(NativeCollectionOrderedIdentity::recordId).toSet() == availableRecordIds) {
            "A reordered collection must contain every active record exactly once."
        }
        require(ordered.map(NativeCollectionOrderedIdentity::recordId).distinct().size == ordered.size) {
            "A reordered collection contains duplicate record identities."
        }
        require(ordered.zipWithNext().all { (first, second) -> first.order < second.order }) {
            "A reordered collection requires strictly increasing order values."
        }
        val payload = JsonArray(
            ordered.map { entry ->
                buildJsonObject {
                    put(identityFieldId, identitySchema.jsonValue(entry.recordId))
                    put(orderFieldId, orderSchema.jsonValue(entry.order.toString()))
                }
            },
        )
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues + (bodyFieldId to payload.toString()),
            confirmed = false,
        )
    }
}

internal enum class NativeCollectionBatchInputKind {
    Boolean,
    Integer,
    Decimal,
    String,
    IntegerArray,
    StringArray,
}

internal data class NativeCollectionBatchInputField(
    val id: String,
    val kind: NativeCollectionBatchInputKind,
    val required: Boolean,
    val nullable: Boolean,
    val enumValues: List<String>?,
    val relatedResourceId: String? = null,
)

internal data class NativeCollectionBatchActionPlan(
    val action: ActionSpec,
    val selectionFieldId: String,
    val fields: List<NativeCollectionBatchInputField>,
    val minimumSelectionSize: Int,
    val maximumSelectionSize: Int,
    private val selectionSchema: NativeCollectionIdentityArraySchema,
    private val fieldSchemas: Map<String, NativeCollectionBatchValueSchema>,
    val selectableRecordIds: Set<String>,
    private val bindingValues: Map<String, String>,
) {
    val requiresConfirmation: Boolean
        get() = action.requiresConfirmation

    fun request(
        selectedRecordIds: List<String>,
        values: Map<String, String> = emptyMap(),
        confirmed: Boolean = false,
    ): NativeActionRequest.Submit {
        require(selectedRecordIds.size in minimumSelectionSize..maximumSelectionSize) {
            "A batch selection must be non-empty and bounded."
        }
        require(selectedRecordIds.distinct().size == selectedRecordIds.size) {
            "A batch selection contains duplicate record identities."
        }
        require(selectedRecordIds.all(selectableRecordIds::contains)) {
            "A batch selection must contain only active collection records."
        }
        require(values.keys.all(fieldSchemas::containsKey)) {
            "The batch action contains an undeclared input."
        }
        fields.filter(NativeCollectionBatchInputField::required).forEach { field ->
            require(values.containsKey(field.id)) { "${field.id} is required." }
        }
        require(!requiresConfirmation || confirmed) {
            "This batch action requires explicit confirmation."
        }
        val normalizedInputs = values.mapValues { (fieldId, value) ->
            requireNotNull(fieldSchemas[fieldId]).canonicalValue(value)
        }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues +
                normalizedInputs +
                (selectionFieldId to selectionSchema.jsonValue(selectedRecordIds).toString()),
            confirmed = confirmed,
        )
    }
}

/**
 * Plans collection-wide commands without treating arbitrary record actions as toolbar actions.
 *
 * [collectionComplete] authorizes reorder only. Batch actions may operate on a bounded selected
 * subset of a paged collection, while an order payload must represent the complete active order.
 */
internal fun nativeCollectionActions(
    schema: NativeAppSchema,
    activeReadAction: ActionSpec,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    navigationContext: Map<String, String>,
    collectionComplete: Boolean,
    authorityContext: NativeRecordAuthorityContext? = null,
): NativeCollectionActionCapabilities {
    val empty = NativeCollectionActionCapabilities(emptyList(), null, emptyList())
    val authoritativeResource = schema.resources
        .filter { candidate -> candidate.id == resource.id }
        .singleOrNull()
        ?: return empty
    if (!schema.hasExactActiveCollection(activeReadAction, authoritativeResource)) return empty
    val context = safeNativeCollectionContext(
        readBinding = activeReadAction.binding,
        navigationContext = navigationContext,
    ) ?: return empty
    val recordIds = safeNativeCollectionRecordIds(
        resource = authoritativeResource,
        records = records,
        navigationContext = context,
    ) ?: return empty
    if (recordIds.size > MAX_COLLECTION_CONTEXT_RECORDS) return empty

    val candidates = schema.actions.filter { action ->
        action.id != activeReadAction.id &&
            action.resourceId == authoritativeResource.id &&
            action.confidence.isSafeNativeCollectionConfidence() &&
            schema.actions.count { candidate -> candidate.id == action.id } == 1
    }

    val commands = candidates
        .mapNotNull { action ->
            action.nativeCollectionCommandPlan(
                activeReadAction = activeReadAction,
                resource = authoritativeResource,
                context = context,
            )
        }
        .groupBy { plan -> plan.effect to plan.action.collectionRouteIdentity() }
        .values
        .mapNotNull(List<NativeCollectionCommandActionPlan>::singleOrNull)
        .sortedWith(compareBy({ it.effect.ordinal }, { it.action.label }))

    val reorder = if (
        collectionComplete &&
        recordIds.size in 2..MAX_COLLECTION_REORDER_RECORDS
    ) {
        candidates.mapNotNull { action ->
            val permittedRecordIds = records
                .filter { record ->
                    record.permitsNativeCollectionMutation(
                        action,
                        authoritativeResource,
                        authorityContext,
                    )
                }
                .mapTo(linkedSetOf(), NativeRecord::id)
            if (permittedRecordIds != recordIds) return@mapNotNull null
            action.nativeCollectionReorderPlan(
                activeReadAction = activeReadAction,
                resource = authoritativeResource,
                recordIds = recordIds,
                context = context,
            )
        }.singleOrNull()
    } else {
        null
    }

    val batches = candidates.mapNotNull { action ->
        val permittedRecordIds = records
            .filter { record ->
                record.permitsNativeCollectionMutation(
                    action,
                    authoritativeResource,
                    authorityContext,
                )
            }
            .mapTo(linkedSetOf(), NativeRecord::id)
        action.nativeCollectionBatchPlan(
            schema = schema,
            resource = authoritativeResource,
            recordIds = permittedRecordIds,
            context = context,
        )
    }
        .groupBy { plan -> plan.action.collectionRouteIdentity() }
        .values
        .mapNotNull(List<NativeCollectionBatchActionPlan>::singleOrNull)
        .sortedBy { plan -> plan.action.label }

    return NativeCollectionActionCapabilities(
        commands = commands,
        reorder = reorder,
        batches = batches,
    )
}

private fun NativeRecord.permitsNativeCollectionMutation(
    action: ActionSpec,
    resource: ResourceSpec,
    authorityContext: NativeRecordAuthorityContext?,
): Boolean {
    // Batch and reorder are transport shapes rather than underlying permission categories.
    // Ordinary collection mutations require edit authority, while destructive batches affect each
    // selected record as a deletion would and therefore require delete authority.
    val permissionAction = when {
        action.effect == ActionEffect.batch && action.risk == ActionRisk.destructive ->
            action.copy(
                intent = ActionIntent.delete,
                effect = ActionEffect.delete,
            )
        action.effect in setOf(ActionEffect.batch, ActionEffect.reorder) ->
            action.copy(
                intent = ActionIntent.update,
                effect = ActionEffect.update,
            )
        else -> action
    }
    return permits(permissionAction, resource, authorityContext)
}

private fun NativeAppSchema.hasExactActiveCollection(
    activeReadAction: ActionSpec,
    resource: ResourceSpec,
): Boolean {
    if (resources.count { candidate -> candidate.id == resource.id } != 1) return false
    if (resources.single { candidate -> candidate.id == resource.id } != resource) return false
    if (actions.count { candidate -> candidate.id == activeReadAction.id } != 1) return false
    if (actions.single { candidate -> candidate.id == activeReadAction.id } != activeReadAction) return false
    return activeReadAction.resourceId == resource.id &&
        activeReadAction.binding.method == HttpMethod.GET &&
        activeReadAction.intent == ActionIntent.list &&
        activeReadAction.risk == ActionRisk.readOnly &&
        !activeReadAction.requiresConfirmation &&
        activeReadAction.confidence.isSafeNativeCollectionConfidence() &&
        activeReadAction.binding.bodyFieldNames.isEmpty() &&
        activeReadAction.binding.bodySchema == null
}

private fun safeNativeCollectionContext(
    readBinding: ApiBinding,
    navigationContext: Map<String, String>,
): Map<String, String>? {
    val safeContext = navigationContext.takeIf { values ->
        values.size <= MAX_COLLECTION_CONTEXT_VALUES &&
            values.all { (name, value) ->
                name.isSafeNativeCollectionBindingName() &&
                    value.isSafeNativeCollectionBindingValue()
            }
    } ?: return null
    val merged = safeActionBindingValues(safeContext) ?: return null
    readBinding.resolveNativeCollectionBindings(merged) ?: return null
    return merged
}

private fun safeNativeCollectionRecordIds(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    navigationContext: Map<String, String>,
): Set<String>? {
    if (records.map(NativeRecord::id).distinct().size != records.size) return null
    val safe = LinkedHashSet<String>(records.size)
    records.forEach { record ->
        if (
            !record.actionSafeIdentity ||
            !record.actionBindingProvenanceValid ||
            !record.effectiveNativeResourceId(resource.id).sameDynamicResourceAs(resource.id) ||
            !record.id.isSafeNativeCollectionIdentity()
        ) {
            return null
        }
        if (safeActionBindingValues(record.bindingContext, navigationContext) == null) return null
        // actionSafeIdentity already proves that record.id came from the contract-selected record
        // identity. A parent collection may legitimately have been loaded through a generic
        // `{id}` route parameter whose value differs from the child record identity; treating that
        // parent binding as the child's canonical ID would incorrectly suppress every action.
        if (!safe.add(record.id)) return null
    }
    return safe
}

private fun ActionSpec.nativeCollectionCommandPlan(
    activeReadAction: ActionSpec,
    resource: ResourceSpec,
    context: Map<String, String>,
): NativeCollectionCommandActionPlan? {
    if (
        effect !in setOf(ActionEffect.empty, ActionEffect.clear) ||
        intent !in setOf(ActionIntent.execute, ActionIntent.delete) ||
        risk != ActionRisk.destructive ||
        !requiresConfirmation ||
        binding.method !in COLLECTION_COMMAND_METHODS ||
        !binding.isSameNativeCollectionRouteAs(activeReadAction.binding, context) ||
        binding.bodyFieldNames.isNotEmpty() ||
        binding.requiredBodyFieldNames.isNotEmpty() ||
        binding.bodySchema != null ||
        binding.bodyContentType != null ||
        binding.allowsObservedBodyFields ||
        binding.queryParameterNames.isNotEmpty() ||
        binding.requiredQueryParameterNames.isNotEmpty() ||
        binding.hasNativeCollectionSelfResourcePathIdentity(resource)
    ) {
        return null
    }
    val bindings = binding.resolveNativeCollectionBindings(context) ?: return null
    return NativeCollectionCommandActionPlan(
        action = this,
        effect = effect,
        bindingValues = bindings,
    )
}

private fun ActionSpec.nativeCollectionReorderPlan(
    activeReadAction: ActionSpec,
    resource: ResourceSpec,
    recordIds: Set<String>,
    context: Map<String, String>,
): NativeCollectionReorderActionPlan? {
    if (
        effect != ActionEffect.reorder ||
        intent != ActionIntent.execute ||
        risk != ActionRisk.mutating ||
        requiresConfirmation ||
        binding.method !in COLLECTION_BODY_COMMAND_METHODS ||
        binding.queryParameterNames.isNotEmpty() ||
        binding.requiredQueryParameterNames.isNotEmpty() ||
        binding.allowsObservedBodyFields ||
        !binding.isDirectChildRouteOf(activeReadAction.binding, resource, context)
    ) {
        return null
    }
    val properties = binding.exactNativeCollectionBodyProperties() ?: return null
    if (properties.size != 1) return null
    val bodyField = properties.entries.single()
    val arraySchema = NativeCollectionReorderArraySchema.create(
        schema = bodyField.value,
        resource = resource,
    ) ?: return null
    val maximumOrderSize = minOf(
        MAX_COLLECTION_REORDER_RECORDS,
        arraySchema.maximumItems ?: MAX_COLLECTION_REORDER_RECORDS,
    )
    if (
        recordIds.size < (arraySchema.minimumItems ?: 0) ||
        recordIds.size > maximumOrderSize
    ) {
        return null
    }
    val derivedOrderValues = arraySchema.orderSchema.monotonicValues(recordIds.size) ?: return null
    val bindings = binding.resolveNativeCollectionBindings(context) ?: return null
    return NativeCollectionReorderActionPlan(
        action = this,
        identityFieldId = arraySchema.identityFieldId,
        orderFieldId = arraySchema.orderFieldId,
        maximumOrderSize = maximumOrderSize,
        bodyFieldId = bodyField.key,
        identitySchema = arraySchema.identitySchema,
        orderSchema = arraySchema.orderSchema,
        derivedOrderValues = derivedOrderValues,
        availableRecordIds = recordIds,
        bindingValues = bindings,
    )
}

private fun ActionSpec.nativeCollectionBatchPlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    recordIds: Set<String>,
    context: Map<String, String>,
): NativeCollectionBatchActionPlan? {
    if (
        effect != ActionEffect.batch ||
        intent != ActionIntent.execute ||
        !hasProperNativeCollectionConfirmation() ||
        binding.method !in COLLECTION_BODY_COMMAND_METHODS ||
        binding.queryParameterNames.isNotEmpty() ||
        binding.requiredQueryParameterNames.isNotEmpty() ||
        binding.allowsObservedBodyFields ||
        binding.hasNativeCollectionSelfResourcePathIdentity(resource)
    ) {
        return null
    }
    val properties = binding.exactNativeCollectionBodyProperties() ?: return null
    val selectionCandidates = properties.mapNotNull { (fieldId, schema) ->
        fieldId.takeIf { candidate -> candidate.isSelfResourceSelectionField(resource) }
            ?.let {
                NativeCollectionIdentityArraySchema.create(schema)
                    ?.let { selection -> fieldId to selection }
            }
    }
    val (selectionFieldId, selectionSchema) = selectionCandidates.singleOrNull() ?: return null
    val declaredFieldSchemas = properties
        .filterKeys { fieldId -> fieldId != selectionFieldId }
        .mapValues { (_, schema) -> NativeCollectionBatchValueSchema.create(schema) ?: return null }
    val schemaRequired = binding.bodySchema.requiredNativeCollectionProperties() ?: return null
    if (declaredFieldSchemas.any { (fieldId, fieldSchema) ->
            fieldId in schemaRequired && fieldSchema.nullable
        }
    ) {
        // The generic batch draft cannot distinguish an explicit JSON null from omission. A
        // required nullable field therefore cannot be submitted without inventing a value.
        return null
    }
    // Optional nullable inputs remain omitted until the generic batch draft has an explicit null
    // state. Keeping them out of both the UI and accepted request values prevents a blank value
    // from being misrepresented as either an empty scalar or an absent clear operation.
    val fieldSchemas = declaredFieldSchemas.filterValues { fieldSchema -> !fieldSchema.nullable }
    val fields = fieldSchemas.map { (fieldId, fieldSchema) ->
        NativeCollectionBatchInputField(
            id = fieldId,
            kind = fieldSchema.kind,
            required = fieldId in schemaRequired,
            nullable = fieldSchema.nullable,
            enumValues = fieldSchema.enumValues,
            relatedResourceId = schema.verifiedNativeCollectionRelatedResourceId(
                resource = resource,
                fieldId = fieldId,
            ),
        )
    }
    val minimumSelectionSize = maxOf(1, selectionSchema.minimumItems ?: 1)
    val maximumSelectionSize = minOf(
        MAX_COLLECTION_BATCH_SELECTION,
        selectionSchema.maximumItems ?: MAX_COLLECTION_BATCH_SELECTION,
    )
    if (
        minimumSelectionSize > maximumSelectionSize ||
        recordIds.size < minimumSelectionSize
    ) {
        return null
    }
    val bindings = binding.resolveNativeCollectionBindings(context) ?: return null
    return NativeCollectionBatchActionPlan(
        action = this,
        selectionFieldId = selectionFieldId,
        fields = fields,
        minimumSelectionSize = minimumSelectionSize,
        maximumSelectionSize = maximumSelectionSize,
        selectionSchema = selectionSchema,
        fieldSchemas = fieldSchemas,
        selectableRecordIds = recordIds,
        bindingValues = bindings,
    )
}

private fun NativeAppSchema.verifiedNativeCollectionRelatedResourceId(
    resource: ResourceSpec,
    fieldId: String,
): String? {
    val candidates = relationships.asSequence()
        .filter { relationship ->
            relationship.confidence == Confidence.verified &&
                relationship.childResourceId.sameDynamicResourceAs(resource.id) &&
                relationship.childFieldId == fieldId
        }
        .map { relationship -> relationship.parentResourceId }
        .distinct()
        .filter { parentResourceId ->
            resources.count { candidate ->
                candidate.id.sameDynamicResourceAs(parentResourceId)
            } == 1
        }
        .toList()
    return candidates.singleOrNull()
}

private fun ActionSpec.hasProperNativeCollectionConfirmation(): Boolean = when (risk) {
    ActionRisk.readOnly -> false
    ActionRisk.mutating -> !requiresConfirmation
    ActionRisk.destructive -> requiresConfirmation
}

private fun ApiBinding.exactNativeCollectionBodyProperties(): JsonObject? {
    if (
        bodyContentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" ||
        bodyFieldNames.isEmpty() ||
        bodyFieldNames.distinct().size != bodyFieldNames.size ||
        requiredBodyFieldNames.any { field -> field !in bodyFieldNames }
    ) {
        return null
    }
    val body = bodySchema as? JsonObject ?: return null
    if (!body.isExactNativeCollectionObjectSchema(NATIVE_COLLECTION_BODY_SCHEMA_KEYS)) return null
    val properties = body["properties"] as? JsonObject ?: return null
    if (properties.keys != bodyFieldNames.toSet()) return null
    val required = body.requiredNativeCollectionProperties() ?: return null
    if (required != requiredBodyFieldNames.toSet()) return null
    return properties
}

private fun JsonElement?.requiredNativeCollectionProperties(): Set<String>? {
    val body = this as? JsonObject ?: return null
    val required = body["required"] ?: return emptySet()
    val array = required as? JsonArray ?: return null
    val values = array.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    }
    val distinct = values.toSet()
    return distinct.takeIf {
        values.size == array.size && distinct.size == values.size
    }
}

private fun ApiBinding.resolveNativeCollectionBindings(
    context: Map<String, String>,
): Map<String, String>? {
    val pathNames = (pathParameterNames + requiredPathParameterNames).distinct()
    if (
        pathParameterNames.distinct().size != pathParameterNames.size ||
        requiredPathParameterNames.distinct().size != requiredPathParameterNames.size ||
        requiredPathParameterNames.any { name -> name !in pathParameterNames } ||
        queryParameterNames.distinct().size != queryParameterNames.size ||
        requiredQueryParameterNames.distinct().size != requiredQueryParameterNames.size ||
        requiredQueryParameterNames.any { name -> name !in queryParameterNames } ||
        path.placeholderNames() != pathNames.toSet()
    ) {
        return null
    }
    val resolved = linkedMapOf<String, String>()
    pathNames.forEach { name ->
        val qualifiedValues = context.resourceQualifiedValuesForGenericPathIdentity(
            path = path,
            parameterName = name,
        )
        val value = when (qualifiedValues.size) {
            0 -> context[name]
                ?: context["id"].takeIf {
                    pathNames.size == 1 &&
                        isProvenSingleCollectionParentIdentityAlias(name)
                }
                ?.takeIf(String::isSafeNativeCollectionPathValue)
                ?: return null
            1 -> qualifiedValues.single()
            else -> return null
        }
        resolved[name] = value
    }
    requiredQueryParameterNames.forEach { name ->
        val value = context[name]
            ?.takeIf(String::isSafeNativeCollectionBindingValue)
            ?: return null
        resolved[name] = value
    }
    return resolved
}

private fun ApiBinding.isDirectChildRouteOf(
    readBinding: ApiBinding,
    resource: ResourceSpec,
    context: Map<String, String>,
): Boolean {
    val readSegments = readBinding.resolvedNativeCollectionPathSegments(context) ?: return false
    val actionSegments = resolvedNativeCollectionPathSegments(context) ?: return false
    // A generic `{id}` is ambiguous in isolation, but is proven to be the collection parent's
    // identity when both exact routes resolve to the same collection and the command appends one
    // literal segment. A record-scoped route still has an extra identity segment and fails below.
    return actionSegments.size == readSegments.size + 1 &&
        actionSegments.dropLast(1) == readSegments &&
        path.trimEnd('/').substringAfterLast('/').let { segment ->
            '{' !in segment && '}' !in segment
        }
}

private fun ApiBinding.isSameNativeCollectionRouteAs(
    other: ApiBinding,
    context: Map<String, String>,
): Boolean =
    resolvedNativeCollectionPathSegments(context) ==
        other.resolvedNativeCollectionPathSegments(context)

private fun ApiBinding.resolvedNativeCollectionPathSegments(
    context: Map<String, String>,
): List<String>? {
    val bindings = resolveNativeCollectionBindings(context) ?: return null
    return path.substringBefore('?')
        .trimEnd('/')
        .split('/')
        .map { segment ->
            if (segment.startsWith('{') && segment.endsWith('}')) {
                val name = segment.substring(1, segment.lastIndex)
                bindings[name] ?: return null
            } else {
                if ('{' in segment || '}' in segment) return null
                segment
            }
        }
}

private fun String.isSelfResourceSelectionField(resource: ResourceSpec): Boolean {
    val normalized = nativeCollectionSemanticId()
    if (normalized in GENERIC_COLLECTION_SELECTION_FIELD_IDS) return true
    if (!normalized.endsWith("ids") || normalized.length <= 3) return false
    return normalized.dropLast(3).sameDynamicResourceAs(resource.id)
}

internal fun String.isSelfResourceIdentityField(resource: ResourceSpec): Boolean {
    val normalized = nativeCollectionSemanticId()
    if (normalized == "id") return true
    if (!normalized.endsWith("id") || normalized.length <= 2) return false
    return normalized.dropLast(2).sameDynamicResourceAs(resource.id)
}

private fun ActionSpec.collectionRouteIdentity(): String =
    "${binding.method}:${binding.path}:${binding.bodyFieldNames.sorted()}"

private data class NativeCollectionReorderArraySchema(
    val identityFieldId: String,
    val orderFieldId: String,
    val identitySchema: NativeCollectionIdentitySchema,
    val orderSchema: NativeCollectionIntegerSchema,
    val minimumItems: Int?,
    val maximumItems: Int?,
) {
    companion object {
        fun create(
            schema: JsonElement,
            resource: ResourceSpec,
        ): NativeCollectionReorderArraySchema? {
            val array = schema as? JsonObject ?: return null
            if (!array.isExactNativeCollectionArraySchema()) return null
            val item = array["items"] as? JsonObject ?: return null
            if (!item.isExactNativeCollectionObjectSchema(NATIVE_COLLECTION_ITEM_OBJECT_SCHEMA_KEYS)) return null
            val properties = item["properties"] as? JsonObject ?: return null
            if (properties.size != 2) return null
            val required = item.requiredNativeCollectionProperties() ?: return null
            if (required != properties.keys) return null
            val identityCandidates = properties.mapNotNull { (fieldId, fieldSchema) ->
                fieldId.takeIf { candidate ->
                    candidate.nativeCollectionSemanticId() == "id" ||
                        candidate.isSelfResourceIdentityField(resource)
                }?.let {
                    NativeCollectionIdentitySchema.create(fieldSchema)
                        ?.let { identity -> fieldId to identity }
                }
            }
            val (identityFieldId, identitySchema) = identityCandidates.singleOrNull() ?: return null
            val orderCandidates = properties
                .filterKeys { fieldId -> fieldId != identityFieldId }
                .mapNotNull { (fieldId, fieldSchema) ->
                    fieldId.takeIf { candidate ->
                        candidate.nativeCollectionSemanticId() in COLLECTION_ORDER_FIELD_IDS
                    }?.let {
                        NativeCollectionIntegerSchema.create(fieldSchema)
                            ?.let { order -> fieldId to order }
                    }
                }
            val (orderFieldId, orderSchema) = orderCandidates.singleOrNull() ?: return null
            return NativeCollectionReorderArraySchema(
                identityFieldId = identityFieldId,
                orderFieldId = orderFieldId,
                identitySchema = identitySchema,
                orderSchema = orderSchema,
                minimumItems = array.nonNegativeInt("minItems"),
                maximumItems = array.nonNegativeInt("maxItems"),
            )
        }
    }
}

internal data class NativeCollectionIdentityArraySchema(
    val identity: NativeCollectionIdentitySchema,
    val minimumItems: Int?,
    val maximumItems: Int?,
) {
    fun jsonValue(values: List<String>): JsonArray {
        minimumItems?.let { minimum -> require(values.size >= minimum) }
        maximumItems?.let { maximum -> require(values.size <= maximum) }
        return JsonArray(values.map(identity::jsonValue))
    }

    companion object {
        fun create(schema: JsonElement): NativeCollectionIdentityArraySchema? {
            val array = schema as? JsonObject ?: return null
            if (!array.isExactNativeCollectionArraySchema()) return null
            val identity = NativeCollectionIdentitySchema.create(array["items"]) ?: return null
            return NativeCollectionIdentityArraySchema(
                identity = identity,
                minimumItems = array.nonNegativeInt("minItems"),
                maximumItems = array.nonNegativeInt("maxItems"),
            )
        }
    }
}

internal sealed interface NativeCollectionIdentitySchema {
    fun jsonValue(value: String): JsonPrimitive

    data class IntegerIdentity(
        val schema: NativeCollectionIntegerSchema,
    ) : NativeCollectionIdentitySchema {
        override fun jsonValue(value: String): JsonPrimitive = schema.jsonValue(value)
    }

    data class StringIdentity(
        val minimumLength: Int?,
        val maximumLength: Int?,
        val enumValues: Set<String>?,
    ) : NativeCollectionIdentitySchema {
        override fun jsonValue(value: String): JsonPrimitive {
            require(value.isSafeNativeCollectionIdentity())
            minimumLength?.let { minimum -> require(value.length >= minimum) }
            maximumLength?.let { maximum -> require(value.length <= maximum) }
            enumValues?.let { allowed -> require(value in allowed) }
            return JsonPrimitive(value)
        }
    }

    companion object {
        fun create(schema: JsonElement?): NativeCollectionIdentitySchema? {
            val scalar = schema as? JsonObject ?: return null
            if (!scalar.keys.all(NATIVE_COLLECTION_IDENTITY_SCHEMA_KEYS::contains)) return null
            return when (scalar.string("type")) {
                "integer" -> NativeCollectionIntegerSchema.create(scalar)?.let(::IntegerIdentity)
                "string" -> {
                    val minimum = scalar.nonNegativeInt("minLength")
                    val maximum = scalar.nonNegativeInt("maxLength")
                    if (
                        !scalar.hasOptionalString("format") ||
                        !scalar.hasOptionalBoolean("nullable") ||
                        !scalar.hasOptionalNonNegativeInt("minLength") ||
                        !scalar.hasOptionalNonNegativeInt("maxLength") ||
                        (minimum != null && maximum != null && minimum > maximum)
                    ) {
                        return null
                    }
                    val enumValues = scalar.stringEnumValues()
                    if ("enum" in scalar && enumValues == null) return null
                    StringIdentity(
                        minimumLength = minimum,
                        maximumLength = maximum,
                        enumValues = enumValues,
                    )
                }
                else -> null
            }
        }
    }
}

internal data class NativeCollectionIntegerSchema(
    val minimum: Long?,
    val maximum: Long?,
) {
    fun jsonValue(value: String): JsonPrimitive {
        val parsed = value.toLongOrNull() ?: error("A collection identity must be a whole number.")
        minimum?.let { lower -> require(parsed >= lower) }
        maximum?.let { upper -> require(parsed <= upper) }
        return JsonPrimitive(parsed)
    }

    fun monotonicValues(count: Int): List<Long>? {
        if (count <= 0) return null
        val lastOffset = (count - 1).toLong()

        fun valuesFrom(start: Long): List<Long>? {
            if (start > Long.MAX_VALUE - lastOffset) return null
            val end = start + lastOffset
            if (minimum?.let { start < it } == true || maximum?.let { end > it } == true) return null
            return List(count) { index -> start + index.toLong() }
        }

        valuesFrom(0L)?.let { return it }
        minimum?.let { lower -> valuesFrom(lower)?.let { return it } }
        maximum?.let { upper ->
            if (upper < Long.MIN_VALUE + lastOffset) return null
            valuesFrom(upper - lastOffset)?.let { return it }
        }
        return null
    }

    companion object {
        fun create(schema: JsonElement?): NativeCollectionIntegerSchema? {
            val scalar = schema as? JsonObject ?: return null
            if (
                scalar.string("type") != "integer" ||
                !scalar.keys.all(NATIVE_COLLECTION_INTEGER_SCHEMA_KEYS::contains) ||
                !scalar.hasOptionalString("format") ||
                !scalar.hasOptionalBoolean("nullable") ||
                !scalar.hasOptionalLong("minimum") ||
                !scalar.hasOptionalLong("maximum")
            ) {
                return null
            }
            val minimum = scalar.long("minimum")
            val maximum = scalar.long("maximum")
            if (minimum != null && maximum != null && minimum > maximum) return null
            return NativeCollectionIntegerSchema(minimum, maximum)
        }
    }
}

internal data class NativeCollectionBatchValueSchema(
    val kind: NativeCollectionBatchInputKind,
    val nullable: Boolean,
    val enumValues: List<String>?,
    val canonicalValue: (String) -> String,
) {
    companion object {
        fun create(schema: JsonElement): NativeCollectionBatchValueSchema? {
            val property = schema as? JsonObject ?: return null
            val nullable = property.boolean("nullable") == true
            val stringEnumValues = property.stringEnumValues()
            if ("enum" in property && stringEnumValues == null) return null
            val enumValues = stringEnumValues?.toList()
            return when (property.string("type")) {
                "boolean" -> {
                    if (
                        !property.keys.all(NATIVE_COLLECTION_BOOLEAN_SCHEMA_KEYS::contains) ||
                        !property.hasOptionalBoolean("nullable")
                    ) {
                        return null
                    }
                    NativeCollectionBatchValueSchema(
                        kind = NativeCollectionBatchInputKind.Boolean,
                        nullable = nullable,
                        enumValues = null,
                    ) { value ->
                        value.toBooleanStrictOrNull()?.toString()
                            ?: error("A batch boolean must be true or false.")
                    }
                }
                "integer" -> {
                    val integer = NativeCollectionIntegerSchema.create(property) ?: return null
                    NativeCollectionBatchValueSchema(
                        kind = NativeCollectionBatchInputKind.Integer,
                        nullable = nullable,
                        enumValues = enumValues,
                    ) { value -> integer.jsonValue(value).content }
                }
                "number" -> {
                    if (
                        !property.keys.all(NATIVE_COLLECTION_NUMBER_SCHEMA_KEYS::contains) ||
                        !property.hasOptionalString("format") ||
                        !property.hasOptionalBoolean("nullable") ||
                        !property.hasOptionalDouble("minimum") ||
                        !property.hasOptionalDouble("maximum")
                    ) {
                        return null
                    }
                    val minimum = property.double("minimum")
                    val maximum = property.double("maximum")
                    if (minimum != null && maximum != null && minimum > maximum) return null
                    NativeCollectionBatchValueSchema(
                        kind = NativeCollectionBatchInputKind.Decimal,
                        nullable = nullable,
                        enumValues = enumValues,
                    ) { value ->
                        val parsed = value.toDoubleOrNull()?.takeIf(Double::isFinite)
                            ?: error("A batch number must be finite.")
                        minimum?.let { lower -> require(parsed >= lower) }
                        maximum?.let { upper -> require(parsed <= upper) }
                        parsed.toString()
                    }
                }
                "string" -> {
                    if (
                        !property.keys.all(NATIVE_COLLECTION_STRING_SCHEMA_KEYS::contains) ||
                        !property.hasOptionalString("format") ||
                        !property.hasOptionalBoolean("nullable") ||
                        !property.hasOptionalNonNegativeInt("minLength") ||
                        !property.hasOptionalNonNegativeInt("maxLength")
                    ) {
                        return null
                    }
                    val minimum = property.nonNegativeInt("minLength")
                    val maximum = property.nonNegativeInt("maxLength")
                    if (minimum != null && maximum != null && minimum > maximum) return null
                    NativeCollectionBatchValueSchema(
                        kind = NativeCollectionBatchInputKind.String,
                        nullable = nullable,
                        enumValues = enumValues,
                    ) { value ->
                        require(value.length <= MAX_COLLECTION_INPUT_LENGTH && value.none(Char::isISOControl))
                        minimum?.let { lower -> require(value.length >= lower) }
                        maximum?.let { upper -> require(value.length <= upper) }
                        enumValues?.let { allowed -> require(value in allowed) }
                        value
                    }
                }
                "array" -> {
                    val array = NativeCollectionIdentityArraySchema.create(property) ?: return null
                    val itemType = (property["items"] as? JsonObject)?.string("type")
                    val kind = when (itemType) {
                        "integer" -> NativeCollectionBatchInputKind.IntegerArray
                        "string" -> NativeCollectionBatchInputKind.StringArray
                        else -> return null
                    }
                    NativeCollectionBatchValueSchema(
                        kind = kind,
                        nullable = nullable,
                        enumValues = null,
                    ) { value ->
                        val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonArray
                            ?: error("A batch array must be a JSON array.")
                        val scalars = parsed.map { element ->
                            val primitive = element as? JsonPrimitive
                                ?: error("A batch array must contain scalar identities.")
                            if (itemType == "string") {
                                primitive.takeIf(JsonPrimitive::isString)?.contentOrNull
                            } else {
                                primitive.takeUnless(JsonPrimitive::isString)?.contentOrNull
                            } ?: error("A batch array identity has the wrong type.")
                        }
                        require(scalars.distinct().size == scalars.size)
                        array.jsonValue(scalars).toString()
                    }
                }
                else -> null
            }
        }
    }
}

private fun JsonObject.isExactNativeCollectionArraySchema(): Boolean =
    string("type") == "array" &&
        keys.all(NATIVE_COLLECTION_ARRAY_SCHEMA_KEYS::contains) &&
        this["items"] != null &&
        hasOptionalString("format") &&
        hasOptionalBoolean("nullable") &&
        hasOptionalBoolean("uniqueItems") &&
        hasOptionalNonNegativeInt("minItems") &&
        hasOptionalNonNegativeInt("maxItems") &&
        (
            nonNegativeInt("minItems") == null ||
                nonNegativeInt("maxItems") == null ||
                requireNotNull(nonNegativeInt("minItems")) <= requireNotNull(nonNegativeInt("maxItems"))
            )

private fun JsonObject.isExactNativeCollectionObjectSchema(allowedKeys: Set<String>): Boolean =
    string("type") == "object" &&
        keys.all(allowedKeys::contains) &&
        this["properties"] is JsonObject &&
        ("additionalProperties" !in this || boolean("additionalProperties") == false)

private fun String.isSafeNativeCollectionBindingName(): Boolean =
    length in 1..128 && all { character ->
        character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
    }

private fun String.isSafeNativeCollectionBindingValue(): Boolean =
    isNotBlank() &&
        length <= MAX_COLLECTION_CONTEXT_VALUE_LENGTH &&
        none { character -> character.isISOControl() || character == '\\' }

private fun String.isSafeNativeCollectionPathValue(): Boolean =
    isSafeNativeCollectionBindingValue() && '/' !in this

private fun String.isSafeNativeCollectionIdentity(): Boolean =
    isNotBlank() &&
        length <= MAX_COLLECTION_IDENTITY_LENGTH &&
        none { character -> character.isISOControl() || character == '/' || character == '\\' }

private fun String.placeholderNames(): Set<String>? {
    val scan = substringBefore('?').scanBracedTemplate()
    if (scan.malformed) return null
    val names = scan.tokens.map { token -> token.name }
    if (names.distinct().size != names.size) return null
    if (names.any { name -> !name.isSafeNativeCollectionTemplateName() }) return null
    return names.toSet()
}

private fun String.isSafeNativeCollectionTemplateName(): Boolean =
    length in 1..128 &&
        first().isLetter() &&
        all { character ->
            character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
        }

private fun Confidence.isSafeNativeCollectionConfidence(): Boolean =
    this == Confidence.high || this == Confidence.verified

internal fun String.nativeCollectionSemanticId(): String = lowercase().filter(Char::isLetterOrDigit)

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.double(name: String): Double? =
    (this[name] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.nonNegativeInt(name: String): Int? =
    long(name)?.takeIf { value -> value in 0..Int.MAX_VALUE }?.toInt()

private fun JsonObject.hasOptionalString(name: String): Boolean =
    name !in this || string(name) != null

private fun JsonObject.hasOptionalBoolean(name: String): Boolean =
    name !in this || boolean(name) != null

private fun JsonObject.hasOptionalLong(name: String): Boolean =
    name !in this || long(name) != null

private fun JsonObject.hasOptionalDouble(name: String): Boolean =
    name !in this || double(name)?.isFinite() == true

private fun JsonObject.hasOptionalNonNegativeInt(name: String): Boolean =
    name !in this || nonNegativeInt(name) != null

private fun JsonObject.stringEnumValues(): Set<String>? {
    val values = this["enum"] ?: return null
    val array = values as? JsonArray ?: return null
    val strings = array.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
    }
    return strings.toSet().takeIf { strings.size == array.size && strings.distinct().size == strings.size }
}

private val COLLECTION_COMMAND_METHODS = setOf(HttpMethod.POST, HttpMethod.DELETE)
private val COLLECTION_BODY_COMMAND_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
private val GENERIC_COLLECTION_SELECTION_FIELD_IDS = setOf("ids", "recordids", "selectedids")
private val COLLECTION_ORDER_FIELD_IDS = setOf(
    "index",
    "order",
    "ordinal",
    "position",
    "rank",
    "sequence",
    "sortorder",
)
private val NATIVE_COLLECTION_BODY_SCHEMA_KEYS = setOf(
    "type",
    "properties",
    "required",
    "additionalProperties",
    "description",
    "title",
)
private val NATIVE_COLLECTION_ITEM_OBJECT_SCHEMA_KEYS = setOf(
    "type",
    "properties",
    "required",
    "additionalProperties",
    "description",
    "title",
)
private val NATIVE_COLLECTION_ARRAY_SCHEMA_KEYS = setOf(
    "type",
    "items",
    "format",
    "nullable",
    "default",
    "description",
    "title",
    "minItems",
    "maxItems",
    "uniqueItems",
)
private val NATIVE_COLLECTION_IDENTITY_SCHEMA_KEYS = setOf(
    "type",
    "format",
    "nullable",
    "default",
    "description",
    "title",
    "enum",
    "minimum",
    "maximum",
    "minLength",
    "maxLength",
)
private val NATIVE_COLLECTION_INTEGER_SCHEMA_KEYS = setOf(
    "type",
    "format",
    "nullable",
    "default",
    "description",
    "title",
    "minimum",
    "maximum",
)
private val NATIVE_COLLECTION_BOOLEAN_SCHEMA_KEYS = setOf(
    "type",
    "nullable",
    "default",
    "description",
    "title",
)
private val NATIVE_COLLECTION_NUMBER_SCHEMA_KEYS = setOf(
    "type",
    "format",
    "nullable",
    "default",
    "description",
    "title",
    "minimum",
    "maximum",
)
private val NATIVE_COLLECTION_STRING_SCHEMA_KEYS = setOf(
    "type",
    "format",
    "nullable",
    "default",
    "description",
    "title",
    "enum",
    "minLength",
    "maxLength",
)
private const val MAX_COLLECTION_CONTEXT_VALUES = 64
private const val MAX_COLLECTION_CONTEXT_VALUE_LENGTH = 4_096
private const val MAX_COLLECTION_CONTEXT_RECORDS = 5_000
private const val MAX_COLLECTION_REORDER_RECORDS = 500
private const val MAX_COLLECTION_BATCH_SELECTION = 256
private const val MAX_COLLECTION_IDENTITY_LENGTH = 256
private const val MAX_COLLECTION_INPUT_LENGTH = 8_192
