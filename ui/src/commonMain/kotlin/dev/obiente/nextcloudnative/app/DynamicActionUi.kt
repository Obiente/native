package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome

internal data class PendingDynamicDirectAction(
    val action: ActionSpec,
    val values: Map<String, String>,
    val targetLabel: String,
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
