package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.nativeCollectionActions
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordActions

internal enum class DynamicOperationSurface {
    rootRead,
    childRead,
    recordRead,
    rootForm,
    contextualForm,
    recordAction,
    collectionAction,
    media,
    inputHelper,
    internalTechnical,
    fallbackOnly,
    unsurfaced,
}

internal data class DynamicOperationSurfaceAudit(
    val surfacesByActionId: Map<String, DynamicOperationSurface>,
) {
    val counts: Map<DynamicOperationSurface, Int>
        get() = surfacesByActionId.values.groupingBy { surface -> surface }.eachCount()

    val unsurfacedActionIds: List<String>
        get() = surfacesByActionId.filterValues { surface -> surface == DynamicOperationSurface.unsurfaced }
            .keys.sorted()
}

/**
 * Exercises the same generic navigation, form, record, collection, and media planners used by the
 * renderer with bounded synthetic contract-shaped records. This proves compiler-to-surface wiring;
 * authenticated network execution remains a separate live-runtime layer.
 */
internal fun DynamicAppDescriptor.auditDynamicOperationSurfaces(): DynamicOperationSurfaceAudit {
    val surfaces = linkedMapOf<String, DynamicOperationSurface>()
    val rootPlan = planDynamicNavigation()
    rootPlan.rootDestinations.forEach { destination ->
        surfaces.putIfAbsent(destination.actionId, DynamicOperationSurface.rootRead)
    }
    rootPlan.rootFormActions.forEach { form ->
        surfaces.putIfAbsent(form.actionId, DynamicOperationSurface.rootForm)
    }

    val reachableResources = rootPlan.rootDestinations.mapTo(linkedSetOf()) { destination ->
        destination.resourceId
    }
    val navigationValues = actions
        .flatMap { action -> action.binding.pathParameters + action.binding.queryParameters }
        .associate { parameter -> parameter.name to syntheticValue(parameter.name) }
    var remainingNavigationPasses = resources.size.coerceAtLeast(1)
    while (remainingNavigationPasses-- > 0) {
        var changed = false
        reachableResources.toList().forEach { resourceId ->
            val context = syntheticContext(resourceId, navigationValues)
            val plan = planDynamicNavigation(context)
            plan.contextualChildDestinations.forEach { destination ->
                surfaces.putIfAbsent(destination.actionId, DynamicOperationSurface.childRead)
                changed = reachableResources.add(destination.resourceId) || changed
            }
            plan.contextualFormActions.forEach { form ->
                surfaces.putIfAbsent(form.actionId, DynamicOperationSurface.contextualForm)
            }
        }
        if (!changed) break
    }

    layouts.mapNotNull(DynamicLayout::sourceActionId).forEach { actionId ->
        val action = actions.firstOrNull { candidate -> candidate.id == actionId } ?: return@forEach
        if (action.resourceId !in reachableResources || actionId in surfaces) return@forEach
        val context = syntheticContext(action.resourceId, navigationValues)
        if (resolveDynamicRecordReadParameters(actionId, context) != null) {
            surfaces[actionId] = DynamicOperationSurface.recordRead
        }
    }

    val schema = toNativeAppSchema()
    reachableResources.forEach { resourceId ->
        val resource = schema.resource(resourceId) ?: return@forEach
        val records = listOf(
            syntheticRecord(resource, "1", navigationValues),
            syntheticRecord(resource, "2", navigationValues),
        )
        records.forEach { record ->
            val capabilities = nativeRecordActions(
                schema = schema,
                resource = resource,
                record = record,
                navigationContext = navigationValues,
            )
            listOfNotNull(
                capabilities.create?.action,
                capabilities.edit?.action,
                capabilities.delete?.action,
                capabilities.completion?.action,
            ).forEach { action ->
                surfaces.putIfAbsent(action.id, DynamicOperationSurface.recordAction)
            }
            (capabilities.commands.map { plan -> plan.action } +
                capabilities.commandForms.map { plan -> plan.action }).forEach { action ->
                surfaces.putIfAbsent(action.id, DynamicOperationSurface.recordAction)
            }
        }
        layouts.filter { layout ->
            layout.resourceId == resourceId && layout.kind == LayoutKind.list
        }.forEach { layout ->
            val activeRead = schema.action(layout.sourceActionId ?: return@forEach) ?: return@forEach
            val capabilities = nativeCollectionActions(
                schema = schema,
                activeReadAction = activeRead,
                resource = resource,
                records = records,
                navigationContext = navigationValues,
                collectionComplete = true,
            )
            capabilities.commands.forEach { plan ->
                surfaces.putIfAbsent(plan.action.id, DynamicOperationSurface.collectionAction)
            }
            capabilities.reorder?.let { plan ->
                surfaces.putIfAbsent(plan.action.id, DynamicOperationSurface.collectionAction)
            }
            capabilities.batches.forEach { plan ->
                surfaces.putIfAbsent(plan.action.id, DynamicOperationSurface.collectionAction)
            }
        }
    }

    resources.mapNotNull(DynamicResource::recordImagePreview).forEach { preview ->
        surfaces.putIfAbsent(preview.actionId, DynamicOperationSurface.media)
    }
    actions.forEach { action ->
        if (action.id in surfaces) return@forEach
        surfaces[action.id] = when {
            action.fallbackOnly -> DynamicOperationSurface.fallbackOnly
            action.isDynamicInputHelper() -> DynamicOperationSurface.inputHelper
            action.isInternalTechnicalOperation() -> DynamicOperationSurface.internalTechnical
            else -> DynamicOperationSurface.unsurfaced
        }
    }
    return DynamicOperationSurfaceAudit(surfaces.toSortedMap())
}

private fun DynamicAppDescriptor.syntheticContext(
    resourceId: String,
    navigationValues: Map<String, String>,
): DynamicResourceRecordContext {
    val resource = resources.firstOrNull { candidate -> candidate.id == resourceId }
    return DynamicResourceRecordContext(
        resourceId = resourceId,
        recordId = "1",
        fieldValues = resource?.fields.orEmpty().associate { field ->
            field.id to syntheticValue(field.id)
        },
        parameterValues = navigationValues,
        currentLayoutId = layouts.firstOrNull { layout -> layout.resourceId == resourceId }?.id,
    )
}

private fun syntheticRecord(
    resource: ResourceSpec,
    id: String,
    navigationValues: Map<String, String>,
): NativeRecord = NativeRecord(
    id = id,
    values = resource.fields.associate { field ->
        field.id to when (field.kind) {
            FieldKind.integer -> id
            FieldKind.decimal, FieldKind.currency -> "$id.0"
            FieldKind.boolean -> "true"
            FieldKind.date -> "2026-01-01"
            FieldKind.dateTime -> "2026-01-01T00:00:00Z"
            FieldKind.enumeration -> field.enumValues?.firstOrNull() ?: "value"
            FieldKind.objectValue -> "{}"
            else -> if (field.id.equals("id", true)) id else syntheticValue(field.id)
        }
    },
    bindingContext = navigationValues,
)

private fun DynamicAction.isDynamicInputHelper(): Boolean {
    if (binding.method != HttpMethod.GET || binding.queryParameters.none(HttpParameter::required)) return false
    val value = "$id $label $resourceId ${binding.path}".lowercase()
    return listOf("autocomplete", "lookup", "search", "suggest", "typeahead").any(value::contains)
}

private fun DynamicAction.isInternalTechnicalOperation(): Boolean {
    if (binding.method != HttpMethod.GET || risk != ActionRisk.readOnly) return false
    val words = "$id $label $resourceId ${binding.path}".lowercase()
        .split('-', '_', '/', '.', ' ')
        .filter(String::isNotBlank)
        .toSet()
    return words.any(INTERNAL_TECHNICAL_OPERATION_WORDS::contains)
}

private fun syntheticValue(name: String): String = when {
    name.contains("date", true) -> "2026-01-01"
    name.contains("email", true) || name.contains("mail", true) -> "audit@example.test"
    name.contains("count", true) || name.contains("limit", true) || name.contains("offset", true) -> "1"
    else -> "1"
}

private val INTERNAL_TECHNICAL_OPERATION_WORDS = setOf(
    "capabilities",
    "health",
    "healthcheck",
    "heartbeat",
    "ping",
    "status",
    "version",
)
