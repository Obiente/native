package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec

internal data class PendingDynamicDirectAction(
    val action: ActionSpec,
    val values: Map<String, String>,
    val targetLabel: String,
)

internal enum class DynamicActionUiMode {
    NavigateToForm,
    ConfirmDirectly,
}

/**
 * Empty-body destructive actions do not benefit from an intermediate empty form. They remain
 * contract-backed and always require explicit confirmation before the executor receives them.
 */
internal fun dynamicActionUiMode(
    action: ActionSpec,
    editableFieldCount: Int,
): DynamicActionUiMode = if (action.intent == ActionIntent.delete && editableFieldCount == 0) {
    DynamicActionUiMode.ConfirmDirectly
} else {
    DynamicActionUiMode.NavigateToForm
}

internal fun dynamicHeaderActionLabel(action: ActionSpec, fallback: String): String = when (action.intent) {
    ActionIntent.update -> "Edit"
    ActionIntent.delete -> "Delete"
    ActionIntent.create -> fallback
    else -> fallback
}

/** Orders the most useful safe workflow actions ahead of destructive or uncommon operations. */
internal fun dynamicQuickActionPriority(action: ActionSpec?): Int = when (action?.intent) {
    ActionIntent.create -> 0
    ActionIntent.update -> 1
    ActionIntent.execute -> 2
    ActionIntent.delete -> 3
    ActionIntent.read, ActionIntent.list, null -> 4
}
