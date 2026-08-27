package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeChoresInvitationAcceptRecoveryPlan

internal data class PendingDynamicDirectAction(
    val action: ActionSpec,
    val values: Map<String, String>,
    val targetLabel: String,
    val invitationAcceptRecoveryPlan: NativeChoresInvitationAcceptRecoveryPlan? = null,
    val durableRecoveryRequired: Boolean = false,
)

internal enum class DynamicActionUiMode {
    NavigateToForm,
    ConfirmDirectly,
}

internal data class DynamicDirectActionFailurePolicy(
    val retryAllowed: Boolean,
    val requiresReconciliation: Boolean,
)

/**
 * A rejected request is known not to have applied and may be retried. An unknown outcome may have
 * reached the server, so the host must refresh authoritative state and suppress the same action
 * until the user reviews that refreshed state.
 */
internal fun dynamicDirectActionFailurePolicy(
    outcome: NativeActionFailureOutcome,
): DynamicDirectActionFailurePolicy = when (outcome) {
    NativeActionFailureOutcome.Rejected -> DynamicDirectActionFailurePolicy(
        retryAllowed = true,
        requiresReconciliation = false,
    )
    NativeActionFailureOutcome.Unknown -> DynamicDirectActionFailurePolicy(
        retryAllowed = false,
        requiresReconciliation = true,
    )
}

/**
 * Empty-body destructive actions do not benefit from an intermediate empty form. They remain
 * contract-backed and always require explicit confirmation before the executor receives them.
 */
internal fun dynamicActionUiMode(
    action: ActionSpec,
    editableFieldCount: Int,
): DynamicActionUiMode = if (
    action.risk == ActionRisk.destructive &&
    action.requiresConfirmation &&
    editableFieldCount == 0
) {
    DynamicActionUiMode.ConfirmDirectly
} else {
    DynamicActionUiMode.NavigateToForm
}

/**
 * Places a planner-issued root form on its active native read surface.
 *
 * Most contracts give the read and create operations the same resource identity. Some generators
 * derive different identities from their response and request schemas even though GET and POST
 * use one exact singleton route. Accept that narrow case only when both operations and the form
 * retain verified contract evidence.
 */
internal fun dynamicRootFormTargetsActiveSurface(
    action: ActionSpec,
    formView: ViewSpec,
    activeView: ViewSpec,
    activeReadAction: ActionSpec?,
    selectedCollectionState: String?,
): Boolean {
    if (selectedCollectionState != null) return false
    if (action.resourceId.sameDynamicResourceAs(activeView.resourceId)) return true
    if (
        action.intent != ActionIntent.create ||
        formView.resourceId != action.resourceId ||
        !formView.hasVerifiedNativeContractEvidence() ||
        !action.hasVerifiedNativeContractEvidence()
    ) {
        return false
    }
    val verifiedActiveRead = activeReadAction?.takeIf { read ->
        read.id == activeView.sourceActionId &&
            read.binding.method == HttpMethod.GET &&
            read.risk == ActionRisk.readOnly &&
            read.hasVerifiedNativeContractEvidence()
    } ?: return false
    return verifiedActiveRead.binding.isExactNativeSingletonRoute(action.binding)
}

/**
 * Places only planner-issued contextual forms on the active native surface.
 *
 * Ordinary updates and deletes still require the selected-record path. The two cross-resource
 * cases are narrow: a verified file upload may target its active collection, and a verified
 * singleton update may target its one active read-only detail surface.
 */
internal fun dynamicContextualFormTargetsActiveSurface(
    action: ActionSpec,
    formView: ViewSpec,
    activeView: ViewSpec,
    activeReadAction: ActionSpec?,
    plannedBindingValues: Map<String, String>,
    selectedRecordResourceId: String,
    selectedCollectionState: String?,
    hasEditableFileField: Boolean,
    uniqueTargetResource: Boolean,
): Boolean {
    val targetsCurrentRecord = action.resourceId.sameDynamicResourceAs(selectedRecordResourceId)
    val targetsCurrentView = action.resourceId.sameDynamicResourceAs(activeView.resourceId)
    val createsCurrentViewResource = action.intent == ActionIntent.create &&
        targetsCurrentView &&
        selectedCollectionState == null
    val editsSelectedRecord = action.intent in setOf(ActionIntent.update, ActionIntent.delete) &&
        targetsCurrentRecord &&
        (targetsCurrentView || activeView.component == NativeComponent.detail)
    if (createsCurrentViewResource || editsSelectedRecord) return true
    if (
        !targetsCurrentView ||
        !uniqueTargetResource ||
        formView.resourceId != action.resourceId ||
        !formView.hasVerifiedNativeContractEvidence() ||
        !action.hasVerifiedNativeContractEvidence() ||
        !action.hasCompleteDynamicFormBindings(plannedBindingValues)
    ) {
        return false
    }
    val verifiedActiveRead = activeReadAction?.takeIf { read ->
        read.resourceId.sameDynamicResourceAs(action.resourceId) &&
            read.binding.method == HttpMethod.GET &&
            read.risk == ActionRisk.readOnly &&
            read.hasVerifiedNativeContractEvidence()
    } ?: return false
    return when {
        action.intent == ActionIntent.execute &&
            action.effect == ActionEffect.upload &&
            action.risk == ActionRisk.mutating &&
            hasEditableFileField &&
            verifiedActiveRead.intent in setOf(ActionIntent.list, ActionIntent.read) -> true
        action.intent == ActionIntent.execute &&
            action.effect != ActionEffect.upload &&
            action.risk == ActionRisk.mutating &&
            action.binding.requiredBodyFieldNames.isNotEmpty() &&
            action.binding.requiredBodyFieldNames.all { name ->
                plannedBindingValues[name]?.isNotBlank() == true
            } &&
            verifiedActiveRead.intent in setOf(ActionIntent.list, ActionIntent.read) -> true
        action.intent == ActionIntent.update &&
            action.risk == ActionRisk.mutating &&
            activeView.component == NativeComponent.detail &&
            verifiedActiveRead.intent in setOf(ActionIntent.read, ActionIntent.list) &&
            verifiedActiveRead.binding.isExactNativeSingletonRoute(action.binding) &&
            selectedCollectionState == null -> true
        else -> false
    }
}

private fun ApiBinding.isExactNativeSingletonRoute(writeBinding: ApiBinding): Boolean =
    path == writeBinding.path &&
        pathParameterNames.toSet() == writeBinding.pathParameterNames.toSet() &&
        requiredQueryParameterNames.toSet() == writeBinding.requiredQueryParameterNames.toSet()

private fun ActionSpec.hasVerifiedNativeContractEvidence(): Boolean =
    confidence == Confidence.verified ||
        (
            confidence == Confidence.high &&
                evidence.any { item ->
                    item.source == EvidenceSource.verifiedAppPackage
                }
            )

private fun ViewSpec.hasVerifiedNativeContractEvidence(): Boolean =
    confidence == Confidence.verified ||
        (
            confidence == Confidence.high &&
                evidence.any { item ->
                    item.source == EvidenceSource.verifiedAppPackage
                }
            )

private fun ActionSpec.hasCompleteDynamicFormBindings(values: Map<String, String>): Boolean {
    val requiredNames = (
        binding.pathParameterNames +
            binding.requiredPathParameterNames +
            binding.requiredQueryParameterNames
        ).distinct()
    return requiredNames.all { name -> values[name]?.isNotBlank() == true }
}

internal fun dynamicHeaderActionLabel(action: ActionSpec, fallback: String): String = when (action.effect) {
    ActionEffect.archive -> "Archive"
    ActionEffect.unarchive -> "Unarchive"
    ActionEffect.restore -> "Restore"
    ActionEffect.permanentDelete -> "Delete permanently"
    ActionEffect.empty -> "Empty"
    ActionEffect.copy -> "Copy"
    ActionEffect.clear -> "Clear"
    ActionEffect.leave -> "Leave"
    else -> when (action.intent) {
        ActionIntent.update -> "Edit"
        ActionIntent.delete -> "Delete"
        ActionIntent.create -> fallback
        else -> fallback
    }
}

internal fun dynamicDirectActionTitle(action: ActionSpec, targetLabel: String): String =
    "${dynamicHeaderActionLabel(action, action.label)} $targetLabel?"

internal fun dynamicDirectActionDescription(action: ActionSpec): String = when (action.effect) {
    ActionEffect.clear -> "This clears the selected data from the server and cannot be undone."
    ActionEffect.empty -> "This permanently removes every item in this server collection."
    ActionEffect.leave -> "You will lose access to this server collection."
    ActionEffect.permanentDelete -> "This permanently deletes the item from the server."
    else -> "This removes the item from the server and cannot be undone."
}

internal fun dynamicDirectActionConfirmLabel(action: ActionSpec): String =
    dynamicHeaderActionLabel(action, action.label)

internal fun dynamicCollectionState(action: ActionSpec?): String? {
    if (action?.intent != ActionIntent.list || action.binding.method != HttpMethod.GET) {
        return null
    }
    return action.binding.path
        .trimEnd('/')
        .substringAfterLast('/')
        .lowercase()
        .filter(Char::isLetterOrDigit)
        .takeIf(DYNAMIC_COLLECTION_STATE_WORDS::contains)
}

internal fun dynamicSecondaryDestinationLabel(
    destinationLabel: String,
    resourceLabel: String,
    duplicate: Boolean,
): String = if (duplicate) {
    listOf(resourceLabel.trim(), destinationLabel.trim())
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .joinToString(" ")
} else {
    destinationLabel
}

/** Orders the most useful safe workflow actions ahead of destructive or uncommon operations. */
internal fun dynamicQuickActionPriority(action: ActionSpec?): Int = when (action?.intent) {
    ActionIntent.create -> 0
    ActionIntent.update -> 1
    ActionIntent.execute -> 2
    ActionIntent.delete -> 3
    ActionIntent.read, ActionIntent.list, null -> 4
}

private val DYNAMIC_COLLECTION_STATE_WORDS = setOf(
    "archive",
    "archived",
    "deleted",
    "trash",
)
