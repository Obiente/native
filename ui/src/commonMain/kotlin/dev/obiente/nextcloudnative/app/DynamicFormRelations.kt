package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs

internal data class DynamicFormRelationLoadPlan(
    val resourceId: String,
    val actionId: String,
)

/**
 * Selects one exact, fully bound collection read for each accepted relationship field used by the
 * active form. This never creates relationship evidence and never supplies missing request values.
 */
internal fun dynamicFormRelationLoadPlans(
    schema: NativeAppSchema,
    formView: ViewSpec,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadPlan> {
    if (formView.component != NativeComponent.form) return emptyList()
    val formAction = schema.action(formView.sourceActionId) ?: return emptyList()
    val editableFieldIds = (
        formAction.binding.bodyFieldNames + formAction.binding.queryParameterNames
    ).toSet()
    val parentResourceIds = schema.relationships.asSequence()
        .filter { relationship ->
            relationship.childResourceId == formView.resourceId &&
                relationship.childFieldId in editableFieldIds &&
                relationship.confidence in setOf(Confidence.high, Confidence.verified)
        }
        .map { relationship -> relationship.parentResourceId }
        .distinct()
        .toList()
    return parentResourceIds.mapNotNull { parentResourceId ->
        schema.views.asSequence()
            .filter { view ->
                view.component != NativeComponent.form &&
                    view.resourceId == parentResourceId
            }
            .mapNotNull { view ->
                val action = schema.action(view.sourceActionId) ?: return@mapNotNull null
                val requiredNames = (
                    action.binding.requiredPathParameterNames +
                        action.binding.requiredQueryParameterNames
                ).distinct()
                val resolvedBindings = dynamicFormRelationBindingValues(action, availableValues)
                action.takeIf {
                    action.resourceId == parentResourceId &&
                        action.intent == ActionIntent.list &&
                        action.risk == ActionRisk.readOnly &&
                        action.binding.method == HttpMethod.GET &&
                        action.confidence in setOf(Confidence.high, Confidence.verified) &&
                        dynamicCollectionState(action) == null &&
                        requiredNames.all(resolvedBindings::containsKey)
                }?.let {
                    Triple(view, action, requiredNames.size)
                }
            }
            .sortedWith(
                compareByDescending<Triple<ViewSpec, dev.obiente.nextcloudnative.nativeui.model.ActionSpec, Int>> {
                    it.third
                }.thenBy { (view, _, _) -> view.id },
            )
            .firstOrNull()
            ?.let { (_, action, _) ->
                DynamicFormRelationLoadPlan(parentResourceId, action.id)
            }
    }.distinctBy(DynamicFormRelationLoadPlan::resourceId)
        .sortedBy(DynamicFormRelationLoadPlan::resourceId)
}

/**
 * Resolves only contract-declared relation-read bindings.
 *
 * Some APIs use a generic `{id}` placeholder even though the preceding literal route segment
 * identifies a specific parent resource. In a nested form, the selected child can also expose an
 * unrelated `id`; when one trusted qualified value matches that route segment, it is the exact
 * value for `{id}`. Ambiguous aliases fail closed instead of falling back to the unrelated value.
 */
internal fun dynamicFormRelationBindingValues(
    action: ActionSpec,
    availableValues: Map<String, String>,
): Map<String, String> {
    val binding = action.binding
    val bindingNames = (
        binding.pathParameterNames +
            binding.requiredPathParameterNames +
            binding.queryParameterNames +
            binding.requiredQueryParameterNames
        ).distinct()
    return bindingNames.mapNotNull { name ->
        binding.dynamicFormRelationValue(name, availableValues)?.let { value -> name to value }
    }.sortedBy { (name, _) -> name }
        .toMap()
}

private fun ApiBinding.dynamicFormRelationValue(
    parameterName: String,
    availableValues: Map<String, String>,
): String? {
    val routeResource = genericIdentityRouteResource(parameterName)
    if (routeResource != null) {
        val qualifiedMatches = availableValues.entries.filter { (name, value) ->
            value.isNotBlank() &&
                name.length > 2 &&
                name.endsWith("Id", ignoreCase = true) &&
                name.dropLast(2).sameDynamicResourceAs(routeResource)
        }
        return qualifiedMatches.singleOrNull()?.value
            ?: if (qualifiedMatches.isEmpty()) {
                availableValues[parameterName]?.takeIf(String::isNotBlank)
            } else {
                null
            }
    }
    return availableValues[parameterName]?.takeIf(String::isNotBlank)
}

private fun ApiBinding.genericIdentityRouteResource(parameterName: String): String? {
    if (!parameterName.equals("id", ignoreCase = true)) return null
    val segments = path.substringBefore('?').split('/').filter(String::isNotBlank)
    val placeholder = "{$parameterName}"
    val indices = segments.indices.filter { index -> segments[index] == placeholder }
    val index = indices.singleOrNull()?.takeIf { it > 0 } ?: return null
    val resourceSegment = segments[index - 1]
    return resourceSegment.takeIf { segment ->
        segment.none { character -> character in "{}" } &&
            segment.any(Char::isLetterOrDigit)
    }
}
