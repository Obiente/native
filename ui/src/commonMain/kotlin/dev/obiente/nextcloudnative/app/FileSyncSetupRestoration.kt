package dev.obiente.nextcloudnative.app

internal suspend fun restoreAndReconcileFileSyncRootSetup(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    draft: FileSyncSetupDraftState,
): Boolean {
    val previous = draft.localRoot.value
    val restored = previous?.let { services.restoreFileSyncLocalRoot(session, it) }
    if (draft.localRoot.value !== previous) return true
    if (previous != null && restored == null) return false
    val reconciled = services.reconcileFileSyncRootSetup(session, restored)
    if (draft.localRoot.value !== previous) return true
    if (reconciled) draft.localRoot.value = restored
    return reconciled
}
