package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeChoresWorkspaceKind

internal fun retainedChoresNavigationContext(
    retainedTeamContext: DynamicResourceRecordContext?,
    currentRecordContext: DynamicResourceRecordContext?,
): DynamicResourceRecordContext? = retainedTeamContext ?: currentRecordContext

internal fun retainedChoresFormActionContext(
    workspaceKind: NativeChoresWorkspaceKind?,
    retainedTeamContext: DynamicResourceRecordContext?,
    currentRecordContext: DynamicResourceRecordContext?,
): DynamicResourceRecordContext? = when (workspaceKind) {
    NativeChoresWorkspaceKind.Chores,
    NativeChoresWorkspaceKind.History,
    -> retainedTeamContext ?: currentRecordContext
    // Keep the Team header at root scope so it exposes the signed create-team action. The team
    // record still reaches the roster renderer separately, where it authorizes invite-member.
    NativeChoresWorkspaceKind.Team,
    NativeChoresWorkspaceKind.Invitations,
    null,
    -> currentRecordContext
}

internal fun showDynamicCollectionCreateAction(
    collectionState: String?,
    choresWorkspaceKind: NativeChoresWorkspaceKind?,
): Boolean = collectionState == null || choresWorkspaceKind == NativeChoresWorkspaceKind.Team
