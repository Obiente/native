package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeChoresWorkspaceKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeChoresWorkspaceUsesTeamContext

internal fun retainedChoresNavigationContext(
    retainedTeamContext: DynamicResourceRecordContext?,
    currentRecordContext: DynamicResourceRecordContext?,
): DynamicResourceRecordContext? = retainedTeamContext ?: currentRecordContext

internal fun retainedChoresFormActionContext(
    workspaceKind: NativeChoresWorkspaceKind?,
    retainedTeamContext: DynamicResourceRecordContext?,
    currentRecordContext: DynamicResourceRecordContext?,
): DynamicResourceRecordContext? = if (nativeChoresWorkspaceUsesTeamContext(workspaceKind)) {
    retainedTeamContext ?: currentRecordContext
} else {
    currentRecordContext
}
