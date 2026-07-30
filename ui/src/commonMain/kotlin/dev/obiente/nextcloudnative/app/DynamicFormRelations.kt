package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

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
                action.takeIf {
                    action.resourceId == parentResourceId &&
                        action.intent == ActionIntent.list &&
                        action.risk == ActionRisk.readOnly &&
                        action.binding.method == HttpMethod.GET &&
                        action.confidence in setOf(Confidence.high, Confidence.verified) &&
                        dynamicCollectionState(action) == null &&
                        requiredNames.all { name -> availableValues[name]?.isNotBlank() == true }
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
